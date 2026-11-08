package dev.xd.fitnessbutler.ui

import android.Manifest
import android.app.Fragment
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import dev.xd.fitnessbutler.MainActivity
import dev.xd.fitnessbutler.R
import android.util.LruCache
import java.io.File
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 自建相册选图页(2026-10-17):系统 PhotoPicker(GET_CONTENT)在这台 vivo 上卡死弹不出,
 * 改为 APP 自己查 MediaStore 出网格(DATE_ADDED 倒序,最近 500 张)。
 * - 单选模式:点一张立即复制进私有 photos/ 并走识别(与原 GET_CONTENT 流程一致)
 * - 多选模式:点图勾选(角标显示序号,≤9 张),底部「识别 N 张」走批量管线
 * - 顶栏「⚡最新一张」=直接取最新照片识别(快速记账/测试两便)
 * - 选中后复制成本机文件再交识别,不持有 content URI(权限会过期)
 * 权限:Android 13+ READ_MEDIA_IMAGES(只给「选中的照片」也能用,仅显示选中项);
 * <=32 用 READ_EXTERNAL_STORAGE。拒绝时空态引导,不闪退。
 */
@Suppress("DEPRECATION")
class AlbumPickFragment : Fragment() {

    companion object {
        /** avatarSlug 非空 = 头像模式:单选后回调 onAvatarPicked,不走识别 */
        fun newInstance(multi: Boolean, avatarSlug: String? = null): AlbumPickFragment =
            AlbumPickFragment().apply {
                arguments = Bundle().apply {
                    putBoolean("multi", multi)
                    putString("avatar_slug", avatarSlug)
                }
            }

        private const val REQ_PERM = 401
        private const val MAX_PICK = 9       // 与批量管线 MAX_BATCH 对齐
        private const val MAX_LOAD = 500     // 只拉最近 500 张,防首屏卡
    }

    private data class Pic(val id: Long)

    private var multi = false
    private var avatarSlug: String? = null
    private val pics = mutableListOf<Pic>()
    private val picked = LinkedHashSet<Int>()   // 勾选的 adapter 位置(按勾选顺序)
    private var adapter: PicsAdapter? = null
    private var busy = false                    // 复制/识别中防重复点击

    /** 缩略图内存缓存 + 单线程串行解码(防一屏几十个 IO 线程) */
    private val thumbCache = LruCache<Long, Bitmap>(288)
    private val loadingIds = Collections.synchronizedSet(mutableSetOf<Long>())
    private val thumbExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onDestroy() {
        thumbExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_album_pick, container, false)
        val act = activity as MainActivity
        multi = arguments?.getBoolean("multi", false) ?: false
        avatarSlug = arguments?.getString("avatar_slug")
        if (avatarSlug != null) {
            v.findViewById<TextView>(R.id.album_title)?.text = "选一张当头像"
            updateBottomBtn(v)
        }

        v.findViewById<TextView>(R.id.album_back).setOnClickListener { act.backHome() }
        v.findViewById<TextView>(R.id.album_latest).setOnClickListener { pickLatest(v) }
        v.findViewById<TextView>(R.id.album_grant_btn).setOnClickListener { ensurePerm() }
        v.findViewById<TextView>(R.id.album_settings_btn).setOnClickListener { openAppSettings() }
        v.findViewById<TextView>(R.id.album_pick_btn).setOnClickListener { onBottomBtn(v) }

        val grid = v.findViewById<GridView>(R.id.album_grid)
        adapter = PicsAdapter()
        grid.adapter = adapter
        grid.onItemClickListener = { _, _, pos, _ -> onGridTap(v, pos) }
        grid.onItemLongClickListener = { _, _, pos, _ ->
            // 单选模式长按 → 切多选并勾当前这张(头像模式不切多选)
            if (!multi && !busy && avatarSlug == null) {
                multi = true
                picked.clear()
                picked.add(pos)
                adapter?.notifyDataSetChanged()
                updateBottomBtn(v)
            }
            true
        }

