package media.qimeng.app.feature.stats

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.qimeng.app.core.data.repository.StatsRepository
import media.qimeng.app.core.model.DEFAULT_STATS_RANGE
import media.qimeng.app.core.model.MostViewedEntry
import media.qimeng.app.core.model.StatsOverviewValues
import media.qimeng.app.core.model.StatsRangeOption
import media.qimeng.app.core.model.TopAuthorEntry
import media.qimeng.app.core.model.TopTagEntry
import media.qimeng.app.core.model.apiRange

/**
 * 分类型趋势的一条系列（统计详情页「类型浏览趋势」卡）：按协议 /stats/trends 的 mediaType
 * 单值参数逐类型取数拼接（REPLICATION_GAPS §3.3 裁定 6），labels 与各系列 values 下标对齐。
 */
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

/**
 * 统计详情页 UI 状态（四模式各自取数；空态「暂无数据」= GUIDE_UI L247）。
 * N4 I3b：常看文件（seconds 榜）/常看作者标签（双排行卡）两模式解冻接线；
 * 来源浏览趋势（normal/cos 双系列）与分布来源构成（overview sourceCounts）补全。
 */
data class StatsDetailUiState(
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
    /** 常看作者 Top15 */
    val topAuthors: List<TopAuthorEntry> = emptyList(),
    /** 常看标签 Top10 */
    val topTags: List<TopTagEntry> = emptyList(),
    val loadFailed: Boolean = false,
) {
    /** 数据全空（加载完且无任何可展示内容）→ 空态文案 */
    val isEmpty: Boolean
        get() = !loading && !loadFailed &&
            typeSeries.all { it.values.isEmpty() } &&
            sourceSeries.all { it.values.isEmpty() } &&
            distribution.isEmpty() && sourceDistribution.isEmpty() &&
            secondsRanking.isEmpty() && topAuthors.isEmpty() && topTags.isEmpty()
}

/**
 * 统计详情页 ViewModel（GUIDE_UI §统计详情页 L225-234，N4 I3b 全量接线）：
 * - 分类型趋势：mediaType 单值逐类型调 /stats/trends 拼系列（image/video/animated_image）
 *   + 「来源浏览趋势」卡（N3 #31b 解冻：source=normal|cos 两次取数双系列）；
 * - 常看文件：/stats/most-viewed metric=seconds Top 20（停留时长榜，DOMAIN_RULES §5 口径）；
 * - 常看作者与标签：/stats/top-authors Top15 + /stats/top-tags Top10 双排行卡；
 * - 分布统计：/stats/overview 类型库存 + 来源构成（sourceNormalCount/sourceCosCount）。
 */
