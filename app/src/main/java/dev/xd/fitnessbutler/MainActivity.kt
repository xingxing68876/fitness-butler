package dev.xd.fitnessbutler

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Fragment
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.xd.fitnessbutler.db.DbHelper
import dev.xd.fitnessbutler.file.PhotoFileProvider
import dev.xd.fitnessbutler.health.VitalsSampler
import dev.xd.fitnessbutler.health.VivoHealthHarvester
import dev.xd.fitnessbutler.rec.FoodRecognizer
import dev.xd.fitnessbutler.rec.ImageUtil
import dev.xd.fitnessbutler.rec.RecognizedFood
import dev.xd.fitnessbutler.rec.RecognizeService
import dev.xd.fitnessbutler.rec.VisionRecognizers
import dev.xd.fitnessbutler.remind.Reminders
import dev.xd.fitnessbutler.ui.AlbumPickFragment
import dev.xd.fitnessbutler.ui.AvatarUtil
import dev.xd.fitnessbutler.ui.CalendarFragment
import dev.xd.fitnessbutler.ui.DayDetailFragment
import dev.xd.fitnessbutler.ui.ShareCardFragment
import dev.xd.fitnessbutler.ui.HomeFragment
import dev.xd.fitnessbutler.ui.MealEditFragment
import dev.xd.fitnessbutler.ui.SettingsFragment
import dev.xd.fitnessbutler.ui.WorkoutFragment
import dev.xd.fitnessbutler.scale.ScaleAutoLink
import dev.xd.fitnessbutler.scale.ScaleFragment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 单 Activity + Fragment 壳:底部导航五入口(对话/日历/拍照/锻炼/图鉴),
 * 拍照为中央大按钮直达;设置/体脂秤/明细编辑为子页。
 *
 * M1.5:
 *  1) 状态栏重叠修复 —— targetSdk 35 强制 edge-to-edge,API 30+ 关闭自动留白,
 *     根容器吃 systemBars insets 做 padding(顶部状态栏底色=主色绿,深色图标);
 *  2) 拍照流 AI 直出 —— 拍照/选图/补识全部汇入对话流(2026-10-18):图片消息 → 视觉 API →
 *     AI 消息自动入账;识别失败图左 🔄 标记重试,「手记」降级兜底。
 */
@Suppress("DEPRECATION")
class MainActivity : Activity() {

    companion object {
        private const val REQ_CAMERA = 201
        private const val PERM_REQ = 100

        /** 桌面快捷方式:直达拍照记一笔 */
        const val ACTION_QUICK_PHOTO = "dev.xd.fitnessbutler.ACTION_QUICK_PHOTO"

        /** 桌面小组件「今日运动」:直达锻炼页 */
        const val ACTION_OPEN_WORKOUT = "dev.xd.fitnessbutler.OPEN_WORKOUT"
        private const val SHORTCUT_PHOTO = "quick_photo"
        // MAX_BATCH=9 限额移入自建选图页 AlbumPickFragment.MAX_PICK(2026-10-17)

        const val SCREEN_HOME = 0
        const val SCREEN_CALENDAR = 1
        const val SCREEN_WORKOUT = 3
        const val SCREEN_SHARE = 4
    }

    private var currentScreen = SCREEN_HOME
    private var pendingPhotoFile: File? = null
    private var recognizing = false

