package media.qimeng.app.feature.upload

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.qimeng.app.core.data.repository.UploadRepository
import media.qimeng.app.core.data.upload.FolderScanPolicy
import media.qimeng.app.core.data.upload.FolderScanResult
import media.qimeng.app.core.data.upload.FolderScanner
import media.qimeng.app.core.model.DirNode
import media.qimeng.app.core.model.LibraryChoice
import media.qimeng.app.core.model.UploadItem
import media.qimeng.app.core.model.UploadLimits
import media.qimeng.app.core.model.UploadQueueEntry
import media.qimeng.app.core.model.UploadRules
import media.qimeng.app.core.model.UploadStatus

/** 上传流 UI 状态（表单 + 拦截/错误文案 + 队列实时状态） */
data class UploadUiState(
    /** 首屏库列表加载中 */
    val loading: Boolean = false,
    /** 可选目标库（GET /libraries） */
    val libraries: List<LibraryChoice> = emptyList(),
    val selectedLibrary: LibraryChoice? = null,
    /** 目标库目录树（GET /dirs；null = 未加载） */
    val dirTree: DirNode? = null,
    /** 已选目标目录（库内相对路径，空串 = 库根） */
    val selectedDirPath: String = "",
    /** 待上传文件（SAF 多选 / 系统分享接收） */
    val pendingItems: List<UploadItem> = emptyList(),
    /** 服务端配置 upload 组（超限本地拦截口径） */
    val limits: UploadLimits? = null,
    /** 超限本地拦截文案（入队时生成；不阻断其余未超限项） */
    val blockMessage: String? = null,
    /** 非拦截类错误（加载失败/新建目录失败等） */
    val errorMessage: String? = null,
    /** 新建目录请求进行中 */
    val creatingDir: Boolean = false,
    /** 入队请求进行中 */
    val enqueueing: Boolean = false,
    /** 文件夹扫描进行中（U10-6c） */
    val scanningFolder: Boolean = false,
    /** 队列实时状态（WorkManager WorkInfo 映射） */
    val queue: List<UploadQueueEntry> = emptyList(),
) {
    /** 队列里仍有活跃任务（排队/上传中） */
    val hasActiveWork: Boolean
        get() = queue.any { it.status == UploadStatus.QUEUED || it.status == UploadStatus.UPLOADING }

    /** 队列聚合行「共 N 个 · 成功 X · 失败 Y」（空队列 null；从 queue 派生，UI 只渲染） */
    val queueSummary: String?
        get() = queue.takeIf { it.isNotEmpty() }?.let { entries ->
            "共 ${entries.size} 个 · 成功 ${entries.count { it.status == UploadStatus.SUCCEEDED }}" +
                " · 失败 ${entries.count { it.status == UploadStatus.FAILED }}"
        }
}

/**
 * 上传流 ViewModel（M4-5）：表单编排 + 超限本地拦截；字节流与队列全在 core 层。
 * 拦截口径（冻结）：大小上限读 GET /config 的 upload 项（入队时现取现判——服务端实时生效），
 * 超限项本地拦截不出网；类型白名单不复制，服务端 4xx 文案透传展示。
 */
