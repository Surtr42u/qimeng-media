package media.qimeng.app.feature.stats

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import media.qimeng.app.core.data.repository.MediaBatchIndex
import media.qimeng.app.core.data.repository.StatsRepository
import media.qimeng.app.core.model.DEFAULT_STATS_RANGE
import media.qimeng.app.core.model.MediaTypeKeys
import media.qimeng.app.core.model.MostViewedEntry
import media.qimeng.app.core.model.RankingEntry
import media.qimeng.app.core.model.SourceKeys
import media.qimeng.app.core.model.StatsOverviewValues
import media.qimeng.app.core.model.StatsRangeOption
import media.qimeng.app.core.model.TopAuthorEntry
import media.qimeng.app.core.model.TopTagEntry
import media.qimeng.app.core.model.apiRange
import media.qimeng.app.core.model.rankingsPeriod

data class TypeTrendSeries(
    val mediaType: String,
    val name: String,
    val values: List<Int>,
)

/** 分布统计一条类型库存（分布详情：overview 的 imageCount/videoCount/totalCount） */
data class TypeStockEntry(
    val name: String,
    val count: Int,
)

/** 详情各模式独立装载服务端结果；失败与真实空结果分开，报告零记录仍可呈现。 */
data class StatsDetailUiState(
    val review: media.qimeng.app.core.model.BrowsingReviewValues? = null,
    val loading: Boolean = true,
    val typeSeries: List<TypeTrendSeries> = emptyList(),
    /** 来源浏览趋势两系列（常规/COS；与 typeSeries 同一渲染封装） */
    val sourceSeries: List<TypeTrendSeries> = emptyList(),
    val trendLabels: List<String> = emptyList(),
    val distribution: List<TypeStockEntry> = emptyList(),
    /** 来源构成对比（常规/COS 库存；overview sourceCounts 派生） */
    val sourceDistribution: List<TypeStockEntry> = emptyList(),
    /** 常看文件 seconds 榜（窗口内 dwell 秒数累计倒序 Top 20） */
    val secondsRanking: List<MostViewedEntry> = emptyList(),
    /** 内容榜（GET /rankings 热度=浏览+播放+点赞累计倒序 Top 20；2026-09-18 批换源：
     * 原 most-viewed views 榜——「按热度」排序档数据源） */
    val contentRanking: List<RankingEntry> = emptyList(),
    /** 常看作者 Top15 */
    val topAuthors: List<TopAuthorEntry> = emptyList(),
    /** 常看标签 Top10 */
    val topTags: List<TopTagEntry> = emptyList(),
    /** 窗口浏览/播放/停留聚合（overview.windowTotals；摘要卡与洞察行数据源） */
    val windowViewCount: Int = 0,
    val windowPlayCount: Int = 0,
    val windowSeconds: Long = 0,
    /** overview(range)（平均浏览次数；MOST_VIEWED 摘要用）；失败 null */
    val overviewValues: StatsOverviewValues? = null,
    /** 窗口内有浏览记录的文件数（服务端 viewedFilesCount 精确值） */
    val filesWithViewRecords: Int = 0,
    /** 分布模式窗口浏览（open 浏览次数；key=mediaType/source 协议值） */
    val typeWindowViews: Map<String, Int> = emptyMap(),
    val sourceWindowViews: Map<String, Int> = emptyMap(),
    val loadFailed: Boolean = false,
    val errorMessage: String? = null,
) {
    /** 数据全空（加载完且无任何可展示内容）→ 空态文案 */
    val isEmpty: Boolean
        get() = !loading && !loadFailed && review == null &&
            typeSeries.all { it.values.isEmpty() } &&
            sourceSeries.all { it.values.isEmpty() } &&
            distribution.isEmpty() && sourceDistribution.isEmpty() &&
            secondsRanking.isEmpty() && contentRanking.isEmpty() &&
            topAuthors.isEmpty() && topTags.isEmpty()
}


