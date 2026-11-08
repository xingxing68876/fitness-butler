package dev.xd.fitnessbutler.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.SpannableString
import android.text.style.StrikethroughSpan
import android.view.View
import android.widget.RemoteViews
import dev.xd.fitnessbutler.MainActivity
import dev.xd.fitnessbutler.R
import dev.xd.fitnessbutler.db.DbHelper
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 桌面小组件 A:当日运动清单。
 * 只读 DB(workout_plans / workout_checkins,另一模块建表维护;表不存在时显示空态不崩)。
 * 打卡后由 Workout 侧调 [updateAll] 即时刷新;另有每小时闹钟兜底刷新。
 * 整体点击 → MainActivity(action OPEN_WORKOUT,主窗口负责分发到锻炼页)。
 */
class PlansWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        scheduleTick(ctx)
        updateAll(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        when (intent.action) {
            ACTION_TICK, Intent.ACTION_BOOT_COMPLETED -> {
                scheduleTick(ctx)
                updateAll(ctx)
            }
        }
    }

    companion object {
        const val ACTION_TICK = "dev.xd.fitnessbutler.WIDGET_TICK"
        const val ACTION_OPEN_WORKOUT = "dev.xd.fitnessbutler.OPEN_WORKOUT"
        private const val REQ_OPEN = 1001
        private const val REQ_TICK = 1002
        private val ROW_IDS = intArrayOf(
            R.id.widget_row0, R.id.widget_row1, R.id.widget_row2,
            R.id.widget_row3, R.id.widget_row4, R.id.widget_row5
        )
        private val WEEK_CN = arrayOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")

        /** 打卡/换计划后调用:签名一字不差,Workout 侧直接引用 */
        fun updateAll(ctx: Context) {
            val mgr = ctx.getSystemService(Context.APPWIDGET_SERVICE) as AppWidgetManager
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, PlansWidget::class.java))
            if (ids.isEmpty()) return
            val rv = buildViews(ctx)
            rv.setOnClickPendingIntent(R.id.widget_plans_root, openAppPending(ctx))
            mgr.updateAppWidget(ids, rv)
        }

        private fun openAppPending(ctx: Context): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java).apply {
                action = ACTION_OPEN_WORKOUT
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                ctx, REQ_OPEN, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun scheduleTick(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                ctx, REQ_TICK,
                Intent(ctx, PlansWidget::class.java).setAction(ACTION_TICK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // 下一小时 5 分起,每小时一次(系统 inexact 合并,几乎零耗电)
            val first = Calendar.getInstance().apply {
                add(Calendar.HOUR_OF_DAY, 1); set(Calendar.MINUTE, 5); set(Calendar.SECOND, 0)
            }.timeInMillis
            am.setInexactRepeating(AlarmManager.RTC, first, AlarmManager.INTERVAL_HOUR, pi)
        }

        private fun buildViews(ctx: Context): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_plans)
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) // 周日=1..周六=7
            val dayNum = if (dow == Calendar.SUNDAY) 7 else dow - 1   // 周一=1..周日=7
            rv.setTextViewText(R.id.widget_plans_title, "今日运动 · ${WEEK_CN[dayNum]}")

            var items = emptyList<String>()
            val done = HashSet<String>()
            try {
                val db = DbHelper.get(ctx).readableDatabase
                db.rawQuery(
                    "SELECT week_json FROM workout_plans WHERE active=1 ORDER BY id DESC LIMIT 1", null
                ).use { c ->
                    if (c.moveToFirst()) {
                        val days = JSONObject(c.getString(0)).optJSONArray("days")
                        if (days != null) {
                            for (i in 0 until days.length()) {
                                val d = days.getJSONObject(i)
                                if (d.optInt("day") != dayNum) continue
                                val arr = d.optJSONArray("items")
                                if (arr != null) items = (0 until arr.length()).map { arr.getString(it) }
                            }
                        }
                    }
                }
                db.rawQuery("SELECT item FROM workout_checkins WHERE date=?", arrayOf(today))
                    .use { c -> while (c.moveToNext()) done.add(c.getString(0)) }
            } catch (_: Exception) {
                // 表未建(计划模块尚未初始化)→ 空态展示,不崩
            }

            if (items.isEmpty()) {
                rv.setTextViewText(R.id.widget_row0, "点开 App 定制运动计划")
                rv.setTextColor(R.id.widget_row0, 0x66141414)
                rv.setViewVisibility(R.id.widget_row0, View.VISIBLE)
                for (k in 1 until ROW_IDS.size) rv.setViewVisibility(ROW_IDS[k], View.GONE)
                rv.setTextViewText(R.id.widget_plans_progress, "暂无计划")
                return rv
            }

            var doneCount = 0
            for (k in ROW_IDS.indices) {
                if (k >= items.size) {
                    rv.setViewVisibility(ROW_IDS[k], View.GONE)
                    continue
                }
                rv.setViewVisibility(ROW_IDS[k], View.VISIBLE)
                val item = items[k]
                val ok = item in done
                if (ok) {
                    doneCount++
                    val s = SpannableString("✓ $item")
                    s.setSpan(StrikethroughSpan(), 0, s.length, 0)
                    rv.setTextViewText(ROW_IDS[k], s)
                    rv.setTextColor(ROW_IDS[k], 0xFF8FB24E.toInt())
                } else {
                    rv.setTextViewText(ROW_IDS[k], "○ $item")
                    rv.setTextColor(ROW_IDS[k], 0xFF141414.toInt())
                }
            }
            rv.setTextViewText(R.id.widget_plans_progress, "$doneCount/${items.size} 已打卡")
            return rv
        }
    }
}
