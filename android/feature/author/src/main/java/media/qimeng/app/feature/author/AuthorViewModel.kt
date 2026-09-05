package media.qimeng.app.feature.author

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import media.qimeng.app.core.data.repository.AuthorRepository
import media.qimeng.app.core.data.repository.AssetOrigUrlResolver
import media.qimeng.app.core.model.AuthorSortOption
import media.qimeng.app.core.model.AuthorSummary
import media.qimeng.app.core.model.Zone
import media.qimeng.app.core.model.applyAuthorRows

data class AuthorUiState(
    val authors: List<AuthorSummary> = emptyList(),
    /** 作者管理胶囊：全部/常规/COS（客户端筛选——GET /authors 无参数全量数组） */
    val zone: Zone = Zone.ALL,
    val keyword: String = "",
    val sort: AuthorSortOption = AuthorSortOption.DEFAULT,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * 作者页 ViewModel：全量拉取一次（作者量有界），体系胶囊/名字搜索/排序三项全客户端
 * （Web AuthorsPage A_SORTERS 同口径：默认=API 原序、浏览数降序、文件数降序）；
 * 关注 toggle 走 PUT /authors/{authorId}/follow，状态以服务端回包后的重拉为准。
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

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    /** 展示行 = 先 filter 后 sort（ zone 胶囊 + 关键词 + 排序三项；纯函数 [applyAuthorRows] 单测锁定） */
    fun visibleRows(state: AuthorUiState = _uiState.value): List<AuthorSummary> =
        state.authors.applyAuthorRows(state.zone, state.keyword, state.sort)

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
    }
}
