package dev.xd.fitnessbutler.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import dev.xd.fitnessbutler.data.DailyMetrics

/**
 * 一日分时段图(Canvas 自绘):0–24 时横轴,0–100 固定纵轴。
 * - 睡眠 = 顶部深浅/REM 分期条(无数据不画)
 * - 血氧/压力 = 小时值 0–100 轴直接映射(95→0.95 高,38→0.38 高),共用纵轴天然上下分开。
 *   2026-10-17 治错乱:废除各自 min-max 归一化(微波动被放大成巨幅穿插跳线=图形错乱根因),
 *   照 demo hp() 同款固定轴。
 * - 运动 = 真实时段色块(无时段汇总条目由喂数据侧过滤,不画)
 * - 称重 = 单点(标注时刻,label 重叠只标最上一个)
 * - 三餐 = 底部小柱 + 热量
 * 左纵轴 0/50/100 纯数字,横轴 0/6/12/18/24 时;小时线无数据不画假线,提示攒采样中。
 */
class DayTimelineView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Exercise(val startHour: Double, val endHour: Double, val intensity: Float)
    data class WeighPoint(val hourFrac: Double, val weightKg: Double, val label: String)
    data class MealMark(val hourFrac: Double, val kcal: Double)

    var sleepSegs: List<DailyMetrics.SleepSeg>? = null
    var spo2Hourly: DoubleArray? = null
    var stressHourly: DoubleArray? = null
    var exercises: List<Exercise> = emptyList()
    var weighings: List<WeighPoint> = emptyList()
    var meals: List<MealMark> = emptyList()

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x14000000; strokeWidth = dp(1f); style = Paint.Style.STROKE
    }
    private val sleepColors = intArrayOf(
        0xFF5A8F2E.toInt(), // 深睡
        0xFF8FB24E.toInt(), // 浅睡
        0xFFC8DFA0.toInt(), // REM
        0x00000000          // 清醒(透明)
    )
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1.8f)
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF141414.toInt(); textSize = dp(7.5f)
    }
    // 刻度小字(灰)
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80141414.toInt(); textSize = dp(7f)
    }
    private val path = Path()
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val padL = dp(22f)   // 左纵轴刻度(0/50/100)
        val padR = dp(6f)
        val padB = dp(11f)   // 横轴时刻文字
        val padT = dp(2f)
        val w = width - padL - padR
        if (w <= 0 || height <= 0) return

        fun xAt(hour: Double) = padL + (hour / 24.0).toFloat() * w

        // 睡眠分期条(顶,允许 23:x→24 与 0→6:x 两截=跨午夜)
        var mainTop = padT
        val segs = sleepSegs
        if (segs != null && segs.isNotEmpty()) {
            val barH = dp(7f)
            for (s in segs) {
                if (s.stage !in 0..3) continue
                val color = sleepColors[s.stage]
                if (color == 0) continue
                fillPaint.color = color
                rect.set(xAt(s.startHour), mainTop, xAt(s.endHour), mainTop + barH)
                canvas.drawRoundRect(rect, dp(3.5f), dp(3.5f), fillPaint)
            }
            mainTop += barH + dp(4f)
        }

        val mainBottom = height - padB
        val mainH = mainBottom - mainTop
        if (mainH <= dp(10f)) return

        // 0–100 固定轴:值直接映射高度(95→0.95,38→0.38),血氧/压力天然上下分开
        fun yAt(v: Double) = mainBottom - (v.coerceIn(0.0, 100.0) / 100.0).toFloat() * mainH

        // 竖网格 0/6/12/18/24 + 横轴时刻文字
        for (hr in intArrayOf(0, 6, 12, 18, 24)) {
            val x = xAt(hr.toDouble())
            canvas.drawLine(x, mainTop, x, mainBottom, gridPaint)
            val label = if (hr == 0) "0时" else if (hr == 24) "24时" else "${hr}时"
            val tw = tickPaint.measureText(label)
            val tx = (x - tw / 2f).coerceIn(dp(1f), width - tw - dp(1f))
            canvas.drawText(label, tx, height - dp(2f), tickPaint)
        }

        // 左纵轴刻度 0/50/100(纯数字,灰)
        for (tick in intArrayOf(0, 50, 100)) {
            val y = yAt(tick.toDouble())
            canvas.drawText(tick.toString(), dp(2f), y + dp(2.5f), tickPaint)
        }

        // 小时线:有数据才画(0–100 直映射);两条都没有=提示攒采样,绝不画假线
        val hasHourly = (spo2Hourly != null) || (stressHourly != null)
        if (!hasHourly) {
            val ph = Paint(textPaint).apply { color = 0x66141414; textSize = dp(9f) }
            val msg = "血氧/压力小时曲线采样攒数据中"
            val tw = ph.measureText(msg)
            canvas.drawText(msg, padL + (w - tw) / 2f, mainTop + mainH / 2f, ph)
        }
        drawHourlyLine(canvas, spo2Hourly, 0xFF4A90D9.toInt(), ::xAt, ::yAt)
        drawHourlyLine(canvas, stressHourly, 0xFFC94F7C.toInt(), ::xAt, ::yAt)

        // 运动色块(色深=强度;无真实时段的汇总条目喂数据侧已过滤)
        for (ex in exercises) {
            val a = (0.10f + 0.22f * ex.intensity.coerceIn(0f, 1f))
            fillPaint.color = (0xFFE07B39.toInt() and 0x00FFFFFF) or ((a * 255).toInt() shl 24)
            rect.set(xAt(ex.startHour) + dp(1f), mainTop, xAt(ex.endHour) - dp(1f), mainBottom)
            canvas.drawRoundRect(rect, dp(3f), dp(3f), fillPaint)
        }

        // 三餐小柱(底,高度∝热量)
        val maxKcal = (meals.maxOfOrNull { it.kcal } ?: 0.0).coerceAtLeast(1.0)
        for (m in meals) {
            val bh = (m.kcal / maxKcal).toFloat() * mainH * 0.42f
            fillPaint.color = 0x8C5A8F2E.toInt()
            val x = xAt(m.hourFrac)
            rect.set(x - dp(2.5f), mainBottom - bh, x + dp(2.5f), mainBottom)
            canvas.drawRoundRect(rect, dp(2f), dp(2f), fillPaint)
        }

        // 称重点 + 时刻(label 防重叠:靠近上一个 label 就只画点不画字)
        var lastLabelX = Float.NEGATIVE_INFINITY
        for (pt in weighings) {
            val x = xAt(pt.hourFrac)
            val y = mainTop + mainH * 0.45f
            dotPaint.color = 0xFF141414.toInt()
            canvas.drawCircle(x, y, dp(3.2f), dotPaint)
            if (x - lastLabelX > dp(22f)) {
                canvas.drawText(pt.label, x + dp(4.5f), y + dp(2.5f), textPaint)
                lastLabelX = x
            }
        }
    }

    /** 0–100 固定轴画线:值直接映射高度,断点提笔;单点画圆点(线需≥2点,点恒可见) */
    private fun drawHourlyLine(
        canvas: Canvas, arr: DoubleArray?, color: Int,
        xAt: (Double) -> Float, yAt: (Double) -> Float
    ) {
        if (arr == null) return
        linePaint.color = color
        dotPaint.color = color
        path.reset()
        var pen = false
        for (h in 0..23) {
            val v = arr[h]
            if (v.isNaN()) { pen = false; continue }
            val x = xAt(h + 0.5)
            val y = yAt(v)
            if (!pen) { path.moveTo(x, y); pen = true } else path.lineTo(x, y)
            canvas.drawCircle(x, y, dp(2.2f), dotPaint)
        }
        canvas.drawPath(path, linePaint)
    }
}