        ensurePerm()
        return v
    }

    // ---------- 权限 ----------

    private fun hasPerm(): Boolean {
        val ctx = activity ?: return false
        return if (Build.VERSION.SDK_INT >= 33) {
            // 「允许全部」或「仅选中的照片」都算有权限(后者 MediaStore 只返回选中项,够用)
            ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) ==
                PackageManager.PERMISSION_GRANTED ||
                ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun ensurePerm() {
        if (hasPerm()) {
            loadPics()
            return
        }
        val perms = if (Build.VERSION.SDK_INT >= 33)
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
        else
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        requestPermissions(perms, REQ_PERM)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        if (requestCode == REQ_PERM) {
            if (hasPerm()) loadPics() else showPermDenied()
        }
    }

    private fun showPermDenied() {
        view?.findViewById<View>(R.id.album_perm_box)?.visibility = View.VISIBLE
        view?.findViewById<View>(R.id.album_grid)?.visibility = View.GONE
        view?.findViewById<View>(R.id.album_pick_btn)?.visibility = View.GONE
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", "dev.xd.fitnessbutler", null)
                )
            )
        }
    }

    // ---------- 数据 ----------

    private fun loadPics() {
        view?.findViewById<View>(R.id.album_perm_box)?.visibility = View.GONE
        view?.findViewById<View>(R.id.album_grid)?.visibility = View.VISIBLE
        view?.findViewById<View>(R.id.album_pick_btn)?.visibility = View.VISIBLE
        val ctx = activity ?: return
        Thread {
            val list = mutableListOf<Pic>()
            runCatching {
                ctx.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID),
                    null, null,
                    "${MediaStore.Images.Media.DATE_ADDED} DESC"
                )?.use { c: Cursor ->
                    while (c.moveToNext() && list.size < MAX_LOAD) list.add(Pic(c.getLong(0)))
                }
            }
            activity?.runOnUiThread {
                if (activity == null || !isAdded) return@runOnUiThread
                pics.clear()
                pics.addAll(list)
                adapter?.notifyDataSetChanged()
                view?.findViewById<TextView>(R.id.album_title)?.text = when {
                    pics.isEmpty() -> "相册没读到照片"
                    avatarSlug != null -> "选一张当头像 · ${pics.size} 张"
                    else -> "相册选图 · ${pics.size} 张"
                }
            }
        }.start()
    }

    // ---------- 交互 ----------

    private fun onGridTap(v: View, pos: Int) {
        if (busy || pics.getOrNull(pos) == null) return
        if (multi) {
            if (picked.contains(pos)) picked.remove(pos)
            else {
                if (picked.size >= MAX_PICK) { (activity as MainActivity).toast("单批最多 $MAX_PICK 张"); return }
                picked.add(pos)
            }
            adapter?.notifyDataSetChanged()
            updateBottomBtn(v)
        } else {
            // 单选:点一张直接识别(最顺手,少一步确认)
            copyAndPick(listOf(pos))
        }
    }

    private fun pickLatest(v: View) {
        if (busy || pics.isEmpty()) {
            if (pics.isEmpty()) (activity as? MainActivity)?.toast("相册还没读到照片")
            return
        }
        copyAndPick(listOf(0))   // DATE_ADDED 倒序,0 位=最新
    }

    private fun onBottomBtn(v: View) {
        if (busy) return
        if (multi) {
            if (picked.isEmpty()) { (activity as MainActivity).toast("先勾选照片(点图勾选)"); return }
            copyAndPick(picked.toList())
        } else {
            (activity as MainActivity).toast(
                if (avatarSlug != null) "点一张照片用作头像" else "点一张照片立即识别;长按可切多选"
            )
        }
    }

    private fun updateBottomBtn(v: View) {
        val btn = v.findViewById<TextView>(R.id.album_pick_btn)
        btn.text = when {
            avatarSlug != null -> "用作头像 ✓"
            multi && picked.isNotEmpty() -> "识别 ${picked.size} 张 ✓"
            else -> "识别选中 ✓"
        }
    }

    // ---------- 复制 + 交给识别管线 ----------

    /** 把选中位置的照片复制进私有 photos/(串行命名防同毫秒撞名),再回调 MainActivity */
    private fun copyAndPick(positions: List<Int>) {
        val act = activity as? MainActivity ?: return
        val uris = positions.mapNotNull { i ->
            pics.getOrNull(i)?.id?.let { id ->
                Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
            }
        }
        if (uris.isEmpty()) return
        busy = true
        val btn = view?.findViewById<TextView>(R.id.album_pick_btn)
        btn?.text = "复制照片中…"
        Thread {
            val dir = File(act.filesDir, "photos").apply { mkdirs() }
            val stamp = System.currentTimeMillis()
            val paths = uris.mapIndexedNotNull { i, u ->
                runCatching {
                    val f = File(dir, "meal_${stamp}_a$i.jpg")
                    act.contentResolver.openInputStream(u)?.use { input ->
                        f.outputStream().use { input.copyTo(it) }
                    } ?: return@mapIndexedNotNull null
                    if (f.length() > 0) f.absolutePath else null
                }.getOrNull()
            }
            act.runOnUiThread {
                busy = false
                when {
                    paths.isEmpty() -> {
                        btn?.text = if (avatarSlug != null) "用作头像 ✓" else "识别选中 ✓"
                        act.toast("图片复制失败")
                    }
                    avatarSlug != null && paths.size == 1 ->
                        act.onAvatarPicked(paths[0], avatarSlug!!)
                    paths.size == 1 -> act.onAlbumSinglePicked(paths[0])
                    else -> act.onAlbumMultiPicked(paths)
                }
            }
        }.start()
    }

    // ---------- 网格 Adapter ----------

    private inner class PicsAdapter : BaseAdapter() {
        override fun getCount() = pics.size
        override fun getItem(position: Int) = pics[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.album_grid_item, parent, false)
            val img = v.findViewById<ImageView>(R.id.album_item_img)
            val check = v.findViewById<TextView>(R.id.album_item_check)
            val id = pics[position].id
            loadThumb(id, img)
            if (picked.contains(position)) {
                check.visibility = View.VISIBLE
                check.text = if (multi) "${picked.indexOf(position) + 1}" else "✓"
            } else {
                check.visibility = View.GONE
            }
            return v
        }
    }

    /** 缩略图:缓存命中直接上;否则串行后台解码,tag 防复用错位 */
    private fun loadThumb(id: Long, img: ImageView) {
        thumbCache.get(id)?.let { img.setImageBitmap(it); return }
        img.setImageDrawable(null)
        img.tag = id
        if (!loadingIds.add(id)) return
        thumbExecutor.execute {
            val bmp = runCatching { decodeThumb(id) }.getOrNull()
            if (bmp != null) thumbCache.put(id, bmp)
            loadingIds.remove(id)
            activity?.runOnUiThread {
                if (img.tag == id) bmp?.let { img.setImageBitmap(it) }
            }
        }
    }

    private fun decodeThumb(id: Long): Bitmap? {
        val ctx = activity ?: return null
        val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
        return if (Build.VERSION.SDK_INT >= 29) {
            ctx.contentResolver.loadThumbnail(uri, android.util.Size(256, 256), null)
        } else {
            MediaStore.Images.Thumbnails.getThumbnail(
                ctx.contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null
            )
        }
    }
}
