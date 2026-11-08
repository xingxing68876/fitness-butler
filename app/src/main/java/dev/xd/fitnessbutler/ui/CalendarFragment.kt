package dev.xd.fitnessbutler.ui

import android.app.Fragment
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.xd.fitnessbutler.MainActivity
import dev.xd.fitnessbutler.R
import dev.xd.fitnessbutler.data.DailyMetrics
import dev.xd.fitnessbutler.db.DbHelper
import dev.xd.fitnessbutler.health.CaloricBalance
import dev.xd.fitnessbutler.health.VivoHealthHarvester
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 日历改版(照 calendar-demo.html):
 *  1) 月历格带缺口数字(摄入−消耗;负=缺口绿 #2E7D32,正=盈余黑;今日墨黑底白字,今日负数浅绿 #9ED67A)
 *  2) 每日图表:左侧 9 行指标(点=显隐叠加,长按=只看这条再点恢复,未选中行变淡)
 *     + 右侧白底多线同图(各自归一化,点日子出真实读数气泡)+ 7/30/90 天范围
 *  3) 点日期格 → 一日详情(DayDetailFragment)
 */
@Suppress("DEPRECATION")
class CalendarFragment : Fragment() {

    private data class DayAgg(var intake: Double = 0.0, var burn: Double = 0.0, var bmr: Double = 0.0)

    private lateinit var db: DbHelper
    private lateinit var root: View

    private val cal = Calendar.getInstance()
    private var selYear = 0
    private var selMonth = 0 // 1~12

    // 图表状态
    private var rangeDays = 30
    private val visibleIds = linkedSetOf("weight", "bodyfat", "burn")
    private var soloPrev: Set<String>? = null
    private var soloId: String? = null
    private lateinit var chartView: TrendChartView
    private val sideRows = mutableMapOf<String, LinearLayout>()
    /** 当前图表系列缓存(侧栏联动读值用,免重复查库) */
    private var curSeries: List<TrendChartView.Series> = emptyList()

    private val isoFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        root = inflater.inflate(R.layout.fragment_calendar, container, false)
        db = DbHelper.get(activity)
        selYear = cal.get(Calendar.YEAR)
        selMonth = cal.get(Calendar.MONTH) + 1

        chartView = root.findViewById(R.id.chart_view)

        root.findViewById<TextView>(R.id.cal_prev).setOnClickListener {
            cal.add(Calendar.MONTH, -1)
            selYear = cal.get(Calendar.YEAR); selMonth = cal.get(Calendar.MONTH) + 1
            refresh()
        }
        root.findViewById<TextView>(R.id.cal_next).setOnClickListener {
            cal.add(Calendar.MONTH, 1)
            selYear = cal.get(Calendar.YEAR); selMonth = cal.get(Calendar.MONTH) + 1
            refresh()
        }

        // 范围切换
        bindRange(R.id.rng_7, 7)
        bindRange(R.id.rng_30, 30)
        bindRange(R.id.rng_90, 90)

        buildSidebar()
        refresh()

        // 进日历页静默收割一次手表数据(失败不打扰),完成刷新图表
        Thread {
            runCatching { VivoHealthHarvester.harvest(activity.applicationContext) }
            activity?.runOnUiThread { runCatching { if (isAdded) refreshChart() } }
        }.start()

