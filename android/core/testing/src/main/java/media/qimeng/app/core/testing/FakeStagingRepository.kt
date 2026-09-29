package media.qimeng.app.core.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import media.qimeng.app.core.data.repository.InboxDirEntry
import media.qimeng.app.core.data.repository.StagingRepository
import media.qimeng.app.core.model.StagingBatchConfig

/**
 * [StagingRepository] 测试替身：内存 StateFlow 模拟持久层（批次配置/收件箱路径/归档路径），
 * File 侧行为（授权/目录浏览）可编程记录（UploadViewModel 与 InboxSettingsViewModel 单测用；
 * File 真实现走 InboxFileStoreTest 的临时目录直测）。
 * 2026-09-29 直传化收窄：随接口收窄删除暂存条目面（seedItems/写记录/扫描探测桩）。
 */
class FakeStagingRepository : StagingRepository {

    /** 批次默认配置（持久层真源） */
    private val _batchConfig = MutableStateFlow(StagingBatchConfig())
    override val batchConfig: Flow<StagingBatchConfig> = _batchConfig.asStateFlow()

    /** 收件箱路径（持久层真源；读取面仅剩 worker 的收件箱来源归档判定） */
    private val _inboxPath = MutableStateFlow<String?>(null)
    override val inboxPath: Flow<String?> = _inboxPath.asStateFlow()

    /** 上传归档文件夹路径（持久层真源；2026-09-28 上传归档文件夹功能） */
    private val _archivePath = MutableStateFlow<String?>(null)
    override val archivePath: Flow<String?> = _archivePath.asStateFlow()

    // ---- 写调用记录（断言 VM -> 仓的写路径）----

    /** 批次配置写调用记录（记写后的完整配置） */
    val batchConfigCalls = mutableListOf<StagingBatchConfig>()

    /** 归档文件夹写调用记录（选定/清除断言用） */
    val archivePathCalls = mutableListOf<String?>()

    // ---- File 侧行为（可编程）----

    /** 「所有文件访问」授权判定返回值 */
    var allFilesAccess = true

    /** 目录浏览返回值（按浏览路径可编程） */
    var directories: (String) -> List<InboxDirEntry> = { emptyList() }

    /** 测试播种：直改批次配置内存层（绕过写记录，模拟既有持久化状态） */
    fun seedBatch(config: StagingBatchConfig) {
        _batchConfig.value = config
    }

    /** 测试播种：直改收件箱路径内存层（worker 收件箱来源判定语义的既有状态模拟） */
    fun seedInboxPath(path: String?) {
        _inboxPath.value = path
    }

    /** 测试播种：直改归档文件夹内存层（绕过写记录，模拟既有持久化状态） */
    fun seedArchivePath(path: String?) {
        _archivePath.value = path
    }

    override suspend fun editBatchConfig(transform: (StagingBatchConfig) -> StagingBatchConfig) {
        val next = transform(_batchConfig.value)
        batchConfigCalls.add(next)
        _batchConfig.value = next
    }

    override suspend fun setArchivePath(path: String?) {
        archivePathCalls.add(path)
        _archivePath.value = path?.takeIf { it.isNotBlank() }
    }

    override suspend fun hasAllFilesAccess(): Boolean = allFilesAccess

    override suspend fun storageRoot(): String = STORAGE_ROOT_FAKE

    override suspend fun listDirectories(path: String): List<InboxDirEntry> = directories(path)

    companion object {
        /** 假根目录（不触真 File 系统；真路径行为归 InboxFileStoreTest） */
        const val STORAGE_ROOT_FAKE = "/storage/emulated/0"
    }
}
