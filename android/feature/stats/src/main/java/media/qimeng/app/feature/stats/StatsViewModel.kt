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
import media.qimeng.app.core.data.repository.StatsRepository
import media.qimeng.app.core.model.DEFAULT_STATS_RANGE
import media.qimeng.app.core.model.StatsOverviewValues
import media.qimeng.app.core.model.StatsRangeOption
import media.qimeng.app.core.model.TrendPoint
import media.qimeng.app.core.model.apiRange

/**
 * 统计页 UI 状态（任务I I3 复刻回改：数字卡 6 指标对齐 GUIDE_UI §数据统计页 L209-211）：
 * - 第一行三窗口指标（总浏览次数/总播放次数/总浏览时长）= [trends] 各桶求和（DOMAIN_RULES §5
 *   「各桶之和=窗口总量」口径），随档位联动——与趋势同一次 /stats/trends 响应派生，天然同源；
 * - 第二行总文件数/总占用空间 = /stats/overview 静态库存值（overview 无 range 参数）；
 * - 「平均浏览次数」协议缺口 #31a 冻结（窗口内分母无端点），UI 显示「—」（StatsScreen 注释）。
 * - 序号防重（GUIDE_UI L212）：窗口指标与趋势同请求通道，防重沿用下方 trendsRequestId——
 *   桶求和是响应落地后的纯派生（无独立异步通道），不存在第二处覆盖窗口。
 */
data class StatsUiState(
    val overview: StatsOverviewValues? = null,
    val overviewLoading: Boolean = true,
    val selectedRange: StatsRangeOption = DEFAULT_STATS_RANGE,
    val trends: List<TrendPoint> = emptyList(),
    val trendsLoading: Boolean = true,
) {
    /** 趋势空态（规格 §数据统计页：空态文案「暂无趋势数据」） */
    val trendsEmpty: Boolean get() = !trendsLoading && trends.isEmpty()

    /** 窗口总浏览次数（Σ 桶 viewCount；纯派生，随档位联动） */
    val windowViews: Long get() = sumWindow(trends) { it.viewCount }

    /** 窗口总播放次数（Σ 桶 playCount） */
    val windowPlays: Long get() = sumWindow(trends) { it.playCount }

    /** 窗口总浏览时长秒数（Σ 桶 seconds） */
    val windowSeconds: Long get() = sumWindow(trends) { it.seconds }

    private inline fun sumWindow(points: List<TrendPoint>, selector: (TrendPoint) -> Int): Long =
        points.fold(0L) { acc, point -> acc + selector(point) }
}

/**
 * 统计页 ViewModel：
 * - 数字卡窗口指标 = /stats/trends 桶求和（协议内联动方案，REPLICATION_GAPS §3.3 裁定 2），
 *   overview 仅供库存两格、进页拉一次不随档位重拉；
 * - 趋势来自 /stats/trends，档位切换重拉；range 参数只经 StatsRangeOption.apiRange 产出；
 * - 快速切换防覆盖（GUIDE_UI §数据统计页「序号防重」）：响应带发起时的档位快照，
 *   回写时当前选中档已变则丢弃——晚完成的旧协程不得覆盖新结果（含窗口指标派生源）。
 */
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val statsRepository: StatsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StatsUiState())
    val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()

    /** 趋势请求序号（防覆盖判定：只接受最新序号的响应） */
    private var trendsRequestId: Long = 0L

    init {
        loadOverview()
        selectRange(DEFAULT_STATS_RANGE)
    }

    /** 档位切换（三档）：立即翻选中态并发起该档请求（窗口指标随 trends 落地一并刷新） */
    fun selectRange(option: StatsRangeOption) {
        if (_uiState.value.selectedRange == option && !_uiState.value.trendsLoading) return
        _uiState.update { it.copy(selectedRange = option, trendsLoading = true) }
        val requestId = ++trendsRequestId
        val rangeParam = option.apiRange
        viewModelScope.launch {
            val points = runCatching { statsRepository.trends(rangeParam) }.getOrDefault(emptyList())
            // 防覆盖：仅当本响应仍是最新请求且选中档未再变化时回写
            _uiState.update { current ->
                val isStale = requestId != trendsRequestId || current.selectedRange != option
                if (isStale) current else current.copy(trends = points, trendsLoading = false)
            }
        }
    }

    private fun loadOverview() {
        viewModelScope.launch {
            val overview = runCatching { statsRepository.overview() }.getOrNull()
            _uiState.update { it.copy(overview = overview, overviewLoading = false) }
        }
    }
}
