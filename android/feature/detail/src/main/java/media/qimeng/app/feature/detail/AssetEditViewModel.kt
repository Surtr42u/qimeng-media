package media.qimeng.app.feature.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.qimeng.app.core.data.repository.AssetAuthorsRejectedException
import media.qimeng.app.core.data.repository.AuthorRepository
import media.qimeng.app.core.data.repository.DetailRepository
import media.qimeng.app.core.data.repository.InboxFileStore
import media.qimeng.app.core.data.repository.MoveConflictException
import media.qimeng.app.core.data.repository.StagingRepository
import media.qimeng.app.core.data.repository.UploadRepository
import media.qimeng.app.core.model.AUTHOR_SUGGEST_DEBOUNCE_MS
import media.qimeng.app.core.model.AuthorSuggestion
import media.qimeng.app.core.model.DetailAuthor
import media.qimeng.app.core.model.individualSourceWords
import media.qimeng.app.core.model.toRegularAuthorSeeds

/**
 * 资产编辑页 UI 状态：文件名编辑（含作品名/序号联想）+ 作者关联全集 + 逐作者来源维护。
 * 「已关联作者」= 编辑中全集（保存时整体 PUT）；来源 = 每作者独立编辑副本
 * （GET 回显 + 变更记脏，保存时逐作者 PUT）；文件名 = 基名编辑 + 扩展名锁定（保存时 POST /move 重命名）。
 */
data class AssetEditUiState(
    /** 首屏装配（详情 + 词表 + 逐作者来源回显） */
    val loading: Boolean = true,
    /** 资产标题（展示原文件名） */
    val assetTitle: String = "",
    /** 所属库 ID（作品名/序号联想接口必填） */
    val libraryId: String = "",
    /** 原始文件名（用于判断改动与删空回退安全兜底） */
    val originalFileName: String = "",
    /** 编辑中的基名（可清空自由输入，不回弹原名） */
    val currentBaseName: String = "",
    /** 扩展名（锁定后缀，带点如 ".mp4"；无扩展名则为空） */
    val extension: String = "",
    /** 目标所在目录（原样保留，用于 moveAsset targetDir） */
    val currentDirectory: String = "",
    /** 作品名/序号联想建议列表（输入基名防抖查询） */
    val nameSuggestions: List<String> = emptyList(),
    /** 文件名是否发生改变（与初始基名不同） */
    val fileNameChanged: Boolean = false,
    /** 编辑中的作者全集（初始 = 详情 authors；行内移除 / 联想添加在此增删） */
    val authors: List<DetailAuthor> = emptyList(),
    /** 添加作者输入框草稿 */
    val authorQuery: String = "",
    /** 联想结果（防抖回填） */
    val authorSuggestions: List<AuthorSuggestion> = emptyList(),
    /** 待新建作者名（回车未命中联想的名字；保存时随 PUT 交服务端按规范身份复用或新建，
     *  保存前不占「已关联作者」计数、也无来源编辑——服务端建行后才是正常作者） */
    val pendingNewAuthorNames: List<String> = emptyList(),
    /** 空输入作者种子（GET /authors 全量过滤常规作者；suggest 空 q 必返空，
     *  空输入的默认全显走全量接口；失败静默为空，不阻断编辑页） */
    val authorSeeds: List<AuthorSuggestion> = emptyList(),
    /** 每作者来源编辑副本（authorId → 来源集；保存前不落服务端） */
    val sourcesByAuthor: Map<String, List<String>> = emptyMap(),
    /** 读服务端既有来源期间先禁用编辑，避免把「尚未加载」误当成空来源集整体覆盖。 */
    val sourceLoadingAuthorIds: Set<String> = emptySet(),
    /** 无法读取既有来源的作者；该行保持禁用并提供重试，不能以空数组为基线写回。 */
    val sourceLoadFailedAuthorIds: Set<String> = emptySet(),
    /** 来源被改动过的作者 id 集（保存时逐个 PUT；未改动的作者不发请求） */
    val dirtySourceAuthorIds: Set<String> = emptySet(),
    /** 全站来源建议词表（GET /authors/source-vocabulary；失败静默=纯自由输入） */
    val sourceOptions: List<String> = emptyList(),
    /** 保存请求进行中（按钮禁用与文案「保存中…」） */
    val saving: Boolean = false,
    /** 错误横幅（error 容器） */
    val errorMessage: String? = null,
    /** 提示横幅（tertiary 容器；重复添加/未命中联想等非失败反馈） */
    val noticeMessage: String? = null,
    /** 保存成功（一次性信号：页面据此返回，返回后路由销毁无需复位） */
    val saved: Boolean = false,
    /** 作者全集相对初始装配是否变化。 */
    val authorsChanged: Boolean = false,
    /** 多步保存中作者关联 PUT 已成功；后续来源写入失败重试时跳过已提交步骤。 */
    val authorAssociationCommitted: Boolean = false,
) {
    /** 实际生效的完整文件名：基名为空时安全回退 originalFileName，否则拼接扩展名 */
    val effectiveFileName: String
        get() {
            val base = currentBaseName.trim()
            val ext = extension.trim().trimStart('.')
            return when {
                base.isBlank() && ext.isBlank() -> originalFileName
                base.isBlank() -> originalFileName
                ext.isBlank() -> base
                else -> "$base.$ext"
            }
        }

    /** 是否存在尚未保存的有效改动（用于保存按钮与离开确认共享同一真相）。 */
    val hasUnsavedChanges: Boolean
        get() = authorsChanged ||
            pendingNewAuthorNames.isNotEmpty() ||
            dirtySourceAuthorIds.isNotEmpty() ||
            (fileNameChanged && currentBaseName.isNotBlank() && effectiveFileName != originalFileName)

    /** 保存按钮可用：有有效改动且不在保存中。 */
    val canSave: Boolean
        get() = !saving && hasUnsavedChanges
}

