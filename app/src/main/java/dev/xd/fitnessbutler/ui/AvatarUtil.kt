package dev.xd.fitnessbutler.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.LruCache
import java.io.File

/**
 * 头像存取(2026-10-18 设计决策):AI 每个风格一个头像 + 用户头像,可自定义换图。
 * - 默认:内置 emoji(AI 按风格,用户 👤)
 * - 自定义:相册选图 → 纠 EXIF 旋转 → 居中裁方 → 256px JPEG 存 filesDir/avatars/。
 *   不走数据库和 settings;删文件即回默认。
 */
object AvatarUtil {

    val AI_STYLES = listOf("嘴毒", "鸡汤", "跪求", "自定义")

    private val cache = LruCache<String, Bitmap>(8)

    fun defaultEmoji(isAi: Boolean, style: String?): String = if (!isAi) "👤" else when (style) {
        "嘴毒" -> "😈"
        "鸡汤" -> "🌞"
        "跪求" -> "🙇"
        else -> "🤖"
    }

    /** 稳定文件名:中文风格名不落文件名,避开各机型文件系统差异 */
    fun slugFor(isAi: Boolean, style: String?): String = if (!isAi) "user" else when (style) {
        "嘴毒" -> "ai_zaidu"
        "鸡汤" -> "ai_jitang"
        "跪求" -> "ai_guiqiu"
        else -> "ai_custom"
    }

    /** slug 反查(相册选图回调只带 slug,Activity 重建也不丢) */
    fun fromSlug(slug: String): Pair<Boolean, String?> = when (slug) {
        "ai_zaidu" -> true to "嘴毒"
        "ai_jitang" -> true to "鸡汤"
        "ai_guiqiu" -> true to "跪求"
        "ai_custom" -> true to "自定义"
        else -> false to null
    }

    fun labelFor(isAi: Boolean, style: String?): String =
        if (!isAi) "我的头像" else "AI · ${style ?: "默认"}"

    fun avatarFile(ctx: Context, isAi: Boolean, style: String?): File =
        File(File(ctx.filesDir, "avatars").apply { mkdirs() }, "${slugFor(isAi, style)}.jpg")

    fun hasCustom(ctx: Context, isAi: Boolean, style: String?): Boolean =
        avatarFile(ctx, isAi, style).let { it.exists() && it.length() > 0 }

    fun load(ctx: Context, isAi: Boolean, style: String?): Bitmap? {
        val f = avatarFile(ctx, isAi, style)
        if (!f.exists()) return null
        val key = f.absolutePath + ":" + f.lastModified()
        cache.get(key)?.let { return it }
        val bmp = runCatching { decodeSquare(f, 256) }.getOrNull() ?: return null
        cache.put(key, bmp)
        return bmp
    }

    fun save(ctx: Context, srcPath: String, isAi: Boolean, style: String?): Boolean = runCatching {
        val bmp = decodeSquare(File(srcPath), 256) ?: return@runCatching false
        val dst = avatarFile(ctx, isAi, style)
        dst.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        cache.evictAll()
        dst.length() > 0
    }.getOrDefault(false)

    fun reset(ctx: Context, isAi: Boolean, style: String?) {
        avatarFile(ctx, isAi, style).delete()
        cache.evictAll()
    }

    /** 解码 + 纠 EXIF 旋转 + 居中裁方 + 缩到 side 像素内 */
    private fun decodeSquare(f: File, side: Int): Bitmap? {
        if (!f.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > side * 2 || bounds.outHeight / sample > side * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = BitmapFactory.decodeFile(f.absolutePath, opts) ?: return null

        val orientation = runCatching {
            ExifInterface(f.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        }
        if (!matrix.isIdentity) {
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        }
        val size = minOf(bmp.width, bmp.height)
        val x = (bmp.width - size) / 2
        val y = (bmp.height - size) / 2
        var out = Bitmap.createBitmap(bmp, x, y, size, size)
        if (out.width > side) out = Bitmap.createScaledBitmap(out, side, side, true)
        return out
    }
}
