package dev.xd.fitnessbutler.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.ImageView
import kotlin.math.max
import kotlin.math.min

/**
 * 全屏看大图(2026-10-26 设计决策:对话流照片点开放大)。
 * 黑底全屏 fitCenter;双指捏合缩放 / 放大后单指拖动 / 双击 1x↔2.5x / 单击关闭。
 * 零第三方依赖,MATRIX scaleType 手写手势;解码按屏幕宽降采样防 OOM。
 */
class PhotoViewerActivity : Activity() {

    private lateinit var img: ImageView
    private val main = Handler(Looper.getMainLooper())

    // 手势状态(经典 Matrix ImageView 三态)
    private val saved = Matrix()
    private val curr = Matrix()
    private var mode = MODE_NONE
    private var lastX = 0f
    private var lastY = 0f
    private var startDist = 0f
    private var midX = 0f
    private var midY = 0f
    private var downAt = 0L
    private var moved = false

    // 双击检测:第一次 tap 延迟 280ms 关窗,期间第二击改切缩放
    private var lastTapAt = 0L
    private var closePending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        img = ImageView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            scaleType = ImageView.ScaleType.MATRIX
        }
        setContentView(img)
        val path = intent.getStringExtra(EXTRA_PATH) ?: ""
        Thread {
            val bmp = decodeForScreen(path)
            runOnUiThread {
                if (bmp == null || isFinishing) {
                    finish()
                    return@runOnUiThread
                }
                onLoaded(bmp)
            }
        }.start()
        img.setOnTouchListener { _, e -> onTouch(e) }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** 载入后初始矩阵:fit-center 居中 */
    private fun onLoaded(bmp: Bitmap) {
        img.setImageBitmap(bmp)
        img.post {
            val vw = img.width.toFloat()
            val vh = img.height.toFloat()
            if (vw <= 0 || vh <= 0) return@post
            val s = min(vw / bmp.width, vh / bmp.height)
            curr.reset()
            curr.setScale(s, s)
            curr.postTranslate((vw - bmp.width * s) / 2f, (vh - bmp.height * s) / 2f)
            img.imageMatrix = curr
        }
    }

    private fun onTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                saved.set(curr)
                lastX = e.x; lastY = e.y
                mode = MODE_DRAG
                downAt = System.currentTimeMillis()
                moved = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount >= 2) {
                    startDist = dist(e)
                    mid(e)
                    saved.set(curr)
                    mode = MODE_ZOOM
                }
            }
            MotionEvent.ACTION_MOVE -> {
                when (mode) {
                    MODE_DRAG -> {
                        val dx = e.x - lastX
                        val dy = e.y - lastY
                        if (Math.abs(e.x - lastX) > 6f || Math.abs(e.y - lastY) > 6f) moved = true
                        curr.set(saved)
                        curr.postTranslate(dx, dy)
                        img.imageMatrix = curr
                    }
                    MODE_ZOOM -> {
                        if (startDist > 0f) {
                            val sc = dist(e) / startDist
                            curr.set(saved)
                            curr.postScale(sc, sc, midX, midY)
                            img.imageMatrix = curr
                        }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // 捏合结束剩一指:以剩余指为拖动锚点续拖
                if (e.pointerCount == 2) {
                    saved.set(curr)
                    lastX = e.getX(if (e.actionIndex == 0) 1 else 0)
                    lastY = e.getY(if (e.actionIndex == 0) 1 else 0)
                    mode = MODE_DRAG
                }
            }
            MotionEvent.ACTION_UP -> {
                if (mode == MODE_DRAG && !moved &&
                    System.currentTimeMillis() - downAt < 250
                ) handleTap()
                mode = MODE_NONE
            }
            MotionEvent.ACTION_CANCEL -> mode = MODE_NONE
        }
        return true
    }

    /** 单击:280ms 内两击=双击切 1x/2.5x;否则延迟关窗 */
    private fun handleTap() {
        if (closePending) return   // 上一次 tap 还在等待期,这一击只会被当双击,不重复排
        val now = System.currentTimeMillis()
        if (now - lastTapAt < 320) {
            lastTapAt = 0L
            toggleZoom()
        } else {
            lastTapAt = now
            closePending = true
            main.postDelayed({
                closePending = false
                if (!isFinishing) finish()
                overridePendingTransition(0, 0)
            }, 290)
        }
    }

    /** 双击:当前接近 1x → 围屏中心放 2.5x;否则回 1x(以 fit 基准算,防多次双击越滚越大) */
    private fun toggleZoom() {
        val bm = img.drawable ?: return
        val vw = img.width.toFloat()
        val vh = img.height.toFloat()
        if (vw <= 0 || vh <= 0) return
        val vals = FloatArray(9)
        curr.getValues(vals)
        val base = min(vw / bm.intrinsicWidth, vh / bm.intrinsicHeight)
        val m = Matrix().apply {
            setScale(base, base)
            postTranslate((vw - bm.intrinsicWidth * base) / 2f, (vh - bm.intrinsicHeight * base) / 2f)
        }
        if (vals[Matrix.MSCALE_X] < base * 1.6f) m.postScale(2.5f, 2.5f, vw / 2f, vh / 2f)
        curr.set(m)
        img.imageMatrix = curr
    }

    private fun dist(e: MotionEvent): Float {
        val dx = e.getX(0) - e.getX(1)
        val dy = e.getY(0) - e.getY(1)
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    private fun mid(e: MotionEvent) {
        midX = (e.getX(0) + e.getX(1)) / 2f
        midY = (e.getY(0) + e.getY(1)) / 2f
    }

    /** 屏幕宽 2 倍内降采样解码(原图 4000×3000 全解会 OOM) */
    private fun decodeForScreen(path: String): Bitmap? {
        if (path.isBlank()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0) return null
            val targetW = max(resources.displayMetrics.widthPixels * 2, 1080)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetW) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val EXTRA_PATH = "path"
        private const val MODE_NONE = 0
        private const val MODE_DRAG = 1
        private const val MODE_ZOOM = 2
    }
}
