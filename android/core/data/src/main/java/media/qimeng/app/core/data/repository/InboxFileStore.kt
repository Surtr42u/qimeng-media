package media.qimeng.app.core.data.repository

import android.os.Build
import android.os.Environment
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import media.qimeng.app.core.model.StagedUpload
import media.qimeng.app.core.model.UploadRules

/**
 * 收件箱 File 侧操作收口（2026-09-25 暂存区重做）：目录浏览/收件箱扫描/存在性探测/
 * uploaded/ 归档，全部 java.io.File 纯 JDK API（App 已持 MANAGE_EXTERNAL_STORAGE，
 * ADR-0015；不用 MediaStore/SAF——收件箱是点前缀隐藏目录，系统相册扫不到、
 * SAF 也不便选）。方法均无状态，JVM 单测用临时目录直测（InboxFileStoreTest）。
 */
@Singleton
class InboxFileStore @Inject constructor() {

    /** 「所有文件访问」授权判定（口径与 ServerSettingsScreen 既有判定一致，注释见接口） */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    /** 主存储根目录（收件箱浏览器起点） */
    fun storageRoot(): String = Environment.getExternalStorageDirectory().absolutePath

    /** 目录浏览：一级子目录（含隐藏目录，名称升序；不可读/非目录返回空表） */
    fun listDirectories(path: String): List<InboxDirEntry> {
        val dir = File(path)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles(File::isDirectory).orEmpty()
            .map { InboxDirEntry(name = it.name, path = it.absolutePath) }
            .sortedBy { it.name.lowercase() }
    }

    /**
     * 收件箱扫描（口径见 [StagingRepository.scanInbox]）：只列一级普通文件（不递归，
     * uploaded/ 子目录与深层文件天然排除）、扩展名白名单过滤、修改时间倒序。
     */
    fun scanInbox(rootPath: String): List<StagedUpload> {
        val root = File(rootPath)
        if (!root.isDirectory) return emptyList()
        return root.listFiles(File::isFile).orEmpty()
            .filter { UploadRules.isAllowedMediaExtension(it.name) }
            .sortedByDescending { it.lastModified() }
            .map { file ->
                StagedUpload(
                    source = file.absolutePath,
                    isPathSource = true,
                    displayName = file.name,
                    sizeBytes = file.length(),
                    isVideo = UploadRules.isVideoExtension(file.name),
                    addedAtMs = file.lastModified(),
                )
            }
    }

    /** 源存在性：路径类 File.exists()；content uri 类恒 true（口径见接口注释） */
    fun sourceExists(item: StagedUpload): Boolean =
        if (item.isPathSource) File(item.source).exists() else true

    /** 归档：源文件 renameTo 到同目录 uploaded/ 子目录（失败不抛，返回 false） */
    fun archiveToUploaded(sourcePath: String): Boolean {
        val file = File(sourcePath)
        if (!file.isFile) return false
        val parent = file.parentFile ?: return false
        val uploadedDir = File(parent, StagingRepository.UPLOADED_DIR_NAME)
        if (!uploadedDir.isDirectory && !uploadedDir.mkdirs()) return false
        return try {
            file.renameTo(File(uploadedDir, file.name))
        } catch (e: Exception) {
            false
        }
    }
}
