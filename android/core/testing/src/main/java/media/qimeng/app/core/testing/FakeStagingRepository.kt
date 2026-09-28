package media.qimeng.app.core.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import media.qimeng.app.core.data.repository.InboxDirEntry
import media.qimeng.app.core.data.repository.StagingRepository
import media.qimeng.app.core.model.StagedUpload
import media.qimeng.app.core.model.StagingBatchConfig

/**
 * [StagingRepository] 测试替身：内存 StateFlow 模拟持久层（暂存条目/批次配置/收件箱路径），
 * File 侧行为（授权/目录浏览/扫描/存在性/归档）全部可编程记录（UploadViewModel 与
 * InboxSettingsViewModel 单测用；File 真实现走 InboxFileStoreTest 的临时目录直测）。
 */
class FakeStagingRepository : StagingRepository {

    /** 暂存条目（持久层真源） */
    private val _items = MutableStateFlow<List<StagedUpload>>(emptyList())
    override val stagedItems: Flow<List<StagedUpload>> = _items.asStateFlow()

    /** 批次默认配置（持久层真源） */
    private val _batchConfig = MutableStateFlow(StagingBatchConfig())
    override val batchConfig: Flow<StagingBatchConfig> = _batchConfig.asStateFlow()

    /** 收件箱路径（持久层真源） */
    private val _inboxPath = MutableStateFlow<String?>(null)
    override val inboxPath: Flow<String?> = _inboxPath.asStateFlow()

    /** 上传归档文件夹路径（持久层真源；2026-09-28 上传归档文件夹功能） */
    private val _archivePath = MutableStateFlow<String?>(null)
    override val archivePath: Flow<String?> = _archivePath.asStateFlow()

    // ---- 写调用记录（断言 VM -> 仓的写路径）----

    val addCalls = mutableListOf<List<StagedUpload>>()
    val updateCalls = mutableListOf<List<StagedUpload>>()

    /** editItems 调用记录（记变换后的完整列表；与 updateCalls 的「更新集」形态不同，分列） */
    val editItemCalls = mutableListOf<List<StagedUpload>>()
    val removeCalls = mutableListOf<List<String>>()

    /** 批次配置写调用记录（setBatchConfig 与 editBatchConfig 同录，形态同为写后的完整配置） */
    val batchConfigCalls = mutableListOf<StagingBatchConfig>()
    val inboxPathCalls = mutableListOf<String?>()

    /** 归档文件夹写调用记录（选定/清除断言用） */
    val archivePathCalls = mutableListOf<String?>()

    // ---- File 侧行为（可编程）----

    /** 「所有文件访问」授权判定返回值 */
    var allFilesAccess = true

    /** 目录浏览返回值（按浏览路径可编程） */
    var directories: (String) -> List<InboxDirEntry> = { emptyList() }

    /** 收件箱扫描返回值 */
    var scannedItems: List<StagedUpload> = emptyList()

    /** 收件箱扫描调用计数（重复扫描/未设路径短路断言用） */
    var scanCalls = 0
        private set

    /** 存在性探测：该集合内的路径类条目源判「不存在」（失效条目用例） */
    var missingSources: Set<String> = emptySet()

    /** 归档返回值（按源路径可编程；默认成功） */
    var archiveResult: (String) -> Boolean = { true }

    /** 归档调用记录 */
    val archiveCalls = mutableListOf<String>()

    /** 存在性探测调用记录（失效探测触发时机断言用） */
    val existsCalls = mutableListOf<String>()

    /** 测试播种：直改内存层（绕过写记录，模拟既有持久化状态） */
    fun seedItems(items: List<StagedUpload>) {
        _items.value = items
    }

    fun seedBatch(config: StagingBatchConfig) {
        _batchConfig.value = config
    }

    fun seedInboxPath(path: String?) {
        _inboxPath.value = path
    }

    /** 测试播种：直改归档文件夹内存层（绕过写记录，模拟既有持久化状态） */
    fun seedArchivePath(path: String?) {
        _archivePath.value = path
    }

    override suspend fun addItems(items: List<StagedUpload>) {
        addCalls.add(items)
        val known = _items.value.mapTo(mutableSetOf()) { it.source }
        _items.value = _items.value + items.filterNot { it.source in known }
    }

    override suspend fun updateItems(items: List<StagedUpload>) {
        updateCalls.add(items)
        val updates = items.associateBy { it.source }
        _items.value = _items.value.map { updates[it.source] ?: it }
    }

    override suspend fun editItems(transform: (List<StagedUpload>) -> List<StagedUpload>) {
        val next = transform(_items.value)
        editItemCalls.add(next)
        _items.value = next
    }

    override suspend fun editBatchConfig(transform: (StagingBatchConfig) -> StagingBatchConfig) {
        val next = transform(_batchConfig.value)
        batchConfigCalls.add(next)
        _batchConfig.value = next
    }

    override suspend fun removeItems(sources: Collection<String>) {
        removeCalls.add(sources.toList())
        _items.value = _items.value.filterNot { it.source in sources }
    }

    override suspend fun setBatchConfig(config: StagingBatchConfig) {
        batchConfigCalls.add(config)
        _batchConfig.value = config
    }

    override suspend fun setInboxPath(path: String?) {
        inboxPathCalls.add(path)
        _inboxPath.value = path?.takeIf { it.isNotBlank() }
    }

    override suspend fun setArchivePath(path: String?) {
        archivePathCalls.add(path)
        _archivePath.value = path?.takeIf { it.isNotBlank() }
    }

    override suspend fun hasAllFilesAccess(): Boolean = allFilesAccess

    override suspend fun storageRoot(): String = STORAGE_ROOT_FAKE

    override suspend fun listDirectories(path: String): List<InboxDirEntry> = directories(path)

    override suspend fun scanInbox(): List<StagedUpload> {
        scanCalls++
        return if (_inboxPath.value == null) emptyList() else scannedItems
    }

    override suspend fun sourceExists(item: StagedUpload): Boolean {
        if (item.isPathSource) existsCalls.add(item.source)
        if (!item.isPathSource) return true
        return item.source !in missingSources
    }

    override suspend fun archiveToUploaded(sourcePath: String): Boolean {
        archiveCalls.add(sourcePath)
        return archiveResult(sourcePath)
    }

    companion object {
        /** 假根目录（不触真 File 系统；真路径行为归 InboxFileStoreTest） */
        const val STORAGE_ROOT_FAKE = "/storage/emulated/0"
    }
}
