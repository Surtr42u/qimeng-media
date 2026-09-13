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
import media.qimeng.app.core.data.repository.AssetOrigUrlResolver
import media.qimeng.app.core.data.repository.MediaBatchIndex
import media.qimeng.app.core.data.repository.MediaRepository
import media.qimeng.app.core.data.repository.SearchHistoryRepository
import media.qimeng.app.core.model.AssetQuery
import media.qimeng.app.core.model.LIST_PAGE_SIZE
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.NameSuggestion
import media.qimeng.app.core.model.sortedShortestFirst

/** 搜索页三态（旧版实录 search_entry/suggest/results：入口 / 建议 / 结果） */
enum class SearchPhase { EMPTY, SUGGEST, RESULT }

data class SearchUiState(
    val phase: SearchPhase = SearchPhase.EMPTY,
    val query: String = "",
    /** 入口态的推荐搜索词（q 空 + recommend=true） */
    val recommendWords: List<NameSuggestion> = emptyList(),
    /** 搜索历史（客户端 DataStore，≤20 条去重最新在前） */
    val history: List<String> = emptyList(),
    /** 建议列表（从短到长 + 右侧类型徽标） */
    val suggestions: List<NameSuggestion> = emptyList(),
    val submittedQuery: String = "",
    val items: List<MediaAsset> = emptyList(),
    val nextCursor: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    /**
     * 深链标记（修复E，2026-09-14）：携词跳转（SearchScreen 的 LaunchedEffect(initialQuery)
     * 经 [submitFromDeepLink]）置位。UI 返回分发据此三分支——深链结果态（词未变）返回直接
     * 退页；手动搜索（[submit] 直调）不置位、清词回入口语义保留。置位后在 [handleBack]
     * 清词时一并清除（深链会话随返回终止；之后的手动搜索回归旧语义）。
     */
    val fromDeepLink: Boolean = false,
)

