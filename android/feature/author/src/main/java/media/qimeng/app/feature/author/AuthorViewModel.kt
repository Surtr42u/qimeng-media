package media.qimeng.app.feature.author

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import media.qimeng.app.core.data.repository.AuthorManageRejectedException
import media.qimeng.app.core.data.repository.AuthorRepository
import media.qimeng.app.core.data.repository.AssetOrigUrlResolver
import media.qimeng.app.core.model.AuthorSortOption
import media.qimeng.app.core.model.AuthorSummary
import media.qimeng.app.core.model.Zone
import media.qimeng.app.core.model.applyAuthorRows

/**
 * 作者管理对话框（新建/改名/删除确认三态；null=关闭；共用一个对话框槽）。
 * 删除态携带展示所需快照（显示名 + 关联文件数——确认文案「关联的 N 个文件
 * 会保留，仅解除关联」，DOMAIN_RULES §6「删除作者保留文件」的用户口径）。
 */
sealed interface AuthorManageDialog {
    /** 新建作者（输入框空态） */
    data object Create : AuthorManageDialog

    /** 改名（输入框预填当前显示名） */
    data class Rename(val authorId: String, val currentDisplayName: String) : AuthorManageDialog

    /** 删除确认（无输入） */
    data class Delete(val authorId: String, val displayName: String, val fileCount: Int) : AuthorManageDialog
}

data class AuthorUiState(
    val authors: List<AuthorSummary> = emptyList(),
    /** 作者管理胶囊：全部/常规/COS（客户端筛选——GET /authors 无参数全量数组） */
    val zone: Zone = Zone.ALL,
    val keyword: String = "",
    val sort: AuthorSortOption = AuthorSortOption.DEFAULT,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    /** 作者管理对话框状态（null=关闭；见 [AuthorManageDialog]） */
    val manageDialog: AuthorManageDialog? = null,
    /** 对话框输入草稿（新建/改名共用；删除态忽略） */
    val manageInput: String = "",
    /** 管理操作进行中（确定钮禁用防重） */
    val manageSubmitting: Boolean = false,
    /** 提示横幅（管理操作成功反馈，一次性；点击消除） */
    val noticeMessage: String? = null,
)

/**
 * 作者页 ViewModel：全量拉取一次（作者量有界），体系胶囊/名字搜索/排序三项全客户端
 * （Web AuthorsPage A_SORTERS 同口径：默认=API 原序、浏览数降序、文件数降序）；
 * 关注 toggle 走 PUT /authors/{authorId}/follow，状态以服务端回包后的重拉为准。
 *
 * 作者管理族（2026-10-13，ADR-0024 修订 5）：新建（POST /authors，规范身份命中
 * 复用）/ 改名（PUT，身份随之迁移）/ 删除（DELETE，TXT 块 + 关联 + 行三清、
 * 文件保留）——成功即重拉列表（改名的 id 变化由重拉收敛，客户端不推断新 id）。
 * 失败（4xx 语义化拒绝）把服务端原因透出并保持对话框打开，用户可自助修正重试。
 */
