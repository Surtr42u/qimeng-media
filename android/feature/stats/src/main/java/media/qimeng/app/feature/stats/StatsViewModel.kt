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

/** 统计页 UI 状态（数字卡静态 C2 + 四档切换 C1 + 趋势线 C3） */
data class StatsUiState(
    val overview: StatsOverviewValues? = null,
    val overviewLoading: Boolean = true,
    val selectedRange: StatsRangeOption = DEFAULT_STATS_RANGE,
    val trends: List<TrendPoint> = emptyList(),
    val trendsLoading: Boolean = true,
) {
    /** 趋势空态（规格 §数据统计页：空态文案「暂无趋势数据」） */
    val trendsEmpty: Boolean get() = !trendsLoading && trends.isEmpty()
}

/**
 * 统计页 ViewModel（M4-6）：
 * - 数字卡来自 /stats/overview，进页拉一次不再随档位重拉（C2 拍板：overview 无 range 参数）；
 * - 趋势来自 /stats/trends，档位切换重拉；range 参数只经 StatsRangeOption.apiRange 产出；
 * - 快速切换防覆盖（GUIDE_UI §数据统计页「序号防重」）：响应带发起时的档位快照，
 *   回写时当前选中档已变则丢弃——晚完成的旧协程不得覆盖新结果。
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

    /** 档位切换（C1 四档）：立即翻选中态并发起该档请求 */
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
