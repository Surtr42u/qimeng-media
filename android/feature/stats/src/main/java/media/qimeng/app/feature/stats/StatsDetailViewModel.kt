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
import media.qimeng.app.core.model.StatsOverviewValues
import media.qimeng.app.core.model.StatsRangeOption
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

/** 统计详情页 UI 状态（两模式各自取数；空态「暂无数据」= GUIDE_UI L247） */
data class StatsDetailUiState(
    val loading: Boolean = true,
    val typeSeries: List<TypeTrendSeries> = emptyList(),
    val trendLabels: List<String> = emptyList(),
    val distribution: List<TypeStockEntry> = emptyList(),
    val loadFailed: Boolean = false,
) {
    /** 数据全空（加载完且无任何可展示内容）→ 空态文案 */
    val isEmpty: Boolean
        get() = !loading && !loadFailed && typeSeries.all { it.values.isEmpty() } && distribution.isEmpty()
}

/**
 * 统计详情页 ViewModel（GUIDE_UI §统计详情页 L225-234，协议内可达成部分）：
 * - 分类型趋势：mediaType 单值逐类型调 /stats/trends 拼系列（image/video/animated_image）；
 * - 分布统计：/stats/overview 类型库存（来源维度协议缺口 #31b 冻结，不取数不渲染）；
 * - 常看文件/常看作者标签两模式：协议缺口 #31c/d 冻结，不进导航结构（StatsDetailMode 注释）。
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
            StatsDetailMode.DISTRIBUTION -> loadDistribution()
        }
    }

    /** 分类型趋势：并发逐 mediaType 取数拼系列（协议单值参数；桶标签取首系列的标签轴） */
    private fun loadTypeTrend() {
        viewModelScope.launch {
            try {
                val results = TYPE_TREND_MEDIA_TYPES.map { mediaType ->
                    async { mediaType to statsRepository.trends(range.apiRange, mediaType) }
                }.awaitAll()
                val labels = results.firstOrNull { it.second.isNotEmpty() }?.second?.map { it.label }.orEmpty()
                _uiState.update {
                    it.copy(
                        loading = false,
                        loadFailed = false,
                        trendLabels = labels,
                        typeSeries = results.mapNotNull { (mediaType, points) ->
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
                    )
                }
            } catch (error: Exception) {
                _uiState.update { it.copy(loading = false, loadFailed = true) }
            }
        }
    }

    /** 分布统计：overview 类型库存（imageCount/videoCount，总计 totalFiles 作对比参照） */
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

    private companion object {
        /** 类型趋势卡系列类型集（GUIDE_UI「图片/视频/动图」；协议 mediaType 三值全集） */
        val TYPE_TREND_MEDIA_TYPES = listOf("image", "video", "animated_image")
    }
}

internal const val IMAGE_DISPLAY_NAME = "图片"
internal const val VIDEO_DISPLAY_NAME = "视频"
internal const val ANIMATED_DISPLAY_NAME = "动图"
internal const val OTHER_DISPLAY_NAME = "其他"
