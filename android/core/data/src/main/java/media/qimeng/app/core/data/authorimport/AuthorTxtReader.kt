package media.qimeng.app.core.data.authorimport

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import media.qimeng.sdk.infrastructure.Serializer
import media.qimeng.sdk.models.ApiV1AuthorsImportTxtPostRequest

data class AuthorTxtDocument(val filename: String, val content: String)

interface AuthorTxtReader {
    suspend fun read(uri: Uri): AuthorTxtDocument
}

/** 与服务端 errors.go maxJSONBody、Web author-txt.ts 同步：整份 JSON（含转义）1 MiB。 */
internal const val AUTHOR_TXT_REQUEST_MAX_BYTES = 1 shl 20

class AuthorTxtReadException(message: String) : Exception(message)

/** 先检查元数据，再按上限+1 截止；provider 声明小于实际大小也无法绕过流上限。 */
class SafAuthorTxtReader @Inject constructor(
    @ApplicationContext private val context: Context,
) : AuthorTxtReader {
    override suspend fun read(uri: Uri): AuthorTxtDocument = withContext(Dispatchers.IO) {
        var filename = uri.lastPathSegment.orEmpty()
        var size: Long? = null
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) filename = cursor.getString(nameColumn)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
            }
        }
        readAuthorTxtDocument(filename, size) { context.contentResolver.openInputStream(uri) }
    }
}

/** 保留既有 UTF-8 解码策略：BOM 不特判，非法字节替换；不增加其他编码支持。 */
internal suspend fun readAuthorTxtDocument(
    filename: String,
    declaredSize: Long?,
    open: () -> InputStream?,
): AuthorTxtDocument {
    if (!filename.endsWith(".txt", ignoreCase = true)) throw AuthorTxtReadException("仅支持 .txt 作者清单文件")
    if (declaredSize != null && declaredSize > AUTHOR_TXT_REQUEST_MAX_BYTES) throw tooLarge()
    currentCoroutineContext().ensureActive()
    val bytes = open()?.use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = stream.read(buffer, 0, minOf(buffer.size, AUTHOR_TXT_REQUEST_MAX_BYTES + 1 - output.size()))
            if (read < 0) break
            if (read == 0) continue
            output.write(buffer, 0, read)
            if (output.size() > AUTHOR_TXT_REQUEST_MAX_BYTES) throw tooLarge()
        }
        output.toByteArray()
    } ?: throw AuthorTxtReadException("读取文件失败，请重试")
    val content = bytes.toString(Charsets.UTF_8)
    validateAuthorTxtRequest(filename, content)
    return AuthorTxtDocument(filename, content)
}

/** 使用实际 SDK 适配器计数，避免用字符量或固定转义倍数误判边界。 */
internal fun validateAuthorTxtRequest(filename: String, content: String) {
    val request = ApiV1AuthorsImportTxtPostRequest(filename = filename, content = content)
    val json = Serializer.moshi.adapter(ApiV1AuthorsImportTxtPostRequest::class.java).toJson(request)
    if (json.toByteArray(Charsets.UTF_8).size > AUTHOR_TXT_REQUEST_MAX_BYTES) throw tooLarge()
}

private fun tooLarge() = AuthorTxtReadException("作者 TXT 请求超过 1 MiB 上限（含文件名与 JSON 转义），请选择较小的片段")

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthorTxtReaderModule {
    @Binds abstract fun bindReader(reader: SafAuthorTxtReader): AuthorTxtReader
}
