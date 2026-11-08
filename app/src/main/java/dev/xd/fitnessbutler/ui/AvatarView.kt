package dev.xd.fitnessbutler.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * 圆形头像(2026-10-18):有自定义图显示图,否则显示风格 emoji。
 * 用户(AI=false)绿底,AI 黑底;圆形裁剪走 outline,零第三方依赖。
 */
class AvatarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val emoji = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = 16f
    }

    private val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        visibility = GONE
    }

    init {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        addView(emoji, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (height > 0) {
                // 字号跟着头像直径走:42dp→约 19sp、34dp→约 15sp
                emoji.textSize = height / resources.displayMetrics.scaledDensity * 0.46f
            }
        }
    }

    /** 绑数据:有自定义图优先,否则风格 emoji */
    fun bind(isAi: Boolean, style: String?) {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (isAi) 0xFF141414.toInt() else 0xFF8FB24E.toInt())
        }
        val bmp: Bitmap? = AvatarUtil.load(context, isAi, style)
        if (bmp != null) {
            image.setImageBitmap(bmp)
            image.visibility = VISIBLE
            emoji.visibility = GONE
        } else {
            emoji.text = AvatarUtil.defaultEmoji(isAi, style)
            emoji.visibility = VISIBLE
            image.setImageDrawable(null)
            image.visibility = GONE
        }
    }
}
