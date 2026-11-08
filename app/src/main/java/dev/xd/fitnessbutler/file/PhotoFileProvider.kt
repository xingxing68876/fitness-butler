package dev.xd.fitnessbutler.file

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * 纯框架自实现的拍照暂存文件提供器(替代 androidx FileProvider,保持零第三方依赖)。
 * 只服务本 app 私有目录 files/photos/ 下的文件:
 *   content://dev.xd.fitnessbutler.fileprovider/photo/<文件名>
 * 供系统相机 Intent 的 EXTRA_OUTPUT 写入。
 */
class PhotoFileProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "dev.xd.fitnessbutler.fileprovider"
    }

    private fun fileOf(uri: Uri): File {
        val segs = uri.pathSegments
        val name = segs.lastOrNull()
            ?: throw FileNotFoundException("空文件名")
        // 防目录穿越
        if (name.contains("..") || name.contains("/") || name.contains("\\")) {
            throw FileNotFoundException("非法文件名")
        }
        // share/ → files/share/(2026-11-06 打卡海报分享);其余 → files/photos/(相机暂存)
        val sub = if (segs.size >= 2 && segs[0] == "share") "share" else "photos"
        val dir = File(context!!.filesDir, sub)
        return File(dir, name)
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = fileOf(uri)
        if (!f.exists()) {
            // 相机写入前文件不存在,按需建空文件
            f.parentFile?.mkdirs()
            f.createNewFile()
        }
        val m = when {
            mode.contains("w") -> ParcelFileDescriptor.MODE_READ_WRITE or
                ParcelFileDescriptor.MODE_CREATE
            else -> ParcelFileDescriptor.MODE_READ_ONLY
        }
        return ParcelFileDescriptor.open(f, m)
    }

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor {
        // 个别相机会查 _display_name / _size,给全
        val f = fileOf(uri)
        val cols = arrayOf("_display_name", "_size")
        val cursor = MatrixCursor(cols)
        cursor.addRow(arrayOf(f.name, if (f.exists()) f.length() else 0L))
        return cursor
    }

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        val f = fileOf(uri)
        return if (f.exists() && f.delete()) 1 else 0
    }

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?
    ): Int = 0
}
