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
import media.qimeng.app.core.data.repository.MediaBatchIndex
import media.qimeng.app.core.data.repository.StatsRepository
import media.qimeng.app.core.model.DEFAULT_STATS_RANGE
import media.qimeng.app.core.model.MostViewedEntry
import media.qimeng.app.core.model.StatsOverviewValues
import media.qimeng.app.core.model.StatsRangeOption
import media.qimeng.app.core.model.TopAuthorEntry
import media.qimeng.app.core.model.TopTagEntry
import media.qimeng.app.core.model.apiRange
import kotlin.math.roundToInt

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
 * 2026-09-13 视觉复刻批：补旧版摘要卡/洞察卡/排序胶囊所需窗口聚合字段
 * （全部经现有端点 trends/overview 派生，无新增协议端点）。
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
    /** 常看文件 views 榜（窗口内 open 次数倒序 Top 20；「按热度」排序档数据源） */
    val viewsRanking: List<MostViewedEntry> = emptyList(),
    /** 常看作者 Top15 */
    val topAuthors: List<TopAuthorEntry> = emptyList(),
    /** 常看标签 Top10 */
    val topTags: List<TopTagEntry> = emptyList(),
    /** 窗口浏览/播放/停留聚合（trends 逐桶求和；摘要卡与洞察行数据源） */
    val windowViewCount: Int = 0,
    val windowPlayCount: Int = 0,
    val windowSeconds: Long = 0,
    /** overview(range)（平均浏览次数；MOST_VIEWED 摘要用）；失败 null */
    val overviewValues: StatsOverviewValues? = null,
    /** 窗口内有浏览记录的文件数（协议无直出字段，[deriveFilesWithViewRecords] 派生） */
    val filesWithViewRecords: Int = 0,
    /** 分布模式窗口浏览（view+play 合计；key=mediaType/source 协议值） */
    val typeWindowViews: Map<String, Int> = emptyMap(),
    val sourceWindowViews: Map<String, Int> = emptyMap(),
    val loadFailed: Boolean = false,
) {
    /** 数据全空（加载完且无任何可展示内容）→ 空态文案 */
    val isEmpty: Boolean
        get() = !loading && !loadFailed &&
            typeSeries.all { it.values.isEmpty() } &&
            sourceSeries.all { it.values.isEmpty() } &&
            distribution.isEmpty() && sourceDistribution.isEmpty() &&
            secondsRanking.isEmpty() && viewsRanking.isEmpty() &&
            topAuthors.isEmpty() && topTags.isEmpty()
}

/**
 * 窗口内有浏览记录的文件数派生（协议无直出字段）：avgViewsPerFile = 窗口 open 总数 ÷
 * 有 open 记录的文件数（openapi 定义），反解 files = viewCount 求和 ÷ avg 四舍五入；
 * avg 缺失/≤0（overview 失败或窗口无浏览）回落榜条数——真实数必然 ≥ 榜条数（Top N 截断）。
 */
internal fun deriveFilesWithViewRecords(windowViews: Int, avgViewsPerFile: Double?, fallback: Int): Int =
    avgViewsPerFile?.takeIf { it > 0 }?.let { avg -> (windowViews / avg).roundToInt() } ?: fallback

/**
 * 统计详情页 ViewModel（GUIDE_UI §统计详情页 L225-234，N4 I3b 全量接线）：
 * - 分类型趋势：mediaType 单值逐类型调 /stats/trends 拼系列（image/video/animated_image）
 *   + 「来源浏览趋势」卡（N3 #31b 解冻：source=normal|cos 两次取数双系列）；
 * - 常看文件：/stats/most-viewed 双榜（metric=views 按热度 + metric=seconds 按时长，Top 20）；
 * - 常看作者与标签：/stats/top-authors Top15 + /stats/top-tags Top10 双排行卡；
 * - 分布统计：/stats/overview 类型库存 + 来源构成 + 窗口浏览（/stats/trends 逐路派生）。
 * 摘要卡/洞察卡/排序胶囊所需窗口聚合全部复用既有端点（2026-09-13 视觉复刻批）。
 */