        TextCopyUtil.apply(root)   // 全文字可复制(2026-10-17)
        return root
    }

    private fun bindRange(id: Int, days: Int) {
        root.findViewById<TextView>(id).setOnClickListener {
            rangeDays = days
            for ((i, d) in listOf(R.id.rng_7 to 7, R.id.rng_30 to 30, R.id.rng_90 to 90)) {
                val tv = root.findViewById<TextView>(i)
                tv.setBackgroundResource(if (d == days) R.drawable.bg_tag_ink else R.drawable.bg_tag_out)
                tv.setTextColor(if (d == days) 0xFFFFFFFF.toInt() else 0xFF141414.toInt())
            }
            chartView.selectedIndex = null   // 换范围重置回最新一天
            refreshChart()
        }
    }

    private fun refresh() {
        root.findViewById<TextView>(R.id.cal_title).text = "$selYear 年 $selMonth 月"
        buildGrid()
        refreshChart()
    }

    // ---------- 月历格(缺口数字) ----------

    private fun loadMonth(): Map<String, DayAgg> {
        val map = mutableMapOf<String, DayAgg>()
        fun agg(date: String) = map.getOrPut(date) { DayAgg() }
        val prefix = "%04d-%02d".format(Locale.US, selYear, selMonth)

        db.readableDatabase.rawQuery(
            "SELECT substr(ts,1,10) d, IFNULL(SUM(total_kcal),0) FROM meals WHERE substr(ts,1,7)=? GROUP BY d",
            arrayOf(prefix)
        ).use {
            while (it.moveToNext()) {
                agg(it.getString(0)).intake = it.getDouble(1)
            }
        }
        db.readableDatabase.rawQuery(
            "SELECT substr(ts,1,10) d, IFNULL(SUM(kcal_burned),0) FROM activities WHERE substr(ts,1,7)=? GROUP BY d",
            arrayOf(prefix)
        ).use {
            while (it.moveToNext()) {
                agg(it.getString(0)).burn = it.getDouble(1)
            }
        }
        // 基础代谢进缺口(2026-10-19):当前 BMR 回填全月(体重短期波动小,近似可接受)
        val bmrVal = CaloricBalance.bmr(db).kcal
        map.values.forEach { it.bmr = bmrVal }
        return map
    }

    private fun buildGrid() {
        val grid = root.findViewById<LinearLayout>(R.id.cal_grid)
        grid.removeAllViews()
        val monthData = loadMonth()

        val first = Calendar.getInstance().apply { set(selYear, selMonth - 1, 1) }
        val daysInMonth = first.getActualMaximum(Calendar.DAY_OF_MONTH)
        val startOffset = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7 // 周一为首
        val todayStr = isoFmt.format(Date())
        val density = resources.displayMetrics.density

        var day = 1
        var done = false
        for (week in 0 until 6) {
            if (done) break
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            var anyCell = false
            for (col in 0 until 7) {
                val lp = LinearLayout.LayoutParams(0, (46 * density).toInt(), 1f).apply {
                    marginStart = (2 * density).toInt(); marginEnd = (2 * density).toInt()
                    topMargin = (2 * density).toInt()
                }
                if (week * 7 + col < startOffset || day > daysInMonth) {
                    row.addView(View(activity), lp)
                } else {
                    anyCell = true
                    val d = day
                    val dateStr = "%04d-%02d-%02d".format(Locale.US, selYear, selMonth, d)
                    val isToday = dateStr == todayStr
                    val agg = monthData[dateStr]
                    val hasGap = agg != null && (agg.intake > 0 || agg.burn > 0)
                    val gapVal = if (hasGap) (agg!!.intake - agg.burn - agg.bmr).toInt() else null

                    val cell = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        if (isToday) setBackgroundResource(R.drawable.bg_cal_today)
                    }
                    cell.addView(TextView(activity).apply {
                        text = d.toString()
                        textSize = 12f
                        setTypeface(typeface, Typeface.BOLD)
                        gravity = Gravity.CENTER
                        setTextColor(if (isToday) 0xFFFFFFFF.toInt() else 0xFF141414.toInt())
                    })
                    cell.addView(TextView(activity).apply {
                        textSize = 8.5f
                        setTypeface(typeface, Typeface.BOLD)
                        gravity = Gravity.CENTER
                        text = when {
                            gapVal == null -> ""
                            gapVal < 0 -> gapVal.toString()   // -820 缺口
                            else -> "+$gapVal"                // +150 盈余
                        }
                        setTextColor(
                            when {
                                gapVal == null -> 0xFF141414.toInt()
                                gapVal < 0 && isToday -> 0xFF9ED67A.toInt()
                                gapVal < 0 -> 0xFF2E7D32.toInt()
                                isToday -> 0xFFFFFFFF.toInt()
                                else -> 0xFF141414.toInt()
                            }
                        )
                    })
                    cell.setOnClickListener { (activity as MainActivity).openDayDetail(dateStr) }
                    row.addView(cell, lp)
                    day++
                    if (day > daysInMonth) done = true
                }
            }
            if (anyCell) grid.addView(row)
        }
    }

    // ---------- 每日图表:侧栏 ----------

    private fun buildSidebar() {
        val side = root.findViewById<LinearLayout>(R.id.chart_side)
        side.removeAllViews()
        sideRows.clear()
        val density = resources.displayMetrics.density

        for (meta in DailyMetrics.METRICS) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_card_white)
                setPadding(
                    (7 * density).toInt(), (5 * density).toInt(),
                    (7 * density).toInt(), (5 * density).toInt()
                )
                alpha = if (meta.id in visibleIds) 1f else 0.35f
            }
            row.addView(
                View(activity).apply { setBackgroundColor(meta.color) },
                LinearLayout.LayoutParams((9 * density).toInt(), (9 * density).toInt())
            )
            row.addView(TextView(activity).apply {
                text = meta.label
                textSize = 9f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFF141414.toInt())
                setPadding((5 * density).toInt(), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(activity).apply {
                tag = "val_${meta.id}"
                text = "--"
                textSize = 9f
                setTextColor(0x8C141414.toInt())
            })

            row.setOnClickListener { onSideTap(meta.id) }
            row.setOnLongClickListener {
                soloPrev = visibleIds.toSet()
                soloId = meta.id
                visibleIds.clear()
                visibleIds.add(meta.id)
                refreshChart()
                true
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (3 * density).toInt() }
            side.addView(row, lp)
            sideRows[meta.id] = row
        }
    }

    /** 点行=显隐叠加;只看模式下再点=恢复,点别的行=退出只看后叠加 */
    private fun onSideTap(id: String) {
        val prev = soloPrev
        if (prev != null) {
            val solo = soloId
            soloPrev = null
            soloId = null
            visibleIds.clear()
            visibleIds.addAll(prev)
            if (id != solo && !visibleIds.remove(id)) visibleIds.add(id)
        } else {
            if (!visibleIds.remove(id)) visibleIds.add(id)
        }
        refreshChart()
    }

    // ---------- 每日图表:数据 + 侧栏最新值 ----------

    private fun refreshChart() {
        val dates = mutableListOf<String>()
        val display = mutableListOf<String>()
        val axisLs = mutableListOf<String>()
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, -(rangeDays - 1))
        val dfIso = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dfShow = SimpleDateFormat("M月d日", Locale.CHINA)
        val dfAxis = SimpleDateFormat("M.d", Locale.US)
        repeat(rangeDays) {
            dates.add(dfIso.format(c.time))
            display.add(dfShow.format(c.time))
            axisLs.add(dfAxis.format(c.time))
            c.add(Calendar.DAY_OF_YEAR, 1)
        }

        curSeries = DailyMetrics.METRICS.map { meta ->
            TrendChartView.Series(meta, DailyMetrics.series(db, meta.id, dates))
        }
        chartView.dates = display
        chartView.axisLabels = axisLs
        chartView.series = curSeries
        chartView.visibleIds = visibleIds.toSet()
        // 选中日出界(范围切换)或未选 → 默认最新一天;侧栏数值随选中日(2026-10-26 设计决策)
        val sel = chartView.selectedIndex
        if (sel == null || sel !in dates.indices) chartView.selectedIndex = dates.size - 1
        chartView.onDateTap = { i -> updateSidebar(i) }
        chartView.invalidate()
        updateSidebar(chartView.selectedIndex ?: dates.size - 1)
    }

    /** 侧栏 9 指标显示选中日的值(拖/点折线图联动) */
    private fun updateSidebar(i: Int) {
        for (meta in DailyMetrics.METRICS) {
            val row = sideRows[meta.id] ?: continue
            row.alpha = if (meta.id in visibleIds) 1f else 0.35f
            val v = curSeries.firstOrNull { it.meta.id == meta.id }?.values?.getOrNull(i)
            (row.findViewWithTag<TextView>("val_${meta.id}"))?.text = DailyMetrics.fmt(meta, v)
        }
    }
}
