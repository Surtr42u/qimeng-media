package media.qimeng.app.core.data.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.ByteArrayOutputStream
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 备份目录内固定名备份文件的状态（大小/修改时间；备份页状态行数据源） */
data class BackupFileStatus(
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
)

/** 固定的两个备份槽；不接受任意文件名或路径。 */
enum class BackupFileKind(val fileName: String) {
    MANUAL(AutoBackupRunner.BACKUP_FILE_NAME),
    AUTOMATIC(AutoBackupRunner.AUTO_BACKUP_FILE_NAME),
}

/** 同一授权目录内，手动/PC备份与自动输出独立，恢复必须显式选定。 */
interface BackupDirAccess {

    /** 覆盖写固定名备份文件（不存在先在树根创建），返回实写字节数；失败返回 null */
    suspend fun writeBytes(dirUri: String, json: String, kind: BackupFileKind = BackupFileKind.MANUAL): Long?

    /** 读固定名备份文件全量字节；无文件/读失败返回 null */
    suspend fun readBytes(dirUri: String, kind: BackupFileKind = BackupFileKind.MANUAL): ByteArray?

    /** 固名备份文件状态（大小/修改时间）；无文件/查询失败返回 null */
    suspend fun fileStatus(dirUri: String, kind: BackupFileKind = BackupFileKind.MANUAL): BackupFileStatus?
}

/**
 * SAF 树 URI 实现（contentResolver）。授权链在屏幕层（BackupScreen 的 OpenDocumentTree +
 * takePersistableUriPermission 持久化），此处只消费已持久化授权的树 URI——同一 URI 读写
 * 都合法（写 openOutputStream("wt") 截断覆盖、读 openInputStream）。
 */
@Singleton
class SafBackupDirAccess @Inject constructor(
    @ApplicationContext private val context: Context,
) : BackupDirAccess {

    override suspend fun writeBytes(dirUri: String, json: String, kind: BackupFileKind): Long? = withContext(Dispatchers.IO) {
        runCatching {
            if (Uri.parse(dirUri).scheme == "file") {
                val folder = checkedSharedFolder(requireNotNull(Uri.parse(dirUri).path))
                writeMaintenanceFile(folder, kind.fileName, json)
                return@runCatching json.toByteArray(Charsets.UTF_8).size.toLong()
            }
            val resolver = context.contentResolver
            val tree = Uri.parse(dirUri)
            val treeDocId = DocumentsContract.getTreeDocumentId(tree)
            val treeDoc = DocumentsContract.buildDocumentUriUsingTree(tree, treeDocId)
            // 已存在则截断覆写；不存在先在树根创建（旧版「绮梦影库目录写 qimeng_backup.json」同语义）
            val target = findDocumentUri(resolver, tree, treeDocId, kind)
                ?: DocumentsContract.createDocument(resolver, treeDoc, JSON_MIME, kind.fileName)
                ?: return@runCatching null
            resolver.openOutputStream(target, WRITE_MODE_TRUNCATE)?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            } ?: return@runCatching null
            json.toByteArray(Charsets.UTF_8).size.toLong()
        }.getOrNull()
    }

    override suspend fun readBytes(dirUri: String, kind: BackupFileKind): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            if (Uri.parse(dirUri).scheme == "file") {
                val folder = checkedSharedFolder(requireNotNull(Uri.parse(dirUri).path))
                val file = File(folder, kind.fileName)
                require(file.canonicalFile.parentFile == folder)
                if (!file.isFile) return@runCatching null
                require(file.length() <= BackupValidator.BACKUP_MAX_BYTES.toLong()) { "备份超过64MiB上限" }
                return@runCatching file.inputStream().use { stream ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = stream.read(buffer, 0, minOf(buffer.size, BackupValidator.BACKUP_MAX_BYTES + 1 - output.size()))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        require(output.size() <= BackupValidator.BACKUP_MAX_BYTES) { "备份超过64MiB上限" }
                    }
                    output.toByteArray()
                }
            }
            val resolver = context.contentResolver
            val tree = Uri.parse(dirUri)
            val target = findDocumentUri(resolver, tree, DocumentsContract.getTreeDocumentId(tree), kind)
                ?: return@runCatching null
            resolver.openInputStream(target)?.use { stream -> stream.readBytes() }
        }.getOrNull()
    }

    override suspend fun fileStatus(dirUri: String, kind: BackupFileKind): BackupFileStatus? = withContext(Dispatchers.IO) {
        runCatching {
            if (Uri.parse(dirUri).scheme == "file") {
                val folder = checkedSharedFolder(requireNotNull(Uri.parse(dirUri).path))
                val file = File(folder, kind.fileName)
                require(file.canonicalFile.parentFile == folder)
                return@runCatching if (file.isFile) BackupFileStatus(file.length(), file.lastModified()) else null
            }
            val resolver = context.contentResolver
            val tree = Uri.parse(dirUri)
            resolver.query(
                DocumentsContract.buildChildDocumentsUriUsingTree(
                    tree,
                    DocumentsContract.getTreeDocumentId(tree),
                ),
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null, null, null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == kind.fileName) {
                        return@runCatching BackupFileStatus(
                            sizeBytes = cursor.getLong(2),
                            lastModifiedMillis = cursor.getLong(3),
                        )
                    }
                }
                null
            }
        }.getOrNull()
    }

    /** 树根下按显示名找固定名文件（部分 provider 不支持 selection 过滤，取回后内存比对） */
    private fun findDocumentUri(resolver: ContentResolver, tree: Uri, treeDocId: String, kind: BackupFileKind): Uri? {
        resolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeDocId),
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == kind.fileName) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                }
            }
        }
        return null
    }

    private companion object {
        /** 备份文件 MIME（创建文档用；内容校验在 BackupValidator） */
        const val JSON_MIME = "application/json"

        /** openOutputStream 截断覆写模式（固定文件名覆盖式语义的落盘载体） */
        const val WRITE_MODE_TRUNCATE = "wt"
    }
}
