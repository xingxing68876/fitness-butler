package dev.xd.fitnessbutler.rec

import android.os.Handler
import android.os.Looper

/**
 * 识别完成事件总线(2026-10-19 后台化改造):
 * 服务线程跑完 → post 到主线程;UI 在前台就当场呈现,不在前台事件丢弃——
 * 结果已落库(status='已识别待呈现'),HomeFragment.onStart 会认领,不丢不重。
 */
object RecognizeBus {

    /** queueId=recognition_queue 行 id;成功 resultJson 非空,失败 error 非空(二选一) */
    data class Done(
        val photoPath: String,
        val queueId: Long,
        val resultJson: String?,
        val error: String?
    )

    private val main = Handler(Looper.getMainLooper())
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<(Done) -> Unit>()

    fun add(l: (Done) -> Unit) { listeners.add(l) }

    fun remove(l: (Done) -> Unit) { listeners.remove(l) }

    fun post(d: Done) {
        main.post {
            for (l in listeners) runCatching { l(d) }
        }
    }
}
