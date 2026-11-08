package dev.xd.fitnessbutler.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * 海报背景裁切(2026-11-06,自研零依赖,离线构建约束不引第三方):
 * 固定 3:4 亮框视窗,单指拖动 + 双指缩放图片,confirm 后按视窗裁源图并缩放到目标尺寸。
 */
class PosterCropView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var src: Bitmap? = null
    private val matrix = Matrix()
    private val viewRect = RectF()
    private val dimPaint = Paint().apply { color = 0xB3000000.toInt() }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.WHITE
    }
    private val scaleDetector: ScaleGestureDetector

    init {
        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(det: ScaleGestureDetector): Boolean {
                matrix.postScale(det.scaleFactor, det.scaleFactor, det.focusX, det.focusY)
                clampMatrix()
                invalidate()
                return true
            }
        })
    }

    /** 视窗:屏宽 86%,3:4 居中 */
    private fun windowRect(): RectF {
        val w = width * 0.86f
        val h = w * 4f / 3f
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        return RectF(left, top, left + w, top + h)
    }

    fun setup(bmp: Bitmap) {
        src = bmp
        post { initMatrix(); invalidate() }
    }

    private fun initMatrix() {
        val b = src ?: return
        viewRect.set(windowRect())
        // 初始:图片 fit 填满视窗(crop-fill)
        val sc = max(viewRect.width() / b.width, viewRect.height() / b.height)
        matrix.reset()
        matrix.postTranslate(viewRect.centerX() - b.width / 2f, viewRect.centerY() - b.height / 2f)
        matrix.postScale(sc, sc, viewRect.centerX(), viewRect.centerY())
        clampMatrix()
        invalidate()
    }

    /** 约束:图片始终盖满视窗;放大不超 6x */
    private fun clampMatrix() {
        val b = src ?: return
        val r = windowRect()
        val m = Matrix(matrix)
        val f = FloatArray(9)
        m.getValues(f)
        val curScale = f[Matrix.MSCALE_X]
        val fill = max(r.width() / b.width, r.height() / b.height)
        val minS = fill
        val maxS = fill * 6f
        val target = curScale.coerceIn(minS, maxS)
        if (abs(target - curScale) > 0.0001f) {
            matrix.postScale(target / curScale, target / curScale, r.centerX(), r.centerY())
        }
        // 平移约束:图片边缘不得进入视窗内
        val mapped = RectF(0f, 0f, b.width.toFloat(), b.height.toFloat())
        matrix.mapRect(mapped)
        var dx = 0f; var dy = 0f
        if (mapped.left > r.left) dx = r.left - mapped.left
        if (mapped.right < r.right) dx = r.right - mapped.right
        if (mapped.top > r.top) dy = r.top - mapped.top
        if (mapped.bottom < r.bottom) dy = r.bottom - mapped.bottom
        matrix.postTranslate(dx, dy)
    }

    private var lastX = 0f; private var lastY = 0f
    private var dragging = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y; dragging = true }
            MotionEvent.ACTION_POINTER_DOWN -> dragging = false
            MotionEvent.ACTION_MOVE -> if (dragging && !scaleDetector.isInProgress) {
                matrix.postTranslate(e.x - lastX, e.y - lastY)
                clampMatrix()
                lastX = e.x; lastY = e.y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val b = src ?: return
        val r = windowRect()
        c.drawColor(Color.BLACK)
        // 图片
        val save = c.saveCount
        c.save()
        c.clipRect(r)
        c.drawBitmap(b, matrix, null)
        c.restoreToCount(save)
        // 视窗外压暗
        val outP = Paint().apply { color = dimPaint.color }
        c.drawRect(0f, 0f, width.toFloat(), r.top, outP)
        c.drawRect(0f, r.bottom, width.toFloat(), height.toFloat(), outP)
        c.drawRect(0f, r.top, r.left, r.bottom, outP)
        c.drawRect(r.right, r.top, width.toFloat(), r.bottom, outP)
        // 亮框
        c.drawRect(r, framePaint)
    }

    /** 按当前视窗裁源图,缩放到 targetW×targetH */
    fun cropTo(targetW: Int, targetH: Int): Bitmap? {
        val b = src ?: return null
        val r = windowRect()
        val inv = Matrix()
        inv.set(matrix)
        val srcRect = RectF(r)
        inv.mapRect(srcRect)
        val l = max(0f, srcRect.left); val t = max(0f, srcRect.top)
        val rr = min(b.width.toFloat(), srcRect.right); val bb = min(b.height.toFloat(), srcRect.bottom)
        val cw = (rr - l).toInt(); val ch = (bb - t).toInt()
        if (cw <= 10 || ch <= 10) return null
        val piece = Bitmap.createBitmap(b, l.toInt(), t.toInt(), cw, ch)
        val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val sc = targetW.toFloat() / cw
        val sc2 = targetH.toFloat() / ch
        val s = max(sc, sc2)
        c.scale(s, s)
        c.drawBitmap(piece, 0f, 0f, null)
        if (piece != b) piece.recycle()
        return out
    }

    private fun abs(v: Float) = if (v < 0) -v else v
}
