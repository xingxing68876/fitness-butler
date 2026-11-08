package dev.xd.fitnessbutler.ui

import android.app.Fragment
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.xd.fitnessbutler.MainActivity
import dev.xd.fitnessbutler.R
import dev.xd.fitnessbutler.db.DbHelper
import dev.xd.fitnessbutler.health.CaloricBalance
import dev.xd.fitnessbutler.file.PhotoFileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 打卡分享图页(2026-11-06 设计决策,替代「图鉴」tab):
 * Keep 风海报 1080×1440(3:4 朋友圈/小红书通用),背景=内置 6 张+相册选图(3:4 裁切),
 * 数据=运动消耗/摄入/缺口(绝对值)+体重体脂差额(隐私:只差额不显绝对值)+连续打卡,
 * 文字=内置鸡汤随机/自定义一行(颜色可选,字号固定),昵称可开关,底部软件名水印+生成时间,
 * 生成后系统分享面板拉微信。
 */
@Suppress("DEPRECATION")
class ShareCardFragment : Fragment() {

    // ---------- 状态 ----------
    private var bgIndex = 0                       // 0..5 内置;6=自定义
    private var showName = true
    private var textMode = MODE_QUOTE             // MODE_QUOTE / MODE_CUSTOM
    private var quoteIdx = 0
    private var customColor = COLOR_WHITE
    private var themeColor = COLOR_WHITE         // 主文字色(2026-11-06:浅色背景可切深色字)
    private var lastQuoteIdx = -1

    private lateinit var db: DbHelper
    private var posterData: PosterRenderer.Data? = null
    private var bgBitmap: Bitmap? = null          // 当前背景(解码后)
    private var pendingCropSrc: Bitmap? = null    // 裁切态的原图
    private var rendering = false

    private lateinit var ivPreview: ImageView
    private lateinit var tvBgIndex: TextView
    private lateinit var tvQuote: TextView
    private lateinit var boxCustom: View
    private lateinit var btnQuote: TextView
    private lateinit var btnCustom: TextView
    private lateinit var etCustom: EditText
    private lateinit var swName: Switch
    private lateinit var cropOverlay: View

