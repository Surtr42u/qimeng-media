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
import media.qimeng.app.core.data.repository.StagingRepository
import media.qimeng.app.core.data.repository.UploadRepository
import media.qimeng.app.core.model.AuthorSuggestion
import media.qimeng.app.core.model.LibraryChoice
import media.qimeng.app.core.model.StagingBatchConfig
import media.qimeng.app.core.model.UploadItem
import media.qimeng.app.core.model.UploadQueueEntry
import media.qimeng.app.core.model.UploadRules
import media.qimeng.app.core.model.individualSourceWords

/**
 * 上传流 ViewModel（M4-5；2026-09-29 直传化）。
 * 暂存区退役（用户拍板「暂存了好像没意义啊，去掉吧」）：选完文件即传——系统文件 SAF
 * 多选与系统分享共用单管道 [submitUris]：describe 解元数据 → 未选库给提示不传 →
 * 逐项判超限（超限项本地拦截不出网，blockText 文案）→ 其余直接 enqueue（继承批次默认
 * 快照：库/目录/作者/来源）。无暂存条目、无逐项编辑、无「开始上传」按钮；
 * 批次默认（作者联想 + 来源多选，新进文件自动继承）仍持久化（StagingRepository.batchConfig，
 * 跨进程重启/隔天不丢），页面只 collect 持久流渲染。
 * 拦截口径（冻结）：大小上限读 GET /config 的 upload 项（提交时现取现判——服务端实时生效）；
 * 类型白名单不复制，服务端 4xx 文案透传展示。
 * 挂靠批：批次默认随载荷入队，挂靠执行在 worker 的 201 之后（mode=append、失败不重试）。
 * 作者仅能选既有（服务端无按名新建端点，联想无命中提示不发请求）；
 * 来源挂靠以作者为前提（服务端来源区挂在作者块下），清作者连带清来源。
 */
