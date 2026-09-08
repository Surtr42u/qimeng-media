package media.qimeng.app.feature.author

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import media.qimeng.app.core.data.repository.AssetOrigUrlResolver
import media.qimeng.app.core.data.repository.MediaRepository
import media.qimeng.app.core.model.AssetQuery
import media.qimeng.app.core.model.LIST_LOAD_FAILED_MESSAGE
import media.qimeng.app.core.model.LIST_PAGE_SIZE
import media.qimeng.app.core.model.MediaAsset

/** 作者集合页聚合状态（cursor 分页字段族与列表族页面同构） */
data class AuthorCollectionUiState(
    val items: List<MediaAsset> = emptyList(),
    val nextCursor: String? = null,
    /** 列表首响的服务端总计数（页头「作者 · N 个文件」的 N 缺省源） */
    val totalMatched: Int? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * 作者集合页 ViewModel（任务G G1b，Web CollectionPage author 分支的 Android 等价物——精简版）：
 * GET /assets authorId 精确过滤 + cursor 分页（真实库可能几千件，复用列表族分页链路）+
 * 下拉刷新。includeCos 恒 true：集合页语义 = 该作者名下全部文件，不受 /assets 缺省排除
 * COS 影响（COS 作者的文件如不显式包含，列表恒空——Web CollectionPage 同口径注释）。
 * 无四维筛选/排序切换（Web collection 页有胶囊栏，本批精简不做，交付报告记差异）。
 * 路由参数：authorId（取数键）+ authorName（标题展示，不参与数据定位）。
 */
@HiltViewModel
class AuthorCollectionViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    val origUrlResolver: AssetOrigUrlResolver,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthorCollectionUiState())
    val uiState: StateFlow<AuthorCollectionUiState> = _uiState.asStateFlow()

    /** 路由参数（键单源在 [AuthorCollectionRoutes]）；id 缺参 = 空态（深链容错，不崩溃） */
    private val authorId: String? = savedStateHandle[AuthorCollectionRoutes.KEY_AUTHOR_ID]

    /** 页标题（URL 解码后的原始名；缺参回退空串由 UI 兜底文案） */
    val authorName: String = savedStateHandle[AuthorCollectionRoutes.KEY_AUTHOR_NAME] ?: ""

    init {
        load(cursor = null, append = false)
    }

    fun onNearBottom() {
        val state = _uiState.value
        if (state.isLoading || state.nextCursor == null) return
        load(cursor = state.nextCursor, append = true)
    }

    fun refresh() {
        load(cursor = null, append = false, isRefresh = true)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    private fun load(cursor: String?, append: Boolean, isRefresh: Boolean = false) {
        val id = authorId ?: return // 缺参（异常深链）：不发请求，UI 走空态
        val state = _uiState.value
        if ((append || isRefresh) && state.isLoading) return
        _uiState.value = state.copy(isLoading = true, isRefreshing = isRefresh)
        viewModelScope.launch {
            runCatching {
                mediaRepository.assets(
                    AssetQuery(
                        cursor = cursor,
                        limit = LIST_PAGE_SIZE,
                        authorId = id,
                        includeCos = true,
                    ),
                )
            }.onSuccess { page ->
                _uiState.value = _uiState.value.copy(
                    items = if (append) _uiState.value.items + page.items else page.items,
                    nextCursor = page.nextCursor,
                    totalMatched = page.totalMatched,
                    isLoading = false,
                    isRefreshing = false,
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isRefreshing = false,
                    errorMessage = LIST_LOAD_FAILED_MESSAGE,
                )
            }
        }
    }
}
