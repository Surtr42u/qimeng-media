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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.qimeng.app.core.data.repository.AuthorRepository
import media.qimeng.app.core.data.repository.BrowserFileEntry
import media.qimeng.app.core.data.repository.StagingRepository
import media.qimeng.app.core.data.repository.UploadRepository
import media.qimeng.app.core.model.AuthorSuggestion
import media.qimeng.app.core.model.LibraryChoice
import media.qimeng.app.core.model.LocalMediaItem
import media.qimeng.app.core.model.StagedUpload
import media.qimeng.app.core.model.StagingBatchConfig
import media.qimeng.app.core.model.UploadItem
import media.qimeng.app.core.model.UploadLimits
import media.qimeng.app.core.model.UploadQueueEntry
import media.qimeng.app.core.model.UploadRules
import media.qimeng.app.core.model.UploadStatus
import media.qimeng.app.core.model.toRegularAuthorSeeds

/**
 * 上传流 ViewModel（M4-5；2026-09-25 流程重排 + 暂存区重做）。
 * 暂存区重做：暂存条目与批次默认（库/作者/来源）全部落 StagingRepository 持久层
 * （跨进程重启/隔天不丢），页面只 collect 持久流渲染；新进暂存四条管道单源——
 * 收件箱扫描（importFromInbox）/ 相册多选（acceptPickedItems）/ 分享与 SAF（acceptUris）/
 * 浏览文件多选（acceptPickedFiles，2026-09-28 补齐隐藏目录场景）。
 * 作品名联想：展开项基名输入防抖拉 GET /assets/name-suggestions（建议基名不含扩展名，
 * 回填后扩展名锁定拼接，见 StagedUpload.effectiveUploadName）。
 * 拦截口径（冻结）：大小上限读 GET /config 的 upload 项（入队时现取现判——服务端实时生效），
 * 超限项本地拦截不出网且保留在暂存区；类型白名单不复制，服务端 4xx 文案透传展示。
 * 挂靠批：批次默认（作者联想 + 来源多选 + 应用到全部；新进项自动继承）+ 逐项编辑
 * （作品名/作者/来源/库覆盖），挂靠执行在 worker 的 201 之后（mode=append、失败不重试）。
 * 作者仅能选既有（服务端无按名新建端点，联想无命中提示不发请求）；
 * 来源挂靠以作者为前提（服务端来源区挂在作者块下），清作者连带清来源。
 */
