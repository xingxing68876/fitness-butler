package dev.xd.fitnessbutler.ui

import android.app.Fragment
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
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
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 一日详情(点日历格进入,✕ 返回日历):
 * 汇总三格 + 一日分时段图(手表小时均值/运动块/睡眠条/称重点/三餐)+ 运动卡片 + 三餐行 + 身体六宫格。
 * 手表层 M3 前为空 = 该层不画,不造假。
 *
 * 崩溃防御(2026-10-17 点 17 号闪退后加):整页分块构建,单块异常只废该块并打
 * logcat 指纹(Log.e "DayDetailCrash"),绝不让整个 Activity 陪葬;真凶以 logcat 堆栈为准。
 */
@Suppress("DEPRECATION")
class DayDetailFragment : Fragment() {

    companion object {
        private const val TAG = "DayDetailCrash"

        fun newInstance(date: String): DayDetailFragment =
            DayDetailFragment().apply {
                arguments = Bundle().apply { putString("date", date) }
            }
    }

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.US)
    private val tsFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_day_detail, container, false)
        val act = activity as MainActivity
        val db = DbHelper.get(activity)

        // 返回键最先绑:后面任何块崩了都得能退出
        v.findViewById<TextView>(R.id.day_close).setOnClickListener { act.backToScreen() }

        // 参数缺失兜底(2026-10-17 修幽灵页):今日详情语义下默认今天,绝不停在错误/空态页
        // (CalendarFragment→openDayDetail 链 dateStr 恒非空,本兜底防 arguments 竞态/恢复异常)
        var date = arguments?.getString("date")
        if (date.isNullOrBlank()) {
            date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
            Log.e(TAG, "date argument missing, fallback to today=$date")
        }

        // 标题:10月16日 · 周五
        runCatching {
            val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)
            v.findViewById<TextView>(R.id.day_title).text =
                if (d != null) SimpleDateFormat("M月d日 · E", Locale.CHINA).format(d) else date
        }.onFailure {
            Log.e(TAG, "title block failed date=$date", it)
            v.findViewById<TextView>(R.id.day_title).text = date
        }

        // 数据装载:三表各自独立降级,一张崩不连坐
        val meals = runCatching { db.mealsOnDate(date) }
            .getOrElse { Log.e(TAG, "load meals failed date=$date", it); emptyList() }
        val acts = runCatching { db.activitiesOnDate(date) }
            .getOrElse { Log.e(TAG, "load activities failed date=$date", it); emptyList() }
        val weighings = runCatching { db.weighingsOnDate(date) }
            .getOrElse { Log.e(TAG, "load weighings failed date=$date", it); emptyList() }

        runCatching { buildSummary(v, db, date, meals, acts, weighings) }
            .onFailure { Log.e(TAG, "summary block failed date=$date", it) }
        runCatching { buildTimeline(v, db, date, meals, acts, weighings) }
            .onFailure { Log.e(TAG, "timeline block failed date=$date", it) }
        runCatching { buildHourlyChart(v, db, date, meals, weighings) }
            .onFailure { Log.e(TAG, "hourly chart block failed date=$date", it) }
        runCatching { buildExercises(v, acts) }
            .onFailure { Log.e(TAG, "exercises block failed date=$date", it) }
        runCatching { buildMeals(v, meals) }
            .onFailure { Log.e(TAG, "meals block failed date=$date", it) }
        runCatching { buildWeighingList(v, weighings) }
            .onFailure { Log.e(TAG, "weighing list block failed date=$date", it) }
        runCatching { buildBody(v, weighings) }
            .onFailure { Log.e(TAG, "body block failed date=$date", it) }

        TextCopyUtil.apply(v)   // 全文字可复制(2026-10-17)
        return v
    }

    // ---------- 汇总三格 ----------

    private fun buildSummary(
        v: View, db: DbHelper, date: String,
        meals: List<DbHelper.Meal>, acts: List<DbHelper.Activity>, weighings: List<DbHelper.Weighing>
    ) {
        val intake = meals.sumOf { it.totalKcal }
        val activity = acts.sumOf { it.kcalBurned ?: 0.0 }
        val bmr = dev.xd.fitnessbutler.health.CaloricBalance.bmr(db).kcal
        val burn = activity + bmr
        val gap = (intake - burn).toInt()

        val gapView = v.findViewById<TextView>(R.id.sum_gap)
        gapView.text = if (intake > 0 || burn > 0) {
            if (gap < 0) gap.toString() else "+$gap"
        } else "--"
        gapView.setTextColor(if (gap < 0) 0xFF2E7D32.toInt() else 0xFF141414.toInt())

        v.findViewById<TextView>(R.id.sum_weight).text =
            weighings.lastOrNull()?.let { "%.2f".format(Locale.US, it.weightKg) } ?: "--"
        v.findViewById<TextView>(R.id.sum_sleep).text =
            DailyMetrics.sleepHours(db, date)?.let { fmtHours(it) } ?: "--"
    }

    // ---------- 分时段图 ----------

    private fun buildTimeline(
        v: View, db: DbHelper, date: String,
        meals: List<DbHelper.Meal>, acts: List<DbHelper.Activity>, weighings: List<DbHelper.Weighing>
    ) {
        val timeline = v.findViewById<DayTimelineView>(R.id.day_timeline)
        timeline.sleepSegs = DailyMetrics.sleepStages(db, date)
        // 小时序列键与采样器写入对齐(spo2_avg/stress_avg,同 daily_stats 日键)
        timeline.spo2Hourly = DailyMetrics.hourly(db, "spo2_avg", date)
        timeline.stressHourly = DailyMetrics.hourly(db, "stress_avg", date)

        // 运动色块只画"有真实起止时段"的段(durationMin>0);
        // "全天活动(手表)"这类无时段汇总条目不画块(会在图上错位成大色块),运动卡片区照显数字
        timeline.exercises = acts.filter { (it.durationMin ?: 0.0) > 0 }.mapNotNull { a ->
            val start = hourFrac(a.ts)
            val dur = ((a.durationMin ?: 0.0) / 60.0).coerceAtLeast(0.25)
            val hr = a.avgHr
            val kc = a.kcalBurned
            val dm = a.durationMin ?: 0.0
            val intensity = when {
                hr != null -> ((hr - 90.0) / 70.0).toFloat().coerceIn(0.15f, 1f)
                kc != null && dm > 0 -> ((kc / dm) / 10.0).toFloat().coerceIn(0.15f, 1f)
                else -> 0.4f
            }
            DayTimelineView.Exercise(start, start + dur, intensity)
        }
        timeline.weighings = weighings.mapNotNull { w ->
            val d = runCatching { tsFmt.parse(w.ts) }.getOrNull() ?: return@mapNotNull null
            DayTimelineView.WeighPoint(hourFrac(w.ts), w.weightKg, timeFmt.format(d))
        }
        timeline.meals = meals.mapNotNull { m ->
            DayTimelineView.MealMark(hourFrac(m.ts), m.totalKcal)
        }
        timeline.invalidate()

        // 手表层读数行:恒定显示,没数据用 -- 占位(图层不画假线,文字位不空)
        val sleepInfo = v.findViewById<TextView>(R.id.day_sleep_info)
        sleepInfo.visibility = View.VISIBLE
        val sleepText = DailyMetrics.sleepHours(db, date)?.let { fmtHours(it) } ?: "--"
        val spo2Text = DailyMetrics.daily(db, "spo2", date)?.let { "${it.toInt()}%" } ?: "--"
        val stressText = DailyMetrics.daily(db, "stress", date)?.let { "${it.toInt()}" } ?: "--"
        val loadText = acts.sumOf { it.trainingLoad ?: 0.0 }
            .let { if (acts.any { a -> a.trainingLoad != null }) it.toInt().toString() else "--" }
        sleepInfo.text = "睡眠 $sleepText · 血氧 $spo2Text · 压力 $stressText · 训练负荷 $loadText"
    }

    // ---------- 当日全数据折线(2026-10-26 设计决策:所有当日数值照日历折线图做;参数栏照日历放左侧) ----------

    /** 显隐状态(点左侧参数行切换;等同日历侧栏交互) */
    private val hourVisible = linkedSetOf("hr", "spo2", "stress", "weight", "meal")
    private val sideRows = mutableMapOf<String, LinearLayout>()
    private var hourChart: TrendChartView? = null
    private var hourSeries: List<TrendChartView.Series> = emptyList()

    private fun buildHourlyChart(
        v: View, db: DbHelper, date: String,
        meals: List<DbHelper.Meal>, weighings: List<DbHelper.Weighing>
    ) {
        val chart = v.findViewById<TrendChartView>(R.id.day_hour_chart)
        hourChart = chart
        val hours = (0..23).map { "$it 时" }
        fun toValues(arr: DoubleArray?): List<Double?> =
            (0..23).map { arr?.getOrNull(it)?.takeUnless { x -> x.isNaN() } }

        // 手表三条(30 分钟采样落小时桶)
        val hr = DailyMetrics.hourly(db, "hr_avg", date)
        val spo2 = DailyMetrics.hourly(db, "spo2_avg", date)
        val stress = DailyMetrics.hourly(db, "stress_avg", date)

        // 体重:各次称重落对应小时桶(同桶多次取最后一次),桶间连线
        val wVals = arrayOfNulls<Double>(24)
        for (w in weighings) {
            val b = hourFrac(w.ts).toInt().coerceIn(0, 23)
            wVals[b] = w.weightKg
        }

        // 三餐热量:按时间累计(餐后小时=截至该时累计摄入),有餐的桶才放点
        val mVals = arrayOfNulls<Double>(24)
        var cum = 0.0
        for (m in meals.sortedBy { hourFrac(it.ts) }) {
            cum += m.totalKcal
            mVals[hourFrac(m.ts).toInt().coerceIn(0, 23)] = cum
        }

        // 自造短名 Meta(侧栏行/气泡同显)
        val mHr = DailyMetrics.Meta("hr", "心率", 0xFFC0392B.toInt(), "bpm", "bpm", 0)
        val mSpo2 = DailyMetrics.Meta("spo2", "血氧", 0xFF4A90D9.toInt(), "%", "%", 0)
        val mStress = DailyMetrics.Meta("stress", "压力", 0xFFC94F7C.toInt(), "", "", 0)
        val mWeight = DailyMetrics.Meta("weight", "体重", 0xFF141414.toInt(), "kg", "kg", 1)
        val mMeal = DailyMetrics.Meta("meal", "三餐", 0xFF5A8F2E.toInt(), "kcal", "kcal", 0)

        hourSeries = listOf(
            TrendChartView.Series(mHr, toValues(hr)),
            TrendChartView.Series(mSpo2, toValues(spo2)),
            TrendChartView.Series(mStress, toValues(stress)),
            TrendChartView.Series(mWeight, wVals.toList()),
            TrendChartView.Series(mMeal, mVals.toList())
        )
        chart.dates = hours
        chart.axisLabels = hours
        chart.series = hourSeries
        chart.visibleIds = hourVisible.toSet()
        chart.connectAcrossGaps = true   // 采样稀疏,相邻有值点直连不留断口
        chart.showLegend = false         // 图例退役:左侧参数栏替代(点行=显隐,面积大不误触)
        chart.onLegendTap = null
        chart.selectedIndex = null
        chart.onDateTap = { i -> updateSideVals(i) }
        chart.invalidate()

        buildChartSide(v)
        val any = listOf(hr, spo2, stress).any { a -> a != null && a.any { !it.isNaN() } } ||
            wVals.any { it != null } || mVals.any { it != null }
        v.findViewById<TextView>(R.id.day_hour_hint).text =
            if (any) "点线看读数 · 点左侧参数显隐" else "这天没有测量数据"
    }

    /** 左侧参数栏(照 CalendarFragment.buildSidebar:色块+名+值,整行可点切换显隐) */
    private fun buildChartSide(v: View) {
        val side = v.findViewById<LinearLayout>(R.id.day_chart_side)
        side.removeAllViews()
        sideRows.clear()
        val density = resources.displayMetrics.density
        for (meta in hourSeries.map { it.meta }) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_card_white)
                setPadding(
                    (6 * density).toInt(), (6 * density).toInt(),
                    (6 * density).toInt(), (6 * density).toInt()
                )
                alpha = if (meta.id in hourVisible) 1f else 0.35f
            }
            row.addView(
                View(activity).apply { setBackgroundColor(meta.color) },
                LinearLayout.LayoutParams((10 * density).toInt(), (10 * density).toInt())
            )
            row.addView(TextView(activity).apply {
                text = meta.label
                textSize = 10f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFF141414.toInt())
                setPadding((5 * density).toInt(), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(activity).apply {
                tag = "val_${meta.id}"
                text = "--"
                textSize = 9.5f
                setTextColor(0x8C141414.toInt())
            })
            row.setOnClickListener {
                if (!hourVisible.remove(meta.id)) hourVisible.add(meta.id)
                hourChart?.visibleIds = hourVisible.toSet()
                hourChart?.invalidate()
                sideRows[meta.id]?.alpha = if (meta.id in hourVisible) 1f else 0.35f
            }
            side.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (3 * density).toInt() })
            sideRows[meta.id] = row
        }
        updateSideVals(-1)   // 初始显各线最新值
    }

    /** 参数栏数值:选中小时显该时读数(i<0 或该时无值→显该线最新值,照日历逻辑) */
    private fun updateSideVals(i: Int) {
        for (s in hourSeries) {
            val tv = sideRows[s.meta.id]?.findViewWithTag<TextView>("val_${s.meta.id}") ?: continue
            val v = if (i in s.values.indices) s.values[i] else null
            tv.text = if (v != null) DailyMetrics.fmt(s.meta, v)
            else s.values.filterNotNull().lastOrNull()?.let { DailyMetrics.fmt(s.meta, it) } ?: "--"
        }
    }

    // ---------- 运动卡片 ----------

    private fun buildExercises(v: View, acts: List<DbHelper.Activity>) {
        val exBox = v.findViewById<LinearLayout>(R.id.day_exercises)
        if (acts.isEmpty()) {
            exBox.addView(plain("这天没有运动记录。"))
            return
        }
        val density = resources.displayMetrics.density
        for (a in acts) {
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_card_white)
                setPadding(
                    (11 * density).toInt(), (9 * density).toInt(),
                    (11 * density).toInt(), (9 * density).toInt()
                )
            }
            val head = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(TextView(activity).apply {
                text = a.type ?: "运动"
                textSize = 11.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFF141414.toInt())
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(TextView(activity).apply {
                text = a.kcalBurned?.let { "约 ${it.toInt()} kcal" } ?: "约 --"
                textSize = 9.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundResource(R.drawable.bg_tag_green)
                setPadding(
                    (10 * density).toInt(), (3 * density).toInt(),
                    (10 * density).toInt(), (3 * density).toInt()
                )
            })
            card.addView(head)

            val startH = hourFrac(a.ts)
            val endH = startH + (a.durationMin ?: 0.0) / 60.0
            val durText = a.durationMin?.let { "${it.toInt()}min" } ?: "-- min"
            card.addView(TextView(activity).apply {
                text = "${fmtHour(startH)}–${fmtHour(endH)} · $durText" +
                    " · 训练负荷 ${a.trainingLoad?.toInt() ?: "--"}" +
                    " · 平均心率 ${a.avgHr?.toInt() ?: "--"}"
                textSize = 9.5f
                setTextColor(0xA6141414.toInt())
                setPadding(0, (3 * density).toInt(), 0, 0)
            })
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * density).toInt() }
            exBox.addView(card, lp)
        }
    }

    // ---------- 三餐行(2026-10-17 起整行可点:进编辑页改/删这笔) ----------

    private fun buildMeals(v: View, meals: List<DbHelper.Meal>) {
        val mealBox = v.findViewById<LinearLayout>(R.id.day_meals)
        if (meals.isEmpty()) {
            mealBox.addView(plain("这天没有餐食记录。"))
            return
        }
        for (m in meals) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_row_white)
                setPadding(
                    (11 * density()).toInt(), (8 * density()).toInt(),
                    (11 * density()).toInt(), (8 * density()).toInt()
                )
                setOnClickListener { (activity as? MainActivity)?.openMealEditForMeal(m.id) }
            }
            val hhmm = runCatching {
                tsFmt.parse(m.ts)?.let { timeFmt.format(it) }
            }.getOrDefault("")
            row.addView(TextView(activity).apply {
                text = "${m.mealType ?: "一餐"} $hhmm"
                textSize = 10.5f
                setTextColor(0xFF141414.toInt())
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(activity).apply {
                text = "约 ${m.totalKcal.toInt()} kcal"
                textSize = 10.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFF141414.toInt())
            })
            row.addView(TextView(activity).apply {
                text = "改"
                textSize = 9.5f
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundResource(R.drawable.bg_tag_green)
                setPadding(
                    (10 * density()).toInt(), (3 * density()).toInt(),
                    (10 * density()).toInt(), (3 * density()).toInt()
                )
            })
            mealBox.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (5 * density()).toInt() })
        }
    }

    // ---------- 称重逐次列表(2026-10-26 设计决策:一天多次全记录) ----------

    private fun buildWeighingList(v: View, weighings: List<DbHelper.Weighing>) {
        val box = v.findViewById<LinearLayout>(R.id.day_weighings)
        if (weighings.isEmpty()) {
            box.addView(plain("这天没有称重记录。"))
            return
        }
        val density = resources.displayMetrics.density
        for (w in weighings) {
            val hhmm = runCatching { tsFmt.parse(w.ts)?.let { timeFmt.format(it) } }.getOrDefault("--:--")
            val j = runCatching { JSONObject(w.metricsJson ?: "") }.getOrNull()
            val bf = j?.optDouble("body_fat_pct", Double.NaN)
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_row_white)
                setPadding(
                    (11 * density).toInt(), (8 * density).toInt(),
                    (11 * density).toInt(), (8 * density).toInt()
                )
            }
            row.addView(TextView(activity).apply {
                text = hhmm
                textSize = 10.5f
                setTextColor(0x99141414.toInt())
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(activity).apply {
                text = String.format(Locale.US, "%.2f kg", w.weightKg)
                textSize = 10.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFF141414.toInt())
            })
            row.addView(TextView(activity).apply {
                text = if (bf != null && !bf.isNaN())
                    "  体脂 " + String.format(Locale.US, "%.1f%%", bf) else ""
                textSize = 9.5f
                setTextColor(0xA6141414.toInt())
            })
            box.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * density).toInt() })
        }
    }

    // ---------- 身体六宫格 ----------

    private fun buildBody(v: View, weighings: List<DbHelper.Weighing>) {
        val bodyBox = v.findViewById<LinearLayout>(R.id.day_body_grid)
        val metrics = weighings.lastOrNull()?.metricsJson
        val json = runCatching { JSONObject(metrics ?: "") }.getOrNull()
        val cells = listOf(
            Triple("体脂率", json?.optDouble("body_fat_pct", Double.NaN), "%"),
            Triple("皮下脂肪", json?.optDouble("subcutaneous_fat_pct", Double.NaN), "%"),
            Triple("骨骼肌", json?.optDouble("skeletal_muscle_pct", Double.NaN), "kg"),
            Triple("内脏脂肪", json?.optDouble("visceral_fat", Double.NaN), " 级"),
            Triple("骨量", json?.optDouble("bone_mass_kg", Double.NaN), "kg"),
            Triple("基础代谢", json?.optDouble("bmr_kcal", Double.NaN), "")
        )
        val density = resources.displayMetrics.density
        for (r in 0 until 2) {
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            for (cIdx in 0 until 3) {
                val (label, value, unit) = cells[r * 3 + cIdx]
                val cell = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setBackgroundResource(R.drawable.bg_card_white)
                    setPadding(
                        (4 * density).toInt(), (5 * density).toInt(),
                        (4 * density).toInt(), (5 * density).toInt()
                    )
                }
                cell.addView(TextView(activity).apply {
                    text = if (value == null || value.isNaN()) "--"
                    else if (label == "内脏脂肪") "${value.toInt()}$unit"
                    else if (label == "基础代谢") "${value.toInt()}$unit"
                    else String.format(Locale.US, "%.1f$unit", value)
                    textSize = 11f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(0xFF141414.toInt())
                    gravity = Gravity.CENTER
                })
                cell.addView(TextView(activity).apply {
                    text = label
                    textSize = 9f
                    setTextColor(0x99141414.toInt())
                    gravity = Gravity.CENTER
                })
                row.addView(cell, LinearLayout.LayoutParams(0, (46 * density).toInt(), 1f).apply {
                    marginStart = if (cIdx == 0) 0 else (3 * density).toInt()
                    topMargin = (3 * density).toInt()
                })
            }
            bodyBox.addView(row)
        }
    }

    // ---------- 工具 ----------

    /** ts → 当日小时小数;任何解析失败都归 0 点,绝不抛 */
    private fun hourFrac(ts: String): Double {
        return try {
            val d = tsFmt.parse(ts) ?: return 0.0
            val c = java.util.Calendar.getInstance().apply { time = d }
            c.get(java.util.Calendar.HOUR_OF_DAY) +
                c.get(java.util.Calendar.MINUTE) / 60.0
        } catch (e: Exception) {
            Log.e(TAG, "hourFrac parse failed ts=$ts", e)
            0.0
        }
    }

    private fun density(): Float = resources.displayMetrics.density

    private fun plain(s: String): View = TextView(activity).apply {
        text = s
        textSize = 11f
        // 对比度铁律(2026-10-17):空态文字压浅底也用墨色,禁低透明度灰
        setTextColor(0xFF141414.toInt())
        setPadding(8, 14, 8, 14)
    }

    /** 小时小数 → 7:50 */
    private fun fmtHour(h: Double): String {
        val hh = h.toInt()
        val mm = ((h - hh) * 60).toInt()
        return "%d:%02d".format(Locale.US, hh, mm)
    }

    /** 小时数 → 6h25 */
    private fun fmtHours(h: Double): String {
        val hh = h.toInt()
        val mm = ((h - hh) * 60).toInt()
        return "${hh}h${mm}"
    }
}
