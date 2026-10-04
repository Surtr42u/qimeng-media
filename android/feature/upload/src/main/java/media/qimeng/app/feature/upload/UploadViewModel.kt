package media.qimeng.app.feature.upload

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlinx.coroutines.withContext
import media.qimeng.app.core.data.di.IoDispatcher
import media.qimeng.app.core.data.repository.AuthorRepository
import media.qimeng.app.core.data.repository.StagingRepository
import media.qimeng.app.core.data.repository.UploadRepository
import media.qimeng.app.core.data.upload.ArchiveBatchItem
import media.qimeng.app.core.data.upload.scanArchiveForUpload
import media.qimeng.app.core.model.AUTHOR_SUGGEST_DEBOUNCE_MS
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
 * 归档一键上传（2026-10-01）：归档根已配置时在库列表就绪后 IO 扫描（scanArchiveForUpload
 * 纯函数 + 内存库列表），确认后按逐条匹配库分组走既有入队管道（alreadyArchived=true，
 * worker 侧跳过二次归档）；不受「未选库」门禁约束——条目自带逐条目标库。入队成功后
 * 源文件仍留在归档根，重扫由防重入队门禁（archiveBatchEnqueued）挡住整批重复入队，
 * 重扫文件数变化自动复位；超限条目本地拦截（与手动直传同一单源）。
 */
