package dev.xd.fitnessbutler.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.LruCache
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import dev.xd.fitnessbutler.data.MoveDemoData
import kotlin.math.max
import kotlin.math.min

/**
 * 动作演示帧序列播放器 v2(2026-10-18,替换火柴人引擎)。
 * 数据:assets/movedemo/<dir>/<front|side>_<00-15>.jpg(opengym3d 离屏渲染,含器材双视角)
 * 播放:Choreographer 帧回调,phase∈[0,1),phase+=dt*speed/durationSec;帧号=phase*frameCount
 * 控制:play/pause/seek/setSpeed(0.5~2 不跳相)/setMirror(canvas 水平翻转)/setView(正/侧)
 * 解码:按需 BitmapFactory.decode + LruCache(6帧),RGB_565 省内存;8fps 节奏下单帧解码 ~10ms 无感
 * 镜像:只翻画面,进度线不变
 */
class MoveDemoView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var demo: MoveDemoData.FrameDemo? = null
    private var phase = 0f
    private var speed = 1f
    private var playing = false
    private var mirror = false
    private var viewSide = "front"
    private var lastNanos = 0L

    private val cache = object : LruCache<String, Bitmap>(6) {
        override fun sizeOf(key: String, value: Bitmap) = 1
    }

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8A8A8A.toInt(); textSize = dp(14f); textAlign = Paint.Align.CENTER
    }
    private val progressBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000 }
    private val progressFg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5A8F2E.toInt() }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    // ---- Choreographer 播放循环 ----
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!playing) return
            if (lastNanos != 0L) {
                val dt = ((frameTimeNanos - lastNanos) / 1_000_000_000.0).toFloat()
                    .coerceAtMost(0.05f)
                val d = demo ?: return
                phase = ((phase + dt * speed / d.durationSec) % 1f + 1f) % 1f
            }
            lastNanos = frameTimeNanos
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // ---- 公开 API(与旧火柴人版兼容) ----
    fun setMove(d: MoveDemoData.FrameDemo?) {
        demo = d
        phase = 0f
        lastNanos = 0L
        cache.evictAll()
        invalidate()
    }

    fun play() {
        if (demo == null || playing) return
        playing = true
        lastNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun pause() {
        playing = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    fun seek(p: Float) {
        phase = ((p % 1f) + 1f) % 1f
        invalidate()
    }

    fun setSpeed(mult: Float) {
        speed = mult.coerceIn(0.25f, 4f) // 切速不重启不跳相
    }

    fun setMirror(on: Boolean) {
        mirror = on
        invalidate()
    }

    /** 切视角:front|side,相位保持 */
    fun setView(side: String) {
        viewSide = if (side == "side") "side" else "front"
        invalidate()
    }

    fun isPlaying(): Boolean = playing

    fun currentPhase(): Float = phase

    fun release() = pause()

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    // ---- 进度线拖动 seek ----
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (demo == null) return false
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            if (event.y >= height - dp(18f)) {
                seek(event.x / width.toFloat())
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // ---- 绘制 ----
    override fun onDraw(canvas: Canvas) {
        val d = demo
        val w = width.toFloat()
        val h = height.toFloat()
        if (d == null) {
            canvas.drawText("该动作暂未收录动画,先照要领练", w / 2f, h / 2f, hintPaint)
            return
        }
        val idx = (phase * d.frameCount).toInt().coerceIn(0, d.frameCount - 1)
        val bmp = loadFrame(d.dir, viewSide, idx)
        if (bmp != null) {
            // fit-center 满幅绘制
            val scale = min(w / bmp.width, h / bmp.height)
            val dw = bmp.width * scale
            val dh = bmp.height * scale
            val left = (w - dw) / 2f
            val top = (h - dh) / 2f
            canvas.save()
            if (mirror) canvas.scale(-1f, 1f, w / 2f, 0f)
            canvas.drawBitmap(bmp, null, RectF(left, top, left + dw, top + dh), bitmapPaint)
            canvas.restore()
        }
        // 进度线(不镜像)
        val barH = dp(3f)
        canvas.drawRect(0f, h - barH, w, h, progressBg)
        canvas.drawRect(0f, h - barH, w * phase, h, progressFg)
    }

    private fun loadFrame(dir: String, side: String, idx: Int): Bitmap? {
        val key = "$dir/$side/$idx"
        cache.get(key)?.let { return it }
        return try {
            context.assets.open("movedemo/$dir/${side}_%02d.jpg".format(idx)).use { ins ->
                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                val bmp = BitmapFactory.decodeStream(ins, null, opts)
                if (bmp != null) cache.put(key, bmp)
                bmp
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
