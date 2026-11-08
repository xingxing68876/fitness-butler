package dev.xd.fitnessbutler.health

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * 血氧/压力/心率快照采样器:AlarmManager 每 30 分钟唤醒采一次最新快照 → hourly_stats 小时槽,
 * 自己攒出小时曲线(vivo provider 只给最新快照,私有库的分时序列第三方读不到)。
 *
 * 零第三方依赖(无 WorkManager/jetpack,原生 AlarmManager + BroadcastReceiver):
 * - [schedule] 注册周期闹钟(幂等,每次开 app 重注册防被系统回收后失联);
 * - AlarmReceiver 被唤醒后 goAsync+后台线程采样,静默落库,无 UI 无 toast;
 * - BootReceiver 开机重注册(闹钟重启即失);Doze 下 setInexactRepeating 会被推迟合并,可接受。
 * 权限缺失/读失败一律静默跳过(采样是后台攒数据,不许打扰用户)。
 */
object VitalsSampler {

    private const val TAG = "VitalsSampler"
    private const val ACTION_SAMPLE = "dev.xd.fitnessbutler.VITALS_SAMPLE"
    private const val REQ_CODE = 7101

    /** 注册 30 分钟周期采样闹钟(幂等:同 PendingIntent 重复 set 覆盖旧的) */
    fun schedule(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = samplePi(ctx)
            // 系统开机后 5 分钟起跳,之后每 30 分钟(非精确,Doze 合并推迟可接受)
            val first = SystemClock.elapsedRealtime() + 5 * 60_000L
            am.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                first,
                AlarmManager.INTERVAL_HALF_HOUR,
                pi
            )
        } catch (e: Exception) {
            Log.w(TAG, "schedule failed", e)
        }
    }

    /** 立即补采一次(开 app 时调,后台线程,静默) */
    fun sampleNow(ctx: Context) {
        Thread {
            val app = ctx.applicationContext
            runCatching { VivoHealthHarvester.harvest(app) } // 睡眠+当日快照+步数卡路里(半小时自动抓)
            runCatching { VivoHealthHarvester.sampleVitalsHourly(app) }
        }.start()
    }

    private fun samplePi(ctx: Context): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_SAMPLE)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(ctx, REQ_CODE, i, flags)
    }

    /** 周期闹钟接收:goAsync 争取 10 秒后台额度,采完静默结束 */
    class AlarmReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent?) {
            if (intent?.action != ACTION_SAMPLE) return
            val pending = goAsync()
            Thread {
                try {
                    val app = ctx.applicationContext
                    runCatching { VivoHealthHarvester.harvest(app) } // 睡眠+当日快照+步数卡路里
                    runCatching { VivoHealthHarvester.sampleVitalsHourly(app) }
                } finally {
                    pending.finish()
                }
            }.start()
        }
    }

    /** 开机重注册周期闹钟(闹钟重启即失) */
    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
            schedule(ctx.applicationContext)
        }
    }
}
