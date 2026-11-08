package dev.xd.fitnessbutler.ui

import android.app.Fragment
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.style.StrikethroughSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import dev.xd.fitnessbutler.MainActivity
import dev.xd.fitnessbutler.R
import dev.xd.fitnessbutler.data.MoveDemoData
import dev.xd.fitnessbutler.data.WorkoutSeed
import dev.xd.fitnessbutler.db.DbHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 锻炼打卡(M2.5):按日打卡清单 + 三态铁律 + 动作库。
 * 三态(用户原话):当日=可勾选;过期=变黑禁操作;未来=绿色禁勾选。
 * 计划来自 workout_plans(默认模板/AI 对话定制均可覆盖);打卡落 workout_checkins。
 * 打卡变动后刷新桌面小组件(PlansWidget,widget 包另一模块提供)。
 */
@Suppress("DEPRECATION")
class WorkoutFragment : Fragment() {

    private enum class DayState { PAST, TODAY, FUTURE }

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val labelFmt = SimpleDateFormat("M月d日 EEE", Locale.CHINA)
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    private lateinit var db: DbHelper
    private lateinit var planNameView: TextView
    private lateinit var dateLabel: TextView
    private lateinit var datesRow: LinearLayout
    private lateinit var checkinList: ListView
    private lateinit var checkinArea: LinearLayout
    private lateinit var libArea: LinearLayout

    private var selected: Calendar = Calendar.getInstance()
    private var dateCells: List<Pair<Calendar, TextView>> = emptyList()
    private var checkinItems: List<String> = emptyList()
    private var checkedSet: Set<String> = emptySet()

