package media.qimeng.app.core.data.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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

/**
 * 备份目录（SAF 树 URI）访问端口：固定名 [AutoBackupRunner.BACKUP_FILE_NAME] 的写/读/状态三件。
 * 为什么抽端口：备份页「导入导出」与「自动备份」共用同一 SAF 授权目录同一文件（任务S 2026-09-19
 * 两卡收敛：导出=直写该文件、导入=直读该文件、自动备份=定时直写），读写细节（find-or-create/
 * 覆盖写/元数据查询）单源收口在此；同时 JVM 单测以 fake 注入（真实 Context 不可得，
 * AutoBackupRunner 测试同款约束）。
 */
interface BackupDirAccess {

    /** 覆盖写固定名备份文件（不存在先在树根创建），返回实写字节数；失败返回 null */
    suspend fun writeBytes(dirUri: String, json: String): Long?

    /** 读固定名备份文件全量字节；无文件/读失败返回 null */
    suspend fun readBytes(dirUri: String): ByteArray?

    /** 固名备份文件状态（大小/修改时间）；无文件/查询失败返回 null */
    suspend fun fileStatus(dirUri: String): BackupFileStatus?
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

    override suspend fun writeBytes(dirUri: String, json: String): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val tree = Uri.parse(dirUri)
            val treeDocId = DocumentsContract.getTreeDocumentId(tree)
            val treeDoc = DocumentsContract.buildDocumentUriUsingTree(tree, treeDocId)
            // 已存在则截断覆写；不存在先在树根创建（旧版「绮梦影库目录写 qimeng_backup.json」同语义）
            val target = findDocumentUri(resolver, tree, treeDocId)
                ?: DocumentsContract.createDocument(resolver, treeDoc, JSON_MIME, AutoBackupRunner.BACKUP_FILE_NAME)
                ?: return@runCatching null
            resolver.openOutputStream(target, WRITE_MODE_TRUNCATE)?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            } ?: return@runCatching null
            json.toByteArray(Charsets.UTF_8).size.toLong()
        }.getOrNull()
    }

    override suspend fun readBytes(dirUri: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val tree = Uri.parse(dirUri)
            val target = findDocumentUri(resolver, tree, DocumentsContract.getTreeDocumentId(tree))
                ?: return@runCatching null
            resolver.openInputStream(target)?.use { stream -> stream.readBytes() }
        }.getOrNull()
    }

    override suspend fun fileStatus(dirUri: String): BackupFileStatus? = withContext(Dispatchers.IO) {
        runCatching {
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
                    if (cursor.getString(1) == AutoBackupRunner.BACKUP_FILE_NAME) {
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
    private fun findDocumentUri(resolver: ContentResolver, tree: Uri, treeDocId: String): Uri? {
        resolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeDocId),
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == AutoBackupRunner.BACKUP_FILE_NAME) {
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
