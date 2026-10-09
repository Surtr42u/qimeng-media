package media.qimeng.app.feature.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.qimeng.app.core.data.repository.AuthorRepository
import media.qimeng.app.core.data.repository.DetailRepository
import media.qimeng.app.core.data.repository.MoveConflictException
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
    /** 空输入作者种子（GET /authors 全量过滤常规作者；suggest 空 q 必返空，
     *  空输入的默认全显走全量接口；失败静默为空，不阻断编辑页） */
    val authorSeeds: List<AuthorSuggestion> = emptyList(),
    /** 每作者来源编辑副本（authorId → 来源集；保存前不落服务端） */
    val sourcesByAuthor: Map<String, List<String>> = emptyMap(),
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
    /** 作者全集相对初始装配是否变化（VM 在增删/装配时回填；data class copy 不携带
     *  body var 属性，故本字段必须是构造参数而非派生属性——变基准放 VM 私有域） */
    val authorsChanged: Boolean = false,
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

    /** 保存按钮可用：有改动（文件名/作者全集/任一来源）且不在保存中 */
    val canSave: Boolean
        get() = !saving && (
            authorsChanged ||
            dirtySourceAuthorIds.isNotEmpty() ||
            (fileNameChanged && currentBaseName.isNotBlank() && effectiveFileName != originalFileName)
        )
}

/**
 * 资产编辑页 ViewModel：作者关联（PUT /assets/{id}/authors 全集替换）+ 逐作者来源
 * （PUT /authors/{id}/sources）+ 来源词表（GET /authors/source-vocabulary）+
 * 添加作者联想（GET /authors/suggest，经 [UploadRepository]——上传挂靠退役后联想
 * 端点的唯一消费场景迁至此）。表单编排在本层，UI 零业务规则（ADR-0008 铁律 7）。
 *
 * 仅能关联**已有**作者：服务端无按名新建作者端点（新建唯一通道是 TXT 导入），
 * 联想无命中时给提示、不发保存请求。
 */
@HiltViewModel
class AssetEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val detailRepository: DetailRepository,
    private val authorRepository: AuthorRepository,
    private val uploadRepository: UploadRepository,
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
     * 首屏/重试装配：详情作者全集 → 逐作者来源回显 → 词表。
     * 回显与词表失败均静默降级（来源置空=可自由编辑、词表退化纯自由输入），
     * 只有详情本体失败才进错误横幅（整页无数据）。
     */
    fun refresh() {
        if (_uiState.value.loading) return
        _uiState.update { it.copy(loading = true, errorMessage = null) }
        load()
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val detail = detailRepository.assetDetail(assetId)
                val authorIds = detail.authors.map { it.id }
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
                        authors = detail.authors,
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

    /**
     * 逐作者来源回显：单作者失败不影响其余（该作者来源置空=可自由编辑）。
     * 只对尚无本地编辑副本的作者回填（refresh 重试不覆盖未保存的编辑）。
     */
    private suspend fun loadSourceEcho() {
        val echo = _uiState.value.authors.associate { author ->
            author.id to runCatching { authorRepository.authorSourcesById(author.id) }.getOrDefault(emptyList())
        }
        _uiState.update { current ->
            current.copy(sourcesByAuthor = current.sourcesByAuthor + echo.filterKeys { it !in current.sourcesByAuthor })
        }
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

    // ---- 添加作者（联想选择；规则与旧上传页一致，去掉新建通道） ----

    /** 输入变化：防抖拉联想（空串清列表；竞态以词条比对回填） */
    fun onAddAuthorQueryChange(query: String) {
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

    /** 回车提交：大小写不敏感精确命中 → 添加该作者；未命中 → 提示仅能关联已有作者 */
    fun commitAddAuthor() {
        val current = _uiState.value
        val trimmed = current.authorQuery.trim()
        if (trimmed.isEmpty()) return
        val exact = current.authorSuggestions.firstOrNull {
            it.displayName.equals(trimmed, ignoreCase = true)
        }
        if (exact != null) {
            addAuthor(exact)
        } else {
            _uiState.update { it.copy(noticeMessage = ONLY_EXISTING_AUTHORS) }
        }
    }

    /** 点选联想项：加入编辑中全集（已关联则提示；输入态复位） */
    fun addAuthor(author: AuthorSuggestion) {
        suggestJob?.cancel()
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
                    authorQuery = "",
                    authorSuggestions = emptyList(),
                    noticeMessage = null,
                )
            }
        }
    }

    /** 行内移除：全集删除 + 来源编辑副本与脏标记连带清理 */
    fun removeAuthor(authorId: String) {
        _uiState.update { current ->
            val remaining = current.authors.filterNot { it.id == authorId }
            current.copy(
                authors = remaining,
                authorsChanged = remaining.map { it.id } != initialAuthorIds,
                sourcesByAuthor = current.sourcesByAuthor - authorId,
                dirtySourceAuthorIds = current.dirtySourceAuthorIds - authorId,
            )
        }
    }

    // ---- 每作者来源编辑（多选词表 + 自由输入；变更记脏，保存时逐作者 PUT） ----

    /** 来源 toggle（trim 后空串忽略） */
    fun toggleSource(authorId: String, name: String) {
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
                }
                // 2. 作者全集整体 PUT 替换
                authorRepository.replaceAssetAuthors(assetId, current.authors.map { it.id })
                // 3. 逐个更新脏来源
                current.dirtySourceAuthorIds.forEach { authorId ->
                    authorRepository.replaceAuthorSources(authorId, current.sourcesByAuthor[authorId].orEmpty())
                }
                _uiState.update { it.copy(saving = false, saved = true) }
            } catch (e: MoveConflictException) {
                _uiState.update { it.copy(saving = false, errorMessage = NAME_CONFLICT) }
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
        const val SAVE_FAILED = "保存失败：请重试"
        const val NAME_CONFLICT = "目标目录已存在同名文件"
        const val ONLY_EXISTING_AUTHORS = "未找到该作者：编辑页仅能关联已有作者，请从联想中选择"
        const val AUTHOR_EXISTS = "该作者已关联"
    }
}
