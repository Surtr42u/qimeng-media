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
import media.qimeng.app.core.data.repository.MediaBatchIndex
import media.qimeng.app.core.data.repository.StatsRepository
import media.qimeng.app.core.model.DEFAULT_STATS_RANGE
import media.qimeng.app.core.model.MostViewedEntry
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

/**
 * 统计页 UI 状态（任务I I3 复刻回改 + N4 I3b 常看族解冻接线）：
 * - 第一行三窗口指标（总浏览次数/总播放次数/总浏览时长）= [trends] 各桶求和（DOMAIN_RULES §5
 *   「各桶之和=窗口总量」口径），随档位联动——与趋势同一次 /stats/trends 响应派生，天然同源；
 * - 第二行总文件数/总占用空间 = /stats/overview 静态库存值；「平均浏览次数」N3 #31a 解冻：
 *   overview(range).avgViewsPerFile 随档位联动（分母 0 → null → 「—」占位，降级语义保留）；
 * - 常看文件卡 Top3（/stats/most-viewed metric=views）、常看作者/标签卡混合 Top3
 *   （/stats/top-authors + /stats/top-tags）——全部随档位联动；失败/空数据 → 空表 → 卡内空态；
 * - 序号防重（GUIDE_UI L212）：窗口指标/趋势/常看族/均值同请求通道共用 [StatsViewModel.windowRequestId]，
 *   桶求和是响应落地后的纯派生（无独立异步通道）。
 */
data class StatsUiState(
    val overview: StatsOverviewValues? = null,
    val overviewLoading: Boolean = true,
    val selectedRange: StatsRangeOption = DEFAULT_STATS_RANGE,
    val trends: List<TrendPoint> = emptyList(),
    val trendsLoading: Boolean = true,
    /** 常看文件卡 Top3（metric=views；失败/空=空表 → 卡内空态） */
    val mostViewed: List<MostViewedEntry> = emptyList(),
    /** 常看作者卡数据源（详情页 Top15 亦用本通道，主页面只取前 3 混排） */
    val topAuthors: List<TopAuthorEntry> = emptyList(),
    /** 常看标签卡数据源 */
    val topTags: List<TopTagEntry> = emptyList(),
    /** 平均浏览次数（窗口联动值；加载中 null+loading、失败/分母 0 → null） */
    val avgViewsPerFile: Double? = null,
    val avgViewsLoading: Boolean = true,
) {
    /** 趋势空态（规格 §数据统计页：空态文案「暂无趋势数据」） */
    val trendsEmpty: Boolean get() = !trendsLoading && trends.isEmpty()

    /** 常看文件卡空态（加载完且无数据——空态保留口径） */
    val mostViewedEmpty: Boolean get() = !trendsLoading && mostViewed.isEmpty()

    /** 常看作者与标签卡空态 */
    val topAuthorsTagsEmpty: Boolean get() = !trendsLoading && topAuthors.isEmpty() && topTags.isEmpty()

    /** 窗口总浏览次数（Σ 桶 viewCount；纯派生，随档位联动） */
    val windowViews: Long get() = sumWindow(trends) { it.viewCount }

    /** 窗口总播放次数（Σ 桶 playCount） */
    val windowPlays: Long get() = sumWindow(trends) { it.playCount }

    /** 窗口总浏览时长秒数（Σ 桶 seconds） */
    val windowSeconds: Long get() = sumWindow(trends) { it.seconds }

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

    private inline fun sumWindow(points: List<TrendPoint>, selector: (TrendPoint) -> Int): Long =
        points.fold(0L) { acc, point -> acc + selector(point) }
}

