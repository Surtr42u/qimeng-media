package media.qimeng.app.core.data.repository

import kotlinx.coroutines.flow.Flow
import media.qimeng.app.core.model.StagedUpload
import media.qimeng.app.core.model.StagingBatchConfig

/** 收件箱目录浏览条目（收件箱选择器的目录浏览器数据面；只列目录，含点前缀隐藏目录） */
data class InboxDirEntry(
    /** 目录名（不含路径） */
    val name: String,
    /** 目录绝对路径（导航/选定的承载值） */
    val path: String,
)

/**
 * 上传暂存仓（2026-09-25 暂存区重做）：暂存条目 + 批次配置 + 收件箱路径的持久化存取，
 * 与收件箱 File 侧操作（扫描/失效探测/归档）的单一数据端口。
 * 持久化实现 = DataStore + moshi JSON（[DataStoreStagingRepository]，杀进程/隔天不丢）；
 * File 操作收口 [media.qimeng.app.core.data.repository.InboxFileStore]（JVM 可测）。
 * 入队后的队列持久化归 WorkManager，本仓只管「未入队」条目。
 */
interface StagingRepository {

    /** 暂存条目流（持久层真源；UI collect 渲染暂存区） */
    val stagedItems: Flow<List<StagedUpload>>

    /** 批次默认配置流（库/作者/来源；持久化） */
    val batchConfig: Flow<StagingBatchConfig>

    /** 下载收件箱路径（null = 未设置；设置页目录浏览器选定后持久化） */
    val inboxPath: Flow<String?>

    /** 新进项合并入库（按 source 去重，已存在的忽略；新项按传入序追加在尾部） */
    suspend fun addItems(items: List<StagedUpload>)

    /** 批量就地更新（按 source 定点替换；单次持久化写入） */
    suspend fun updateItems(items: List<StagedUpload>)

    /**
     * 原子读-改-写（暂存条目，2026-09-25 P2 竞态修复）：单次持久化 edit 内读最新条目 ->
     * transform -> 写回，并发写经持久层天然串行不互相覆盖。VM 侧逐项编辑/应用到全部等
     * 一律走此通道，禁止外部 first()+updateItems 两步拼装（两步间旧快照可覆盖新写入）。
     */
    suspend fun editItems(transform: (List<StagedUpload>) -> List<StagedUpload>)

    /** 原子读-改-写（批次默认配置）：同 [editItems] 口径，变换基于 edit 内最新配置 */
    suspend fun editBatchConfig(transform: (StagingBatchConfig) -> StagingBatchConfig)

    /** 移除条目（含失效清除与入队成功后的出清） */
    suspend fun removeItems(sources: Collection<String>)

    /** 覆写批次默认配置 */
    suspend fun setBatchConfig(config: StagingBatchConfig)

    /** 设置/清除收件箱路径（null = 清除） */
    suspend fun setInboxPath(path: String?)

    /**
     * 「所有文件访问」授权判定（收件箱目录浏览/扫描的前置）。
     * 口径与 ServerSettingsScreen 既有判定一致：API < 30 无 scoped storage 强制视为已授权，
     * 30+ 看 Environment.isExternalStorageManager()（MANAGE_EXTERNAL_STORAGE）。
     */
    suspend fun hasAllFilesAccess(): Boolean

    /** 主存储根目录绝对路径（/storage/emulated/0；收件箱浏览器起点） */
    suspend fun storageRoot(): String

    /** 目录浏览：path 下的一级子目录（含点前缀隐藏目录，名称升序；path 非法/不可读返回空） */
    suspend fun listDirectories(path: String): List<InboxDirEntry>

    /**
     * 收件箱扫描：收件箱根下一级媒体文件 -> 暂存候选条目。
     * 过滤口径：扩展名白名单（UploadRules.isAllowedMediaExtension，与服务端 filing 同源）、
     * 排除 uploaded/ 归档子目录（只列一级文件不递归，双保险）、按文件修改时间倒序。
     */
    suspend fun scanInbox(): List<StagedUpload>

    /**
     * 源是否仍存在：路径类 File.exists()；content uri 类恒 true（媒体读权限存活窗口内
     * 可读性由上传时 openInputStream 的重试兜底，不做 Uri 存在性预判）。
     */
    suspend fun sourceExists(item: StagedUpload): Boolean

    /**
     * 收件箱文件归档：把源文件移入其所在目录的 uploaded/ 子目录（File.renameTo，
     * 目录不存在先建）。移动失败返回 false（调用方只记提示不阻断——源文件留在收件箱，
     * 用户下次扫描仍会看到它）。
     */
    suspend fun archiveToUploaded(sourcePath: String): Boolean

    companion object {
        /** 收件箱内「已上传」归档子目录名（扫描排除 + 上传成功归档目标，单源） */
        const val UPLOADED_DIR_NAME = "uploaded"
    }
}