    private val timeFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applyEdgeToEdge()
        setupNav()
        ensureBlePermissions()
        if (savedInstanceState == null) {
            // 2026-10-18 设计决策:识别失败不留存;2026-11-05 改口径:
            // 划卡杀服务冤死的 6 小时内新图 → 冷启动补跑识别;超龄/文件丢的照旧丢弃
            recoverStaleQueues()
            showScreen(SCREEN_HOME)
        }
        startAutoData()
        registerQuickShortcut()
        Reminders.reschedule(this)   // 每日提醒:开 APP 重注册防闹钟失联(2026-11-07)
        handleQuickIntent(intent)
    }

    /** 桌面快捷方式点进来(launchMode=singleTop,热启动走 onNewIntent) */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleQuickIntent(intent)
    }

    /**
     * 键盘收起(2026-10-18 用户):输入框有焦点时,点框外任何位置先收键盘;
     * 事件照常下发给被点的控件,不吞点击。
     */
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev?.action == MotionEvent.ACTION_DOWN) {
            val focused = currentFocus
            if (focused is EditText) {
                val r = Rect()
                focused.getGlobalVisibleRect(r)
                if (!r.contains(ev.x.toInt(), ev.y.toInt())) {
                    focused.clearFocus()
                    getSystemService(InputMethodManager::class.java)
                        ?.hideSoftInputFromWindow(focused.windowToken, 0)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun handleQuickIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_QUICK_PHOTO -> openPhotoEntry()
            // 桌面小组件「今日运动」清单点击直达锻炼页
            ACTION_OPEN_WORKOUT -> showScreen(SCREEN_WORKOUT)
        }
    }

    /** 桌面快捷方式「拍照记一笔」:动态快捷方式幂等注册;固定到桌面只请求一次(系统弹窗确认) */
    private fun registerQuickShortcut() {
        val sm = getSystemService(ShortcutManager::class.java) ?: return
        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_QUICK_PHOTO
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val shortcut = ShortcutInfo.Builder(this, SHORTCUT_PHOTO)
            .setShortLabel("拍照记一笔")
            .setLongLabel("拍照记一笔(AI 直出)")
            .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(intent)
            .build()
        runCatching { sm.addDynamicShortcuts(listOf(shortcut)) }
        val db = DbHelper.get(this)
        if (db.getSetting("shortcut_pin_requested", "0") != "1" && sm.isRequestPinShortcutSupported) {
            db.setSetting("shortcut_pin_requested", "1")
            runCatching { sm.requestPinShortcut(shortcut, null) }
        }
    }

    /** 开 APP 自动化:①手表数据后台自动同步(幂等,免手动按) ②今日未称重→后台自动连秤(静默超时) ③快照采样器注册+补采(攒小时曲线) */
    private fun startAutoData() {
        window.decorView.postDelayed({
            Thread {
                val report = runCatching {
                    VivoHealthHarvester.harvest(applicationContext)
                }.getOrNull()
                if (report != null && report.ok) {
                    runOnUiThread {
                        runCatching { toast("手表数据已同步:${report.summary}") }
                    }
                }
                runCatching { ScaleAutoLink.maybeAutoLink(this@MainActivity) }
                // 血氧/压力/心率快照采样:注册 30 分钟周期闹钟(幂等)+ 立即补采一次
                runCatching { VitalsSampler.schedule(applicationContext) }
                runCatching { VitalsSampler.sampleNow(applicationContext) }
            }.start()
        }, 3000)
    }

    /** 蓝牙权限弹窗结果(自动连秤 9201):通过即自动续跑连秤链路 */
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == ScaleAutoLink.PERM_REQ_AUTO) {
            val ok = grantResults.isNotEmpty() &&
                grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            ScaleAutoLink.onPermResult(ok, applicationContext)
        }
    }

    // ---------- 状态栏重叠修复(全 app 各屏统一走根容器 padding) ----------

    private fun applyEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // targetSdk 35 强制 edge-to-edge:关掉系统自动留白,自己吃 insets
            window.setDecorFitsSystemWindows(false)
            val root = findViewById<LinearLayout>(R.id.root_main)
            root.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                // 键盘弹起时底部再让出 IME 高度:输入栏/发送/拍照整体抬到键盘上方(2026-10-18)
                val ime = insets.getInsets(WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
                insets
            }
            // 顶底系统栏区域露出根布局主色绿;浅色底,图标用深色保证可读
            window.insetsController?.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else {
            // API 26~29:系统默认留白即可,只保证状态栏深色图标(绿底)
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            // 键盘弹起时窗口整体缩小,输入栏不被盖(2026-10-18)
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    // ---------- 底部导航 ----------

    private fun setupNav() {
        findViewById<LinearLayout>(R.id.nav_home).setOnClickListener { showScreen(SCREEN_HOME) }
        findViewById<LinearLayout>(R.id.nav_calendar).setOnClickListener { showScreen(SCREEN_CALENDAR) }
        findViewById<LinearLayout>(R.id.nav_photo).setOnClickListener { openPhotoEntry() }
        findViewById<LinearLayout>(R.id.nav_workout).setOnClickListener { showScreen(SCREEN_WORKOUT) }
        findViewById<LinearLayout>(R.id.nav_guide).setOnClickListener { showScreen(SCREEN_SHARE) }
    }

    /** 切主屏并高亮底部入口 */
    fun showScreen(screen: Int) {
        currentScreen = screen
        val frag: Fragment = when (screen) {
            SCREEN_HOME -> HomeFragment()
            SCREEN_CALENDAR -> CalendarFragment()
            SCREEN_WORKOUT -> WorkoutFragment()
            SCREEN_SHARE -> ShareCardFragment()
            else -> HomeFragment()
        }
        replace(frag)
        highlightNav(screen)
    }

    private fun replace(frag: Fragment) {
        // 切页先收键盘(2026-10-18):否则从对话页带键盘进设置/相册会挡半屏
        runCatching {
            currentFocus?.clearFocus()
            getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
        }
        fragmentManager.beginTransaction()
            .replace(R.id.container, frag)
            .commit()
    }

    fun openSettings() {
        replace(SettingsFragment())
        highlightNav(-1)
    }

    fun openScale() {
        replace(ScaleFragment())
        highlightNav(-1)
    }

    fun openMealEdit(
        photoPath: String?, prefillJson: String? = null, notice: String? = null, mealId: Long = -1L
    ) {
        replace(MealEditFragment.newInstance(photoPath, prefillJson, notice, mealId))
        highlightNav(-1)
    }

    /** 改/删一笔已有餐(详情页点三餐行进) */
    fun openMealEditForMeal(mealId: Long) {
        replace(MealEditFragment.newForEdit(mealId))
        highlightNav(-1)
    }

    // ---------- 自建相册选图页(2026-10-17:系统 PhotoPicker 卡死,不再走 GET_CONTENT) ----------

    fun openAlbumPick(multi: Boolean) {
        replace(AlbumPickFragment.newInstance(multi))
        highlightNav(-1)
    }

    /** 对话页📷入口:选完图回对话流识别(2026-10-17 对话式识别主线) */
    private var albumPickForChat = false

    /** 对话页等着的照片(可多张):HomeFragment 重建时消费并发起对话识别(2026-10-18 全入口汇入对话流) */
    var pendingChatPhotos: List<String> = emptyList()

    /** 打卡海报选图(2026-11-06):相册单选 → 裁切作分享背景 */
    private var pendingShareCropPath: String? = null

    fun openAlbumPickForShare() {
        pendingShareCropPath = "__WAIT__"   // 占位:选图回执走 onAlbumSinglePicked 分流
        openAlbumPick(false)
    }

    /** ShareCardFragment onStart 消费(防 Fragment 重建丢图) */
    fun consumeShareCropPath(): String? {
        val p = pendingShareCropPath ?: return null
        pendingShareCropPath = null
        return if (p == "__WAIT__") null else p
    }

    fun openAlbumPickForChat() {
        albumPickForChat = true
        openAlbumPick(false)
    }

    /** 选图页单选回执:统一汇入对话流识别自动入账(2026-10-18 起不再直填编辑页) */
    fun onAlbumSinglePicked(photoPath: String) {
        if (pendingShareCropPath != null) {   // 打卡海报选图(2026-11-06):交 ShareCardFragment 裁切
            pendingShareCropPath = photoPath
            showScreen(SCREEN_SHARE)
            return
        }
        albumPickForChat = false
        pendingChatPhotos = listOf(photoPath)
        showScreen(SCREEN_HOME)
    }

    /** 选图页多选回执 → 逐张提交对话流识别 */
    fun onAlbumMultiPicked(paths: List<String>) {
        pendingChatPhotos = paths
        showScreen(SCREEN_HOME)
    }

    /** 头像选图(2026-10-18):相册选一张 → 存成对应用户/风格头像 → 回设置页 */
    fun openAvatarPick(slug: String) {
        replace(AlbumPickFragment.newInstance(false, slug))
        highlightNav(-1)
    }

    fun onAvatarPicked(photoPath: String, slug: String) {
        val (isAi, style) = AvatarUtil.fromSlug(slug)
        Thread {
            val ok = AvatarUtil.save(this, photoPath, isAi, style)
            runCatching { File(photoPath).delete() }   // 选图页的临时副本用完即删
            runOnUiThread {
                toast(if (ok) "头像已更新" else "头像保存失败")
                openSettings()
            }
        }.start()
    }

    /** 对话式识别用:视觉识别器(settings 表取引擎/Key,零泄露) */
    fun buildRecognizerFor(): dev.xd.fitnessbutler.rec.FoodRecognizer? = buildRecognizer()

    /** 对话式识别用:照片私有目录 */
    fun photosDirFor(): File = photosDir()

    /** 一日详情(日历点日期进,✕ 返回日历) */
    fun openDayDetail(date: String) {
        replace(DayDetailFragment.newInstance(date))
        highlightNav(-1)
    }

    /** 从子页回首页 */
    fun backHome() {
        // 相册取消未选(2026-11-06):清分享选图占位,防后续识别误分流
        if (pendingShareCropPath == "__WAIT__") pendingShareCropPath = null
        showScreen(SCREEN_HOME)
    }

    fun backToScreen() {
        showScreen(currentScreen)
    }

    private fun highlightNav(screen: Int) {
        val labels = mapOf(
            SCREEN_HOME to (R.id.nav_home_label to R.id.nav_home_icon),
            SCREEN_CALENDAR to (R.id.nav_calendar_label to R.id.nav_calendar_icon),
            SCREEN_WORKOUT to (R.id.nav_workout_label to R.id.nav_workout_icon),
            SCREEN_SHARE to (R.id.nav_guide_label to R.id.nav_guide_icon)
        )
        for ((s, ids) in labels) {
            val on = s == screen
            findViewById<TextView>(ids.first).alpha = if (on) 1f else 0.55f
            findViewById<TextView>(ids.second).alpha = if (on) 1f else 0.55f
        }
    }

    // ---------- 拍照 / 相册(AI 直出) ----------

    /**
     * 拍照记一笔入口(2026-10-18 设计决策):每次打开都是干净菜单——
     * 不再展示、不再自动重试历史待补识;识别失败的照片当场丢弃,不跨轮次保存。
     */
    fun openPhotoEntry() {
        openPhotoChooser()
    }

    /** 记一笔菜单:固定四项,无「待补识」行 */
    fun openPhotoChooser() {
        val items = arrayOf(
            "📷 拍照识别(AI 直出)",
            "🖼 相册选图(AI 直出)",
            "📚 批量选图(逐张识别自动入账)",
            "✏️ 手记一笔(识别失败兜底)"
        )
        AlertDialog.Builder(this)
            .setTitle("记一笔")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startCamera()
                    1 -> startGallery()
                    2 -> startGalleryMulti()
                    3 -> openMealEdit(null, null, "手记模式:不走识别,直接加项录入")
                }
            }
            .show()
    }

    /**
     * 冷启动处理没识别完的图(2026-11-05 改:划卡杀服务冤死图自救):
     * 6 小时内 + 照片文件还在 → 重新投喂识别(enqueue 幂等复用原队列行,结果照常呈现);
     * 超龄/文件已丢 → 按原 10-18 口径丢弃(队列行+图片消息+文件)。
     */
    private fun recoverStaleQueues() {
        runCatching {
            val db = DbHelper.get(this)
            val requeue = db.requeueablePending(6 * 3600_000L).filter { p -> File(p).exists() }
            if (requeue.isNotEmpty()) {
                Log.i("MealRecog", "requeue stale pending photos: ${requeue.size}")
                RecognizeService.start(this, requeue)
            }
            val stale = db.discardPendingRecognitions(6 * 3600_000L)
            stale.forEach { p -> runCatching { File(p).delete() } }
            if (stale.isNotEmpty()) Log.i("MealRecog", "stale pending photos discarded: ${stale.size}")
        }.onFailure { Log.e("MealRecog", "recover stale queues failed", it) }
    }

    /** 拍照副本落系统相册(2026-10-18 设计决策):Pictures/FitnessButler,Android 10+ 免权限 */
    fun saveToSystemGallery(f: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        Thread {
            runCatching {
                val name = "FitnessButler_${
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                }.jpg"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/FitnessButler"
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                ) ?: return@runCatching
                contentResolver.openOutputStream(uri)?.use { out ->
                    f.inputStream().use { it.copyTo(out) }
                }
                contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null, null
                )
            }.onFailure { Log.e("GallerySave", "save photo to gallery failed", it) }
        }.start()
    }

    private fun photosDir(): File = File(filesDir, "photos").apply { mkdirs() }

    private fun startCamera() {
        val f = File(photosDir(), "meal_${System.currentTimeMillis()}.jpg")
        pendingPhotoFile = f
        val uri = Uri.parse("content://${PhotoFileProvider.AUTHORITY}/photo/${f.name}")
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            clipData = ClipData.newRawUri("photo", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        if (intent.resolveActivity(packageManager) == null) {
            toast("没有可用相机")
            return
        }
        val resList = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        for (ri in resList) {
            grantUriPermission(
                ri.activityInfo.packageName, uri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        try {
            startActivityForResult(intent, REQ_CAMERA)
        } catch (e: Exception) {
            toast("唤起相机失败:${e.message}")
        }
    }

    /** 相册选图(单张):自建选图页,点一张直接识别 */
    private fun startGallery() {
        openAlbumPick(false)
    }

    /** 批量选图(多选,单批 ≤9 张):同一选图页多选模式 */
    private fun startGalleryMulti() {
        openAlbumPick(true)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_CAMERA -> {
                val f = pendingPhotoFile
                if (f != null && f.exists() && f.length() > 0) {
                    // 2026-10-18 设计决策:拍照同时落一份到系统相册(识别失败也不删相册副本)
                    saveToSystemGallery(f)
                    // 拍照结果统一汇入对话流:识别成功自动入账,失败当场丢弃
                    pendingChatPhotos = listOf(f.absolutePath)
                    showScreen(SCREEN_HOME)
                } else {
                    toast("未拍到照片")
                }
            }
        }
        // 相册选图已改自建选图页(AlbumPickFragment 内复制后回调),GET_CONTENT 路线废弃
    }

    /** 后台线程:压缩 → 识别;回来后直填 MealEdit(仅供手记页「重试识别」按钮用) */
    private fun runRecognition(recognizer: FoodRecognizer, photoPath: String, queueId: Long) {
        if (recognizing) return
        recognizing = true
        Thread {
            val compressed = ImageUtil.compressForUpload(photoPath, photosDir())
            var items: List<RecognizedFood>? = null
            var error: String? = null
            recognizer.recognize(compressed) { it, err ->
                items = it
                error = err
            }
            runOnUiThread {
                recognizing = false
                val db = DbHelper.get(this)
                val list = items
                if (list != null && list.isNotEmpty()) {
                    val json = itemsToJson(list)
                    db.markRecognition(queueId, "已识别", json)
                    // 自动入账(2026-10-17 修丢账:识别成功只停在编辑页、忘点「记一笔」就丢)
                    // 设置「AI 识别后自动入账」默认开;开了→先落库再进编辑页,编辑页保存=改这笔
                    val autoSave = db.getSetting("auto_save_meal", "1") == "1"
                    val mealId = if (autoSave)
                        MealEditFragment.quickSaveFromRecognition(this, photoPath, json, "AI 识别自动入账")
                    else -1L
                    openMealEdit(
                        photoPath, json,
                        if (autoSave && mealId > 0)
                            "已自动入账 ≈ ${list.sumOf { it.kcal }.toInt()} kcal,这里是账单核对页:改完点「✓ 记一笔」更新,「删这笔」可撤"
                        else
                            "AI 识别到 ${list.size} 项(约),可直接记账,也可改",
                        mealId
                    )
                } else {
                    // 2026-10-18 设计决策:识别失败不跨轮次保存,队列行直接删
                    db.deleteRecognition(queueId)
                    openMealEdit(
                        photoPath, null,
                        "识别失败:${error ?: "没认出菜品"}(可点重试,或手记一笔)"
                    )
                }
            }
        }.start()
    }

    /** 手动重试识别(MealEdit 里点「重试识别」) */
    fun retryRecognition(photoPath: String) {
        val db = DbHelper.get(this)
        val queueId = db.enqueueRecognition(photoPath, timeFmt.format(Date()))
        val recognizer = buildRecognizer()
        if (recognizer == null) {
            toast("未配置视觉 API Key,先到设置里填好")
            return
        }
        toast("AI 识别中…")
        runRecognition(recognizer, photoPath, queueId)
    }

    private fun buildRecognizer(): FoodRecognizer? {
        val db = DbHelper.get(this)
        val engine = db.getSetting("vision_engine", VisionRecognizers.ENGINE_DEEPSEEK)
        var key = db.getSetting("vision_api_key_$engine", "")
        if (engine == VisionRecognizers.ENGINE_DEEPSEEK && key.isBlank())
            key = db.getSetting("ds_api_key", "")   // DeepSeek 复用聊天 key(2026-11-05)
        if (key.isBlank()) return null
        val model = db.getSetting("vision_model_$engine", "")
        return VisionRecognizers.build(engine, key, model)
    }

    /** 识别结果 → MealEdit 预填 JSON(三宏+候选与 parseItemsJson 键名对齐,2026-10-17) */
    private fun itemsToJson(items: List<RecognizedFood>): String {
        val arr = JSONArray()
        for (it in items) {
            arr.put(JSONObject().apply {
                put("name", it.name)
                put("grams", it.grams)
                put("kcal", it.kcal)
                put("protein", it.protein)
                put("carbs", it.carbs)
                put("fat", it.fat)
                if (it.candidates.isNotEmpty()) put("candidates", JSONArray(it.candidates))
                put("source", DbHelper.SRC_AI_VISION)
                put("foodId", it.foodId)
            })
        }
        return arr.toString()
    }

    // ---------- 蓝牙权限(秤用) ----------

    private fun neededBlePerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun ensureBlePermissions() {
        val missing = neededBlePerms().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), PERM_REQ)
    }

    fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