/**
 * 资产编辑页 ViewModel：作者关联（PUT /assets/{id}/authors 全集替换）+ 逐作者来源
 * （PUT /authors/{id}/sources）+ 来源词表（GET /authors/source-vocabulary）+
 * 添加作者联想（GET /authors/suggest，经 [UploadRepository]——上传挂靠退役后联想
 * 端点的唯一消费场景迁至此）。表单编排在本层，UI 零业务规则（ADR-0008 铁律 7）。
 *
 * 作者添加两条通道：联想命中 = 关联已有作者；未命中 = 进「待新建」名单，保存时随
 * PUT 交服务端按规范身份复用或新建（协议 2026-10-11 修订，服务端是新建的执行者，
 * 客户端不做按名建行）。待新建名此刻还没有作者 id，故保存前不提供来源编辑。
 */
@HiltViewModel
class AssetEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val detailRepository: DetailRepository,
    private val authorRepository: AuthorRepository,
    private val uploadRepository: UploadRepository,
    private val stagingRepository: StagingRepository,
    private val inboxFileStore: InboxFileStore = InboxFileStore(),
) : ViewModel() {

    private val assetId: String = savedStateHandle.get<String>(AssetEditRoutes.KEY_ASSET_ID).orEmpty()

    private val _uiState = MutableStateFlow(AssetEditUiState())

    /** 初始作者 id 序（装配快照；[AssetEditUiState.authorsChanged] 的比较基准，VM 私有域） */
    private var initialAuthorIds: List<String> = emptyList()

    /** 初始文件名基名（装配快照；[AssetEditUiState.fileNameChanged] 的比较基准） */
    private var initialBaseName: String = ""

    val uiState: StateFlow<AssetEditUiState> = _uiState.asStateFlow()

    /** 作者联想防抖任务（同 SearchViewModel / 旧上传页实现口径） */
    private var suggestJob: Job? = null

    /** 作品名/序号联想防抖任务 */
    private var nameSuggestJob: Job? = null

    init {
        load()
    }

    /**
     * 首屏/重试装配：详情作者全集 → 启动逐作者来源回显 → 词表。
     * 词表失败可降级为自由输入；但逐作者来源是整体替换写入，读失败时必须禁用该行，
     * 不能把「未知的旧值」误当空数组后覆盖服务端记录。详情本体失败仍走页面错误横幅。
     */
    fun refresh() {
        if (_uiState.value.loading || _uiState.value.saving) return
        _uiState.update { it.copy(loading = true, errorMessage = null) }
        load()
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val detail = detailRepository.assetDetail(assetId)
                // COS 作者由目录结构派生，作者替换端点只接受 regular；与 Web authorDraftsFromDetail 同口径，
                // 不把只读 COS 行放进可编辑全集，否则改一个普通作者也会因为携带 COS id 被服务端 400 拒绝。
                val editableAuthors = detail.authors.filterNot { it.isCos || it.id.startsWith("cos_") }
                val authorIds = editableAuthors.map { it.id }
                initialAuthorIds = authorIds
                val fileName = detail.fileName
                val ext = if (fileName.contains('.')) ".${fileName.substringAfterLast('.')}" else ""
                val base = fileName.removeSuffix(ext)
                initialBaseName = base
                _uiState.update { state ->
                    state.copy(
                        loading = false,
                        assetTitle = detail.fileName,
                        libraryId = detail.libraryId,
                        originalFileName = fileName,
                        currentBaseName = base,
                        extension = ext,
                        currentDirectory = detail.directory.orEmpty(),
                        authors = editableAuthors,
                        authorsChanged = false,
                        fileNameChanged = false,
                    )
                }
                loadSourceEcho()
                loadVocabulary()
                loadAuthorSeeds()
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, errorMessage = LOAD_FAILED) }
            }
        }
    }

    /** 启动当前已关联作者的来源读取；每行独立加载，失败只禁用该行并允许重试。 */
    private fun loadSourceEcho() {
        val authorIds = _uiState.value.authors.map { it.id }.toSet()
        _uiState.update { current ->
            current.copy(
                sourceLoadingAuthorIds = current.sourceLoadingAuthorIds intersect authorIds,
                sourceLoadFailedAuthorIds = current.sourceLoadFailedAuthorIds intersect authorIds,
            )
        }
        authorIds.forEach(::loadSourcesForAuthor)
    }

    /**
     * 加载一个作者的当前来源。来源 PUT 是整体替换，因此读取失败不能静默回退为空集；
     * 若用户已在本地改过该作者的来源，重试/刷新成功也不得覆盖草稿。
     */
    private fun loadSourcesForAuthor(authorId: String) {
        if (authorId.isBlank()) return
        _uiState.update { current ->
            current.copy(
                sourceLoadingAuthorIds = current.sourceLoadingAuthorIds + authorId,
                sourceLoadFailedAuthorIds = current.sourceLoadFailedAuthorIds - authorId,
            )
        }
        viewModelScope.launch {
            try {
                val sources = authorRepository.authorSourcesById(authorId)
                _uiState.update { current ->
                    current.copy(
                        sourcesByAuthor = if (authorId in current.dirtySourceAuthorIds) {
                            current.sourcesByAuthor
                        } else {
                            current.sourcesByAuthor + (authorId to sources)
                        },
                        sourceLoadingAuthorIds = current.sourceLoadingAuthorIds - authorId,
                        sourceLoadFailedAuthorIds = current.sourceLoadFailedAuthorIds - authorId,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { current ->
                    current.copy(
                        sourceLoadingAuthorIds = current.sourceLoadingAuthorIds - authorId,
                        sourceLoadFailedAuthorIds = current.sourceLoadFailedAuthorIds + authorId,
                    )
                }
            }
        }
    }

    /** 行内「重试」入口；失败期间行内容不可编辑，成功取到完整旧值后再恢复操作。 */
    fun retryAuthorSources(authorId: String) {
        if (_uiState.value.saving) return
        loadSourcesForAuthor(authorId)
    }

    /** 全站来源词表（快捷选项，仅单独词；失败静默=纯自由输入，同旧上传页词表降级口径） */
    private suspend fun loadVocabulary() {
        val options = runCatching { authorRepository.sourceVocabulary() }.getOrDefault(emptyList())
        _uiState.update { it.copy(sourceOptions = options.individualSourceWords()) }
    }

    /** 空输入作者种子（全量接口过滤常规作者）：suggest 空 q 必返空，空输入默认全显
     *  只能走全量；失败静默为空（同词表降级口径，不阻断编辑页） */
    private suspend fun loadAuthorSeeds() {
        val seeds = runCatching { authorRepository.authors().toRegularAuthorSeeds() }.getOrDefault(emptyList())
        _uiState.update { it.copy(authorSeeds = seeds) }
    }

    // ---- 添加作者（联想命中=关联已有；未命中=进待新建名单，保存时交服务端） ----

    /** 输入变化：防抖拉联想（空串清列表；竞态以词条比对回填） */
    fun onAddAuthorQueryChange(query: String) {
        if (_uiState.value.saving) return
        _uiState.update { it.copy(authorQuery = query) }
        suggestJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(authorSuggestions = emptyList()) }
            return
        }
        suggestJob = viewModelScope.launch {
            delay(AUTHOR_SUGGEST_DEBOUNCE_MS)
            runCatching { uploadRepository.suggestAuthors(trimmed) }
                .onSuccess { list ->
                    if (_uiState.value.authorQuery.trim() == trimmed) {
                        _uiState.update { it.copy(authorSuggestions = list) }
                    }
                }
        }
    }

    /**
     * 回车提交：联想列表忽略大小写精确命中 → 关联该作者（既有通道，行为不变）；
     * 未命中 → 进「待新建」名单（保存时随 PUT 交服务端复用或新建），不再报
     * 「仅能关联已有作者」。超协议 maxLength 的名字给明确提示、不入列；
     * 与已关联作者显示名及名单内部忽略大小写去重（名字此刻无 id，靠显示名归一挡重）。
     */
    fun commitAddAuthor() {
        if (_uiState.value.saving) return
        val current = _uiState.value
        val trimmed = current.authorQuery.trim()
        if (trimmed.isEmpty()) return
        val exact = current.authorSuggestions.firstOrNull {
            it.displayName.equals(trimmed, ignoreCase = true)
        }
        if (exact != null) {
            addAuthor(exact)
            return
        }
        if (trimmed.length > NEW_AUTHOR_NAME_MAX) {
            _uiState.update { it.copy(noticeMessage = AUTHOR_NAME_TOO_LONG) }
            return
        }
        if (current.authors.any { it.displayName.equals(trimmed, ignoreCase = true) }) {
            // 与已关联作者显示名同名（联想未载入时的兜底去重）：给既有「已关联」提示，不入列
            _uiState.update { it.copy(noticeMessage = AUTHOR_EXISTS) }
            return
        }
        if (current.pendingNewAuthorNames.any { it.equals(trimmed, ignoreCase = true) }) {
            // 名单内部忽略大小写去重：静默 no-op（同名胶囊已可见，报错反而误导）
            return
        }
        if (current.pendingNewAuthorNames.size >= NEW_AUTHOR_NAMES_MAX) {
            // 项数上限（协议 maxItems 客户端投影，超限服务端 400）：第 21 个不入列。
            // 提示引导先保存本页——保存后待新建成真作者、名单清空即可继续加（口径对齐
            // Web asset-authors.ts 的 too-many 文案）
            _uiState.update {
                it.copy(noticeMessage = PENDING_AUTHORS_FULL.format(NEW_AUTHOR_NAMES_MAX))
            }
            return
        }
        suggestJob?.cancel()
        _uiState.update {
            it.copy(
                pendingNewAuthorNames = it.pendingNewAuthorNames + trimmed,
                authorAssociationCommitted = false,
                authorQuery = "",
                authorSuggestions = emptyList(),
                noticeMessage = null,
            )
        }
    }

    /** 点按待新建胶囊移除（保存前来去自由；无作者 id，无来源等连带状态需要清理） */
    fun removePendingNewAuthor(name: String) {
        if (_uiState.value.saving) return
        _uiState.update { current ->
            current.copy(
                pendingNewAuthorNames = current.pendingNewAuthorNames.filterNot { it == name },
                authorAssociationCommitted = false,
            )
        }
    }

    /** 点选联想项：加入编辑中全集（已关联则提示；输入态复位）。新增作者先读取其现有来源。 */
    fun addAuthor(author: AuthorSuggestion) {
        if (_uiState.value.saving) return
        suggestJob?.cancel()
        val isNewAssociation = _uiState.value.authors.none { it.id == author.id }
        _uiState.update { current ->
            when {
                current.authors.any { it.id == author.id } ->
                    current.copy(noticeMessage = AUTHOR_EXISTS)

                else -> current.copy(
                    authors = current.authors + DetailAuthor(
                        id = author.id,
                        displayName = author.displayName,
                        isCos = false,
                        followed = false,
                    ),
                    authorsChanged = true,
                    authorAssociationCommitted = false,
                    authorQuery = "",
                    authorSuggestions = emptyList(),
                    noticeMessage = null,
                )
            }
        }
        if (isNewAssociation) loadSourcesForAuthor(author.id)
    }

    /** 行内移除：全集删除 + 来源编辑副本与脏标记连带清理 */
    fun removeAuthor(authorId: String) {
        if (_uiState.value.saving) return
        _uiState.update { current ->
            val remaining = current.authors.filterNot { it.id == authorId }
            current.copy(
                authors = remaining,
                authorsChanged = remaining.map { it.id } != initialAuthorIds,
                authorAssociationCommitted = false,
                sourcesByAuthor = current.sourcesByAuthor - authorId,
                sourceLoadingAuthorIds = current.sourceLoadingAuthorIds - authorId,
                sourceLoadFailedAuthorIds = current.sourceLoadFailedAuthorIds - authorId,
                dirtySourceAuthorIds = current.dirtySourceAuthorIds - authorId,
            )
        }
    }

    // ---- 每作者来源编辑（多选词表 + 自由输入；变更记脏，保存时逐作者 PUT） ----

    /** 来源 toggle（trim 后空串忽略） */
    fun toggleSource(authorId: String, name: String) {
        val current = _uiState.value
        if (current.saving || authorId in current.sourceLoadingAuthorIds || authorId in current.sourceLoadFailedAuthorIds) return
        val normalized = name.trim()
        if (normalized.isEmpty()) return
        _uiState.update { current ->
            val currentSources = current.sourcesByAuthor[authorId].orEmpty()
            val updated = if (normalized in currentSources) {
                currentSources - normalized
            } else {
                currentSources + normalized
            }
            current.copy(
                sourcesByAuthor = current.sourcesByAuthor + (authorId to updated),
                dirtySourceAuthorIds = current.dirtySourceAuthorIds + authorId,
            )
        }
    }

    /** 自由输入加入来源：trim、去重；空串忽略 */
    fun addCustomSource(authorId: String, raw: String) {
        val current = _uiState.value
        if (current.saving || authorId in current.sourceLoadingAuthorIds || authorId in current.sourceLoadFailedAuthorIds) return
        val normalized = raw.trim()
        if (normalized.isEmpty()) return
        _uiState.update { current ->
            val currentSources = current.sourcesByAuthor[authorId].orEmpty()
            if (normalized in currentSources) return@update current
            current.copy(
                sourcesByAuthor = current.sourcesByAuthor + (authorId to currentSources + normalized),
                dirtySourceAuthorIds = current.dirtySourceAuthorIds + authorId,
            )
        }
    }

    // ---- 文件名编辑（基名输入 + 作品名/序号联想推荐；保存时 POST /move 改名） ----

    /**
     * 文件名基名输入变更：实时同步 currentBaseName，防抖 200ms 拉取作品名/序号联想。
     * 保持用户空串输入不回弹原名（对齐上传页修复口径）。
     */
    fun onFileNameChange(newBaseName: String) {
        if (_uiState.value.saving) return
        val changed = newBaseName.trim() != initialBaseName
        _uiState.update {
            it.copy(
                currentBaseName = newBaseName,
                fileNameChanged = changed,
            )
        }
        nameSuggestJob?.cancel()
        val trimmed = newBaseName.trim()
        val libId = _uiState.value.libraryId
        if (trimmed.isEmpty() || libId.isEmpty()) {
            _uiState.update { it.copy(nameSuggestions = emptyList()) }
            return
        }
        nameSuggestJob = viewModelScope.launch {
            delay(200L) // 200ms 防抖，与 UploadViewModel 保持一致
            runCatching { uploadRepository.suggestNames(libId, trimmed) }
                .onSuccess { suggestions ->
                    if (_uiState.value.currentBaseName.trim() == trimmed) {
                        _uiState.update { it.copy(nameSuggestions = suggestions) }
                    }
                }
                .onFailure {
                    _uiState.update { it.copy(nameSuggestions = emptyList()) }
                }
        }
    }

    /** 点选作品名/序号联想建议：回填基名并清空联想浮层 */
    fun pickFileNameSuggestion(suggestionBase: String) {
        if (_uiState.value.saving) return
        nameSuggestJob?.cancel()
        val changed = suggestionBase.trim() != initialBaseName
        _uiState.update {
            it.copy(
                currentBaseName = suggestionBase,
                nameSuggestions = emptyList(),
                fileNameChanged = changed,
            )
        }
    }

    // ---- 保存：文件名改名 (POST /move) + 作者全集整体 PUT + 脏来源逐作者 PUT ----

    /** 保存（canSave 门控；请求幂等，失败横幅可重按重试） */
    fun save() {
        val current = _uiState.value
        if (!current.canSave) return
        viewModelScope.launch {
            _uiState.update { it.copy(saving = true, errorMessage = null, noticeMessage = null) }
            try {
                // 1. 若文件名改动且有效，调用 moveAsset 执行库内重命名
                if (current.fileNameChanged && current.currentBaseName.isNotBlank() && current.effectiveFileName != current.originalFileName) {
                    detailRepository.moveAsset(
                        assetId = assetId,
                        targetDir = current.currentDirectory,
                        newName = current.effectiveFileName,
                    )
                    // 文件名这一步已经在服务端提交成功：立即落成本地基线，后续作者/来源失败重试时
                    // 不要再次对已完成的移动发请求（可能已落库的多步保存不能整体回滚）。
                    val renamedFileName = current.effectiveFileName
                    val renamedBaseName = current.currentBaseName.trim()
                    initialBaseName = renamedBaseName
                    _uiState.update {
                        it.copy(
                            assetTitle = renamedFileName,
                            originalFileName = renamedFileName,
                            currentBaseName = renamedBaseName,
                            fileNameChanged = false,
                        )
                    }
                    // 联动重命名手机本地归档目录中的物理文件（尽力而为，不阻断主流程）
                    runCatching {
                        val archiveRoot = stagingRepository.archivePath.first()
                        if (!archiveRoot.isNullOrBlank()) {
                            val libName = runCatching {
                                uploadRepository.libraries().firstOrNull { it.id == current.libraryId }?.name
                            }.getOrNull()
                            inboxFileStore.renameArchivedFile(
                                archiveRoot = archiveRoot,
                                libraryName = libName,
                                oldFileName = current.originalFileName,
                                newFileName = current.effectiveFileName,
                            )
                        }
                    }
                }
                // 2. 仅当作者全集或待新建名单确实变化时才整体 PUT。文件名单独改动、或只改来源时
                //    不写无关作者关联，避免无谓数据库/TXT 写入及失败后把已成功的文件操作报成失败。
                if (
                    (current.authorsChanged || current.pendingNewAuthorNames.isNotEmpty()) &&
                    !current.authorAssociationCommitted
                ) {
                    authorRepository.replaceAssetAuthors(
                        assetId,
                        current.authors.map { it.id },
                        current.pendingNewAuthorNames,
                    )
                    // 作者关联已提交：若后续某个来源 PUT 失败，重试时跳过此步骤，避免重复写。
                    _uiState.update { it.copy(authorAssociationCommitted = true) }
                }
                // 3. 逐个更新脏来源；单项成功立即清脏，部分失败重试时只重试失败项。
                current.dirtySourceAuthorIds.forEach { authorId ->
                    authorRepository.replaceAuthorSources(authorId, current.sourcesByAuthor[authorId].orEmpty())
                    _uiState.update { state ->
                        state.copy(dirtySourceAuthorIds = state.dirtySourceAuthorIds - authorId)
                    }
                }
                // 4. 成功：待新建名已被服务端实体化（复用或新建），清空名单。
                _uiState.update {
                    it.copy(
                        saving = false,
                        saved = true,
                        pendingNewAuthorNames = emptyList(),
                        authorsChanged = false,
                        authorAssociationCommitted = false,
                    )
                }
            } catch (e: MoveConflictException) {
                _uiState.update { it.copy(saving = false, errorMessage = NAME_CONFLICT) }
            } catch (e: AssetAuthorsRejectedException) {
                // 4xx 语义化拒绝（如 newAuthorNames 某项非法）：服务端原因直接透出，
                // 用户可自助修正名字后重按（待新建名单不清空，改完即可重试）；
                // 兜底文案已含状态码（拿不到服务端 body 时），与网络失败区分
                _uiState.update {
                    it.copy(saving = false, errorMessage = "$SAVE_FAILED_PREFIX${e.serverMessage}")
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(saving = false, errorMessage = SAVE_FAILED) }
            }
        }
    }

    fun dismissError() = _uiState.update { it.copy(errorMessage = null) }

    fun dismissNotice() = _uiState.update { it.copy(noticeMessage = null) }

    private companion object {
        // 作者联想防抖常量已单源化 core:model ListQueryDefaults（AUTHOR_SUGGEST_DEBOUNCE_MS，
        // 与上传页/Web 端同值口径），经 import 引用

        const val LOAD_FAILED = "加载资产信息失败，请重试"

        /** 保存失败前缀（服务端原因透出时拼在其后，如「保存失败：INVALID_PARAM …」；[SAVE_FAILED] 同前缀） */
        const val SAVE_FAILED_PREFIX = "保存失败："
        const val SAVE_FAILED = SAVE_FAILED_PREFIX + "请重试"
        const val NAME_CONFLICT = "目标目录已存在同名文件"
        const val AUTHOR_EXISTS = "该作者已关联"

        /**
         * 作者名单名上限（协议 AssetAuthorsReplaceRequest.newAuthorNames maxLength=200 rune
         * 的客户端投影，超限服务端 400——VocabularyLimits 同款「协议容量投影」先例，
         * 本地提前拦截给明确提示。按 UTF-16 长度计，非基本平面字符比 rune 口径更严，
         * 取保守方向）。
         */
        const val NEW_AUTHOR_NAME_MAX = 200

        /**
         * 待新建作者项数上限（协议 AssetAuthorsReplaceRequest.newAuthorNames maxItems=20
         * 的客户端投影，超限服务端 400——协议侧改动须同步此处，反之亦然；Web
         * lib/asset-authors.ts 的 NEW_AUTHOR_NAMES_MAX_ITEMS 同值双写）。
         */
        const val NEW_AUTHOR_NAMES_MAX = 20
        const val AUTHOR_NAME_TOO_LONG = "作者名过长（最多 200 字）"

        /** 待新建满额提示（%d 取 [NEW_AUTHOR_NAMES_MAX]；文案与 Web pendingAuthorRejectionText 的
         *  too-many 分支逐字对齐——「先保存本页」后待新建成真作者、名单清空即可继续加） */
        const val PENDING_AUTHORS_FULL = "待新建作者最多 %d 个，请先保存本页再继续添加"
    }
}
