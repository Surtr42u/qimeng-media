package media.qimeng.app.feature.upload

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.qimeng.app.core.data.repository.AuthorRepository
import media.qimeng.app.core.data.repository.UploadRepository
import media.qimeng.app.core.model.AuthorSuggestion
import media.qimeng.app.core.model.DirNode
import media.qimeng.app.core.model.LibraryChoice
import media.qimeng.app.core.model.LocalMediaItem
import media.qimeng.app.core.model.UploadItem
import media.qimeng.app.core.model.UploadLimits
import media.qimeng.app.core.model.UploadQueueEntry
import media.qimeng.app.core.model.UploadRules
import media.qimeng.app.core.model.UploadStatus

/** 上传流 UI 状态（表单 + 拦截/错误文案 + 队列实时状态 + 挂靠批次默认/逐项编辑） */
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
    /** 待上传文件（SAF 多选 / 系统分享接收 / 内置相册选择器） */
    val pendingItems: List<UploadItem> = emptyList(),
    /** 批次默认作者（新进项继承；「应用到全部」据此回填全部待传项） */
    val batchAuthor: AuthorSuggestion? = null,
    /** 批次默认来源（与批次作者同进退：清作者连带清空） */
    val batchSources: List<String> = emptyList(),
    /** 批次作者联想输入草稿 */
    val batchAuthorQuery: String = "",
    /** 批次作者联想结果（防抖回填） */
    val batchAuthorSuggestions: List<AuthorSuggestion> = emptyList(),
    /** 全站来源词表（GET /authors/source-vocabulary；失败静默 = 纯自由输入） */
    val sourceOptions: List<String> = emptyList(),
    /** 当前展开编辑的待传项 uri（null = 无展开项；同时至多一项展开） */
    val editingUri: String? = null,
    /** 展开项的作者联想输入草稿 */
    val itemAuthorQuery: String = "",
    /** 展开项的作者联想结果（防抖回填） */
    val itemAuthorSuggestions: List<AuthorSuggestion> = emptyList(),
    /** 服务端配置 upload 组（超限本地拦截口径） */
    val limits: UploadLimits? = null,
    /** 超限本地拦截文案（入队时生成；不阻断其余未超限项） */
    val blockMessage: String? = null,
    /** 非拦截类错误（加载失败/新建目录失败等） */
    val errorMessage: String? = null,
    /** 提示文案（联想未命中等非失败反馈） */
    val noticeMessage: String? = null,
    /** 新建目录请求进行中 */
    val creatingDir: Boolean = false,
    /** 入队请求进行中 */
    val enqueueing: Boolean = false,
    /** 队列实时状态（WorkManager WorkInfo 映射） */
    val queue: List<UploadQueueEntry> = emptyList(),
) {
    /** 队列里仍有活跃任务（排队/上传中） */
    val hasActiveWork: Boolean
        get() = queue.any { it.status == UploadStatus.QUEUED || it.status == UploadStatus.UPLOADING }

    /** 队列聚合行「共 N 个 · 成功 X · 失败 Y · 挂靠失败 Z」（空队列 null；从 queue 派生）。
     *  取消（CANCELLED）不计失败数——用户取消不是失败（批C 任务Q C-2）；
     *  挂靠失败（ATTACH_FAILED，挂靠批）文件已入库，成功/失败都不计、单独分列。 */
    val queueSummary: String?
        get() = queue.takeIf { it.isNotEmpty() }?.let { entries ->
            val base = "共 ${entries.size} 个 · 成功 ${entries.count { it.status == UploadStatus.SUCCEEDED }}" +
                " · 失败 ${entries.count { it.status == UploadStatus.FAILED }}"
            val attachFailed = entries.count { it.status == UploadStatus.ATTACH_FAILED }
            if (attachFailed > 0) "$base · 挂靠失败 $attachFailed" else base
        }
}

/**
 * 上传流 ViewModel（M4-5）：表单编排 + 超限本地拦截；字节流与队列全在 core 层。
 * 拦截口径（冻结）：大小上限读 GET /config 的 upload 项（入队时现取现判——服务端实时生效），
 * 超限项本地拦截不出网；类型白名单不复制，服务端 4xx 文案透传展示。
 * 挂靠批：暂存列表逐项快捷编辑（作品名/作者/来源）+ 批次默认（作者联想 + 来源多选 +
 * 应用到全部；新进项自动继承），挂靠执行在 worker 的 201 之后（mode=append、失败不重试）。
 * 作者仅能选既有（服务端无按名新建端点，联想无命中提示不发请求）；
 * 来源挂靠以作者为前提（服务端来源区挂在作者块下），清作者连带清来源。
 */
