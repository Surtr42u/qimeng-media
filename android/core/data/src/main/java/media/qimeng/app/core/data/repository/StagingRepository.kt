package media.qimeng.app.core.data.repository

import kotlinx.coroutines.flow.Flow
import media.qimeng.app.core.model.StagingBatchConfig

/** 收件箱目录浏览条目（目录浏览器的目录数据面；只列目录，含点前缀隐藏目录） */
data class InboxDirEntry(
    /** 目录名（不含路径） */
    val name: String,
    /** 目录绝对路径（导航/选定的承载值） */
    val path: String,
)

/**
 * 上传批次默认与归档路径持久仓（2026-09-29 直传化收窄：暂存条目面随暂存区退役整体删除，
 * 职责收窄为「批次默认配置 + 路径类持久化 + 目录浏览」端口）。
 * - 批次默认（库/作者/来源）：上传页直传管道入队时继承的快照源，DataStore + moshi JSON
 *   持久化（[DataStoreStagingRepository]，杀进程/隔天不丢）；
 * - archivePath：上传归档文件夹路径，消费方为 worker 的 201 后归档分派
 *   （UploadWorker.archiveNote，红线不动）；
 * - inboxPath：仅保留读取面——worker 的收件箱来源回退归档（uploaded/）判定用它；
 * - File 操作收口 [media.qimeng.app.core.data.repository.InboxFileStore]（JVM 可测）。
 * 入队后的队列持久化归 WorkManager，本仓不再承载任何「待传条目」。
 */
interface StagingRepository {

    /** 批次默认配置流（库/作者/来源；持久化） */
    val batchConfig: Flow<StagingBatchConfig>

    /** 下载收件箱路径（null = 未设置；读取面仅剩 worker 的收件箱来源归档判定） */
    val inboxPath: Flow<String?>

    /**
     * 上传归档文件夹路径（null = 未设置；2026-09-28 上传归档文件夹功能）。
     * 设置后，上传成功的路径类源文件移入 <该文件夹>/<库名>/<原文件名>（用户手动复制同步
     * 到电脑的自留归档区）；未设置则维持源文件同目录 uploaded/ 的既有归档（向后兼容）。
     */
    val archivePath: Flow<String?>

    /** 原子读-改-写（批次默认配置）：edit 内基于最新配置变换，并发写不互相覆盖 */
    suspend fun editBatchConfig(transform: (StagingBatchConfig) -> StagingBatchConfig)

    /** 设置/清除上传归档文件夹路径（null = 清除，回退 uploaded/ 归档） */
    suspend fun setArchivePath(path: String?)

    /**
     * 「所有文件访问」授权判定（归档文件夹目录浏览的前置）。
     * 口径与 ServerSettingsScreen 既有判定一致：API < 30 无 scoped storage 强制视为已授权，
     * 30+ 看 Environment.isExternalStorageManager()（MANAGE_EXTERNAL_STORAGE）。
     */
    suspend fun hasAllFilesAccess(): Boolean

    /** 主存储根目录绝对路径（/storage/emulated/0；目录浏览器起点） */
    suspend fun storageRoot(): String

    /** 目录浏览：path 下的一级子目录（含点前缀隐藏目录，名称升序；path 非法/不可读返回空） */
    suspend fun listDirectories(path: String): List<InboxDirEntry>

    companion object {
        /** 源文件「已上传」归档子目录名（worker 收件箱来源回退归档目标，单源） */
        const val UPLOADED_DIR_NAME = "uploaded"
    }
}