/** 详情页只装配服务端统计；不在客户端反推数量或选择分桶粒度。 */
@HiltViewModel
class StatsDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val statsRepository: StatsRepository,
    private val batchIndex: MediaBatchIndex,
) : ViewModel() {
    val mode: StatsDetailMode = savedStateHandle.get<String>(StatsDetailRoutes.KEY_MODE)
        ?.let { runCatching { StatsDetailMode.valueOf(it) }.getOrNull() } ?: StatsDetailMode.TYPE_TREND
    val range: StatsRangeOption = savedStateHandle.get<String>(StatsDetailRoutes.KEY_RANGE)
        ?.let { runCatching { StatsRangeOption.valueOf(it) }.getOrNull() } ?: DEFAULT_STATS_RANGE
    private val _uiState = MutableStateFlow(StatsDetailUiState())
    val uiState: StateFlow<StatsDetailUiState> = _uiState.asStateFlow()
    private val _filesSortByHeat = MutableStateFlow(true)
    val filesSortByHeat: StateFlow<Boolean> = _filesSortByHeat.asStateFlow()
    private var requestJob: Job? = null
    private var requestId = 0L

    init { retry() }

    fun retry() {
        requestJob?.cancel()
        val generation = ++requestId
        _uiState.value = _uiState.value.copy(loading = true, loadFailed = false, errorMessage = null)
        requestJob = viewModelScope.launch {
            try {
                val result = when (mode) {
                    StatsDetailMode.REVIEW -> StatsDetailUiState(loading = false, review = statsRepository.browsingReview(range.apiRange))
                    StatsDetailMode.TYPE_TREND -> loadTypeTrend()
                    StatsDetailMode.MOST_VIEWED -> loadMostViewed()
                    StatsDetailMode.AUTHORS_TAGS -> loadAuthorsTags()
                    StatsDetailMode.DISTRIBUTION -> loadDistribution()
                }
                if (generation == requestId) _uiState.value = result
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (generation == requestId) _uiState.value = _uiState.value.copy(
                    loading = false, loadFailed = true, errorMessage = statsFailureMessage(error),
                )
            }
        }
    }

    private suspend fun loadTypeTrend(): StatsDetailUiState {
        // 一次响应的每个桶都含三类型/两来源，所有系列共用服务端 start/end。
        val points = statsRepository.trends(range.apiRange)
        return StatsDetailUiState(
            loading = false, trendLabels = points.map { it.label },
            typeSeries = if (points.isEmpty()) emptyList() else TYPE_KEYS.map { key ->
                TypeTrendSeries(key, mediaTypeDisplayName(key), points.map { it.mediaTypes.getValue(key).viewCount })
            },
            sourceSeries = if (points.isEmpty()) emptyList() else SOURCE_KEYS.map { key ->
                TypeTrendSeries(key, sourceDisplayName(key), points.map { it.sources.getValue(key).viewCount })
            },
        )
    }

    private suspend fun loadMostViewed(): StatsDetailUiState = coroutineScope {
        val overview = async { statsRepository.overview(range.apiRange) }
        val seconds = async { statsRepository.mostViewed(range.apiRange, METRIC_SECONDS, RANKING_LIMIT_MOST_VIEWED) }
        val content = async { statsRepository.rankings(range.rankingsPeriod, RANKING_LIMIT_MOST_VIEWED) }
        val values = overview.await()
        baseState(values).copy(secondsRanking = seconds.await(), contentRanking = content.await())
    }

    private suspend fun loadAuthorsTags(): StatsDetailUiState = coroutineScope {
        val overview = async { statsRepository.overview(range.apiRange) }
        val authors = async { statsRepository.topAuthors(range.apiRange, RANKING_LIMIT_AUTHORS) }
        val tags = async { statsRepository.topTags(range.apiRange, RANKING_LIMIT_TAGS) }
        baseState(overview.await()).copy(topAuthors = authors.await(), topTags = tags.await())
    }

    private suspend fun loadDistribution(): StatsDetailUiState {
        val values = statsRepository.overview(range.apiRange)
        return baseState(values).copy(
            distribution = listOf(TypeStockEntry(IMAGE_DISPLAY_NAME, values.stillImageCount),
                TypeStockEntry(VIDEO_DISPLAY_NAME, values.videoCount), TypeStockEntry(ANIMATED_DISPLAY_NAME, values.animatedImageCount)),
            sourceDistribution = listOf(TypeStockEntry(SOURCE_NORMAL_DISPLAY_NAME, values.sourceNormalCount),
                TypeStockEntry(SOURCE_COS_DISPLAY_NAME, values.sourceCosCount)),
            typeWindowViews = values.mediaTypeWindowTotals.mapValues { it.value.viewCount },
            sourceWindowViews = values.sourceWindowTotals.mapValues { it.value.viewCount },
        )
    }

    private fun baseState(values: StatsOverviewValues) = StatsDetailUiState(
        loading = false, overviewValues = values, filesWithViewRecords = values.viewedFilesCount,
        windowViewCount = values.windowTotals.viewCount, windowPlayCount = values.windowTotals.playCount,
        windowSeconds = values.windowTotals.seconds.toLong(),
    )

    fun toggleFilesSort() { _filesSortByHeat.value = !_filesSortByHeat.value }
    fun enterDetail(assetId: String) {
        batchIndex.ids = if (filesSortByHeat.value) _uiState.value.contentRanking.map { it.assetId }
            else _uiState.value.secondsRanking.map { it.assetId }
    }
    internal fun mediaTypeDisplayName(mediaType: String): String = when (mediaType) {
        MediaTypeKeys.IMAGE -> IMAGE_DISPLAY_NAME
        MediaTypeKeys.VIDEO -> VIDEO_DISPLAY_NAME
        MediaTypeKeys.ANIMATED_IMAGE -> ANIMATED_DISPLAY_NAME
        else -> mediaType
    }
    internal fun sourceDisplayName(source: String): String = when (source) {
        SourceKeys.NORMAL -> SOURCE_NORMAL_DISPLAY_NAME
        SourceKeys.COS -> SOURCE_COS_DISPLAY_NAME
        else -> source
    }
    private companion object {
        val TYPE_KEYS = listOf(MediaTypeKeys.IMAGE, MediaTypeKeys.VIDEO, MediaTypeKeys.ANIMATED_IMAGE)
        val SOURCE_KEYS = listOf(SourceKeys.NORMAL, SourceKeys.COS)
        const val METRIC_SECONDS = "seconds"
        const val RANKING_LIMIT_MOST_VIEWED = 20
        const val RANKING_LIMIT_AUTHORS = 15
        const val RANKING_LIMIT_TAGS = 10
    }
}

internal const val IMAGE_DISPLAY_NAME = "图片"
internal const val VIDEO_DISPLAY_NAME = "视频"
internal const val ANIMATED_DISPLAY_NAME = "动图"
internal const val OTHER_DISPLAY_NAME = "其他"
internal const val SOURCE_NORMAL_DISPLAY_NAME = "常规"
internal const val SOURCE_COS_DISPLAY_NAME = "COS"