@HiltViewModel
class StatsDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val statsRepository: StatsRepository,
) : ViewModel() {

    /** 模式与档位（路由参数解析；非法值回落默认——路由参数不可信原则） */
    val mode: StatsDetailMode = savedStateHandle.get<String>(StatsDetailRoutes.KEY_MODE)
        ?.let { runCatching { StatsDetailMode.valueOf(it) }.getOrNull() }
        ?: StatsDetailMode.TYPE_TREND

    val range: StatsRangeOption = savedStateHandle.get<String>(StatsDetailRoutes.KEY_RANGE)
        ?.let { runCatching { StatsRangeOption.valueOf(it) }.getOrNull() }
        ?: DEFAULT_STATS_RANGE

    private val _uiState = MutableStateFlow(StatsDetailUiState())
    val uiState: StateFlow<StatsDetailUiState> = _uiState.asStateFlow()

    init {
        when (mode) {
            StatsDetailMode.TYPE_TREND -> loadTypeTrend()
            StatsDetailMode.MOST_VIEWED -> loadMostViewed()
            StatsDetailMode.AUTHORS_TAGS -> loadAuthorsTags()
            StatsDetailMode.DISTRIBUTION -> loadDistribution()
        }
    }

    /** 分类型趋势：并发逐 mediaType 取数拼系列（协议单值参数；桶标签取首系列的标签轴）+ 来源趋势 */
    private fun loadTypeTrend() {
        viewModelScope.launch {
            try {
                val typeResults = TYPE_TREND_MEDIA_TYPES.map { mediaType ->
                    async { mediaType to statsRepository.trends(range.apiRange, mediaType) }
                }.awaitAll()
                // 来源趋势（N3 #31b）：source=normal|cos 各一次（与类型趋势同 viewCount 口径）
                val sourceResults = SOURCE_TREND_KEYS.map { source ->
                    async { source to statsRepository.trends(range.apiRange, null, source) }
                }.awaitAll()
                val labels = (typeResults + sourceResults)
                    .firstOrNull { it.second.isNotEmpty() }?.second?.map { it.label }.orEmpty()
                _uiState.update {
                    it.copy(
                        loading = false,
                        loadFailed = false,
                        trendLabels = labels,
                        typeSeries = typeResults.mapNotNull { (mediaType, points) ->
                            if (points.isEmpty()) {
                                null // 该类型窗口内无数据：不出系列（轴以有数系列为准）
                            } else {
                                TypeTrendSeries(
                                    mediaType = mediaType,
                                    name = mediaTypeDisplayName(mediaType),
                                    values = points.map { point -> point.viewCount },
                                )
                            }
                        },
                        sourceSeries = sourceResults.mapNotNull { (source, points) ->
                            if (points.isEmpty()) {
                                null
                            } else {
                                TypeTrendSeries(
                                    mediaType = source,
                                    name = sourceDisplayName(source),
                                    values = points.map { point -> point.viewCount },
                                )
                            }
                        },
                    )
                }
            } catch (error: Exception) {
                _uiState.update { it.copy(loading = false, loadFailed = true) }
            }
        }
    }

    /** 常看文件：most-viewed metric=seconds Top 20（无 dwell 事件的文件不参与 seconds 榜） */
    private fun loadMostViewed() {
        viewModelScope.launch {
            val ranking = runCatching {
                statsRepository.mostViewed(range.apiRange, METRIC_SECONDS, RANKING_LIMIT_MOST_VIEWED)
            }.getOrDefault(emptyList())
            _uiState.update { it.copy(loading = false, secondsRanking = ranking) }
        }
    }

    /** 常看作者与标签：双排行卡（作者 Top15 + 标签 Top10，各自独立端点） */
    private fun loadAuthorsTags() {
        viewModelScope.launch {
            try {
                val authors = async { runCatching { statsRepository.topAuthors(range.apiRange, RANKING_LIMIT_AUTHORS) }.getOrDefault(emptyList()) }
                val tags = async { runCatching { statsRepository.topTags(range.apiRange, RANKING_LIMIT_TAGS) }.getOrDefault(emptyList()) }
                _uiState.update {
                    it.copy(loading = false, topAuthors = authors.await(), topTags = tags.await())
                }
            } catch (error: Exception) {
                _uiState.update { it.copy(loading = false, loadFailed = true) }
            }
        }
    }

    /** 分布统计：overview 类型库存 + 来源构成（sourceNormalCount/sourceCosCount，N3 #31b 解冻） */
    private fun loadDistribution() {
        viewModelScope.launch {
            val overview: StatsOverviewValues? = runCatching { statsRepository.overview() }.getOrNull()
            _uiState.update {
                it.copy(
                    loading = false,
                    loadFailed = overview == null,
                    distribution = overview?.let { values ->
                        buildList {
                            add(TypeStockEntry(IMAGE_DISPLAY_NAME, values.imageCount))
                            add(TypeStockEntry(VIDEO_DISPLAY_NAME, values.videoCount))
                            val others = values.totalFiles - values.imageCount - values.videoCount
                            if (others > 0) add(TypeStockEntry(OTHER_DISPLAY_NAME, others))
                        }
                    } ?: emptyList(),
                    sourceDistribution = overview?.let { values ->
                        buildList {
                            add(TypeStockEntry(SOURCE_NORMAL_DISPLAY_NAME, values.sourceNormalCount))
                            add(TypeStockEntry(SOURCE_COS_DISPLAY_NAME, values.sourceCosCount))
                        }
                    } ?: emptyList(),
                )
            }
        }
    }

    /** mediaType 协议值 → 中文系列名（GUIDE_UI：图片/视频/动图） */
    internal fun mediaTypeDisplayName(mediaType: String): String = when (mediaType) {
        "image" -> IMAGE_DISPLAY_NAME
        "video" -> VIDEO_DISPLAY_NAME
        "animated_image" -> ANIMATED_DISPLAY_NAME
        else -> mediaType
    }

    /** source 协议值 → 中文系列名（来源浏览趋势：常规/COS；DOMAIN_RULES §6 分区口径） */
    internal fun sourceDisplayName(source: String): String = when (source) {
        "normal" -> SOURCE_NORMAL_DISPLAY_NAME
        "cos" -> SOURCE_COS_DISPLAY_NAME
        else -> source
    }

    private companion object {
        /** 类型趋势卡系列类型集（GUIDE_UI「图片/视频/动图」；协议 mediaType 三值全集） */
        val TYPE_TREND_MEDIA_TYPES = listOf("image", "video", "animated_image")

        /** 来源趋势卡系列集（协议 /stats/trends source 枚举 normal|cos） */
        val SOURCE_TREND_KEYS = listOf("normal", "cos")

        /** metric=seconds（常看文件详情=停留时长榜） */
        const val METRIC_SECONDS = "seconds"

        /** 常看文件榜条数（协议 limit 上限 50；详情页 Top 20 与旧版榜同档） */
        const val RANKING_LIMIT_MOST_VIEWED = 20

        /** 常看作者 Top15 / 常看标签 Top10（GUIDE_UI §统计详情页 MODE_AUTHORS 逐字） */
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