/**
 * 搜索页 ViewModel（M4-2A-B4 对齐旧版三态，实录 search_entry/suggest/results 逐字口径）：
 * 补全防抖 + 历史记录 + 结果分页。
 * 查询=纯 q 检索固定 includeCos=true（旧版搜索范围=合并常规+COS，GUIDE_UI §首页「搜索范围」）；
 * 分区/类型筛选已删——标签等筛选由 B3 万能面板承接，搜索按旧版不做筛选。
 * 返回链镜像旧版 [handleBack]（系统返回与左上箭头同链）；「点搜索栏」是另一条路径
 * （词保留回建议态），由 [backToSuggest] 承接。
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val searchHistoryRepository: SearchHistoryRepository,
    private val batchIndex: MediaBatchIndex,
    val origUrlResolver: AssetOrigUrlResolver,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var suggestJob: Job? = null

    /**
     * 结果代际号：查询变化（submit）即递增。弱网下仓库响应可能乱序归位（与相册页同族缺陷，
     * 2026-09-06 审查清偿补齐），请求发起时快照代际、响应落地前校验——旧代响应（含失败）
     * 一律丢弃，不再覆盖新查询态。读写都在 Main（viewModelScope 与状态更新同线程），无需原子类。
     */
    private var queryGeneration = 0

    /**
     * 结果网格列数（页内存态，不持久化）。按旧仓库源码判读（B4-round1 P2-1）：搜索页
     * PinchZoomHelper.setup 未传 onColumnsChanged（SearchFragment L240，对比相册/作者页均传）
     * → 列数从不落盘；columnsRef = ColumnsRef(3)（L90）每次进搜索页重置 3 列起步。
     * Compose 侧每次进入搜索路由=新 VM 实例，自然获得同样的「进页重置」。
     */
    private val _gridColumns = MutableStateFlow(SEARCH_DEFAULT_COLUMNS)
    val gridColumns: StateFlow<Int> = _gridColumns.asStateFlow()

    /** 双指缩放期间的瞬时列数（null=无进行中手势，展示 [gridColumns]；步进不直接改已提交值） */
    private val _pinchColumns = MutableStateFlow<Int?>(null)
    val pinchColumns: StateFlow<Int?> = _pinchColumns.asStateFlow()

    init {
        loadRecommendWords()
        searchHistoryRepository.history
            .onEach { words -> _uiState.value = _uiState.value.copy(history = words) }
            .launchIn(viewModelScope)
    }

    /** 输入变化：空→入口态；非空→建议态（防抖拉建议；结果态直接改词=旧版 TextWatcher 语义切回建议态） */
    fun onQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
        suggestJob?.cancel()
        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(phase = SearchPhase.EMPTY, suggestions = emptyList())
            return
        }
        if (_uiState.value.phase != SearchPhase.SUGGEST) {
            _uiState.value = _uiState.value.copy(phase = SearchPhase.SUGGEST)
        }
        suggestJob = viewModelScope.launch {
            delay(SUGGEST_DEBOUNCE_MS)
            runCatching {
                mediaRepository.suggestions(q = query.trim(), limit = SUGGEST_LIMIT, recommend = false)
            }.onSuccess { list ->
                // 只在仍处于建议态且词条未变时回填（防竞态旧词覆盖新词）
                val current = _uiState.value
                if (current.phase != SearchPhase.RESULT && current.query.trim() == query.trim()) {
                    _uiState.value = current.copy(suggestions = list.sortedShortestFirst())
                }
            }
        }
    }

    /** 提交搜索（按钮/IME/词丸/建议行共用）：记历史 + 切结果态 + 拉第一页；空词不生效 */
    fun submit(rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isEmpty()) return
        suggestJob?.cancel()
        queryGeneration += 1 // 新查询=新代际：在途旧查询响应一律作废
        _uiState.value = _uiState.value.copy(
            query = query,
            submittedQuery = query,
            phase = SearchPhase.RESULT,
            suggestions = emptyList(), // 结果态只展示网格；回建议态时按词重拉
        )
        viewModelScope.launch { searchHistoryRepository.record(query) }
        loadItems(cursor = null, append = false)
    }

    /**
     * 深链携词提交（修复E，2026-09-14；提交点=SearchScreen 的 LaunchedEffect(initialQuery)，
     * 不与按钮/IME/词丸共用的 [submit] 混线）：置深链标记后按词提交。
     * 依据=用户反馈「标签深链搜索页返回多一层」+旧版搜索是常驻 tab（清词回入口即回到 tab
     * 本体）、新版是覆盖页——深链进页被清词后只剩一个空搜索页，返回再「回入口态」毫无意义，
     * 应直接退页；手动搜索的清词回入口语义保留（=旧版逐字）。
     */
    fun submitFromDeepLink(rawQuery: String) {
        _uiState.value = _uiState.value.copy(fromDeepLink = true)
        submit(rawQuery)
    }

    /**
     * 返回（系统返回 + 左上箭头共用，镜像旧版 handleBack，SearchFragment L253-270）：
     * 结果/建议态一律清词回入口态（旧版 showState(STATE_EMPTY) 前无条件 setText("")）
     * 并重拉推荐词；结果残留（submittedQuery/items/nextCursor）一并清空，回 pristine 入口；
     * 入口态不动作——退页由 UI 层调 onBack（壳层 popBackStack）承接。
     * 修复E：深链标记在此一并清除——清词回入口=深链会话终止（UI 三分支在深链结果态已
     * 直接退页、不会走到本方法的深链场景，能走到即用户已改词/手动操作），之后的手动搜索
     * 回归旧语义。
     */
    fun handleBack() {
        val current = _uiState.value
        if (current.phase == SearchPhase.EMPTY) return
        suggestJob?.cancel()
        _uiState.value = current.copy(
            phase = SearchPhase.EMPTY,
            query = "",
            suggestions = emptyList(),
            submittedQuery = "",
            items = emptyList(),
            nextCursor = null,
            fromDeepLink = false,
        )
        loadRecommendWords()
    }

    /**
     * 结果态「点搜索栏」回建议态（词保留可改；GUIDE_UI §搜索页「结果状态下点击搜索栏：
     * 切回补全状态」=点击路径，非返回键路径——返回键一律走 [handleBack] 清词）
     */
    fun backToSuggest() {
        val current = _uiState.value
        if (current.phase != SearchPhase.RESULT) return
        _uiState.value = current.copy(phase = if (current.query.isBlank()) SearchPhase.EMPTY else SearchPhase.SUGGEST)
        if (current.query.isNotBlank()) onQueryChange(current.query)
    }

    fun onNearBottom() {
        val state = _uiState.value
        if (state.isLoading || state.nextCursor == null) return
        loadItems(state.nextCursor, append = true)
    }

    /**
     * 进详情前的批次上下文写入（RES R3 补齐 N1 范式，清偿 SearchScreen D3 注释挂账）：
     * 「已加载 = 当前显示清单」口径——结果态 items（含翻页追加件，显示顺序）即整页显示，
     * 快照式整体替换 [MediaBatchIndex.ids]，详情页据此得「i / N」序号与滑动切换
     * （调用点在 SearchScreen 卡片点击，先写批次再交壳层导航；空态/建议态点不到卡片，
     * items 恒空表=空批次语义，无需按 phase 分支）。
     */
    fun enterDetail(assetId: String) {
        batchIndex.ids = _uiState.value.items.map { it.id }
    }

    fun clearHistory() {
        viewModelScope.launch { searchHistoryRepository.clear() }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    /** 双指缩放步进（delta=±1：放大减列、缩小加列），clamp 2..5（GUIDE_UI §搜索页「双指缩放列数 2-5 列」） */
    fun adjustColumnsLive(delta: Int) {
        val current = _pinchColumns.value ?: _gridColumns.value
        _pinchColumns.value = (current + delta).coerceIn(SEARCH_MIN_COLUMNS, SEARCH_MAX_COLUMNS)
    }

    /** 手势结束：瞬时列数落为本页内存态（仅本页生效；搜索列数不持久化，依据见 [gridColumns] KDoc） */
    fun commitPinchColumns() {
        val target = _pinchColumns.value ?: return
        _pinchColumns.value = null
        _gridColumns.value = target
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

    private fun loadItems(cursor: String?, append: Boolean) {
        val state = _uiState.value
        // 防重语义（与代际防乱序正交）：翻页在途时丢弃重复触发；
        // 提交重载不受 isLoading 拦截——在途的是旧代请求，其响应会被代际校验丢弃
        if (append && state.isLoading) return
        val gen = queryGeneration
        _uiState.value = state.copy(isLoading = true)
        viewModelScope.launch {
            runCatching {
                mediaRepository.assets(
                    AssetQuery(
                        cursor = cursor,
                        limit = LIST_PAGE_SIZE,
                        // 旧版搜索=合并常规+COS（GUIDE_UI §首页搜索范围），固定 includeCos=1
                        includeCos = true,
                        q = state.submittedQuery,
                    ),
                )
            }.onSuccess { page ->
                if (gen != queryGeneration) return@onSuccess // 旧代迟到响应，丢弃
                _uiState.value = _uiState.value.copy(
                    items = if (append) _uiState.value.items + page.items else page.items,
                    nextCursor = page.nextCursor,
                    isLoading = false,
                )
            }.onFailure {
                if (gen != queryGeneration) return@onFailure // 旧代失败不污染新查询态
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
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

        // 分页大小不再本地定义：共享常量 core/model LIST_PAGE_SIZE（协议 /assets
        // 缺省 60，2026-09-07 审查 P3 与相册/收藏/历史同款单源）。

        /** 结果网格缺省列数（旧版 columnsRef = ColumnsRef(3)：每次进搜索页重置 3 列起步，SearchFragment L90） */
        const val SEARCH_DEFAULT_COLUMNS = 3

        /** 列数 clamp 下/上界（GUIDE_UI §搜索页「支持双指缩放列数 2-5 列」） */
        const val SEARCH_MIN_COLUMNS = 2
        const val SEARCH_MAX_COLUMNS = 5

        /** 搜索页无下拉刷新，失败文案与列表页通用款不同（「请重试」非「请下拉重试」），保留本地 */
        private const val LOAD_FAILED_MESSAGE = "加载失败，请重试"
    }
}