/**
 * 统计页 ViewModel：
 * - 数字卡窗口指标 = /stats/trends 桶求和（协议内联动方案，REPLICATION_GAPS §3.3 裁定 2），
 *   overview 供库存两格（进页拉一次不随档位重拉）+ 窗口均值 avgViewsPerFile（随档位经
 *   overview(range) 联动，N3 #31a 解冻）；
 * - 趋势来自 /stats/trends，档位切换重拉；range 参数只经 StatsRangeOption.apiRange 产出；
 * - 常看族三口（most-viewed metric=views / top-authors / top-tags）随档位联动（N4 I3b）；
 * - 快速切换防覆盖（GUIDE_UI §数据统计页「序号防重」）：响应带发起时的档位快照，
 *   回写时当前选中档已变则丢弃——晚完成的旧协程不得覆盖新结果（含窗口指标派生源）；
 *   常看族/均值单口失败降级为空表/null，不拖垮同通道其余数据（getOrDefault 隔离）。
 */
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val statsRepository: StatsRepository,
    private val batchIndex: MediaBatchIndex,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StatsUiState())
    val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()

    /** 窗口请求序号（防覆盖判定：只接受最新序号的响应；趋势/常看族/均值共用一通道） */
    private var windowRequestId: Long = 0L

    init {
        loadOverview()
        selectRange(DEFAULT_STATS_RANGE)
    }

    /** 档位切换（三档）：立即翻选中态并发起该档请求（窗口指标随 trends 落地一并刷新） */
    fun selectRange(option: StatsRangeOption) {
        if (_uiState.value.selectedRange == option && !_uiState.value.trendsLoading) return
        _uiState.update { it.copy(selectedRange = option, trendsLoading = true, avgViewsLoading = true) }
        val requestId = ++windowRequestId
        val rangeParam = option.apiRange
        viewModelScope.launch {
            // 单口失败各自降级（空表/null），不互相拖垮——常看卡空态保留、均值「—」占位
            val points = runCatching { statsRepository.trends(rangeParam) }.getOrDefault(emptyList())
            val mostViewed = runCatching {
                statsRepository.mostViewed(rangeParam, METRIC_VIEWS, TOP_CARD_LIMIT)
            }.getOrDefault(emptyList())
            val topAuthors = runCatching { statsRepository.topAuthors(rangeParam, TOP_CARD_LIMIT) }
                .getOrDefault(emptyList())
            val topTags = runCatching { statsRepository.topTags(rangeParam, TOP_CARD_LIMIT) }
                .getOrDefault(emptyList())
            val avgViews = runCatching { statsRepository.overview(rangeParam) }
                .getOrNull()?.avgViewsPerFile
            // 防覆盖：仅当本响应仍是最新请求且选中档未再变化时回写
            _uiState.update { current ->
                val isStale = requestId != windowRequestId || current.selectedRange != option
                if (isStale) current else current.copy(
                    trends = points,
                    trendsLoading = false,
                    mostViewed = mostViewed,
                    topAuthors = topAuthors,
                    topTags = topTags,
                    avgViewsPerFile = avgViews,
                    avgViewsLoading = false,
                )
            }
        }
    }

    private fun loadOverview() {
        viewModelScope.launch {
            val overview = runCatching { statsRepository.overview() }.getOrNull()
            _uiState.update { it.copy(overview = overview, overviewLoading = false) }
        }
    }

    /**
     * 常看文件条目进详情前的批次上下文写入（任务J J1，GUIDE_UI L218-224 跳转链；
     * HomeViewModel/FavoriteViewModel 的 enterDetail 同款范式）：「已加载=当前显示清单」
     * 口径——主页面常看卡即 Top3 榜单，快照式整体替换 [MediaBatchIndex.ids]，
     * DetailViewModel 既有消费零改动。参数保留 assetId 与同款签名对齐（触发时机语义）。
     */
    fun enterDetail(assetId: String) {
        batchIndex.ids = _uiState.value.mostViewed.map { it.assetId }
    }

    private companion object {
        /** /stats/most-viewed 的 metric 参数值（协议 views|seconds；主页面卡=views 榜） */
        const val METRIC_VIEWS = "views"
    }
}
