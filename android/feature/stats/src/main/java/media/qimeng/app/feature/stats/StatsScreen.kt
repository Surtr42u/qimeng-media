package media.qimeng.app.feature.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import media.qimeng.app.core.model.StatsRangeOption
import media.qimeng.app.core.model.TrendPoint
import media.qimeng.app.core.ui.component.Dimens
import media.qimeng.app.core.ui.component.QimengSegPill
import media.qimeng.app.core.ui.component.formatBytesHumanReadable

/**
 * 数据统计页（M4-6；C3 拍板已被用户 2026-09-08 推翻，见 ADR-0018）：
 * - 数字卡 6 指标静态（/stats/overview，不随时段联动——overview 无 range 参数，C2）；
 * - 时段四档胶囊 7天/30天/90天/全部（对齐 Web，C1；30 天档 = range=day，见 StatsRange 注释）；
 * - 趋势卡渐变面积 + 折线 + 数据点：原 C3 拍板为 Compose Canvas 自绘，用户 2026-09-08
 *   「不要自绘」「统计页折线图换 Vico」原话优先推翻之，现渲染层 = Vico（ADR-0018，
 *   QimengTrendLineChart 封装，I3 统计详情页多系列趋势图复用同一封装）。
 *   交互规格 = 旧项目 GUIDE_UI §数据统计页。
 */
@Composable
fun StatsScreen(
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.ScreenPadding),
    ) {
        item { Text(text = "数据", style = MaterialTheme.typography.headlineSmall) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatsRangeOption.entries.forEach { option ->
                    QimengSegPill(
                        text = option.label,
                        selected = state.selectedRange == option,
                        onClick = { viewModel.selectRange(option) },
                    )
                }
            }
        }
        item { OverviewCards(state = state) }
        item { TrendCard(points = state.trends, loading = state.trendsLoading, empty = state.trendsEmpty) }
        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

/** 数字卡两行 6 指标（静态，C2）：文件数三格 + 浏览/容量三格 */
@Composable
private fun OverviewCards(state: StatsUiState) {
    val overview = state.overview
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (overview == null) {
            MetricCell(title = "总文件", value = if (state.overviewLoading) "加载中…" else "—")
            return
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCell(title = "总文件", value = overview.totalFiles.toDisplayText(), modifier = Modifier.weight(1f))
            MetricCell(title = "图片", value = overview.imageCount.toDisplayText(), modifier = Modifier.weight(1f))
            MetricCell(title = "视频", value = overview.videoCount.toDisplayText(), modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCell(title = "库容量", value = formatBytesHumanReadable(overview.totalSizeBytes), modifier = Modifier.weight(1f))
            MetricCell(title = "今日浏览", value = overview.todayViews.toDisplayText(), modifier = Modifier.weight(1f))
            MetricCell(title = "总浏览", value = overview.totalViews.toDisplayText(), modifier = Modifier.weight(1f))
        }
    }
}

/** 单格指标卡（浅面底 + 数值 + 标题） */
@Composable
private fun MetricCell(title: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = value, style = MaterialTheme.typography.titleMedium)
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 趋势卡：浏览次数（TrendPoint.viewCount）逐桶折线。
 * 渐变面积 + 折线 + 数据点，渲染层走 Vico（QimengTrendLineChart，ADR-0018）；
 * X 轴日期标签防重叠抽稀由 Vico ItemPlacer 内置（替代旧 Canvas labelStep 手工截断）。
 * 空数据时显示规格文案「暂无趋势数据」。
 */
@Composable
private fun TrendCard(points: List<TrendPoint>, loading: Boolean, empty: Boolean) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "浏览趋势", style = MaterialTheme.typography.titleMedium)
            when {
                loading -> Text(text = "加载中…", style = MaterialTheme.typography.bodyMedium)
                empty -> Text(
                    text = TREND_EMPTY_TEXT,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> QimengTrendLineChart(
                    series = listOf(QimengTrendSeries(values = points.map { it.viewCount })),
                    seriesColors = listOf(MaterialTheme.colorScheme.primary),
                    xLabels = points.map { it.label },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(TREND_CHART_HEIGHT_DP.dp),
                )
            }
        }
    }
}

/** 数值展示（千分位；Locale 显式 US 保证分组符稳定不随设备语言漂移） */
private fun Int.toDisplayText(): String = String.format(Locale.US, "%,d", this)

private fun Long.toDisplayText(): String = String.format(Locale.US, "%,d", this)

/** 趋势空态文案（GUIDE_UI §数据统计页规格原文） */
private const val TREND_EMPTY_TEXT = "暂无趋势数据"

/** 趋势图固定高度（一屏内不挤压列表；纯展示尺寸） */
private const val TREND_CHART_HEIGHT_DP = 200
