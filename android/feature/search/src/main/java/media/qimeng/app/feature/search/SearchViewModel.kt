package media.qimeng.app.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import media.qimeng.app.core.data.repository.MediaRepository
import media.qimeng.app.core.data.repository.SearchHistoryRepository
import media.qimeng.app.core.data.repository.AssetOrigUrlResolver
import media.qimeng.app.core.model.AssetQuery
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.model.NameSuggestion
import media.qimeng.app.core.model.SuggestionKind
import media.qimeng.app.core.model.Zone
import media.qimeng.app.core.model.sortedShortestFirst

/** 搜索页三层状态（规格书 §搜索页：空 / 补全 / 结果） */
enum class SearchPhase { EMPTY, SUGGEST, RESULT }

data class SearchUiState(
    val phase: SearchPhase = SearchPhase.EMPTY,
    val query: String = "",
    /** 空态的推荐搜索词（q 空 + recommend=true） */
    val recommendWords: List<NameSuggestion> = emptyList(),
    /** 搜索历史（客户端 DataStore，≤20 条去重最新在前） */
    val history: List<String> = emptyList(),
    /** 补全列表（从短到长 + 右侧类型徽标） */
    val suggestions: List<NameSuggestion> = emptyList(),
    /** 分区胶囊：缺省全部（显式传 includeCos=1——/assets 服务端缺省排除 COS，与历史页方向不同） */
    val partition: Zone = Zone.ALL,
    /** 类型：综合(null)/图片/动图/视频（无音频） */
    val mediaType: MediaKind? = null,
    val submittedQuery: String = "",
    val items: List<MediaAsset> = emptyList(),
    val nextCursor: String? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
)

