package media.qimeng.app.core.data.upload

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File

/** 源时间为可选提示；不支持该列的分享提供方不影响文件名/大小读取与上传。 */
object UploadSourceMetadata {
    private const val MILLIS_PER_SECOND = 1000L
    private const val MAX_MTIME = 253402300799999L // OpenAPI sourceMtime 上界，双向同步。

    fun modifiedAt(context: Context, uri: Uri): Long? {
        if (uri.scheme == "file" || uri.scheme == null) {
            return File(uri.path ?: uri.toString()).lastModified().takeIf { it in 1..MAX_MTIME }
        }
        fun column(name: String, multiplier: Long): Long? = runCatching {
            context.contentResolver.query(uri, arrayOf(name), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(name)
                if (!cursor.moveToFirst() || index < 0 || cursor.isNull(index)) null
                else cursor.getLong(index).takeIf { it in 1..(MAX_MTIME / multiplier) }?.times(multiplier)
            }
        }.getOrNull()
        return column(DocumentsContract.Document.COLUMN_LAST_MODIFIED, 1L)
            ?: column(MediaStore.MediaColumns.DATE_MODIFIED, MILLIS_PER_SECOND)
    }
}
