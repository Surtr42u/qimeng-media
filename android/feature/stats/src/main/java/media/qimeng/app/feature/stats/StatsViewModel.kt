package media.qimeng.app.feature.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import media.qimeng.app.core.model.STATS_LOAD_FAILED_MESSAGE
import media.qimeng.app.core.model.STATS_PROTOCOL_UPDATE_MESSAGE
import media.qimeng.app.core.data.repository.MediaBatchIndex
import media.qimeng.app.core.model.DiscoveryEntry
import media.qimeng.app.core.data.repository.StatsRepository
import media.qimeng.app.core.model.DEFAULT_STATS_RANGE
import media.qimeng.app.core.model.StatsOverviewValues
import media.qimeng.app.core.model.StatsRangeOption
import media.qimeng.app.core.model.TopAuthorEntry
import media.qimeng.app.core.model.TopTagEntry
import media.qimeng.app.core.model.TrendPoint
import media.qimeng.app.core.model.apiRange

/** 数字卡/常看卡共享的 Top 条数（GUIDE_UI §数据统计页「紧凑文本列表 Top 3」） */
internal const val TOP_CARD_LIMIT = 3

/**
 * 常看作者/标签混合卡单条（任务J J1 详情页跳转链，GUIDE_UI L218-224）：条目可点击——
 * AUTHOR → 作者集合页（[id] = authorId 取数键，真实 id 来自 /stats/top-authors 响应）；
 * TAG → 搜索页携词（[id] = 标签名，与展示名同值）。视图层按 kind 分发，不在此耦合导航。
 */
data class TopAuthorTagEntry(
    val kind: Kind,
    val id: String,
    val name: String,
    val views: Int,
) {
    enum class Kind { AUTHOR, TAG }
}

/** 统计页面状态：所有窗口值取服务端 overview，趋势同轴；失败单独展示并可重试。 */
data class StatsUiState(
    val overview: StatsOverviewValues? = null,
    val loadFailed: Boolean = false,
    val errorMessage: String? = null,
    val overviewLoading: Boolean = true,
    val selectedRange: StatsRangeOption = DEFAULT_STATS_RANGE,
    val trends: List<TrendPoint> = emptyList(),
    val trendsLoading: Boolean = true,
    val discoveries: List<DiscoveryEntry> = emptyList(),
    val discoveriesLoading: Boolean = true,
    val discoveriesError: String? = null,
    /** 常看作者卡数据源（详情页 Top15 亦用本通道，主页面只取前 3 混排） */
    val topAuthors: List<TopAuthorEntry> = emptyList(),
    /** 常看标签卡数据源 */
    val topTags: List<TopTagEntry> = emptyList(),
    /** 平均浏览次数（窗口联动值；加载中 null+loading、失败/分母 0 → null） */
    val avgViewsPerFile: Double? = null,
    val avgViewsLoading: Boolean = true,
) {
    /** 趋势空态（规格 §数据统计页：空态文案「暂无趋势数据」） */
    val trendsEmpty: Boolean get() = !loadFailed && !trendsLoading && trends.isEmpty()

    /** 常看作者与标签卡空态 */
    val topAuthorsTagsEmpty: Boolean get() = !loadFailed && !trendsLoading && topAuthors.isEmpty() && topTags.isEmpty()

    /** 窗口总浏览次数（服务端窗口汇总，随档位联动） */
    val windowViews: Long get() = overview?.windowTotals?.viewCount?.toLong() ?: 0L

    /** 窗口总播放次数（服务端窗口汇总） */
    val windowPlays: Long get() = overview?.windowTotals?.playCount?.toLong() ?: 0L

    /** 窗口总浏览时长秒数（服务端窗口汇总） */
    val windowSeconds: Long get() = overview?.windowTotals?.seconds?.toLong() ?: 0L

    /**
     * 常看作者与标签混合 Top3（GUIDE_UI §数据统计页「作者/标签按窗口浏览聚合混合 Top 3」）：
     * 作者/标签各转可点击条目（[TopAuthorTagEntry]，J1 跳转链），按 views 降序混排取前 3
     * （稳定排序：同分作者在前）。
     */
    val topAuthorsTagsMixed: List<TopAuthorTagEntry>
        get() = (
            topAuthors.map {
                TopAuthorTagEntry(TopAuthorTagEntry.Kind.AUTHOR, it.authorId, it.displayName, it.views)
            } + topTags.map {
                TopAuthorTagEntry(TopAuthorTagEntry.Kind.TAG, it.tag, it.tag, it.views)
            }
            ).sortedByDescending { it.views }
            .take(TOP_CARD_LIMIT)


}

