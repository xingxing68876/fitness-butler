package dev.xd.fitnessbutler.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import dev.xd.fitnessbutler.data.DailyMetrics
import java.util.Locale

/**
 * 每日图表·多线同图(Canvas 自绘,零第三方依赖)。
 * null 断线留空;点任意日子出气泡(所有选中指标真实读数)。
 *
 * 左纵轴(2026-10-17 终版定案:一根纯数值轴):
 * - 所有可见指标按**纯数值**共用一根轴:范围=[全部可见已知值 min,max] 留 10% 边距,
 *   点/线 y 严格=纯数值映射,与左侧刻度逐点对齐(所见即读数);单/多指标行为统一。
 * - 刻度 3–5 档纯数字(不带单位):范围≥20 整数、≥2 一位小数、更小两位。
 * - 同 x 数值相同/极近的点仅 x 微移(±5dp)保可见,y 一个像素不动。
 * - 已知取舍:数值量级差大的指标同显(如消耗 809 vs 体脂 22.4)小数值贴底——纯数值轴固有特性。
 */
class TrendChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Series(val meta: DailyMetrics.Meta, val values: List<Double?>)

    /** 展示日期标签(与 values 对齐) */
    var dates: List<String> = emptyList()
    /** X 轴短日期(如 10.26,与 dates 对齐;空则不画轴标) */
    var axisLabels: List<String> = emptyList()
    var series: List<Series> = emptyList()
    var visibleIds: Set<String> = emptySet()
    var selectedIndex: Int? = null
    var onDateTap: ((Int) -> Unit)? = null

    /** 图例行(2026-10-26 当日详情用):true 时底部画各系列图例,点击回调 onLegendTap;日历页默认关(侧栏即图例) */
    var showLegend = false
    var onLegendTap: ((String) -> Unit)? = null

    /** 跨断点连线(2026-10-26 当日详情用):采样稀疏时相邻非空点直连,不留断口;日历页默认关(null=真无数据) */
    var connectAcrossGaps = false

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x14000000; strokeWidth = dp(1f); style = Paint.Style.STROKE
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2.6f)
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x40141414; strokeWidth = dp(1f); style = Paint.Style.STROKE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubbleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF141414.toInt(); style = Paint.Style.FILL
    }
    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = dp(9.5f)
    }
    private val bubbleTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = dp(10f); isFakeBoldText = true
    }
    // 刻度小字(灰)
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80141414.toInt(); textSize = dp(7f)
    }
    private val path = Path()
    private val bubbleRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val padL = dp(28f)   // 左纵轴刻度
        val padR = dp(8f)
        val padT = dp(10f)
        val legendH = if (showLegend) dp(18f) else 0f
        val padB = (if (axisLabels.size == dates.size) dp(18f) else dp(8f)) + legendH   // 底部 X 轴日期 + 图例
        val w = width - padL - padR
        val h = height - padT - padB
        if (w <= 0 || h <= 0 || dates.size < 2) return

        // 网格 5 行
        for (r in 0..5) {
            val y = padT + r * h / 5f
            canvas.drawLine(padL, y, padL + w, y, gridPaint)
        }

        val step = w / (dates.size - 1).toFloat()
        fun xAt(i: Int) = padL + i * step

        // 可见且有数据的线
        val vis = series.filter { it.meta.id in visibleIds && it.values.any { v -> v != null } }

        // ---- 一根纯数值轴(2026-10-17 终版):全部可见已知值按纯数值共用一轴,
        // 点/线 y 严格=纯数值映射,与左侧刻度逐点对齐;废除各自归一化与纵向 dodge。----
        var axisMin = 0.0
        var axisMax = 100.0
        val allKnown = vis.flatMap { it.values.filterNotNull() }
        if (allKnown.isNotEmpty()) {
            var mn = allKnown.min(); var mx = allKnown.max()
            if (mx - mn < 1e-9) {
                val d = maxOf(Math.abs(mn) * 0.02, 1.0)   // 范围退化防御:±2% 或 ±1
                mn -= d; mx += d
            }
            val pad = (mx - mn) * 0.10
            axisMin = mn - pad; axisMax = mx + pad
        }
        val axisRange = axisMax - axisMin

        /** 值 → y:纯数值直接映射(验收铁线:69.05 的点就在 69 刻度高度) */
        fun yOf(v: Double): Float =
            padT + (1f - ((v - axisMin) / axisRange).coerceIn(0.0, 1.0).toFloat()) * h

        // 左纵轴刻度:纯数字 5 档
        for (k in 0..4) {
            val frac = k / 4f
            val y = padT + (1f - frac) * h
            canvas.drawText(fmtTick(axisMin + axisRange * frac, axisRange), dp(2f), y + dp(2.5f), tickPaint)
        }

        // ---- 同 x 重叠仅横向微移(±5dp 交替):y 一个像素不动(动了就对不上左侧刻度);
        // 线仍连真实 x 序,点画在微移后落点。----
        val dotR = dp(2.6f)
        val minDistY = dotR * 3f
        val dodgeX = Array(vis.size) { DoubleArray(dates.size) { Double.NaN } }
        for (i in dates.indices) {
            val items = vis.indices.mapNotNull { si ->
                val v = vis[si].values.getOrNull(i) ?: return@mapNotNull null
                si to yOf(v).toDouble()
            }
            if (items.isEmpty()) continue
            val sorted = items.sortedBy { it.second }
            var cluster = mutableListOf<Pair<Int, Double>>()
            fun flush(cl: MutableList<Pair<Int, Double>>) {
                val n = cl.size
                for ((k, item) in cl.withIndex()) {
                    dodgeX[item.first][i] = (xAt(i) + (k - (n - 1) / 2f) * dp(5f)).toDouble()
                }
            }
            for (p in sorted) {
                if (cluster.isEmpty() || p.second - cluster.last().second < minDistY) cluster.add(p)
                else { flush(cluster); cluster = mutableListOf(p) }
            }
            flush(cluster)
        }

        // 画线(null 断线/跨断点直连,已知点小圆点;y=纯数值映射,x 同值簇微移)
        for ((si, s) in vis.withIndex()) {
            linePaint.color = s.meta.color
            dotPaint.color = s.meta.color
            path.reset()
            var penDown = false
            for (i in s.values.indices) {
                val v = s.values[i]
                if (v == null) {
                    if (!connectAcrossGaps) penDown = false
                    continue
                }
                val x = xAt(i)
                val y = yOf(v)
                if (!penDown) { path.moveTo(x, y); penDown = true } else path.lineTo(x, y)
                // 已知点画小圆点:单点数据也能看见(线需要≥2点,点恒可见)
                val px = dodgeX[si][i].let { if (it.isNaN()) x else it.toFloat() }
                canvas.drawCircle(px, y, dotR, dotPaint)
            }
            canvas.drawPath(path, linePaint)
        }

        // 选中日:竖线 + 各线读数点 + 气泡
        val sel = selectedIndex
        if (sel != null && sel in dates.indices) {
            val x = xAt(sel)
            canvas.drawLine(x, padT, x, padT + h, guidePaint)

            // 气泡列"全部可见指标"(含无数据显示 --),勾了就有反馈;强调点 y=纯数值映射
            val lines = mutableListOf<String>()
            for (s in series) {
                if (s.meta.id !in visibleIds) continue
                val v = s.values.getOrNull(sel)
                lines.add("${s.meta.label} ${DailyMetrics.fmtBubble(s.meta, v)}")
                val si = vis.indexOfFirst { it.meta.id == s.meta.id }
                if (si >= 0 && v != null) {
                    dotPaint.color = s.meta.color
                    val px = dodgeX[si][sel].let { if (it.isNaN()) x else it.toFloat() }
                    canvas.drawCircle(px, yOf(v), dotR + dp(0.6f), dotPaint)
                }
            }
            drawBubble(canvas, x, padT, lines, dates[sel])
        }

        // X 轴日期标签(2026-10-26 设计决策:如 10.26;按宽度抽稀防重叠)
        if (axisLabels.size == dates.size && dates.size >= 2) {
            val stepPx = w / (dates.size - 1).toFloat()
            val every = maxOf(1, Math.ceil((dp(32f) / stepPx).toDouble()).toInt())
            for (i in dates.indices) {
                val isLast = i == dates.size - 1
                if (i % every != 0 && !isLast) continue
                val t = axisLabels[i]
                val tw = tickPaint.measureText(t)
                var lx = xAt(i) - tw / 2
                if (lx < padL - dp(6f)) lx = padL - dp(6f)
                if (lx + tw > width - dp(2f)) lx = width - dp(2f) - tw
                canvas.drawText(t, lx, height - dp(4f) - legendH, tickPaint)
            }
        }

        // 图例行(当日详情):可见系列色块+名,隐藏系列淡显;点击切换
        if (showLegend && series.isNotEmpty()) {
            legendRects.clear()
            var lx = padL
            val ly = height - legendH + dp(3f)
            val labelPaint = Paint(tickPaint).apply { textSize = dp(8.5f); color = 0xCC141414.toInt() }
            for (s in series) {
                val label = s.meta.label
                val tw = labelPaint.measureText(label)
                val itemW = dp(12f) + tw + dp(8f)
                if (lx + itemW > width - dp(4f)) break   // 一行放不下就截断
                val on = s.meta.id in visibleIds
                dotPaint.color = if (on) s.meta.color else 0x44141414
                canvas.drawCircle(lx + dp(4f), ly + dp(4.5f), dp(3.4f), dotPaint)
                labelPaint.color = if (on) 0xCC141414.toInt() else 0x44141414
                canvas.drawText(label, lx + dp(12f), ly + dp(8f), labelPaint)
                legendRects.add(Triple(s.meta.id, RectF(lx, height - legendH, lx + itemW, height.toFloat()), on))
                lx += itemW
            }
        }
    }

    /** 图例命中区(onDraw 填充,触摸查询:id, 区域, 当前是否可见) */
    private val legendRects: MutableList<Triple<String, RectF, Boolean>> = mutableListOf()

    /** 刻度纯数字:范围大取整数,中小带 1~2 位小数(不带任何单位) */
    private fun fmtTick(v: Double, range: Double): String =
        if (range >= 20) String.format(Locale.US, "%.0f", v)
        else if (range >= 2) String.format(Locale.US, "%.1f", v)
        else String.format(Locale.US, "%.2f", v)

    private fun drawBubble(canvas: Canvas, x: Float, top: Float, lines: List<String>, title: String) {
        if (lines.isEmpty()) return
        val padH = dp(8f); val padV = dp(6f); val lineH = dp(12f)
        var textW = bubbleTitlePaint.measureText(title)
        for (l in lines) textW = maxOf(textW, bubbleTextPaint.measureText(l))
        val bw = textW + padH * 2
        val bh = padV * 2 + lineH * (lines.size + 1)
        var left = x - bw / 2
        if (left < dp(2f)) left = dp(2f)
        if (left + bw > width - dp(2f)) left = width - dp(2f) - bw
        val bt = top + dp(2f)
        bubbleRect.set(left, bt, left + bw, bt + bh)
        canvas.drawRoundRect(bubbleRect, dp(9f), dp(9f), bubbleBgPaint)
        var ty = bt + padV + lineH * 0.8f
        canvas.drawText(title, left + padH, ty, bubbleTitlePaint)
        for (l in lines) {
            ty += lineH
            canvas.drawText(l, left + padH, ty, bubbleTextPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 图例点击(DOWN 才触发,拖不误触):切系列显隐由外部执行
        if (event.action == MotionEvent.ACTION_DOWN && showLegend) {
            for ((id, rect, on) in legendRects) {
                if (rect.contains(event.x, event.y)) {
                    onLegendTap?.invoke(id)
                    return true
                }
            }
        }
        // 按住即选中,拖动连续跟手(2026-10-26 设计决策);抬起保持选中
        if (dates.size >= 2 &&
            (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE)
        ) {
            val padL = dp(28f); val padR = dp(8f)
            val w = width - padL - padR
            if (w > 0 && event.y >= 0 && event.y <= height) {
                val step = w / (dates.size - 1).toFloat()
                val i = Math.round((event.x - padL) / step).coerceIn(0, dates.size - 1)
                if (i != selectedIndex) {
                    selectedIndex = i
                    onDateTap?.invoke(i)
                    invalidate()
                }
            }
        }
        return true
    }

    /** 侧栏最新值格式化工具(与侧栏共用) */
    companion object {
        fun fmtSidebar(meta: DailyMetrics.Meta, v: Double?): String =
            if (v == null) "--"
            else if (meta.decimals == 0) v.toInt().toString() + meta.sidebarUnit
            else String.format(Locale.US, "%.${meta.decimals}f", v) + meta.sidebarUnit
    }
}
