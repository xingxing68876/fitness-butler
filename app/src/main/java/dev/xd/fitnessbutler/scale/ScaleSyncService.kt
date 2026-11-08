package dev.xd.fitnessbutler.scale

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * 连秤前台保活(2026-11-05 小组件称重间歇失败修复):
 * 点小组件后放下手机/熄屏,vivo 冻结 APP 进程 → 秤发的实时帧/锁定帧投递到冻结进程被系统丢弃 →
 * 浮层「没等到锁定读数」且不入账(时好时不好:拿在手里看浮层就成功)。
 * 前台服务进程不被冻结,熄屏照样收帧入账。
 * 只做保活:连秤逻辑全在 ScaleAutoLink,收工(fireDone)时 stop() 撤通知停服务。
 */
class ScaleSyncService : Service() {

    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
        // 兜底:连秤链路若异常未收工(超时分支全失效的极端情况),3 分钟后自动撤通知
        main.postDelayed({ ScaleSyncService.stop(this) }, 180_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_NOT_STICKY

    private fun buildNotif(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "连秤称重", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .setContentTitle(text)
            .setOngoing(true)
            .build()
    }

    private fun goForeground() {
        val n = buildNotif("正在连体重秤,请上秤…")
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIF_ID, n)
    }

    companion object {
        private const val CHANNEL_ID = "scale_sync"
        private const val NOTIF_ID = 9301

        /** 连秤会话开始时拉起(熄屏保进程不冻结) */
        fun start(ctx: Context) {
            val i = Intent(ctx, ScaleSyncService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        /** 连秤收工:撤通知停服务(未启动过时调用无害) */
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ScaleSyncService::class.java))
        }
    }
}