/** 并发读取同档统计；取消/序号校验防迟到覆盖，不把接口失败伪装成零值。 */
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val statsRepository: StatsRepository,
    private val batchIndex: MediaBatchIndex,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StatsUiState())
    val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()

    /** 窗口请求序号（防覆盖判定：只接受最新序号的响应；趋势/常看族/均值共用一通道） */
    private var windowRequestId: Long = 0L

    private var requestJob: Job? = null

    private var discoveryJob: Job? = null
    private var discoveryRequestId = 0L
    private var discoverySeed = System.currentTimeMillis()
    private var hasBeenVisible = false

    init { loadRange(DEFAULT_STATS_RANGE); changeDiscoveries() }

    fun onVisible() {
        if (hasBeenVisible) changeDiscoveries() else hasBeenVisible = true
    }

    /** 独立于统计窗口；新请求取消旧请求，拒绝迟到覆盖，失败保留上一组并提示重试。 */
    fun changeDiscoveries() {
        discoveryJob?.cancel()
        val requestId = ++discoveryRequestId
        discoverySeed = maxOf(System.currentTimeMillis(), discoverySeed + 1)
        val seed = discoverySeed
        _uiState.update { it.copy(discoveriesLoading = true, discoveriesError = null) }
        discoveryJob = viewModelScope.launch {
            try {
                val items = statsRepository.discoveries(seed, DISCOVERY_LIMIT)
                if (requestId == discoveryRequestId) _uiState.update { it.copy(discoveries = items, discoveriesLoading = false) }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (requestId == discoveryRequestId) _uiState.update { it.copy(discoveriesLoading = false, discoveriesError = "发现加载失败，请重试") }
            }
        }
    }

    fun selectRange(option: StatsRangeOption) {
        if (_uiState.value.selectedRange == option && !_uiState.value.loadFailed && !_uiState.value.trendsLoading) return
        loadRange(option)
    }

    fun retry() = loadRange(_uiState.value.selectedRange)

    private fun loadRange(option: StatsRangeOption) {
        requestJob?.cancel()
        val requestId = ++windowRequestId
        _uiState.update { it.copy(selectedRange = option, overviewLoading = true, trendsLoading = true,
            avgViewsLoading = true, loadFailed = false, errorMessage = null) }
        requestJob = viewModelScope.launch {
            try {
                val result = coroutineScope {
                val overview = async { statsRepository.overview(option.apiRange) }
                val trends = async { statsRepository.trends(option.apiRange) }
                val authors = async { statsRepository.topAuthors(option.apiRange, TOP_CARD_LIMIT) }
                val tags = async { statsRepository.topTags(option.apiRange, TOP_CARD_LIMIT) }
                _uiState.value.copy(
                    selectedRange = option, overview = overview.await(), trends = trends.await(),
                    topAuthors = authors.await(), topTags = tags.await(),
                    overviewLoading = false, trendsLoading = false, avgViewsLoading = false,
                    avgViewsPerFile = overview.await().avgViewsPerFile,
                )
                }
                if (requestId == windowRequestId) _uiState.update { current -> current.copy(
                    selectedRange = result.selectedRange, overview = result.overview, trends = result.trends,
                    topAuthors = result.topAuthors, topTags = result.topTags,
                    overviewLoading = false, trendsLoading = false, avgViewsLoading = false,
                    avgViewsPerFile = result.avgViewsPerFile, loadFailed = false, errorMessage = null,
                ) }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (requestId == windowRequestId) _uiState.update { it.copy(
                    overviewLoading = false, trendsLoading = false, avgViewsLoading = false,
                    loadFailed = true, errorMessage = statsFailureMessage(error),
                ) }
            }
        }
    }

    /** 发现组作为详情批次；导航期间保留当前响应的身份与顺序。 */
    fun enterDetail(assetId: String) {
        batchIndex.ids = _uiState.value.discoveries.map { it.assetId }
    }
}

private const val DISCOVERY_LIMIT = 6

internal fun statsFailureMessage(error: Exception): String =
    if (error.message == STATS_PROTOCOL_UPDATE_MESSAGE) STATS_PROTOCOL_UPDATE_MESSAGE else STATS_LOAD_FAILED_MESSAGE