    companion object {
        private const val MODE_QUOTE = 0
        private const val MODE_CUSTOM = 1
        private const val COLOR_WHITE = 0xFFFFFFFF.toInt()
        private const val COLOR_YELLOW = 0xFFFFD54F.toInt()
        private const val COLOR_GREEN = 0xFF4ADE80.toInt()
        private const val COLOR_ORANGE = 0xFFFB923C.toInt()
        private const val COLOR_BLACK = 0xFF141414.toInt()
        private const val DOWN = 0xFF4ADE80.toInt()   // 降=绿
        private const val UP = 0xFFFB923C.toInt()     // 升=橙

        /** 内置背景名(assets/sharebg/bg1..6.jpg) */
        private val BG_LABELS = listOf("雾气杠铃", "日落跑者", "海滩日落", "黄金拉伸", "水下自由泳", "深水飞溅")

        private val QUOTES = listOf(
            "自律给我自由",
            "汗水不会辜负每一次坚持",
            "今天的努力,是明天的实力",
            "每一步都算数",
            "身体是革命的本钱,锻炼是最好的投资",
            "强壮的身体,装得下有趣的灵魂",
            "别人在休息,你在变强",
            "坚持的意义,是遇见更好的自己",
            "流汗的感觉,比后悔好受多了",
            "你流的每一滴汗,都在雕刻未来的你",
            "与其羡慕别人,不如点亮自己",
            "今天也是元气满满的一天"
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_share, container, false)
        db = DbHelper.get(activity)

        bgIndex = db.getSetting("share_bg_index", "0").toIntOrNull()?.coerceIn(0, 6) ?: 0
        showName = db.getSetting("share_show_name", "1") == "1"
        textMode = db.getSetting("share_text_mode", "0").toIntOrNull() ?: MODE_QUOTE
        customColor = db.getSetting("share_custom_color", COLOR_WHITE.toString()).toIntOrNull() ?: COLOR_WHITE
        themeColor = db.getSetting("share_theme_color", COLOR_WHITE.toString()).toIntOrNull() ?: COLOR_WHITE
        quoteIdx = (0 until QUOTES.size).random()

        ivPreview = v.findViewById(R.id.iv_preview)
        tvBgIndex = v.findViewById(R.id.tv_bg_index)
        tvQuote = v.findViewById(R.id.tv_quote)
        boxCustom = v.findViewById(R.id.box_custom)
        btnQuote = v.findViewById(R.id.btn_mode_quote)
        btnCustom = v.findViewById(R.id.btn_mode_custom)
        etCustom = v.findViewById(R.id.et_custom)
        swName = v.findViewById(R.id.sw_nickname)
        cropOverlay = v.findViewById(R.id.crop_overlay)
        etCustom.setText(db.getSetting("share_custom_text", ""))
        swName.isChecked = showName
        // 主文字色选择(全图文字,语义色除外)
        mapOf(
            R.id.t_white to COLOR_WHITE, R.id.t_black to COLOR_BLACK,
            R.id.t_yellow to COLOR_YELLOW, R.id.t_green to COLOR_GREEN,
            R.id.t_orange to COLOR_ORANGE
        ).forEach { (id, c) ->
            v.findViewById<TextView>(id).setOnClickListener { setThemeColor(c) }
        }
        paintThemeButtons(v)

        // 背景切换
        v.findViewById<TextView>(R.id.btn_bg_prev).setOnClickListener { shiftBg(-1) }
        v.findViewById<TextView>(R.id.btn_bg_next).setOnClickListener { shiftBg(1) }
        v.findViewById<TextView>(R.id.btn_bg_custom).setOnClickListener { openPickerForShare() }
        // 昵称
        swName.setOnCheckedChangeListener { _, checked ->
            showName = checked
            db.setSetting("share_show_name", if (checked) "1" else "0")
            renderPreview()
        }
        // 文字模式
        btnQuote.setOnClickListener { setTextMode(MODE_QUOTE) }
        btnCustom.setOnClickListener { setTextMode(MODE_CUSTOM) }
        // 颜色
        v.findViewById<TextView>(R.id.c_white).setOnClickListener { setCustomColor(COLOR_WHITE) }
        v.findViewById<TextView>(R.id.c_yellow).setOnClickListener { setCustomColor(COLOR_YELLOW) }
        v.findViewById<TextView>(R.id.c_green).setOnClickListener { setCustomColor(COLOR_GREEN) }
        v.findViewById<TextView>(R.id.c_orange).setOnClickListener { setCustomColor(COLOR_ORANGE) }
        v.findViewById<TextView>(R.id.c_black).setOnClickListener { setCustomColor(COLOR_BLACK) }
        // 文案
        tvQuote.setOnClickListener {
            quoteIdx = ((quoteIdx + 1) % QUOTES.size).let { r -> if (r == lastQuoteIdx) (r + 1) % QUOTES.size else r }
            lastQuoteIdx = quoteIdx
            renderPreview()
        }
        // 自定义输入
        etCustom.setOnFocusChangeListener { _, has ->
            if (!has) {
                db.setSetting("share_custom_text", etCustom.text.toString().trim())
                renderPreview()
            }
        }
        // 生成&分享
        v.findViewById<TextView>(R.id.btn_generate).setOnClickListener { generateAndShare() }
        // 裁切按钮
        v.findViewById<TextView>(R.id.btn_crop_cancel).setOnClickListener {
            cropOverlay.visibility = View.GONE
        }
        v.findViewById<TextView>(R.id.btn_crop_ok).setOnClickListener { confirmCrop() }

        setTextMode(textMode)
        paintModeButtons()
        loadBgAndRender()
        loadData()
        return v
    }

    override fun onStart() {
        super.onStart()
        // 相册选图回执(MainActivity.pendingShareCropPath,2026-11-06 分享背景选图)
        val act = activity as? MainActivity ?: return
        val p = act.consumeShareCropPath()
        if (p != null) {
            val bmp = BitmapFactory.decodeFile(p)
            if (bmp != null) {
                pendingCropSrc = bmp
                cropOverlay.visibility = View.VISIBLE
                v.findViewById<dev.xd.fitnessbutler.ui.PosterCropView>(R.id.crop_view).setup(bmp)
            } else Toast.makeText(act, "图片读取失败", Toast.LENGTH_SHORT).show()
        }
    }

