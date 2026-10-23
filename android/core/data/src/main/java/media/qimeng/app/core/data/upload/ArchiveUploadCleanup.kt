package media.qimeng.app.core.data.upload

import java.io.File
import javax.inject.Inject
import media.qimeng.app.core.data.repository.InboxFileStore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 仅清理已确认传到外部服务器的归档副本；失败不触发文件重传。 */
class ArchiveUploadCleanup @Inject constructor(private val fileStore: InboxFileStore) {
    fun finish(spec: UploadWorkSpec.UploadRequestSpec, archiveRoot: String?, confirmed: Boolean): String {
        if (!spec.alreadyArchived) return ""
        val host = spec.targetServerUrl?.toHttpUrlOrNull()?.host ?: return FAILED_NOTE
        // 手机本机上传仍保留归档，供之后切到 PC/NAS 上传。回环的其他端口也不能当外部副本。
        if (host == "localhost" || host.endsWith(".localhost") || host == "::1" || host.startsWith("127.")) {
            return ""
        }
        if (!confirmed || archiveRoot == null) return FAILED_NOTE
        return if (fileStore.deleteUploadedArchive(archiveRoot, File(spec.uri), spec.sizeBytes, spec.sourceMtime)) {
            ""
        } else {
            FAILED_NOTE
        }
    }

    companion object {
        const val FAILED_NOTE = "（已上传，手机归档未清理：文件变化或无法安全删除，已保留）"
    }
}