@HiltViewModel
class AuthorViewModel @Inject constructor(
    private val authorRepository: AuthorRepository,
    val origUrlResolver: AssetOrigUrlResolver,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthorUiState())
    val uiState: StateFlow<AuthorUiState> = _uiState.asStateFlow()

    init {
        load(isInitial = true)
    }

    fun selectZone(zone: Zone) {
        _uiState.value = _uiState.value.copy(zone = zone)
    }

    fun onKeywordChange(keyword: String) {
        _uiState.value = _uiState.value.copy(keyword = keyword)
    }

    fun selectSort(sort: AuthorSortOption) {
        _uiState.value = _uiState.value.copy(sort = sort)
    }

    fun refresh() {
        load(isInitial = false, isRefresh = true)
    }

    /** 关注 toggle：先本地乐观翻转，失败回滚（服务端 followed 是真相） */
    fun toggleFollow(author: AuthorSummary) {
        val target = !author.followed
        _uiState.value = _uiState.value.copy(
            authors = _uiState.value.authors.map {
                if (it.id == author.id) it.copy(followed = target) else it
            },
        )
        viewModelScope.launch {
            runCatching {
                authorRepository.setFollowed(author.id, target)
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    errorMessage = FOLLOW_FAILED_MESSAGE,
                    authors = _uiState.value.authors.map {
                        if (it.id == author.id) it.copy(followed = author.followed) else it
                    },
                )
            }
        }
    }

    // ---- 作者管理族（新建/改名/删除；对话框状态机 + 成功重拉） ----

    fun openCreateDialog() {
        _uiState.value = _uiState.value.copy(
            manageDialog = AuthorManageDialog.Create, manageInput = "", errorMessage = null,
        )
    }

    fun openRenameDialog(author: AuthorSummary) {
        _uiState.value = _uiState.value.copy(
            manageDialog = AuthorManageDialog.Rename(author.id, author.displayName),
            manageInput = author.displayName,
            errorMessage = null,
        )
    }

    fun openDeleteDialog(author: AuthorSummary) {
        _uiState.value = _uiState.value.copy(
            manageDialog = AuthorManageDialog.Delete(author.id, author.displayName, author.fileCount ?: 0),
            errorMessage = null,
        )
    }

    /**
     * 对话框输入草稿：按协议 displayName maxLength 200 封顶（api/openapi.yaml
     * 三端点 displayName maxLength 双写；rune/码点口径与校验一致），超限整串拒收
     * 不截断（截断会产生用户没输入的中间态）。空白输入由确定钮禁用拦下。
     */
    fun onManageInputChange(text: String) {
        if (text.codePointCount(0, text.length) > AUTHOR_NAME_MAX_RUNES) return
        _uiState.value = _uiState.value.copy(manageInput = text)
    }

    /** 关闭对话框（操作在途时忽略，防手势关闭丢结果反馈）。 */
    fun dismissManageDialog() {
        if (_uiState.value.manageSubmitting) return
        _uiState.value = _uiState.value.copy(manageDialog = null, manageInput = "")
    }

    /** 对话框确定：按状态分派新建/改名/删除；非法态（空白名）静默忽略（钮已禁用兜底）。 */
    fun submitManageDialog() {
        val state = _uiState.value
        val dialog = state.manageDialog ?: return
        if (state.manageSubmitting) return
        when (dialog) {
            AuthorManageDialog.Create, is AuthorManageDialog.Rename -> {
                val name = state.manageInput.trim()
                if (name.isEmpty()) return
                val targetId = (dialog as? AuthorManageDialog.Rename)?.authorId
                submitName(renameAuthorId = targetId, name = name)
            }

            is AuthorManageDialog.Delete -> submitDelete(dialog)
        }
    }

    private fun submitName(renameAuthorId: String?, name: String) {
        _uiState.value = _uiState.value.copy(manageSubmitting = true, errorMessage = null)
        viewModelScope.launch {
            runCatching {
                if (renameAuthorId == null) {
                    authorRepository.createAuthor(name)
                } else {
                    authorRepository.renameAuthor(renameAuthorId, name)
                }
            }.onSuccess { author ->
                _uiState.value = _uiState.value.copy(
                    manageSubmitting = false,
                    manageDialog = null,
                    manageInput = "",
                    noticeMessage = if (renameAuthorId == null) {
                        "已添加作者：${author.displayName}"
                    } else {
                        "已改名：${author.displayName}"
                    },
                )
                load(isInitial = false)
            }.onFailure { e ->
                // 失败保持对话框与草稿（用户可改名字直接重按），原因透出
                _uiState.value = _uiState.value.copy(
                    manageSubmitting = false,
                    errorMessage = manageFailureMessage(e),
                )
            }
        }
    }

    private fun submitDelete(dialog: AuthorManageDialog.Delete) {
        _uiState.value = _uiState.value.copy(manageSubmitting = true, errorMessage = null)
        viewModelScope.launch {
            runCatching {
                authorRepository.deleteAuthor(dialog.authorId)
            }.onSuccess {
                _uiState.value = _uiState.value.copy(
                    manageSubmitting = false,
                    manageDialog = null,
                    noticeMessage = "已删除作者：${dialog.displayName}（关联文件已保留）",
                )
                load(isInitial = false)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    manageSubmitting = false,
                    errorMessage = manageFailureMessage(e),
                )
            }
        }
    }

    /** 管理操作失败文案：4xx 语义化拒绝直透服务端原因（自助修正），其余给通用文案。 */
    private fun manageFailureMessage(e: Throwable): String = when (e) {
        is AuthorManageRejectedException -> "$MANAGE_FAILED_PREFIX${e.serverMessage}"
        else -> MANAGE_FAILED_GENERIC
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun clearNotice() {
        _uiState.value = _uiState.value.copy(noticeMessage = null)
    }

    /** 展示行 = 先 filter 后 sort（ zone 胶囊 + 关键词 + 排序三项；纯函数 [applyAuthorRows] 单测锁定） */
    fun visibleRows(state: AuthorUiState = _uiState.value): List<AuthorSummary> =
        state.authors.applyAuthorRows(state.zone, state.keyword, state.sort)

    /**
     * 计数行「全部作者 · N 位」的 N（G2）：**全量作者数**——对齐 Web AuthorsPage page-head
     * 副行 `全部作者 · {authors.length}`（Web 计数不随体系/关键词过滤，运行时口径 2026-09-08
     * 复核）；UI 不内嵌计数规则（铁律 7）。
     */
    fun authorCount(state: AuthorUiState = _uiState.value): Int = state.authors.size

    private fun load(isInitial: Boolean, isRefresh: Boolean = false) {
        val state = _uiState.value
        if (state.isLoading) return
        _uiState.value = state.copy(isLoading = true, isRefreshing = isRefresh)
        viewModelScope.launch {
            runCatching {
                authorRepository.authors()
            }.onSuccess { authors ->
                _uiState.value = _uiState.value.copy(
                    authors = authors,
                    isLoading = false,
                    isRefreshing = false,
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isRefreshing = false,
                    errorMessage = LOAD_FAILED_MESSAGE,
                )
            }
        }
    }

    companion object {
        private const val LOAD_FAILED_MESSAGE = "作者列表加载失败，请下拉重试"
        private const val FOLLOW_FAILED_MESSAGE = "关注操作失败，请重试"
        private const val MANAGE_FAILED_PREFIX = "操作失败："
        private const val MANAGE_FAILED_GENERIC = "操作失败，请重试"

        /**
         * 作者名 rune（码点）上限：与 api/openapi.yaml 三端点 displayName
         * maxLength: 200 双写同步——协议侧改动须同步此处，反之亦然。
         */
        private const val AUTHOR_NAME_MAX_RUNES = 200
    }
}