@HiltViewModel
class UploadViewModel @Inject constructor(
    private val uploadRepository: UploadRepository,
    private val authorRepository: AuthorRepository,
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

    /** 批次作者联想防抖任务（与编辑页 AssetEditViewModel 同口径） */
    private var batchSuggestJob: Job? = null

    /** 展开项作者联想防抖任务 */
    private var itemSuggestJob: Job? = null

    init {
        refreshLibraries()
        refreshSourceOptions()
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
                form.value.selectedLibrary?.let {
                    loadDirTree(it.id)
                }
                refreshLimits()
            } catch (e: Exception) {
                form.update { it.copy(loading = false, errorMessage = LOAD_FAILED_MESSAGE) }
            }
        }
    }

    /** 全站来源词表（快捷选项；失败静默 = 纯自由输入，同编辑页词表降级口径） */
    private fun refreshSourceOptions() {
        viewModelScope.launch {
            val options = runCatching { authorRepository.sourceVocabulary() }.getOrDefault(emptyList())
            form.update { it.copy(sourceOptions = options) }
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
            form.update {
                it.copy(
                    selectedLibrary = library,
                    errorMessage = null,
                )
            }
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

    /** 接收 SAF 多选 / 系统分享的 uri 字符串：解元数据进待上传列表（去重，继承批次默认） */
    fun acceptUris(uris: List<String>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val items = try {
                uploadRepository.describe(uris)
            } catch (e: Exception) {
                form.update { it.copy(errorMessage = DESCRIBE_FAILED_MESSAGE) }
                return@launch
            }
            appendPendingItems(items)
        }
    }

    /**
     * 接收内置相册选择器的选中项（2026-09-25 拍板：上传选取弃 SAF 改 App 内置相册式选择器）。
     * 与 SAF 结果同管道进 pendingItems（后续超限拦截/入队共用），但元数据不走 describe
     * 重查——MediaStore 查询已给出展示名与字节数（客户端现成数据直接复用）。
     * 挂靠批：新进项继承当前批次默认（作者 + 来源）。
     */
    fun acceptPickedItems(picked: List<LocalMediaItem>) {
        if (picked.isEmpty()) return
        appendPendingItems(picked.map(::toUploadItem))
    }

    /** 待传项合并入口（uri 去重 + 新项继承批次默认，两条接收路径单源） */
    private fun appendPendingItems(items: List<UploadItem>) {
        form.update { current ->
            val existing = current.pendingItems.map { it.uri }.toSet()
            current.copy(
                pendingItems = current.pendingItems +
                    items.filterNot { it.uri in existing }.map { it.withBatchDefaults(current) },
                blockMessage = null,
            )
        }
    }

    /** 待传项继承批次默认（新进项/应用到全部共用一条口径） */
    private fun UploadItem.withBatchDefaults(state: UploadUiState) = copy(
        attachAuthorId = state.batchAuthor?.id,
        attachAuthorName = state.batchAuthor?.displayName,
        attachSources = state.batchSources.takeIf { it.isNotEmpty() },
    )

    /** 选择器条目 → 待上传条目（文件级上传：relativeDir 恒空串） */
    private fun toUploadItem(item: LocalMediaItem) = UploadItem(
        uri = item.uri,
        displayName = item.displayName,
        sizeBytes = item.sizeBytes,
    )

    fun removeItem(item: UploadItem) {
        form.update { current ->
            current.copy(
                pendingItems = current.pendingItems.filterNot { candidate -> candidate.uri == item.uri },
                editingUri = current.editingUri?.takeIf { it != item.uri },
            )
        }
    }

    // ---- 批次默认（作者联想 + 来源多选 + 应用到全部） ----

    /** 批次作者输入变化：防抖拉联想（空串清列表；竞态以词条比对回填） */
    fun onBatchAuthorQueryChange(query: String) {
        form.update { it.copy(batchAuthorQuery = query) }
        batchSuggestJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            form.update { it.copy(batchAuthorSuggestions = emptyList()) }
            return
        }
        batchSuggestJob = viewModelScope.launch {
            delay(AUTHOR_SUGGEST_DEBOUNCE_MS)
            runCatching { uploadRepository.suggestAuthors(trimmed) }
                .onSuccess { list ->
                    if (form.value.batchAuthorQuery.trim() == trimmed) {
                        form.update { it.copy(batchAuthorSuggestions = list) }
                    }
                }
        }
    }

    /** 批次作者回车提交：大小写不敏感精确命中 → 选中；未命中 → 提示仅能选既有作者 */
    fun commitBatchAuthor() {
        val trimmed = form.value.batchAuthorQuery.trim()
        if (trimmed.isEmpty()) return
        val exact = form.value.batchAuthorSuggestions.firstOrNull {
            it.displayName.equals(trimmed, ignoreCase = true)
        }
        if (exact != null) {
            pickBatchAuthor(exact)
        } else {
            form.update { it.copy(noticeMessage = ONLY_EXISTING_AUTHORS) }
        }
    }

    /** 选中批次作者（清除输入态；来源区随作者可用） */
    fun pickBatchAuthor(author: AuthorSuggestion) {
        batchSuggestJob?.cancel()
        form.update {
            it.copy(
                batchAuthor = author,
                batchAuthorQuery = "",
                batchAuthorSuggestions = emptyList(),
                noticeMessage = null,
            )
        }
    }

    /** 清空批次作者：来源以其为前提，连带清空批次来源 */
    fun clearBatchAuthor() {
        form.update { it.copy(batchAuthor = null, batchSources = emptyList()) }
    }

    /** 批次来源 toggle（trim 后空串忽略；批次作者未选时不生效——来源挂靠以作者为前提） */
    fun toggleBatchSource(name: String) {
        val normalized = name.trim()
        if (normalized.isEmpty()) return
        form.update { current ->
            if (current.batchAuthor == null) return@update current
            val updated = toggleIn(current.batchSources, normalized)
            current.copy(batchSources = updated)
        }
    }

    /** 自由输入加入批次来源：trim、去重；空串忽略 */
    fun addCustomBatchSource(raw: String) {
        val normalized = raw.trim()
        if (normalized.isEmpty()) return
        form.update { current ->
            if (current.batchAuthor == null || normalized in current.batchSources) return@update current
            current.copy(batchSources = current.batchSources + normalized)
        }
    }

    /**
     * 批次默认一键应用到全部待传项：作者（含展示名）与来源（仅批次已选时）覆盖每项。
     * 批次未选来源时不清既有逐项来源（「应用」只写批次已配置的维度）。
     */
    fun applyBatchToAll() {
        form.update { current ->
            val author = current.batchAuthor ?: return@update current
            current.copy(
                pendingItems = current.pendingItems.map { item ->
                    item.copy(
                        attachAuthorId = author.id,
                        attachAuthorName = author.displayName,
                        attachSources = current.batchSources.takeIf { it.isNotEmpty() } ?: item.attachSources,
                    )
                },
            )
        }
    }

    // ---- 逐项编辑（展开态；同时至多一项展开） ----

    /** 展开某项编辑态（重复点收起） */
    fun toggleItemExpanded(item: UploadItem) {
        form.update { current ->
            val next = if (current.editingUri == item.uri) null else item.uri
            current.copy(editingUri = next, itemAuthorQuery = "", itemAuthorSuggestions = emptyList())
        }
    }

    /** 编辑作品名（落库文件名草稿；空串合法——入队时 effectiveUploadName 回退展示名） */
    fun setItemUploadName(item: UploadItem, name: String) {
        updateItem(item.uri) { it.copy(uploadFileName = name) }
    }

    /** 展开项作者输入变化：防抖拉联想（同批次通道口径） */
    fun onItemAuthorQueryChange(query: String) {
        form.update { it.copy(itemAuthorQuery = query) }
        itemSuggestJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            form.update { it.copy(itemAuthorSuggestions = emptyList()) }
            return
        }
        itemSuggestJob = viewModelScope.launch {
            delay(AUTHOR_SUGGEST_DEBOUNCE_MS)
            runCatching { uploadRepository.suggestAuthors(trimmed) }
                .onSuccess { list ->
                    if (form.value.itemAuthorQuery.trim() == trimmed) {
                        form.update { it.copy(itemAuthorSuggestions = list) }
                    }
                }
        }
    }

    /** 展开项作者回车提交：精确命中 → 覆盖该项作者；未命中 → 提示仅能选既有作者 */
    fun commitItemAuthor(item: UploadItem) {
        val trimmed = form.value.itemAuthorQuery.trim()
        if (trimmed.isEmpty()) return
        val exact = form.value.itemAuthorSuggestions.firstOrNull {
            it.displayName.equals(trimmed, ignoreCase = true)
        }
        if (exact != null) {
            pickItemAuthor(item, exact)
        } else {
            form.update { it.copy(noticeMessage = ONLY_EXISTING_AUTHORS) }
        }
    }

    /** 覆盖该项作者（逐项覆盖后该项独立于批次默认） */
    fun pickItemAuthor(item: UploadItem, author: AuthorSuggestion) {
        itemSuggestJob?.cancel()
        updateItem(item.uri) { target ->
            target.copy(
                attachAuthorId = author.id,
                attachAuthorName = author.displayName,
            )
        }
        form.update {
            it.copy(itemAuthorQuery = "", itemAuthorSuggestions = emptyList(), noticeMessage = null)
        }
    }

    /** 清空该项作者 = 该项不带挂靠（来源以作者为前提，连带清空） */
    fun clearItemAuthor(item: UploadItem) {
        updateItem(item.uri) {
            it.copy(attachAuthorId = null, attachAuthorName = null, attachSources = null)
        }
    }

    /** 该项来源 toggle（作者未挂时不生效——来源挂靠以作者为前提，UI 同步禁用） */
    fun toggleItemSource(item: UploadItem, name: String) {
        val normalized = name.trim()
        if (normalized.isEmpty()) return
        updateItem(item.uri) { target ->
            if (target.attachAuthorId == null) return@updateItem target
            target.copy(attachSources = toggleIn(target.attachSources.orEmpty(), normalized))
        }
    }

    /** 自由输入加入该项来源：trim、去重；空串忽略 */
    fun addCustomItemSource(item: UploadItem, raw: String) {
        val normalized = raw.trim()
        if (normalized.isEmpty()) return
        updateItem(item.uri) { target ->
            val current = target.attachSources.orEmpty()
            if (target.attachAuthorId == null || normalized in current) return@updateItem target
            target.copy(attachSources = current + normalized)
        }
    }

    /** 按 uri 就地更新待传项（挂靠编辑的唯一变更通道） */
    private fun updateItem(uri: String, transform: (UploadItem) -> UploadItem) {
        form.update { current ->
            current.copy(
                pendingItems = current.pendingItems.map { if (it.uri == uri) transform(it) else it },
            )
        }
    }

    /** toggle 通用（批次与逐项共用）：在集则移除、不在则加入 */
    private fun toggleIn(sources: List<String>, name: String): List<String> =
        if (name in sources) sources - name else sources + name

    // ---- 拦截/入队/取消 ----

    /** 清空拦截文案（用户关闭横幅） */
    fun dismissBlock() {
        form.update { it.copy(blockMessage = null) }
    }

    fun dismissError() {
        form.update { it.copy(errorMessage = null) }
    }

    fun dismissNotice() {
        form.update { it.copy(noticeMessage = null) }
    }

    /**
     * 开始上传：现取服务端配置做超限本地拦截（实时生效口径），未超限项进串行队列。
     * 全部被拦时不入队；部分被拦时拦截文案列出被移除项、其余照常上传。
     * 挂靠批：编辑值（effectiveUploadName/attachAuthorId/attachSources）随 UploadItem 入队。
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
                uploadRepository.enqueue(
                    allowed,
                    library.id,
                    current.selectedDirPath,
                )
                form.update {
                    it.copy(
                        enqueueing = false,
                        pendingItems = emptyList(),
                        editingUri = null,
                        itemAuthorQuery = "",
                        itemAuthorSuggestions = emptyList(),
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
        val names = blocked.joinToString("、") { it.effectiveUploadName }
        return "以下文件超过服务端上限 $limitMb MB，已停止上传：$names"
    }

    /**
     * 取消单个队列任务（批C 任务Q C-2；排队中与上传中皆可，对齐 Web 无需二次确认）。
     * 协作式取消全在 core 层（UploadCancelRegistry + worker 检查），VM 只透传；
     * 队列行状态由 queueUpdates 流即时翻「已取消」，UI 无本地回滚状态。
     */
    fun cancel(entry: UploadQueueEntry) {
        uploadRepository.cancel(entry.localId)
    }

    private companion object {
        /** 配置不可达时的文案兜底值（正常不出现；仅展示层） */
        const val UNKNOWN_LIMIT_MB = 2048L

        /** 作者联想防抖（ms）：与编辑页/Web 端同值口径 */
        const val AUTHOR_SUGGEST_DEBOUNCE_MS = 200L

        const val LOAD_FAILED_MESSAGE = "加载库列表失败：请检查登录与服务端连接"
        const val DIR_LOAD_FAILED_MESSAGE = "目录树加载失败，请重试"
        const val DIR_CREATE_FAILED_MESSAGE = "新建目录失败，请重试"
        const val INVALID_DIR_NAME_MESSAGE = "目录名不合法：不能为空、点段或包含路径分隔符"
        const val DESCRIBE_FAILED_MESSAGE = "读取所选文件信息失败，请重试"
        const val ENQUEUE_FAILED_MESSAGE = "上传任务创建失败，请重试"
        const val ONLY_EXISTING_AUTHORS = "未找到该作者：仅能选择已有作者，请从联想中选择"
    }
}