@HiltViewModel
class UploadViewModel @Inject constructor(
    private val uploadRepository: UploadRepository,
    private val authorRepository: AuthorRepository,
    private val stagingRepository: StagingRepository,
) : ViewModel() {

    /** 页面会话态（批次默认由 uiState combine 侧以仓库流覆写，见下） */
    private val form = MutableStateFlow(UploadUiState())

    /**
     * 会话态 + 持久流合并。Eagerly 而非 WhileSubscribed：上传页是低频页面、队列流只是
     * 单条 WorkManager 观察，常驻订阅成本可忽略；换来 JVM 单测无需手动订阅即可断言状态。
     */
    val uiState: StateFlow<UploadUiState> = combine(
        form,
        stagingRepository.batchConfig,
        uploadRepository.queueUpdates(),
    ) { current, batch, queue ->
        current.copy(
            batchLibraryId = batch.libraryId,
            batchAuthorId = batch.authorId,
            batchAuthorName = batch.authorName,
            batchSources = batch.sources,
            queue = queue,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UploadUiState())

    /** 批次作者联想防抖任务（与编辑页 AssetEditViewModel 同口径） */
    private var batchSuggestJob: Job? = null

    init {
        refreshLibraries()
        refreshSourceOptions()
    }

    /**
     * 拉取库列表（进页面即拉，不阻塞选文件/选择器——选择器只查 MediaStore，与库无关）。
     * 直传化：不自动选库——批次库由用户手选并持久化；库列表就绪后回放持久化的批次库
     * 目录树（库已被删则静默留空，提交门禁会拦）。limits 与库列表同批拉取。
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

    /** 全站来源词表（快捷选项，仅单独词；失败静默 = 纯自由输入，同编辑页词表降级口径） */
    private fun refreshSourceOptions() {
        viewModelScope.launch {
            val options = runCatching { authorRepository.sourceVocabulary() }.getOrDefault(emptyList())
            form.update { it.copy(sourceOptions = options.individualSourceWords()) }
        }
    }

    private suspend fun loadDirTree(libraryId: String) {
        form.update { it.copy(dirTree = uploadRepository.dirTree(libraryId), selectedDirPath = "") }
    }

    private suspend fun refreshLimits() {
        try {
            form.update { it.copy(limits = uploadRepository.uploadLimits()) }
        } catch (e: Exception) {
            // 配置读不到不拦提交：超限判定退化为服务端 413 兜底
        }
    }

    // ---- 批次默认（库/作者/来源，全部持久化） ----

    /** 选择批次库（必选项）：写持久配置 + 目录树加载/随库重载、目标目录回库根 */
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

    /** 批次配置变更通道（editBatchConfig 原子读-改-写单源；所有批次默认写路径经此） */
    private suspend fun updateBatchConfig(transform: (StagingBatchConfig) -> StagingBatchConfig) {
        stagingRepository.editBatchConfig(transform)
    }

    /** toggle 通用（批次来源共用）：在集则移除、不在则加入 */
    private fun toggleIn(sources: List<String>, name: String): List<String> =
        if (name in sources) sources - name else sources + name

    // ---- 直传（系统文件 SAF 多选与系统分享共用 submitUris 单管道） ----

    /**
     * 选完即传（2026-09-29 直传化，暂存区退役后的唯一入队管道）：
     * 1) 门禁前置——批次库未选直接提示不传（避免无谓出网 describe）；
     * 2) describe 解元数据（失败给错误横幅，整批不传）；
     * 3) 现取服务端配置逐项判超限（实时生效口径），超限项本地拦截不出网、给 blockText
     *    文案；未超限项照常入队（部分被拦不阻塞其余）；
     * 4) 其余直接 enqueue（批次库 + 已选目录，继承批次默认快照：作者/来源/库名）。
     * 收件箱/归档文件夹的源文件归档在 worker 上传成功后执行（UploadWorker.archiveNote）。
     */
    fun submitUris(uris: List<String>) {
        if (uris.isEmpty()) return
        if (form.value.submitting) return
        viewModelScope.launch {
            form.update { it.copy(submitting = true, blockMessage = null, errorMessage = null) }
            val batch = stagingRepository.batchConfig.first()
            val batchLibraryId = batch.libraryId
            if (batchLibraryId == null) {
                form.update { it.copy(submitting = false, blockMessage = LIBRARY_REQUIRED_MESSAGE) }
                return@launch
            }
            val described = try {
                uploadRepository.describe(uris)
            } catch (e: Exception) {
                form.update { it.copy(submitting = false, errorMessage = DESCRIBE_FAILED_MESSAGE) }
                return@launch
            }
            refreshLimits()
            val limits = form.value.limits
            val blocked = limits?.let { rule -> described.filter { rule.overLimit(it.sizeBytes) } }.orEmpty()
            val blockedUris = blocked.map { it.uri }.toSet()
            val allowed = described.filterNot { it.uri in blockedUris }
            if (allowed.isEmpty()) {
                form.update { it.copy(submitting = false, blockMessage = blockText(limits, blocked)) }
                return@launch
            }
            try {
                uploadRepository.enqueue(
                    allowed.map { it.withBatchDefaults(batch, libraryNameOf(batchLibraryId)) },
                    batchLibraryId,
                    form.value.selectedDirPath,
                )
                form.update { it.copy(submitting = false, blockMessage = blockText(limits, blocked)) }
            } catch (e: Exception) {
                form.update { it.copy(submitting = false, errorMessage = ENQUEUE_FAILED_MESSAGE) }
            }
        }
    }

    /** 入队条目继承批次默认快照（作者/来源；库名随载荷入队供 worker 归档分派） */
    private fun UploadItem.withBatchDefaults(batch: StagingBatchConfig, libraryName: String) = copy(
        attachAuthorId = batch.authorId,
        attachAuthorName = batch.authorName,
        attachSources = batch.sources.takeIf { it.isNotEmpty() },
        libraryName = libraryName,
    )

    /**
     * 库 id -> 展示名（2026-09-28 上传归档文件夹功能：库名随载荷入队，worker 上传成功后
     * 归档到 <归档文件夹>/<库名>/）。解析不到（库列表加载失败/库已被删）回退空串——
     * worker 侧对空库名回退既有 uploaded/ 归档，归档不会因缺名而丢文件。
     */
    private fun libraryNameOf(libraryId: String?): String =
        libraryId?.let { id -> uiState.value.libraries.firstOrNull { it.id == id }?.name }.orEmpty()

    // ---- 拦截/错误/提示 ----

    /** 清空拦截/错误/提示文案（用户关闭横幅；三个入口同一形态单行） */
    fun dismissBlock() { form.update { it.copy(blockMessage = null) } }

    fun dismissError() { form.update { it.copy(errorMessage = null) } }

    fun dismissNotice() { form.update { it.copy(noticeMessage = null) } }

    /**
     * 取消单个队列任务（批C 任务Q C-2；排队中与上传中皆可，对齐 Web 无需二次确认）。
     * 协作式取消全在 core 层（UploadCancelRegistry + worker 检查），VM 只透传；
     * 队列行状态由 queueUpdates 流即时翻「已取消」，UI 无本地回滚状态。
     */
    fun cancel(entry: UploadQueueEntry) {
        uploadRepository.cancel(entry.localId)
    }
}
