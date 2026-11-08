package dev.xd.fitnessbutler.rec

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
import dev.xd.fitnessbutler.db.DbHelper
import dev.xd.fitnessbutler.ui.HomeFragment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 前台服务识别器(2026-10-19 设计决策:APP 缩后台/划走,识别继续跑完):
 * - 旧链路=Fragment 内裸 Thread,APP 一进后台(vivo 冻结进程/杀后台)识别必挂;
 *   现改前台服务(dataSync 型)托管,带常驻通知,系统不再冻结。
 * - 结果协议:成功 → 队列行标「已识别待呈现」(带结果 JSON)+ RecognizeBus 通知;
 *   UI 在前台当场呈现;不在前台则回 APP 时 HomeFragment.onStart 认领,不丢不重。
 * - 失败仍走「失败即丢」(2026-10-18 设计决策):删队列行/删图片消息/删照片文件,
 *   并落一条失败消息进对话库,用户回 APP 能看到原因。
 * - 提速两板斧(2026-10-19 用户反馈等太久):多图 3 线程并行 + 上传图压到 640px。
 */
class RecognizeService : Service() {

    companion object {
        const val EXTRA_PATHS = "paths"
        const val CHANNEL_ID = "recognize"
        const val NOTIF_ID = 10

        /** 入口(UI 线程调):paths=照片绝对路径列表 */
        fun start(ctx: Context, paths: List<String>) {
            if (paths.isEmpty()) return
            val app = ctx.applicationContext
            val i = Intent(app, RecognizeService::class.java)
                .putStringArrayListExtra(EXTRA_PATHS, ArrayList(paths))
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    private val remaining = AtomicInteger(0)
    private var pool: java.util.concurrent.ExecutorService? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        val paths = intent?.getStringArrayListExtra(EXTRA_PATHS)
        if (paths.isNullOrEmpty()) { finishIfIdle(); return START_NOT_STICKY }
        if (pool == null) pool = Executors.newFixedThreadPool(3)
        remaining.addAndGet(paths.size)
        updateNotif(remaining.get())
        for (p in paths) {
            val path = p
            pool?.execute { recognizeOne(path) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        pool?.shutdown()
        pool = null
        super.onDestroy()
    }

    // ---------- 单张识别 ----------

    private fun recognizeOne(photoPath: String) {
        val db = DbHelper.get(this)
        var queueId = -1L
        var compressed = photoPath
        var resultJson: String? = null
        var error: String? = null
        try {
            // 2026-11-05 默认引擎切 DeepSeek(实测部分网络到火山引擎间歇断,DeepSeek 走腾讯 EdgeOne 稳;
            // 实测同图 9.7s vs 豆包 45s 效果同档)。key 复用聊天 ds_api_key,零配置。
            val engine = db.getSetting("vision_engine", VisionRecognizers.ENGINE_DEEPSEEK)
            var key = db.getSetting("vision_api_key_$engine", "")
            if (engine == VisionRecognizers.ENGINE_DEEPSEEK && key.isBlank())
                key = db.getSetting("ds_api_key", "")   // 聊天同 key,fallback
            val model = db.getSetting("vision_model_$engine", "")
            if (key.isBlank()) {
                error = "未配置 API Key"
            } else {
                queueId = db.enqueueRecognition(photoPath, timeFmt.format(Date()))
                compressed = ImageUtil.compressForUpload(photoPath, File(filesDir, "photos"))
                // 网络抖动兜底(2026-10-26 建重试;2026-11-05 备用引擎改 DeepSeek↔Doubao 互备:
                // 主引擎网络类失败重试 1 次,仍败切对方引擎再补 2 次,全败才走「失败即丢」)
                val altEngine = if (engine == VisionRecognizers.ENGINE_DOUBAO)
                    VisionRecognizers.ENGINE_DEEPSEEK else VisionRecognizers.ENGINE_DOUBAO
                var altKey = db.getSetting("vision_api_key_$altEngine", "")
                if (altEngine == VisionRecognizers.ENGINE_DEEPSEEK && altKey.isBlank())
                    altKey = db.getSetting("ds_api_key", "")   // 聊天同 key,fallback
                val attempts = mutableListOf(Triple(engine, key, model))
                if (altKey.isNotBlank()) attempts.add(
                    Triple(altEngine, altKey, db.getSetting("vision_model_$altEngine", ""))
                )
                outer@ for ((ai, att) in attempts.withIndex()) {
                    for (round in 0 until 2) {
                        if (round > 0 || ai > 0) Thread.sleep(if (round > 0) 2000L else 1000L)
                        var items: List<RecognizedFood>? = null
                        var e: String? = null
                        VisionRecognizers.build(att.first, att.second, att.third)
                            .recognize(compressed) { it, err -> items = it; e = err }
                        val got = items
                        if (got != null && got.isNotEmpty()) {
                            resultJson = VisionRecognizers.itemsToJson(got)
                            break@outer
                        }
                        error = e ?: "没认出菜品"
                        if (!isNetError(error)) break@outer   // 业务错(Key 额度/没认出)重试无意义
                    }
                }
            }
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }

        if (resultJson != null && queueId > 0) {
            db.markRecognition(queueId, "已识别待呈现", resultJson)
            RecognizeBus.post(RecognizeBus.Done(photoPath, queueId, resultJson, null))
        } else {
            // 失败即丢:队列行/图片消息/压缩图/原图全删,失败原因落一条对话消息(DB)
            if (queueId > 0) db.deleteRecognition(queueId)
            db.deleteChatMessage("${HomeFragment.PHOTO_PREFIX}$photoPath>")
            runCatching { if (compressed != photoPath) File(compressed).delete() }
            runCatching { File(photoPath).delete() }
            db.insertChatMessage(
                "assistant",
                "❌ 识别失败:${(error ?: "没认出菜品").take(120)}\n照片已丢弃(不保存),重新拍一张再试。",
                timeFmt.format(Date()), currentSession(db)
            )
            RecognizeBus.post(RecognizeBus.Done(photoPath, queueId, null, error))
        }

        val left = remaining.decrementAndGet()
        if (left > 0) updateNotif(left) else finishIfIdle()
    }

    /** 当前会话(与 HomeFragment.chatSession 同一 settings 键) */
    private fun currentSession(db: DbHelper): String =
        db.getSetting("chat_session", "main").ifBlank { "main" }

    /**
     * 网络类错误(值得重试):连接中断/超时/DNS 类。
     * HTTP 状态错误("HTTP 4xx/5xx:…"开头)是服务端明确应答,重试同引擎无意义。
     */
    private fun isNetError(e: String?): Boolean {
        if (e == null) return false
        if (e.startsWith("HTTP", ignoreCase = true)) return false
        val l = e.lowercase(Locale.US)
        return "abort" in l || "timeout" in l || "timed out" in l || "connect" in l ||
            "unreachable" in l || "reset" in l || "eof" in l || "socket" in l ||
            "unknownhost" in l || "stream" in l
    }

    // ---------- 通知 / 生命周期收尾 ----------

    private fun buildNotif(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "餐食识别", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(text)
            .setOngoing(true)
            .build()
    }

    private fun goForeground() {
        val n = buildNotif("正在识别餐食照片…")
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIF_ID, n)
    }

    private fun updateNotif(left: Int) {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotif("识别中,还剩 $left 张"))
        }
    }

    /** 全部跑完:撤通知停服务。post 到主线程汇合,避免与新一批 start 的竞态 */
    private fun finishIfIdle() {
        mainHandler.post {
            if (remaining.get() <= 0) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }
}
