package dev.xd.fitnessbutler.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dev.xd.fitnessbutler.R
import dev.xd.fitnessbutler.db.DbHelper
import dev.xd.fitnessbutler.scale.ScaleSyncActivity

/**
 * 桌面小组件 C:一键连秤同步(2026-10-25 设计决策)。
 * 1×1 单格:⚖️ + 最新体重,点击拉起 ScaleSyncActivity 直接连秤入账。
 * 入账成功后 ScaleAutoLink 调 [pushUpdate] 广播刷新体重文本。
 */
class ScaleWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        render(ctx, mgr, ids)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_REFRESH) {
            val mgr = AppWidgetManager.getInstance(ctx)
            render(ctx, mgr, mgr.getAppWidgetIds(ComponentName(ctx, ScaleWidget::class.java)))
        }
    }

    companion object {
        const val ACTION_REFRESH = "dev.xd.fitnessbutler.WIDGET_SCALE_REFRESH"
        private const val REQ_OPEN = 2101

        /** 连秤入账后刷新桌面显示(应用内广播,不出包) */
        fun pushUpdate(ctx: Context) {
            ctx.sendBroadcast(Intent(ACTION_REFRESH).setPackage(ctx.packageName))
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            val w = DbHelper.get(ctx).latestWeighing()
            val rv = RemoteViews(ctx.packageName, R.layout.widget_scale)
            if (w != null) {
                rv.setTextViewText(
                    R.id.widget_scale_value, String.format(java.util.Locale.US, "%.1f", w.weightKg)
                )
                // ts 形如 2026-10-25T07:31:02,取 MM-dd
                rv.setTextViewText(R.id.widget_scale_label, w.ts.substring(5, 10))
            } else {
                rv.setTextViewText(R.id.widget_scale_value, "--")
                rv.setTextViewText(R.id.widget_scale_label, "称重")
            }
            val i = Intent(ctx, ScaleSyncActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                ctx, REQ_OPEN, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(R.id.widget_scale_root, pi)
            mgr.updateAppWidget(ids, rv)
        }
    }
}