    private val v: View get() = view!!

    // ---------- 背景 ----------

    private fun shiftBg(delta: Int) {
        bgIndex = ((bgIndex + delta + 7) % 7)
        db.setSetting("share_bg_index", bgIndex.toString())
        loadBgAndRender()
    }

    private fun loadBgAndRender() {
        // Thread 外先抓 context 局部引用,防切页后 activity 为 null 崩溃
        val fd = filesDir()
        val am = assets()
        val idx = bgIndex
        Thread {
            val bmp: Bitmap? = if (idx == 6) {
                BitmapFactory.decodeFile(File(File(fd, "share"), "custom_bg.jpg").absolutePath)
            } else {
                runCatching {
                    am.open("sharebg/bg${idx + 1}.jpg").use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
            bgBitmap = bmp
            activity?.runOnUiThread {
                tvBgIndex.text = if (bgIndex == 6) "自定义图" else "背景 ${bgIndex + 1}/${BG_LABELS.size} · ${BG_LABELS[bgIndex]}"
                renderPreview()
            }
        }.start()
    }

    private fun setThemeColor(c: Int) {
        themeColor = c
        db.setSetting("share_theme_color", c.toString())
        paintThemeButtons()
        renderPreview()
    }

    private fun paintThemeButtons(root: View? = null) {
        val r = root ?: view ?: return
        val map = mapOf(
            R.id.t_white to COLOR_WHITE, R.id.t_black to COLOR_BLACK,
            R.id.t_yellow to COLOR_YELLOW, R.id.t_green to COLOR_GREEN,
            R.id.t_orange to COLOR_ORANGE
        )
        map.forEach { (id, c) ->
            val btn = r.findViewById<TextView>(id)
            btn.setBackgroundResource(if (c == themeColor) R.drawable.bg_tag_ink else R.drawable.bg_tag_out)
            btn.setTextColor(if (c == themeColor) Color.WHITE else 0xFF141414.toInt())
        }
    }

    private fun setCustomColor(c: Int) {
        customColor = c
        db.setSetting("share_custom_color", c.toString())
        renderPreview()
    }

    private fun setTextMode(m: Int) {
        textMode = m
        db.setSetting("share_text_mode", m.toString())
        boxCustom.visibility = if (m == MODE_CUSTOM) View.VISIBLE else View.GONE
        tvQuote.text = if (m == MODE_CUSTOM)
            (etCustom.text.toString().ifBlank { "自定义文字(输入后生效)" })
        else QUOTES[quoteIdx]
        paintModeButtons()
    }

    private fun paintModeButtons() {
        btnQuote.setBackgroundResource(if (textMode == MODE_QUOTE) R.drawable.bg_tag_ink else R.drawable.bg_tag_out)
        btnQuote.setTextColor(if (textMode == MODE_QUOTE) Color.WHITE else 0xFF141414.toInt())
        btnCustom.setBackgroundResource(if (textMode == MODE_CUSTOM) R.drawable.bg_tag_ink else R.drawable.bg_tag_out)
        btnCustom.setTextColor(if (textMode == MODE_CUSTOM) Color.WHITE else 0xFF141414.toInt())
    }

    private fun openPickerForShare() {
        (activity as? MainActivity)?.openAlbumPickForShare()
    }

    private fun confirmCrop() {
        val cv = v.findViewById<dev.xd.fitnessbutler.ui.PosterCropView>(R.id.crop_view)
        val cropped = cv.cropTo(1080, 1440)
        if (cropped == null) {
            Toast.makeText(activity, "裁切失败,再调整一下", Toast.LENGTH_SHORT).show()
            return
        }
        val dir = File(filesDir(), "share").apply { mkdirs() }
        File(dir, "custom_bg.jpg").outputStream().use { cropped.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        cropped.recycle()
        cropOverlay.visibility = View.GONE
        bgIndex = 6
        db.setSetting("share_bg_index", "6")
        loadBgAndRender()
    }

    // ---------- 数据 ----------

    private fun loadData() {
        Thread {
            val d = fetch()
            posterData = d
            activity?.runOnUiThread { renderPreview() }
        }.start()
    }

    private fun fetch(): PosterRenderer.Data {
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val today = dateFmt.format(Date())

        val burn = db.burnKcalOn(today)
        val intake = db.intakeKcalOn(today)
        val gap = -(CaloricBalance.gapOn(db, today))   // 正=缺口

        // 称重:最近 14 天
        val cal = Calendar.getInstance()
        val all = ArrayList<Pair<String, DbHelper.Weighing>>()
        for (i in 0..13) {
            cal.time = Date()
            cal.add(Calendar.DAY_OF_YEAR, -i)
            val dstr = dateFmt.format(cal.time)
            db.weighingsOnDate(dstr).lastOrNull()?.let { all.add(dstr to it) }
        }
        fun fat(w: DbHelper.Weighing): Double? = runCatching {
            val f = org.json.JSONObject(w.metricsJson ?: "").optDouble("body_fat_pct", Double.NaN)
            if (f.isNaN()) null else f
        }.getOrNull()

        val latest = all.firstOrNull()
        // 昨天:日期恰好昨天的记录
        cal.time = Date(); cal.add(Calendar.DAY_OF_YEAR, -1)
        val ystr = dateFmt.format(cal.time)
        val yday = all.firstOrNull { it.first == ystr }
        // 上周:≥6 天前的最近一条
        val week = all.firstOrNull { d -> daysBetween(d.first, today) >= 6 }

        val wDay: Double? = if (latest != null && yday != null) latest.second.weightKg - yday.second.weightKg else null
        val wWeek: Double? = if (latest != null && week != null) latest.second.weightKg - week.second.weightKg else null
        val fLatest = latest?.second?.let { fat(it) }
        val fWeek = week?.second?.let { fat(it) }
        val fDelta: Double? = if (fLatest != null && fWeek != null) fLatest - fWeek else null

        // 连续打卡:今天没数据从昨天起算,逐日回数有称重或有记账的天
        var streak = 0
        run {
            val c = Calendar.getInstance()
            fun has(dstr: String) = db.weighingsOnDate(dstr).isNotEmpty() || db.intakeKcalOn(dstr) > 0
            c.time = Date()
            if (!has(dateFmt.format(c.time))) c.add(Calendar.DAY_OF_YEAR, -1)
            for (i in 0..365) {
                if (has(dateFmt.format(c.time))) { streak++; c.add(Calendar.DAY_OF_YEAR, -1) } else break
            }
        }

        val nickname = db.getSetting("nickname", "老板")
        val quote = if (textMode == MODE_CUSTOM) etCustom.text.toString().ifBlank { "今天也是元气满满的一天" }
        else QUOTES[quoteIdx]
        val quoteColor = if (textMode == MODE_CUSTOM) customColor else themeColor

        val nowFmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)

        return PosterRenderer.Data(
            burn = burn.takeIf { it > 0 },
            intake = intake.takeIf { it > 0 },
            gap = gap.takeIf { intake > 0 },
            wDeltaDay = wDay, wDeltaWeek = wWeek, fatDelta = fDelta,
            streak = streak.takeIf { it > 0 },
            nickname = nickname, showName = showName,
            themeColor = themeColor,
            quote = quote, quoteColor = quoteColor,
            nowText = nowFmt.format(Date())
        )
    }

    private fun daysBetween(a: String, b: String): Int {
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val da = f.parse(a) ?: return 0
        val dbb = f.parse(b) ?: return 0
        return ((dbb.time - da.time) / 86400000L).toInt()
    }

    private fun filesDir(): File = activity!!.filesDir
    private fun assets() = activity!!.assets

    // ---------- 渲染 / 生成 ----------

    private fun renderPreview() {
        var d = posterData ?: return
        // 实时同步:UI 上的状态(颜色/文案/昵称)刷进数据快照,切了立即生效
        d = d.copy(
            themeColor = themeColor,
            quote = if (textMode == MODE_CUSTOM) etCustom.text.toString().ifBlank { "今天也是元气满满的一天" }
                    else QUOTES[quoteIdx],
            quoteColor = if (textMode == MODE_CUSTOM) customColor else themeColor,
            showName = showName
        )
        posterData = d
        ivPreview.setImageBitmap(PosterRenderer.render(bgBitmap, d, 540, 720))
    }

    private fun generateAndShare() {
        val act = activity as? MainActivity ?: return
        if (posterData == null) {
            Toast.makeText(act, "数据还在加载", Toast.LENGTH_SHORT).show()
            return
        }
        val dir = File(filesDir(), "share").apply { mkdirs() }
        val f = File(dir, "poster.jpg")
        Thread {
            val bmp = PosterRenderer.render(bgBitmap, posterData!!, 1080, 1440)
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 98, it) }
            bmp.recycle()
            (act as? android.app.Activity)?.runOnUiThread {
                // 存相册副本
                act.saveToSystemGallery(f)
                // 系统分享面板(content:// 走 PhotoFileProvider share/ 路径)
                val uri = Uri.parse("content://${PhotoFileProvider.AUTHORITY}/share/poster.jpg")
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, "分享打卡海报"))
            }
        }.start()
    }
}