@HiltViewModel
class UploadViewModel @Inject constructor(
    private val uploadRepository: UploadRepository,
    private val authorRepository: AuthorRepository,
    private val stagingRepository: StagingRepository,
    /** 归档扫描是磁盘遍历（IO 性质）；测试注入测试调度器保证确定性 */
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    /** 页面会话态（批次默认由 uiState combine 侧以仓库流覆写，见下） */
    private val form = MutableStateFlow(UploadUiState())

    /** 归档扫描任务（新扫描顶替旧扫描，防并发扫描竞写状态） */
    private var archiveScanJob: Job? = null

    /**
     * 防重入队门禁的比对基准：入队成功时那一轮扫描的条目总数（null = 本会话尚未成功
     * 入队过）。重扫结果文件数与该值不同 = 归档区内容已变化，复位门禁（refreshArchiveBatch）。
     */
    private var archiveEnqueuedScanCount: Int? = null

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
            // 归档一键重传扫描（2026-10-01）：在库加载 try/catch 之外独立执行——库列表加载
            // 失败也让它跑（失败时空库列表会如实落「未找到同名库」未匹配清单，不静默隐藏
            // 归档区现状），不等待 limits 网络往返
            refreshArchiveBatch()
        }
    }

    /**
     * 归档一键重传扫描（2026-10-01 归档一键上传）：归档根已配置时在 IO 协程跑
     * [scanArchiveForUpload] 纯函数（目标库匹配用内存库列表）；未配置则清空扫描态。
     * 库列表加载失败时仍扫描（调用点在库加载 try/catch 之外）——匹配不到库的文件夹会
     * 如实落「未找到同名库」未匹配清单，不因网络问题静默隐藏归档区现状。
     * public：入队成功后 VM 自触发重扫（防重入队门禁的复位判定挂在此处），单测以直调
     * 驱动「文件数变化复位门禁」路径；新扫描顶替旧扫描（防并发扫描竞写状态）。
     */
    fun refreshArchiveBatch() {
        archiveScanJob?.cancel()
        archiveScanJob = viewModelScope.launch {
            val scanJob = coroutineContext[Job]
            val archiveRoot = stagingRepository.archivePath.first()
            if (archiveRoot == null) {
                archiveEnqueuedScanCount = null
                form.update {
                    it.copy(archiveBatch = null, archiveBatchLoading = false, archiveBatchEnqueued = false)
                }
                return@launch
            }
            form.update { it.copy(archiveBatchLoading = true) }
            try {
                val result = withContext(ioDispatcher) {
                    scanArchiveForUpload(File(archiveRoot), form.value.libraries)
                }
                // 防重入队门禁复位判定：重扫文件数与入队时不同 = 归档区内容已变化，解除
                // 门禁允许新一轮一键上传；文件数未变（源文件上传成功前仍原位）则保持置位
                val enqueuedScanCount = archiveEnqueuedScanCount
                val gateLifted = enqueuedScanCount != null && result.items.size != enqueuedScanCount
                if (gateLifted) archiveEnqueuedScanCount = null
                form.update {
                    it.copy(
                        archiveBatch = result,
                        archiveBatchLoading = false,
                        archiveBatchEnqueued = it.archiveBatchEnqueued && !gateLifted,
                    )
                }
            } finally {
                // 审查修整项：job 在 loading=true 后被取消时终态必须复位，否则归档区卡「扫描中」。
                // 仅当自己仍是当前扫描 job 时才回写——viewModelScope 走 Main.immediate，被顶替的
                // 旧 job 的取消续体会晚于新 job 体开跑，无守卫会把新扫描刚置位的 loading 清掉
                if (archiveScanJob === scanJob) {
                    form.update { it.copy(archiveBatchLoading = false) }
                }
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
            // 本地超限拦截（与归档一键路径同一单源 partitionOverLimit；口径见 UploadUiState.kt）
            val (blocked, allowed) = partitionOverLimit(described, limits) { it.sizeBytes }
            if (allowed.isEmpty()) {
                form.update {
                    it.copy(submitting = false, blockMessage = blockText(limits, blocked) { it.effectiveUploadName })
                }
                return@launch
            }
            try {
                uploadRepository.enqueue(
                    allowed.map { it.withBatchDefaults(batch, libraryNameOf(batchLibraryId)) },
                    batchLibraryId,
                    form.value.selectedDirPath,
                )
                form.update {
                    it.copy(
                        submitting = false,
                        blockMessage = blockText(limits, blocked) { it.effectiveUploadName },
                    )
                }
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

    // ---- 归档一键上传（2026-10-01） ----

    /**
     * 归档一键上传（用户在确认弹窗点确认后调用）：把扫描条目按目标库分组走**既有入队管道**
     * ——每条 libraryId/libraryName 用该条文件夹匹配到的库（不用批次默认库，也不继承批次
     * 作者/来源：归档区是用户整理过的存量，整批挂同一个默认作者等于错误挂靠）；dir 用
     * 条目 relDir（经 UploadItem.relativeDir 拼接，沿用「库内子目录」dir 语义）；条目一律
     * alreadyArchived=true（源已在归档根，worker 上传成功后跳过归档移动，见 UploadWorker）。
     * 门禁口径：不受「未选库」门禁约束（条目自带逐条目标库）；items 为空（无匹配条目）
     * 不允许触发；防重入队门禁（archiveBatchEnqueued）置位时拦截——入队成功后源文件仍在
     * 归档根，重扫文件数变化前不允许整批再入队（重复确认 = 服务端同名副本）。
     * 超限口径：现取服务端配置逐项判超限（与手动直传同一单源 partitionOverLimit），超限
     * 条目本地拦截不入队、给 blockText 文案；全部超限整批不入队且不置门禁（归档区未变，
     * 调整配置后可重试）。入队成功后扫描态归零并立即重扫（文件上传成功前仍原位，重扫会
     * 再列出——由门禁位挡住重复入队，文件数变化时自动复位）；队列进度由既有 queueUpdates
     * 流呈现（队列区自动出现）。
     */
    fun onArchiveBatchUpload() {
        val scan = form.value.archiveBatch ?: return
        if (scan.items.isEmpty()) return
        if (form.value.submitting) return
        if (form.value.archiveBatchEnqueued) return
        viewModelScope.launch {
            form.update { it.copy(submitting = true, blockMessage = null, errorMessage = null) }
            try {
                refreshLimits()
                val limits = form.value.limits
                val (overLimited, allowed) = partitionOverLimit(scan.items, limits) { it.file.length() }
                if (allowed.isEmpty()) {
                    form.update {
                        it.copy(
                            submitting = false,
                            blockMessage = blockText(limits, overLimited) { it.file.name },
                        )
                    }
                    return@launch
                }
                allowed.groupBy { it.libraryId }.forEach { (libraryId, groupItems) ->
                    uploadRepository.enqueue(
                        groupItems.map { it.toUploadItem() },
                        libraryId,
                        // 调用级 dir 传库根：条目各自的 relDir 经 relativeDir 拼成任务自己的 dir
                        dir = "",
                        libraryName = groupItems.first().libraryName,
                    )
                }
                // 入队成功：记录比对基准（入队时那轮扫描的条目总数）→ 扫描态归零并立即重扫
                archiveEnqueuedScanCount = scan.items.size
                form.update {
                    it.copy(
                        submitting = false,
                        noticeMessage = archiveBatchQueuedMessage(allowed.size),
                        blockMessage = blockText(limits, overLimited) { it.file.name },
                        archiveBatch = null,
                        archiveBatchEnqueued = true,
                    )
                }
                refreshArchiveBatch()
            } catch (e: Exception) {
                form.update { it.copy(submitting = false, errorMessage = ENQUEUE_FAILED_MESSAGE) }
            }
        }
    }

    /** 扫描条目 -> 上传条目（路径类 uri 直传 + 逐条目标库/子目录 + alreadyArchived 旗标） */
    private fun ArchiveBatchItem.toUploadItem() = UploadItem(
        uri = file.absolutePath,
        displayName = file.name,
        sizeBytes = file.length(),
        relativeDir = relDir,
        libraryName = libraryName,
        alreadyArchived = true,
    )

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
