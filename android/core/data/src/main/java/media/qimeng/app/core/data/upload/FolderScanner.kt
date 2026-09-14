package media.qimeng.app.core.data.upload

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import media.qimeng.app.core.model.UploadItem
import media.qimeng.app.core.model.UploadRules

/**
 * [FolderScanner] 的 SAF 实现（U10-6c）：DocumentsContract 树枚举递归深搜。
 * tree uri 即用即弃、不 takePersistableUriPermission（M4-5 先例：扫描与上传都在
 * 本会话完成，读权限到设备重启前有效，覆盖队列重试窗口）。
 */
@Singleton
class SafFolderScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) : FolderScanner {

    override suspend fun scan(treeUri: String): FolderScanResult = withContext(Dispatchers.IO) {
        val tree = Uri.parse(treeUri)
        val rootDocId = DocumentsContract.getTreeDocumentId(tree)
        // 口径①：所选文件夹名作为 dir 首段——文件直接落在「文件夹名/...」下
        val rootName = queryDisplayName(tree, rootDocId) ?: fallbackRootName(rootDocId)
        val sink = UploadableFileSink()
        scanChildren(tree, rootDocId, rootName, sink)
        sink.toResult()
    }

    /** 深搜一层子项：目录递归（相对路径随层拼接），文件按扩展名白名单收录/计数。 */
    private fun scanChildren(tree: Uri, parentDocId: String, parentRel: String, sink: UploadableFileSink) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val cursor = context.contentResolver.query(childrenUri, PROJECTION, null, null, null)
        if (cursor == null) {
            // 根层枚举直接失败（provider 拒绝/uri 失效）按扫描失败上抛，避免伪装成"空文件夹"；
            // 子层读不到（权限/瞬时）按空目录跳过，不阻断整次扫描
            if (parentDocId == rootDocId(tree) && sink.totalUploadable == 0 && sink.skippedCount == 0) {
                throw IllegalStateException("文件夹枚举失败 docId=$parentDocId")
            }
            return
        }
        cursor.use {
            while (it.moveToNext()) {
                val docId = it.getString(INDEX_DOCUMENT_ID) ?: continue
                val name = it.getString(INDEX_DISPLAY_NAME) ?: continue
                if (it.getString(INDEX_MIME_TYPE) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    scanChildren(tree, docId, UploadRules.joinUploadDirPath(parentRel, name), sink)
                } else if (FolderScanPolicy.isUploadableName(name)) {
                    val size = if (it.isNull(INDEX_SIZE)) -1L else it.getLong(INDEX_SIZE)
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                    sink.addUploadable(
                        UploadItem(
                            uri = docUri.toString(),
                            displayName = name,
                            sizeBytes = size,
                            relativeDir = parentRel,
                        ),
                    )
                } else {
                    // 口径②：非媒体扩展名跳过+计数提示，不照传吃服务端 400
                    sink.addSkipped()
                }
            }
        }
    }

    /** 查询树根文档的展示名；读不到返回 null（调用方走 documentId 解析兜底）。 */
    private fun queryDisplayName(tree: Uri, rootDocId: String): String? = try {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, rootDocId)
        context.contentResolver.query(docUri, arrayOf(NAME_PROJECTION), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    /** documentId 形如 "primary:DCIM/作者A"——取冒号后、末段路径即文件夹名。 */
    private fun fallbackRootName(rootDocId: String): String =
        rootDocId.substringAfter(':').substringAfterLast('/')

    private fun rootDocId(tree: Uri): String = DocumentsContract.getTreeDocumentId(tree)

    private companion object {
        /** 子项枚举投影（顺序即 [INDEX_*]） */
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )

        const val INDEX_DOCUMENT_ID = 0
        const val INDEX_DISPLAY_NAME = 1
        const val INDEX_MIME_TYPE = 2
        const val INDEX_SIZE = 3

        val NAME_PROJECTION = DocumentsContract.Document.COLUMN_DISPLAY_NAME
    }
}

/** 扫描器 Hilt 绑定（DataModule 是并行任务改动热区，绑定收口在本文件内）。 */
@Module
@InstallIn(SingletonComponent::class)
interface FolderScannerModule {

    @Binds
    @Singleton
    abstract fun bindFolderScanner(impl: SafFolderScanner): FolderScanner
}