/** 海报绘制器:纯函数,预览与成图共用一套坐标(按宽缩放) */
object PosterRenderer {

    const val BASE_W = 1080
    const val BASE_H = 1440

    data class Data(
        val burn: Double?,
        val intake: Double?,
        val gap: Double?,          // 正=缺口
        val wDeltaDay: Double?,    // 体重 vs 昨天
        val wDeltaWeek: Double?,   // vs 上周
        val fatDelta: Double?,     // 体脂 vs 上周
        val streak: Int?,
        val nickname: String?,
        val showName: Boolean,
        val themeColor: Int,
        val quote: String,
        val quoteColor: Int,
        val nowText: String
    )

    private const val DOWN = 0xFF4ADE80.toInt()
    private const val UP = 0xFFFB923C.toInt()

    fun render(bg: Bitmap?, d: Data, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val s = w.toFloat() / BASE_W          // 缩放系数

        // 背景 crop-to-fill
        if (bg != null) {
            val sc = max(w.toFloat() / bg.width, h.toFloat() / bg.height)
            val dw = bg.width * sc; val dh = bg.height * sc
            val m = Matrix()
            m.postScale(sc, sc)
            m.postTranslate((w - dw) / 2f, (h - dh) / 2f)
            c.drawBitmap(bg, m, null as Paint?)
        } else {
            c.drawColor(0xFF1B1E24.toInt())
        }
        // 主色与亮度:字色深→白蒙版,字色浅→黑蒙版(蒙版永远与字色反配)
        val tc = d.themeColor
        fun tint(a: Int): Int = Color.argb(a, Color.red(tc), Color.green(tc), Color.blue(tc))
        val lum = (0.299f * Color.red(tc) + 0.587f * Color.green(tc) + 0.114f * Color.blue(tc)) / 255f
        val lightBg = lum < 0.5f
        // 蒙版与字色反配:黑字→白蒙版压浅背景,白字→黑蒙版压深背景(2026-11-06 用户定)
        val mask = Paint().apply {
            shader = if (lightBg) LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(0x59FFFFFF.toInt(), 0x40FFFFFF.toInt(), 0x99FFFFFF.toInt(), 0xD9FFFFFF.toInt()),
                floatArrayOf(0f, 0.30f, 0.62f, 1f), Shader.TileMode.CLAMP
            ) else LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(0x64000000.toInt(), 0x2E000000.toInt(), 0xB3000000.toInt(), 0xE6000000.toInt()),
                floatArrayOf(0f, 0.30f, 0.62f, 1f), Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), mask)

        fun px(dp: Float) = dp * s
        fun paint(sizeDp: Float, color: Int, bold: Boolean = true): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = px(sizeDp)
            this.color = color
            typeface = if (bold) Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD) else Typeface.DEFAULT
        }

        // 顶部:昵称 + 日期(2x 定稿排版 2026-11-06)
        val dateText = java.text.SimpleDateFormat("yyyy.M.d", Locale.US).format(Date())
        if (d.showName && !d.nickname.isNullOrBlank()) {
            c.drawText(d.nickname!!, px(56f), px(120f), paint(48f, tint(0xE6)))
        }
        c.drawText(dateText, w - px(56f), px(120f), paint(48f, tint(0xB3), bold = false).apply { textAlign = Paint.Align.RIGHT })

        // 运动消耗(大数字)
        c.drawText("今日运动消耗", px(56f), px(262f), paint(42f, tint(0xCC), bold = false))
        val burnV = d.burn
        val bp = paint(148f, tc)
        val kcalP = paint(52f, tint(0xD9), bold = false)
        if (burnV != null) {
            val t = if (burnV >= 10) "%.0f".format(Locale.US, burnV) else "%.1f".format(Locale.US, burnV)
            c.drawText(t, px(56f), px(470f), bp)
            c.drawText("kcal", px(56f) + bp.measureText(t) + px(20f), px(470f), kcalP)
        } else {
            c.drawText("--", px(56f), px(470f), paint(148f, tint(0x66)))
        }

        // 摄入 / 缺口
        c.drawText(
            "摄入热量  " + (d.intake?.let { "%.0f".format(Locale.US, it) + " kcal" } ?: "--"),
            px(56f), px(600f), paint(54f, tint(0xF2))
        )
        if (d.gap != null) {
            val g = d.gap
            val gp = paint(54f, if (g >= 0) DOWN else UP)
            val txt = if (g >= 0) "热量缺口  %.0f kcal".format(Locale.US, g) else "热量超支  %.0f kcal".format(Locale.US, -g)
            c.drawText(txt, px(56f), px(690f), gp)
        }

        // 分隔线
        c.drawRect(px(56f), px(742f), w - px(56f), px(742f) + max(px(3f), px(1.2f)), Paint().apply { color = tint(0x40) })

        // 差额三行(左标签右值;升降语义色保留)
        data class Row(val label: String, val v: Double?, val unit: String)
        val rows = listOf(
            Row("体重 vs 昨天", d.wDeltaDay, "kg"),
            Row("体重 vs 上周", d.wDeltaWeek, "kg"),
            Row("体脂 vs 上周", d.fatDelta, "%")
        )
        var y = px(840f)
        val labelP = paint(44f, tint(0xCC), bold = false)
        val valP = paint(56f, tc)
        for (r in rows) {
            c.drawText(r.label, px(56f), y, labelP)
            if (r.v == null || abs(r.v) < 0.049) {
                c.drawText(if (r.v == null) "--" else "持平", w - px(56f), y,
                    valP.apply { color = tint(0xB3); textAlign = Paint.Align.RIGHT })
            } else {
                val sign = if (r.v > 0) "+" else "−"
                // 分段拼接:unit 含 "%" 时不能进 format 串(裸 % 会让 Formatter 炸 End of String,2026-11-06 实证)
                val num = "%.1f".format(Locale.US, abs(r.v))
                val txt = sign + num + " " + r.unit
                c.drawText(txt, w - px(56f), y, valP.apply { color = if (r.v > 0) UP else DOWN; textAlign = Paint.Align.RIGHT })
            }
            valP.textAlign = Paint.Align.LEFT
            y += px(95f)
        }

        // 打卡(黄色强调,黑白底均醒目)
        if (d.streak != null) {
            c.drawText("🔥 已连续打卡 ${d.streak} 天", px(56f), y + px(30f), paint(54f, 0xFFFFD54F.toInt()))
        }

        // 文案(居中,最多 2 行)
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = px(60f); color = d.quoteColor
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        }
        val sl = StaticLayout.Builder
            .obtain(d.quote, 0, d.quote.length, tp, (w - px(112f)).toInt())
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(2).build()
        c.save(); c.translate(px(56f), px(1150f)); sl.draw(c); c.restore()

        // 底部:水印 + 生成时间
        c.drawText("健身管家 · 记录每一天", px(56f), h - px(38f), paint(36f, tint(0x99), bold = false))
        c.drawText("${d.nowText} 生成", w - px(56f), h - px(38f), paint(36f, tint(0x99), bold = false).apply { textAlign = Paint.Align.RIGHT })

        return out
    }
}