    // 动作库 tab 状态
    private var category = "全部"
    private var shown: List<WorkoutSeed.Move> = emptyList()
    private lateinit var moveAdapter: MoveAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_workout, container, false)
        val act = activity as MainActivity
        db = DbHelper.get(activity)
        val density = resources.displayMetrics.density

        planNameView = v.findViewById(R.id.workout_plan_name)
        dateLabel = v.findViewById(R.id.workout_date_label)
        datesRow = v.findViewById(R.id.workout_dates)
        checkinList = v.findViewById(R.id.workout_checkin_list)
        checkinArea = v.findViewById(R.id.workout_checkin_area)
        libArea = v.findViewById(R.id.workout_lib_area)

        // 没有激活计划 → 自动落默认模板(按性别)
        if (db.activePlan() == null) {
            val male = db.getSetting("gender", "男") != "女"
            db.setActivePlan(WorkoutSeed.planName(male), "template", WorkoutSeed.defaultWeekJson(male))
        }
        refreshPlanName()

        // ✨AI 对话定制:跳对话页(不动 MainActivity)
        v.findViewById<TextView>(R.id.workout_ai_plan).setOnClickListener {
            act.showScreen(MainActivity.SCREEN_HOME)
            act.toast("说「帮我定制训练计划」,AI 会直接给你下发生效")
        }

        // 换默认模板:男增肌 ⇄ 女塑形轮换
        v.findViewById<TextView>(R.id.workout_swap).setOnClickListener {
            val cur = db.activePlan()
            val toMale = !(cur?.name?.contains("增肌") ?: true)
            db.setActivePlan(
                WorkoutSeed.planName(toMale), "template", WorkoutSeed.defaultWeekJson(toMale)
            )
            refreshPlanName()
            refreshCheckins()
            act.toast("已换成「${WorkoutSeed.planName(toMale)}」")
        }

        // tab 切换
        val tabCheckin = v.findViewById<TextView>(R.id.workout_tab_checkin)
        val tabLib = v.findViewById<TextView>(R.id.workout_tab_lib)
        fun paintTabs(checkinOn: Boolean) {
            tabCheckin.setBackgroundResource(if (checkinOn) R.drawable.bg_tag_ink else R.drawable.bg_tag_out)
            tabCheckin.setTextColor(if (checkinOn) 0xFFFFFFFF.toInt() else 0xFF141414.toInt())
            tabLib.setBackgroundResource(if (!checkinOn) R.drawable.bg_tag_ink else R.drawable.bg_tag_out)
            tabLib.setTextColor(if (!checkinOn) 0xFFFFFFFF.toInt() else 0xFF141414.toInt())
            checkinArea.visibility = if (checkinOn) View.VISIBLE else View.GONE
            libArea.visibility = if (checkinOn) View.GONE else View.VISIBLE
        }
        tabCheckin.setOnClickListener { paintTabs(true) }
        tabLib.setOnClickListener { paintTabs(false) }
        paintTabs(true)

        // 日期胶囊:过去 7 + 今天 + 未来 7,默认选今天
        buildDateCells(density)
        selectDate(Calendar.getInstance())

        v.findViewById<TextView>(R.id.workout_prev_day).setOnClickListener { shiftDate(-1) }
        v.findViewById<TextView>(R.id.workout_next_day).setOnClickListener { shiftDate(1) }

        // 动作库(原 M2 逻辑)
        val cats = v.findViewById<LinearLayout>(R.id.workout_cats)
        for (cat in WorkoutSeed.CATEGORIES) {
            val pill = TextView(activity).apply {
                text = cat
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(
                    (28 * density).toInt(), (14 * density).toInt(),
                    (28 * density).toInt(), (14 * density).toInt()
                )
                setOnClickListener {
                    category = cat
                    drawCats(cats)
                    refreshMoves()
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (14 * density).toInt() }
            cats.addView(pill, lp)
        }
        drawCats(cats)
        moveAdapter = MoveAdapter()
        v.findViewById<ListView>(R.id.workout_list).adapter = moveAdapter
        refreshMoves()
        TextCopyUtil.apply(v)   // 全文字可复制(2026-10-17)
        return v
    }

    // ---------- 日期条 ----------

    private fun buildDateCells(density: Float) {
        val cells = mutableListOf<Pair<Calendar, TextView>>()
        val today = Calendar.getInstance()
        for (offset in -7..7) {
            val cal = Calendar.getInstance().apply {
                time = today.time
                add(Calendar.DAY_OF_MONTH, offset)
            }
            val cell = TextView(activity).apply {
                gravity = Gravity.CENTER
                setPadding(
                    (16 * density).toInt(), (10 * density).toInt(),
                    (16 * density).toInt(), (10 * density).toInt()
                )
                textSize = 10f
                setOnClickListener { selectDate(cal) }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * density).toInt() }
            datesRow.addView(cell, lp)
            cells.add(cal to cell)
        }
        dateCells = cells
    }

    private fun shiftDate(delta: Int) {
        val target = Calendar.getInstance().apply {
            time = selected.time
            add(Calendar.DAY_OF_MONTH, delta)
        }
        // 只能在 -7..+7 窗口内移动
        val today = Calendar.getInstance()
        val diff = ((target.timeInMillis - today.timeInMillis) / (24L * 3600 * 1000)).toInt()
        if (diff < -7 || diff > 7) return
        selectDate(target)
    }

    private fun selectDate(cal: Calendar) {
        selected = cal
        paintDateCells()
        refreshCheckins()
    }

    private fun paintDateCells() {
        val state = dayStateOf(selected)
        for ((cal, cell) in dateCells) {
            val on = dateFmt.format(cal.time) == dateFmt.format(selected.time)
            val s = dayStateOf(cal)
            cell.text = "${weekdayShort(cal)}\n${cal.get(Calendar.DAY_OF_MONTH)}"
            when {
                on -> {
                    cell.setBackgroundResource(R.drawable.bg_tag_ink)
                    cell.setTextColor(0xFFFFFFFF.toInt())
                }
                s == DayState.TODAY -> {
                    cell.setBackgroundResource(R.drawable.bg_tag_out)
                    cell.setTextColor(0xFF2E7D32.toInt())
                }
                s == DayState.FUTURE -> {
                    cell.setBackgroundResource(R.drawable.bg_tag_out)
                    cell.setTextColor(0xFF8FB24E.toInt())
                }
                else -> {
                    cell.setBackgroundResource(R.drawable.bg_tag_out)
                    cell.setTextColor(0xFF8A8A8A.toInt())
                }
            }
        }
    }

    private fun weekdayShort(cal: Calendar): String =
        when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "周一"; Calendar.TUESDAY -> "周二"; Calendar.WEDNESDAY -> "周三"
            Calendar.THURSDAY -> "周四"; Calendar.FRIDAY -> "周五"; Calendar.SATURDAY -> "周六"
            else -> "周日"
        }

    /** ISO 周几:1=周一…7=周日(与 week_json 的 day 对齐) */
    private fun isoWeekday(cal: Calendar): Int = ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1

    private fun dayStateOf(cal: Calendar): DayState {
        val sel = dateFmt.format(cal.time)
        val today = dateFmt.format(Date())
        return when {
            sel == today -> DayState.TODAY
            sel < today -> DayState.PAST
            else -> DayState.FUTURE
        }
    }

    // ---------- 打卡清单 ----------

    private fun refreshPlanName() {
        val plan = db.activePlan()
        val src = when (plan?.source) {
            "ai" -> "AI 定制"; "template" -> "默认模板"; else -> "自定义"
        }
        planNameView.text = "当前计划 · ${plan?.name ?: "未设置"}($src)"
    }

    private fun refreshCheckins() {
        val dateStr = dateFmt.format(selected.time)
        checkinItems = db.planItemsForDay(isoWeekday(selected))
        checkedSet = db.checkedItems(dateStr)
        val done = checkinItems.count { it in checkedSet }
        val prefix = when (dateFmt.format(selected.time)) {
            dateFmt.format(Date()) -> "今天"
            dateFmt.format(Date(System.currentTimeMillis() - 24L * 3600 * 1000)) -> "昨天"
            dateFmt.format(Date(System.currentTimeMillis() + 24L * 3600 * 1000)) -> "明天"
            else -> ""
        }
        val tag = when (dayStateOf(selected)) {
            DayState.PAST -> " · 已过期"
            DayState.FUTURE -> " · 未到"
            DayState.TODAY -> ""
        }
        dateLabel.text = "${if (prefix.isNotEmpty()) "$prefix · " else ""}${labelFmt.format(selected.time)}$tag   $done/${checkinItems.size}"
        checkinList.adapter = CheckinAdapter()
        paintDateCells()
    }

    /** 三态打卡行(2026-10-18 改两半防误触):左半=打勾打卡(当日可勾/过期黑/未来绿),右半=「▶ 演示」看动作 */
    private inner class CheckinAdapter : BaseAdapter() {
        override fun getCount(): Int = checkinItems.size
        override fun getItem(position: Int): String = checkinItems[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = checkinItems[position]
            val done = item in checkedSet
            val state = dayStateOf(selected)
            val density = resources.displayMetrics.density

            // row 本身不设监听;左半打勾、右半演示各自监听(防误触)
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    (14 * density).toInt(), (10 * density).toInt(),
                    (10 * density).toInt(), (10 * density).toInt()
                )
            }
            val leftPane = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    (8 * density).toInt(), (8 * density).toInt(),
                    (8 * density).toInt(), (8 * density).toInt()
                )
            }
            val mark = TextView(activity).apply {
                textSize = 16f
                gravity = Gravity.CENTER
            }
            val name = TextView(activity).apply {
                text = item
                textSize = 13f
                setPadding((14 * density).toInt(), 0, 0, 0)
            }
            val right = TextView(activity).apply {
                textSize = 10f
                gravity = Gravity.END
            }

            when (state) {
                DayState.TODAY -> {
                    row.setBackgroundResource(R.drawable.bg_row_white)
                    mark.text = if (done) "✔" else "○"
                    mark.setTextColor(if (done) 0xFF2E7D32.toInt() else 0xFF141414.toInt())
                    if (done) {
                        val sp = SpannableString(item).apply {
                            setSpan(StrikethroughSpan(), 0, length, 0)
                        }
                        name.text = sp
                    }
                    name.setTextColor(0xFF141414.toInt())
                    right.text = if (done) "已完成" else "点左侧打卡"
                    right.setTextColor(0xFF8A8A8A.toInt())
                    leftPane.setOnClickListener {
                        val d = dateFmt.format(selected.time)
                        val ts = timeFmt.format(Date())
                        if (item in checkedSet) db.uncheckin(d, item) else db.checkin(d, item, ts)
                        runCatching {
                            dev.xd.fitnessbutler.widget.PlansWidget.updateAll(activity.applicationContext)
                        }
                        refreshCheckins()
                    }
                }
                DayState.PAST -> {
                    // 过期变黑:整行黑底灰字,禁打卡;演示按钮照常可看(补看是刚需)
                    row.setBackgroundResource(R.drawable.bg_card_dark)
                    mark.text = if (done) "✔" else "✕"
                    mark.setTextColor(if (done) 0xFFCCCCCC.toInt() else 0xFF666666.toInt())
                    if (done) {
                        val sp = SpannableString(item).apply {
                            setSpan(StrikethroughSpan(), 0, length, 0)
                        }
                        name.text = sp
                    }
                    name.setTextColor(0xFF9A9A9A.toInt())
                    right.text = if (done) "已完成" else "未打卡"
                    right.setTextColor(0xFF777777.toInt())
                    leftPane.isClickable = false
                }
                DayState.FUTURE -> {
                    // 未来=绿,禁止勾选;演示按钮照常可看(预习是刚需)
                    row.setBackgroundResource(R.drawable.bg_card_green)
                    mark.text = "○"
                    mark.setTextColor(0xFF8FB24E.toInt())
                    name.setTextColor(0xFF2E7D32.toInt())
                    right.text = "未到"
                    right.setTextColor(0xFF8FB24E.toInt())
                    leftPane.isClickable = false
                }
            }

            leftPane.addView(mark)
            leftPane.addView(name, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ))
            leftPane.addView(right)

            val demoBtn = TextView(activity).apply {
                text = "▶ 演示"
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                minWidth = (64 * density).toInt()
                setPadding(
                    (12 * density).toInt(), (10 * density).toInt(),
                    (12 * density).toInt(), (10 * density).toInt()
                )
                setBackgroundResource(R.drawable.bg_tag_out)
                setTextColor(0xFF141414.toInt())
                setOnClickListener { openDemo(item) }
            }

            row.addView(leftPane, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ))
            row.addView(demoBtn)
            return row
        }
    }

    /** 开演示面板:plan 文本→严格匹配→MoveDemoDialog(匹配不到也开面板显文字要领+暂无动画) */
    private fun openDemo(rawItem: String) {
        val act = activity as? MainActivity ?: return
        val r = MoveDemoData.resolve(rawItem)
        MoveDemoDialog.show(act, r.title, r.tip, r.demo)
    }

    override fun onDestroyView() {
        // 弹窗随页面走,防 WindowLeak(Dialog 自身 dismiss 时会 release 演示帧回调)
        runCatching { MoveDemoDialog.dismissIfShowing() }
        super.onDestroyView()
    }

    // ---------- 动作库(原 M2 逻辑) ----------

    private fun drawCats(cats: LinearLayout) {
        for (i in 0 until cats.childCount) {
            val pill = cats.getChildAt(i) as TextView
            val on = pill.text.toString() == category
            pill.setBackgroundResource(if (on) R.drawable.bg_tag_ink else R.drawable.bg_tag_out)
            pill.setTextColor(if (on) 0xFFFFFFFF.toInt() else 0xFF141414.toInt())
        }
    }

    private fun refreshMoves() {
        shown = WorkoutSeed.byCategory(category)
        moveAdapter.notifyDataSetChanged()
    }

    /** 动作卡:代码动态拼(名称/分类 · 要领 · 组次),不另建 item 布局 */
    private inner class MoveAdapter : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): WorkoutSeed.Move = shown[position]
        override fun getItemId(position: Int): Long = shown[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val m = shown[position]
            val density = resources.displayMetrics.density
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_row_white)
                setPadding(
                    (22 * density).toInt(), (18 * density).toInt(),
                    (22 * density).toInt(), (18 * density).toInt()
                )
            }
            // 首行:名称·分类 + 「▶ 演示」按钮(2026-10-18)
            val head = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(TextView(activity).apply {
                text = "${m.name}  ·  ${m.category}"
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFF141414.toInt())
            }, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ))
            head.addView(TextView(activity).apply {
                text = "▶ 演示"
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                minWidth = (64 * density).toInt()
                setPadding(
                    (12 * density).toInt(), (8 * density).toInt(),
                    (12 * density).toInt(), (8 * density).toInt()
                )
                setBackgroundResource(R.drawable.bg_tag_out)
                setTextColor(0xFF141414.toInt())
                setOnClickListener {
                    val act = activity as? MainActivity ?: return@setOnClickListener
                    val r = MoveDemoData.resolveByName(m.name)
                    MoveDemoDialog.show(act, r.title, m.tip, r.demo)
                }
            })
            row.addView(head)
            row.addView(TextView(activity).apply {
                text = m.tip
                textSize = 11f
                setTextColor(0xFF666666.toInt())
                setPadding(0, (6 * density).toInt(), 0, 0)
            })
            row.addView(TextView(activity).apply {
                text = m.sets
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFF5A8F2E.toInt())
                setPadding(0, (8 * density).toInt(), 0, 0)
            })
            return row
        }
    }
}