/** 搜索页 ViewModel：补全防抖 + 历史记录 + 结果分页（q+筛选 AND 叠加，服务端全参数就绪） */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val searchHistoryRepository: SearchHistoryRepository,
    val origUrlResolver: AssetOrigUrlResolver,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var suggestJob: Job? = null

    /**
     * 结果代际号：查询词/分区/类型变化即递增。弱网下仓库响应可能乱序归位
     * （与相册页同族缺陷，2026-09-06 审查清偿补齐），请求发起时快照代际、
     * 响应落地前校验——旧代响应（含失败）一律丢弃，不再覆盖新查询态。
     * 读写都在 Main（viewModelScope 与状态更新同线程），无需原子类。
     */
    private var filterGeneration = 0

    init {
        loadRecommendWords()
        searchHistoryRepository.history
            .onEach { words -> _uiState.value = _uiState.value.copy(history = words) }
            .launchIn(viewModelScope)
    }

    /** 输入变化：空→空态；非空→补全态（防抖拉建议） */
    fun onQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
        suggestJob?.cancel()
        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(phase = SearchPhase.EMPTY, suggestions = emptyList())
            return
        }
        if (_uiState.value.phase == SearchPhase.EMPTY) {
            _uiState.value = _uiState.value.copy(phase = SearchPhase.SUGGEST)
        }
        suggestJob = viewModelScope.launch {
            delay(SUGGEST_DEBOUNCE_MS)
            runCatching {
                mediaRepository.suggestions(q = query.trim(), limit = SUGGEST_LIMIT, recommend = false)
            }.onSuccess { list ->
                // 只在仍处于补全态且词条未变时回填（防竞态旧词覆盖新词）
                val current = _uiState.value
                if (current.phase != SearchPhase.RESULT && current.query.trim() == query.trim()) {
                    _uiState.value = current.copy(suggestions = list.sortedShortestFirst())
                }
            }
        }
    }

    /** 提交搜索：记历史 + 切结果态 + 拉第一页 */
    fun submit(rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isEmpty()) return
        suggestJob?.cancel()
        filterGeneration += 1 // 新查询=新代际：在途旧查询响应一律作废
        _uiState.value = _uiState.value.copy(
            query = query,
            submittedQuery = query,
            phase = SearchPhase.RESULT,
        )
        viewModelScope.launch { searchHistoryRepository.record(query) }
        loadItems(cursor = null, append = false, isRefresh = false)
    }

    fun selectPartition(zone: Zone) {
        filterGeneration += 1 // 分区变化=新代际：在途旧结果作废（同 selectMediaType）
        _uiState.value = _uiState.value.copy(partition = zone)
        if (_uiState.value.phase == SearchPhase.RESULT) loadItems(null, append = false, isRefresh = false)
    }

    fun selectMediaType(kind: MediaKind?) {
        filterGeneration += 1
        _uiState.value = _uiState.value.copy(mediaType = kind)
        if (_uiState.value.phase == SearchPhase.RESULT) loadItems(null, append = false, isRefresh = false)
    }

    /** 结果态点搜索栏回补全态（规格书：可修改搜索词）；phase 由 query 非空自然落在 SUGGEST */
    fun backToSuggest() {
        val current = _uiState.value
        if (current.phase != SearchPhase.RESULT) return
        _uiState.value = current.copy(phase = if (current.query.isBlank()) SearchPhase.EMPTY else SearchPhase.SUGGEST)
        if (current.query.isNotBlank()) onQueryChange(current.query)
    }

    fun refresh() {
        if (_uiState.value.submittedQuery.isNotBlank()) {
            loadItems(null, append = false, isRefresh = true)
        } else {
            loadRecommendWords()
        }
    }

    fun onNearBottom() {
        val state = _uiState.value
        if (state.isLoading || state.nextCursor == null) return
        loadItems(state.nextCursor, append = true, isRefresh = false)
    }

    fun clearHistory() {
        viewModelScope.launch { searchHistoryRepository.clear() }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    private fun loadRecommendWords() {
        viewModelScope.launch {
            runCatching {
                mediaRepository.suggestions(q = "", limit = SUGGEST_LIMIT, recommend = true)
            }.onSuccess { list ->
                _uiState.value = _uiState.value.copy(recommendWords = list.sortedShortestFirst())
            }
        }
    }

    private fun loadItems(cursor: String?, append: Boolean, isRefresh: Boolean) {
        val state = _uiState.value
        // 防重语义（与代际防乱序正交）：翻页/下拉刷新在途时照旧丢弃重复触发；
        // 提交/切分区/切类型重载不受 isLoading 拦截——在途的是旧代请求，其响应会被代际校验丢弃
        if ((append || isRefresh) && state.isLoading) return
        val gen = filterGeneration
        _uiState.value = state.copy(isLoading = true, isRefreshing = isRefresh)
        viewModelScope.launch {
            runCatching {
                mediaRepository.assets(
                    AssetQuery(
                        cursor = cursor,
                        limit = PAGE_SIZE,
                        // 分区三态（缺省全部=显式 includeCos=1；常规=不传；COS=cosOnly=1）
                        includeCos = if (state.partition == Zone.ALL) true else null,
                        cosOnly = if (state.partition == Zone.COS) true else null,
                        mediaType = state.mediaType,
                        q = state.submittedQuery,
                    ),
                )
            }.onSuccess { page ->
                if (gen != filterGeneration) return@onSuccess // 旧代迟到响应，丢弃
                _uiState.value = _uiState.value.copy(
                    items = if (append) _uiState.value.items + page.items else page.items,
                    nextCursor = page.nextCursor,
                    isLoading = false,
                    isRefreshing = false,
                )
            }.onFailure {
                if (gen != filterGeneration) return@onFailure // 旧代失败不污染新查询态
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isRefreshing = false,
                    errorMessage = LOAD_FAILED_MESSAGE,
                )
            }
        }
    }

    companion object {
        /** 补全防抖（输入停顿 300ms 才出网；调度常量具名化） */
        const val SUGGEST_DEBOUNCE_MS = 300L

        /** 补全/推荐词条数上限（规格书 §搜索页 ≤10 条；协议 limit 缺省 10） */
        const val SUGGEST_LIMIT = 10

        /** 列表分页大小（协议 /assets 缺省 60） */
        const val PAGE_SIZE = 60

        private const val LOAD_FAILED_MESSAGE = "加载失败，请重试"
    }
}