@HiltViewModel
class StatsDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val statsRepository: StatsRepository,
    private val batchIndex: MediaBatchIndex,
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

    /** 常看文件：双榜并发（views=按热度 / seconds=按时长，旧版排序胶囊两档）+ 窗口聚合派生 */
    private fun loadMostViewed() {
        viewModelScope.launch {
            val secondsDeferred = async {
                runCatching { statsRepository.mostViewed(range.apiRange, METRIC_SECONDS, RANKING_LIMIT_MOST_VIEWED) }
                    .getOrDefault(emptyList())
            }
            val viewsDeferred = async {
                runCatching { statsRepository.mostViewed(range.apiRange, METRIC_VIEWS, RANKING_LIMIT_MOST_VIEWED) }
                    .getOrDefault(emptyList())
            }
            // 窗口聚合（摘要卡「总浏览/浏览时长」+ 洞察行 + 有浏览记录文件数派生）：
            // 全部走既有 /stats/trends 与 /stats/overview(range)，无新增协议端点
            val window = runCatching { statsRepository.trends(range.apiRange) }.getOrDefault(emptyList())
            val overview = runCatching { statsRepository.overview(range.apiRange) }.getOrNull()
            val secondsRanking = secondsDeferred.await()
            val viewsRanking = viewsDeferred.await()
            val windowViews = window.sumOf { it.viewCount }
            _uiState.update {
                it.copy(
                    loading = false,
                    secondsRanking = secondsRanking,
                    viewsRanking = viewsRanking,
                    windowViewCount = windowViews,
                    windowPlayCount = window.sumOf { point -> point.playCount },
                    windowSeconds = window.sumOf { point -> point.seconds.toLong() },
                    overviewValues = overview,
                    filesWithViewRecords = deriveFilesWithViewRecords(windowViews, overview?.avgViewsPerFile, viewsRanking.size),
                )
            }
        }
    }

    /** 常看作者与标签：双排行卡 + 窗口浏览总次（摘要卡「浏览总次数」洞察行；trends 既有端点） */
    private fun loadAuthorsTags() {
        viewModelScope.launch {
            try {
                val authors = async { runCatching { statsRepository.topAuthors(range.apiRange, RANKING_LIMIT_AUTHORS) }.getOrDefault(emptyList()) }
                val tags = async { runCatching { statsRepository.topTags(range.apiRange, RANKING_LIMIT_TAGS) }.getOrDefault(emptyList()) }
                val window = runCatching { statsRepository.trends(range.apiRange) }.getOrDefault(emptyList())
                _uiState.update {
                    it.copy(
                        loading = false,
                        topAuthors = authors.await(),
                        topTags = tags.await(),
                        windowViewCount = window.sumOf { point -> point.viewCount },
                        windowPlayCount = window.sumOf { point -> point.playCount },
                        windowSeconds = window.sumOf { point -> point.seconds.toLong() },
                    )
                }
            } catch (error: Exception) {
                _uiState.update { it.copy(loading = false, loadFailed = true) }
            }
        }
    }

    /**
     * 常看文件（榜单）条目进详情前的批次上下文写入（任务J J1，GUIDE_UI L218-224）：
     * 「已加载=当前显示清单」——随排序档取对应榜整表快照式替换 [MediaBatchIndex.ids]；
     * 参数保留 assetId 对齐签名。
     */
    fun enterDetail(assetId: String) {
        val ranking = if (filesSortByHeat.value) _uiState.value.viewsRanking else _uiState.value.secondsRanking
        batchIndex.ids = ranking.map { it.assetId }
    }

    /**
     * 常看文件排序档（旧版 filesSortByHeat 同款，默认按热度；状态收进 VM——
     * enterDetail 的批次快照要跟显示清单同源，放 UI 层会两处各持一份真相）。
     */
    private val _filesSortByHeat = MutableStateFlow(true)
    val filesSortByHeat: StateFlow<Boolean> = _filesSortByHeat.asStateFlow()

    /** 「按热度」/「按时长」点击互换（旧版 setupFilesSortToggle 同语义） */
    fun toggleFilesSort() {
        _filesSortByHeat.value = !_filesSortByHeat.value
    }

    /** 分布统计：overview 类型库存 + 来源构成 + 窗口浏览（按类型/来源，trends 既有端点派生） */
    private fun loadDistribution() {
        viewModelScope.launch {
            val overviewDeferred = async { runCatching { statsRepository.overview() }.getOrNull() }
            // 窗口浏览按类型/来源拆分（旧版 aggregateDailyByType/BySource 同口径 view+play 合计）：
            // mediaType/source 单值逐路取数，与 TYPE_TREND 模式同一取数路径
            val typeViews = TYPE_TREND_MEDIA_TYPES.map { mediaType ->
                async {
                    mediaType to runCatching { statsRepository.trends(range.apiRange, mediaType) }
                        .getOrDefault(emptyList())
                        .sumOf { point -> point.viewCount + point.playCount }
                }
            }.awaitAll()
            val sourceViews = SOURCE_TREND_KEYS.map { source ->
                async {
                    source to runCatching { statsRepository.trends(range.apiRange, null, source) }
                        .getOrDefault(emptyList())
                        .sumOf { point -> point.viewCount + point.playCount }
                }
            }.awaitAll()
            val overview: StatsOverviewValues? = overviewDeferred.await()
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
                    typeWindowViews = typeViews.toMap(),
                    sourceWindowViews = sourceViews.toMap(),
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

        /** metric=views（按热度档；协议 most-viewed 双 metric 枚举） */
        const val METRIC_VIEWS = "views"

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