@HiltViewModel
class UploadViewModel @Inject constructor(
    private val uploadRepository: UploadRepository,
    private val authorRepository: AuthorRepository,
    private val stagingRepository: StagingRepository,
) : ViewModel() {

    /** 页面会话态（持久字段由 uiState combine 侧以仓库流覆写，见下） */
    private val form = MutableStateFlow(UploadUiState())

    /** 新进暂存摄取器（三条管道单源编排，见 UploadStagingIngestor；VM 组合注入仓库） */
    private val ingestor = UploadStagingIngestor(uploadRepository, stagingRepository)

    /**
     * 会话态 + 持久流合并。Eagerly 而非 WhileSubscribed：上传页是低频页面、队列流只是
     * 单条 WorkManager 观察，常驻订阅成本可忽略；换来 JVM 单测无需手动订阅即可断言状态。
     */
    val uiState: StateFlow<UploadUiState> = combine(
        form,
        stagingRepository.stagedItems,
        stagingRepository.batchConfig,
        stagingRepository.inboxPath,
        uploadRepository.queueUpdates(),
    ) { current, items, batch, inbox, queue ->
        current.copy(
            pendingItems = items,
            batchLibraryId = batch.libraryId,
            batchAuthorId = batch.authorId,
            batchAuthorName = batch.authorName,
            batchSources = batch.sources,
            inboxPath = inbox,
            queue = queue,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UploadUiState())

    /** 批次作者联想防抖任务（与编辑页 AssetEditViewModel 同口径） */
    private var batchSuggestJob: Job? = null

    /** 展开项作者联想防抖任务 */
    private var itemSuggestJob: Job? = null

    /** 展开项作品名联想防抖任务（暂存区重做新通道） */
    private var nameSuggestJob: Job? = null

    init {
        refreshLibraries()
        refreshSourceOptions()
        refreshAuthorSeeds()
        observeMissingSources()
    }

    /**
     * 拉取库列表（进页面即拉，不阻塞选文件/选择器——选择器只查 MediaStore，与库无关）。
     * 流程重排：不自动选库——批次库由用户手选并持久化；库列表就绪后回放持久化的批次库
     * 目录树（库已被删则静默留空，开始上传门禁会拦）。limits 与库列表同批拉取。
     */
    private fun refreshLibraries() {
        viewModelScope.launch {
            form.update { it.copy(loading = true, errorMessage = null) }
            try {
                val libs = uploadRepository.libraries()
                form.update { it.copy(loading = false, libraries = libs) }
                refreshLimits()
                val persistedLibraryId = stagingRepository.batchConfig.first().libraryId
                if (persistedLibraryId != null && libs.any { it.id == persistedLibraryId }) {
                    loadDirTree(persistedLibraryId)
                }
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

    /** 空输入作者种子（全量接口过滤常规作者）：suggest 空 q 必返空，空输入默认全显
     *  只能走全量；失败静默为空列表（联想输入仍可用，不阻断页面） */
    private fun refreshAuthorSeeds() {
        viewModelScope.launch {
            val seeds = runCatching { authorRepository.authors().toRegularAuthorSeeds() }
                .getOrDefault(emptyList())
            form.update { it.copy(authorSeeds = seeds) }
        }
    }

    /**
     * 失效探测（暂存区重做）：暂存条目每次变化时对收件箱路径类条目做 File.exists 校验，
     * 失效者标「文件已不存在」（missingSources，UI 渲染 + 可清除），不阻塞其他项。
     * content:// 类不做预判（读权限存活窗口内的可读性由上传时重试兜底，见 AssetUploader）。
     */
    private fun observeMissingSources() {
        viewModelScope.launch {
            stagingRepository.stagedItems.collect { items ->
                val missing = items
                    .filter { it.isPathSource && !stagingRepository.sourceExists(it) }
                    .map { it.source }
                    .toSet()
                form.update { it.copy(missingSources = missing) }
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

    // ---- 批次默认（库/作者/来源，全部持久化） ----

    /** 选择批次库（暂存区必选项）：写持久配置 + 目录树加载/随库重载、目标目录回库根 */
    fun selectLibrary(library: LibraryChoice) {
        viewModelScope.launch {
            form.update { it.copy(errorMessage = null) }
            updateBatchConfig { it.copy(libraryId = library.id) }
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
        val library = uiState.value.selectedLibrary ?: return
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

    /** 选中批次作者（写持久配置并清除输入态；来源区随作者可用） */
    fun pickBatchAuthor(author: AuthorSuggestion) {
        batchSuggestJob?.cancel()
        form.update { it.copy(batchAuthorQuery = "", batchAuthorSuggestions = emptyList(), noticeMessage = null) }
        viewModelScope.launch {
            updateBatchConfig { it.copy(authorId = author.id, authorName = author.displayName) }
        }
    }

    /** 清空批次作者：来源以其为前提，连带清空批次来源（写持久配置） */
    fun clearBatchAuthor() {
        viewModelScope.launch {
            updateBatchConfig { it.copy(authorId = null, authorName = null, sources = emptyList()) }
        }
    }

    /** 批次来源 toggle（trim 后空串忽略；批次作者未选时不生效——来源挂靠以作者为前提） */
    fun toggleBatchSource(name: String) {
        val normalized = name.trim()
        if (normalized.isEmpty()) return
        viewModelScope.launch {
            updateBatchConfig { batch ->
                if (batch.authorId == null) batch else batch.copy(sources = toggleIn(batch.sources, normalized))
            }
        }
    }

    /** 自由输入加入批次来源：trim、去重；空串忽略 */
    fun addCustomBatchSource(raw: String) {
        val normalized = raw.trim()
        if (normalized.isEmpty()) return
        viewModelScope.launch {
            updateBatchConfig { batch ->
                if (batch.authorId == null || normalized in batch.sources) {
                    batch
                } else {
                    batch.copy(sources = batch.sources + normalized)
                }
            }
        }
    }

    /**
     * 批次默认一键应用到全部暂存项：作者（含展示名）与来源（仅批次已选时）覆盖每项。
     * 批次未选来源时不清既有逐项来源（「应用」只写批次已配置的维度）。
     * 暂存条目走 editItems 原子变换（批次配置只读不写，读一次作变换输入）。
     */
    fun applyBatchToAll() {
        viewModelScope.launch {
            val batch = stagingRepository.batchConfig.first()
            val authorId = batch.authorId ?: return@launch
            stagingRepository.editItems { items ->
                items.map { item ->
                    item.copy(
                        attachAuthorId = authorId,
                        attachAuthorName = batch.authorName,
                        attachSources = batch.sources.takeIf { it.isNotEmpty() } ?: item.attachSources,
                    )
                }
            }
        }
    }

    /** 批次配置变更通道（editBatchConfig 原子读-改-写单源；所有批次默认写路径经此） */
    private suspend fun updateBatchConfig(transform: (StagingBatchConfig) -> StagingBatchConfig) {
        stagingRepository.editBatchConfig(transform)
    }

    // ---- 新进暂存（收件箱扫描 / 相册多选 / 分享与 SAF / 浏览文件多选；编排单源在 [ingestor]） ----

    /**
     * 收件箱导入（暂存区重做）：扫描收件箱 → 新文件按批次默认进暂存区（已暂存去重跳过）。
     * 未设收件箱给设置引导；扫描无新文件给提示；扫描失败给错误横幅。
     */
    fun importFromInbox() {
        viewModelScope.launch {
            form.update { it.copy(scanningInbox = true, blockMessage = null) }
            try {
                when (val result = ingestor.importFromInbox()) {
                    null -> form.update { it.copy(scanningInbox = false, noticeMessage = INBOX_NOT_SET_MESSAGE) }
                    else -> if (result.itemsAdded == 0) {
                        form.update { it.copy(scanningInbox = false, noticeMessage = INBOX_NO_NEW_MESSAGE) }
                    } else {
                        form.update { it.copy(scanningInbox = false) }
                    }
                }
            } catch (e: Exception) {
                form.update { it.copy(scanningInbox = false, errorMessage = INBOX_SCAN_FAILED_MESSAGE) }
            }
        }
    }

    /**
     * 「浏览文件」入口的「所有文件访问」闸门判定：转发 [StagingRepository.hasAllFilesAccess]
     * 单源（口径实现收口 InboxFileStore，与收件箱设置页同一数据面）——此前 UploadScreen
     * 私有第三份同款实现已删，防三处口径漂移。
     */
    suspend fun hasAllFilesAccess(): Boolean = stagingRepository.hasAllFilesAccess()

    /**
     * 接收内置相册选择器的选中项（2026-09-25 拍板：上传选取弃 SAF 改 App 内置相册式选择器）。
     * 元数据不走 describe 重查——MediaStore 查询已给出展示名与字节数（现成数据直接复用），
     * 按批次默认进持久暂存区。
     */
    fun acceptPickedItems(picked: List<LocalMediaItem>) {
        viewModelScope.launch { ingestor.ingestPicked(picked) }
    }

    /**
     * 接收浏览文件弹层的选中项（2026-09-28「浏览文件」入口）：绝对路径类条目，
     * File 元数据列举侧已带齐，按 source 去重（与收件箱导入同款）后按批次默认进暂存区；
     * 全部已暂存给提示。
     */
    fun acceptPickedFiles(files: List<BrowserFileEntry>) {
        if (files.isEmpty()) return
        viewModelScope.launch {
            if (ingestor.ingestPickedFiles(files) == 0) {
                form.update { it.copy(noticeMessage = BROWSE_NO_NEW_MESSAGE) }
            }
        }
    }

    /** 接收 SAF 多选 / 系统分享的 uri 字符串：解元数据进持久暂存区（去重，继承批次默认） */
    fun acceptUris(uris: List<String>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            if (!ingestor.ingestUris(uris)) {
                form.update { it.copy(errorMessage = DESCRIBE_FAILED_MESSAGE) }
            }
        }
    }

    // ---- 逐项编辑（展开态；同时至多一项展开） ----

    /** 移除暂存项（失效清除与主动移除同一通道） */
    fun removeItem(item: StagedUpload) {
        viewModelScope.launch {
            stagingRepository.removeItems(listOf(item.source))
            form.update { current ->
                current.copy(editingSource = current.editingSource?.takeIf { it != item.source })
            }
        }
    }

    /** 展开某项编辑态（重复点收起；切换清空联想草稿） */
    fun toggleItemExpanded(item: StagedUpload) {
        form.update { current ->
            val next = if (current.editingSource == item.source) null else item.source
            current.copy(
                editingSource = next,
                itemAuthorQuery = "",
                itemAuthorSuggestions = emptyList(),
                itemNameSuggestions = emptyList(),
            )
        }
    }

    /**
     * 编辑作品名基名（仅基名可编辑，扩展名锁定——完整落库名由
     * StagedUpload.effectiveUploadName 拼装）+ 防抖拉作品名联想。
     * 联想需要库上下文（逐项覆盖库优先，缺省批次库）；无库上下文或空输入不发请求。
     */
    fun onItemNameChanged(item: StagedUpload, baseName: String) {
        updateStagedItem(item.source) { it.copy(uploadBaseName = baseName) }
        nameSuggestJob?.cancel()
        val trimmed = baseName.trim()
        val libraryId = item.libraryIdOverride ?: uiState.value.batchLibraryId
        if (trimmed.isEmpty() || libraryId == null) {
            form.update { it.copy(itemNameSuggestions = emptyList()) }
            return
        }
        nameSuggestJob = viewModelScope.launch {
            delay(NAME_SUGGEST_DEBOUNCE_MS)
            runCatching { uploadRepository.suggestNames(libraryId, trimmed) }
                .onSuccess { list ->
                    if (form.value.editingSource == item.source) {
                        form.update { it.copy(itemNameSuggestions = list) }
                    }
                }
        }
    }

    /** 点选作品名建议：基名回填（扩展名锁定拼接在 effectiveUploadName 单源保证） */
    fun pickNameSuggestion(item: StagedUpload, baseName: String) {
        nameSuggestJob?.cancel()
        updateStagedItem(item.source) { it.copy(uploadBaseName = baseName) }
        form.update { it.copy(itemNameSuggestions = emptyList()) }
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
    fun commitItemAuthor(item: StagedUpload) {
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
    fun pickItemAuthor(item: StagedUpload, author: AuthorSuggestion) {
        itemSuggestJob?.cancel()
        updateStagedItem(item.source) {
            it.copy(attachAuthorId = author.id, attachAuthorName = author.displayName)
        }
        form.update {
            it.copy(itemAuthorQuery = "", itemAuthorSuggestions = emptyList(), noticeMessage = null)
        }
    }

    /** 清空该项作者 = 该项不带挂靠（来源以作者为前提，连带清空） */
    fun clearItemAuthor(item: StagedUpload) {
        updateStagedItem(item.source) {
            it.copy(attachAuthorId = null, attachAuthorName = null, attachSources = null)
        }
    }

    /** 该项来源 toggle（作者未挂时不生效——来源挂靠以作者为前提，UI 同步禁用） */
    fun toggleItemSource(item: StagedUpload, name: String) {
        val normalized = name.trim()
        if (normalized.isEmpty()) return
        updateStagedItem(item.source) { target ->
            if (target.attachAuthorId == null) target
            else target.copy(attachSources = toggleIn(target.attachSources.orEmpty(), normalized))
        }
    }

    /** 自由输入加入该项来源：trim、去重；空串忽略 */
    fun addCustomItemSource(item: StagedUpload, raw: String) {
        val normalized = raw.trim()
        if (normalized.isEmpty()) return
        updateStagedItem(item.source) { target ->
            val current = target.attachSources.orEmpty()
            if (target.attachAuthorId == null || normalized in current) {
                target
            } else {
                target.copy(attachSources = current + normalized)
            }
        }
    }

    /** 逐项目标库覆盖 toggle（再点同库 = 取消覆盖，回归批次默认） */
    fun toggleItemLibrary(item: StagedUpload, library: LibraryChoice) {
        updateStagedItem(item.source) {
            it.copy(libraryIdOverride = if (it.libraryIdOverride == library.id) null else library.id)
        }
    }

    /** 清除逐项目标库覆盖（「跟随批次默认」胶囊） */
    fun clearItemLibraryOverride(item: StagedUpload) {
        updateStagedItem(item.source) { it.copy(libraryIdOverride = null) }
    }

    /**
     * 按 source 就地更新暂存项（逐项编辑的唯一变更通道）：editItems 原子变换内基于最新
     * 条目定点替换（2026-09-25 P2 竞态修复——原 first()+updateItems 两步在途写可乱序落地，
     * 键入会被旧快照回退）。
     */
    private fun updateStagedItem(source: String, transform: (StagedUpload) -> StagedUpload) {
        viewModelScope.launch {
            stagingRepository.editItems { items ->
                items.map { if (it.source == source) transform(it) else it }
            }
        }
    }

    /** toggle 通用（批次与逐项共用）：在集则移除、不在则加入 */
    private fun toggleIn(sources: List<String>, name: String): List<String> =
        if (name in sources) sources - name else sources + name

    // ---- 拦截/入队/取消 ----

    /** 清空拦截/错误/提示文案（用户关闭横幅；三个入口同一形态单行） */
    fun dismissBlock() { form.update { it.copy(blockMessage = null) } }

    fun dismissError() { form.update { it.copy(errorMessage = null) } }

    fun dismissNotice() { form.update { it.copy(noticeMessage = null) } }

    /**
     * 开始上传：现取服务端配置做超限本地拦截（实时生效口径），未超限项进串行队列。
     * 门禁（canEnqueue 同源兜底）：批次库必选——逐项全部带库覆盖时可缺省。
     * 分组入队：批次项走批次库 + 页面已选目录；库覆盖项走各自库的根目录
     * （覆盖项未加载该库目录树，落库根由服务端 NormalizeRelPath 兜底）。
     * 超限被拦项保留在暂存区（其余照常入队——持久层语义下不再整体清空）；
     * 入队成功的项从暂存区移除（队列持久化归 WorkManager，暂存区只管未入队条目）；
     * 收件箱源文件的 uploaded/ 归档在 worker 上传成功后执行（UploadWorker.archiveNote）。
     */
    fun enqueue() {
        if (form.value.enqueueing) return
        viewModelScope.launch {
            form.update { it.copy(enqueueing = true, blockMessage = null, errorMessage = null) }
            refreshLimits()
            val limits = form.value.limits
            val items = stagingRepository.stagedItems.first()
            if (items.isEmpty()) {
                form.update { it.copy(enqueueing = false) }
                return@launch
            }
            val batchLibraryId = stagingRepository.batchConfig.first().libraryId
            val batchItems = items.filter { it.libraryIdOverride == null }
            if (batchItems.isNotEmpty() && batchLibraryId == null) {
                form.update { it.copy(enqueueing = false, blockMessage = LIBRARY_REQUIRED_MESSAGE) }
                return@launch
            }
            val blocked = limits?.let { rule -> items.filter { rule.overLimit(it.sizeBytes) } }.orEmpty()
            val blockedSources = blocked.map { it.source }.toSet()
            val allowed = items.filterNot { it.source in blockedSources }
            if (allowed.isEmpty()) {
                form.update { it.copy(enqueueing = false, blockMessage = blockText(limits, blocked)) }
                return@launch
            }
            try {
                enqueueGrouped(allowed, batchLibraryId)
                stagingRepository.removeItems(allowed.map { it.source })
                form.update {
                    it.copy(
                        enqueueing = false,
                        editingSource = null,
                        itemAuthorQuery = "",
                        itemAuthorSuggestions = emptyList(),
                        itemNameSuggestions = emptyList(),
                        blockMessage = blockText(limits, blocked),
                    )
                }            } catch (e: Exception) {
                form.update { it.copy(enqueueing = false, errorMessage = ENQUEUE_FAILED_MESSAGE) }
            }
        }
    }

    /** 分组入队：批次项（批次库+已选目录）与逐项覆盖项（各自库+库根）两条通道 */
    private fun enqueueGrouped(allowed: List<StagedUpload>, batchLibraryId: String?) {
        val batchGroup = allowed.filter { it.libraryIdOverride == null }
        if (batchGroup.isNotEmpty()) {
            uploadRepository.enqueue(
                batchGroup.map { it.toUploadItem(libraryNameOf(batchLibraryId)) },
                requireNotNull(batchLibraryId),
                form.value.selectedDirPath,
            )
        }
        allowed.filter { it.libraryIdOverride != null }
            .groupBy { requireNotNull(it.libraryIdOverride) }
            .forEach { (libraryId, group) ->
                uploadRepository.enqueue(
                    group.map { it.toUploadItem(libraryNameOf(libraryId)) },
                    libraryId,
                    "",
                )
            }
    }

    /**
     * 库 id -> 展示名（2026-09-28 上传归档文件夹功能：库名随载荷入队，worker 上传成功后
     * 归档到 <归档文件夹>/<库名>/）。解析不到（库列表加载失败/库已被删）回退空串——
     * worker 侧对空库名回退既有 uploaded/ 归档，归档不会因缺名而丢文件。
     */
    private fun libraryNameOf(libraryId: String?): String =
        libraryId?.let { id -> uiState.value.libraries.firstOrNull { it.id == id }?.name }.orEmpty()

    /** 暂存条目 → 入队条目（uploadFileName 解析为实际值：编辑基名+锁定扩展名/回退展示名） */
    private fun StagedUpload.toUploadItem(libraryName: String) = UploadItem(
        uri = source,
        displayName = displayName,
        sizeBytes = sizeBytes,
        uploadFileName = effectiveUploadName,
        attachAuthorId = attachAuthorId,
        attachAuthorName = attachAuthorName,
        attachSources = attachSources,
        libraryName = libraryName,
    )

    /**
     * 取消单个队列任务（批C 任务Q C-2；排队中与上传中皆可，对齐 Web 无需二次确认）。
     * 协作式取消全在 core 层（UploadCancelRegistry + worker 检查），VM 只透传；
     * 队列行状态由 queueUpdates 流即时翻「已取消」，UI 无本地回滚状态。
     */
    fun cancel(entry: UploadQueueEntry) {
        uploadRepository.cancel(entry.localId)
    }
}