@HiltViewModel
class UploadViewModel @Inject constructor(
    private val uploadRepository: UploadRepository,
    private val folderScanner: FolderScanner,
) : ViewModel() {

    private val form = MutableStateFlow(UploadUiState())

    /**
     * 队列状态（WorkManager WorkInfo 流）与表单状态合并。
     * Eagerly 而非 WhileSubscribed：上传页是低频页面、队列流只是单条 WorkManager 观察，
     * 常驻订阅成本可忽略；换来 JVM 单测无需手动订阅即可断言状态（与壳层 MainViewModel 同口径）。
     */
    val uiState: StateFlow<UploadUiState> = combine(
        form,
        uploadRepository.queueUpdates(),
    ) { current, queue ->
        current.copy(queue = queue)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UploadUiState())

    init {
        refreshLibraries()
    }

    private fun refreshLibraries() {
        viewModelScope.launch {
            form.update { it.copy(loading = true, errorMessage = null) }
            try {
                val libs = uploadRepository.libraries()
                form.update {
                    it.copy(
                        loading = false,
                        libraries = libs,
                        selectedLibrary = it.selectedLibrary ?: libs.firstOrNull(),
                    )
                }
                form.value.selectedLibrary?.let { loadDirTree(it.id) }
                refreshLimits()
            } catch (e: Exception) {
                form.update { it.copy(loading = false, errorMessage = LOAD_FAILED_MESSAGE) }
            }
        }
    }

    private suspend fun loadDirTree(libraryId: String) {
        form.update { it.copy(dirTree = uploadRepository.dirTree(libraryId), selectedDirPath = "") }
    }

    private suspend fun refreshLimits() {
        try {
            form.update { it.copy(limits = uploadRepository.uploadLimits()) }
        } catch (e: Exception) {
            // 配置读不到不拦入队：超限判定退化为服务端 413 兜底
        }
    }

    /** 切换目标库：目录树随库重载、目标目录回到库根 */
    fun selectLibrary(library: LibraryChoice) {
        viewModelScope.launch {
            form.update { it.copy(selectedLibrary = library, errorMessage = null) }
            try {
                loadDirTree(library.id)
            } catch (e: Exception) {
                form.update { it.copy(errorMessage = DIR_LOAD_FAILED_MESSAGE) }
            }
        }
    }

    /** 点选目标目录（库根传空串） */
    fun selectDir(path: String) {
        form.update { it.copy(selectedDirPath = path) }
    }

    /** 幂等新建子目录（POST /dirs）；成功后重载目录树并选中新目录 */
    fun createSubDir(name: String) {
        val path = UploadRules.joinDirPath(form.value.selectedDirPath, name)
        if (path == null) {
            form.update { it.copy(errorMessage = INVALID_DIR_NAME_MESSAGE) }
            return
        }
        val library = form.value.selectedLibrary ?: return
        viewModelScope.launch {
            form.update { it.copy(creatingDir = true, errorMessage = null) }
            try {
                uploadRepository.createDir(library.id, path)
                loadDirTree(library.id)
                form.update { it.copy(creatingDir = false, selectedDirPath = path) }
            } catch (e: Exception) {
                form.update { it.copy(creatingDir = false, errorMessage = DIR_CREATE_FAILED_MESSAGE) }
            }
        }
    }

    /** 接收 SAF 多选 / 系统分享的 uri 字符串：解元数据进待上传列表（去重） */
    fun acceptUris(uris: List<String>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val items = try {
                uploadRepository.describe(uris)
            } catch (e: Exception) {
                form.update { it.copy(errorMessage = DESCRIBE_FAILED_MESSAGE) }
                return@launch
            }
            form.update { current ->
                val existing = current.pendingItems.map { it.uri }.toSet()
                current.copy(
                    pendingItems = current.pendingItems + items.filterNot { it.uri in existing },
                    blockMessage = null,
                )
            }
        }
    }

    /**
     * 选文件夹入口（U10-6c）：treeUri 交 [FolderScanner] 递归枚举（SAF 细节收口在
     * core:data），扫描结果走 [acceptFolder] 合并。VM 不 import android.*（JVM 单测注入 fake）。
     */
    fun acceptFolderTree(treeUri: String) {
        if (form.value.scanningFolder) return
        viewModelScope.launch {
            form.update { it.copy(scanningFolder = true, errorMessage = null) }
            val scan = try {
                folderScanner.scan(treeUri)
            } catch (e: Exception) {
                form.update { it.copy(scanningFolder = false, errorMessage = FOLDER_SCAN_FAILED_MESSAGE) }
                return@launch
            }
            form.update { it.copy(scanningFolder = false) }
            acceptFolder(scan)
        }
    }

    /**
     * 合并文件夹扫描结果进待上传列表（去重口径同 [acceptUris]：按 uri，先到先得）。
     * 跳过/截断提示走 blockMessage 横幅（MessageCard 既有模式）；全空给空文件夹提示。
     */
    fun acceptFolder(scanResult: FolderScanResult) {
        if (scanResult.files.isEmpty()) {
            form.update { it.copy(blockMessage = folderScanNotice(scanResult) ?: FOLDER_EMPTY_MESSAGE) }
            return
        }
        form.update { current ->
            val existing = current.pendingItems.map { it.uri }.toSet()
            current.copy(
                pendingItems = current.pendingItems + scanResult.files.filterNot { it.uri in existing },
                blockMessage = folderScanNotice(scanResult),
            )
        }
    }

    /** 扫描提示文案：截断（口径③）+ 跳过计数（口径②）；无提示返回 null。 */
    private fun folderScanNotice(scan: FolderScanResult): String? {
        val parts = mutableListOf<String>()
        if (scan.truncated) {
            parts += "文件夹过大：已选前 ${FolderScanPolicy.MAX_FOLDER_FILES} 个，共 ${scan.totalUploadable} 个"
        }
        if (scan.skippedCount > 0) parts += "已跳过 ${scan.skippedCount} 个非媒体文件"
        return parts.joinToString("；").ifEmpty { null }
    }

    fun removeItem(item: UploadItem) {
        form.update { it.copy(pendingItems = it.pendingItems.filterNot { candidate -> candidate.uri == item.uri }) }
    }

    /** 清空拦截文案（用户关闭横幅） */
    fun dismissBlock() {
        form.update { it.copy(blockMessage = null) }
    }

    fun dismissError() {
        form.update { it.copy(errorMessage = null) }
    }

    /**
     * 开始上传：现取服务端配置做超限本地拦截（实时生效口径），未超限项进串行队列。
     * 全部被拦时不入队；部分被拦时拦截文案列出被移除项、其余照常上传。
     */
    fun enqueue() {
        val current = form.value
        val library = current.selectedLibrary ?: return
        val items = current.pendingItems
        if (items.isEmpty()) return
        if (current.enqueueing) return

        viewModelScope.launch {
            form.update { it.copy(enqueueing = true, blockMessage = null, errorMessage = null) }
            refreshLimits()
            val limits = form.value.limits
            val blocked = limits?.let { rule -> items.filter { rule.overLimit(it.sizeBytes) } }.orEmpty()
            val allowed = items.filterNot { candidate -> blocked.any { it.uri == candidate.uri } }

            if (allowed.isEmpty()) {
                form.update {
                    it.copy(
                        enqueueing = false,
                        blockMessage = blockText(limits, blocked),
                    )
                }
                return@launch
            }
            try {
                uploadRepository.enqueue(allowed, library.id, current.selectedDirPath)
                form.update {
                    it.copy(
                        enqueueing = false,
                        pendingItems = emptyList(),
                        blockMessage = blockText(limits, blocked),
                    )
                }
            } catch (e: Exception) {
                form.update { it.copy(enqueueing = false, errorMessage = ENQUEUE_FAILED_MESSAGE) }
            }
        }
    }

    /** 拦截文案（中文；列明上限与被拦文件） */
    private fun blockText(limits: UploadLimits?, blocked: List<UploadItem>): String? {
        if (blocked.isEmpty()) return null
        val limitMb = limits?.maxBytesMb ?: UNKNOWN_LIMIT_MB
        val names = blocked.joinToString("、") { it.displayName }
        return "以下文件超过服务端上限 $limitMb MB，已停止上传：$names"
    }

    private companion object {
        /** 配置不可达时的文案兜底值（正常不出现；仅展示层） */
        const val UNKNOWN_LIMIT_MB = 2048L

        const val LOAD_FAILED_MESSAGE = "加载库列表失败：请检查登录与服务端连接"
        const val DIR_LOAD_FAILED_MESSAGE = "目录树加载失败，请重试"
        const val DIR_CREATE_FAILED_MESSAGE = "新建目录失败，请重试"
        const val INVALID_DIR_NAME_MESSAGE = "目录名不合法：不能为空、点段或包含路径分隔符"
        const val DESCRIBE_FAILED_MESSAGE = "读取所选文件信息失败，请重试"
        const val ENQUEUE_FAILED_MESSAGE = "上传任务创建失败，请重试"
        const val FOLDER_SCAN_FAILED_MESSAGE = "扫描文件夹失败，请重试"
        const val FOLDER_EMPTY_MESSAGE = "所选文件夹中没有可上传的媒体文件"
    }
}
