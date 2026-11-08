package dev.xd.fitnessbutler.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dev.xd.fitnessbutler.MainActivity
import dev.xd.fitnessbutler.R

/**
 * 桌面小组件 B:一键拍照食物。
 * 点击直达拍照记账流(复用 MainActivity.handleQuickIntent 已识别的 ACTION_QUICK_PHOTO)。
 * 静态按钮,无数据刷新。
 */
class PhotoWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_photo)
        val i = Intent(ctx, MainActivity::class.java).apply {
            action = "dev.xd.fitnessbutler.ACTION_QUICK_PHOTO"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            ctx, REQ_OPEN, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        rv.setOnClickPendingIntent(R.id.widget_photo_root, pi)
        mgr.updateAppWidget(ids, rv)
    }

    companion object {
        private const val REQ_OPEN = 2001
    }
}
