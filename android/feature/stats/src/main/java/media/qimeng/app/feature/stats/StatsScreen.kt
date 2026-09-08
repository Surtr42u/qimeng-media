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
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberToggleOnTap
import com.patrykandpatrick.vico.core.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.core.cartesian.marker.CartesianMarkerController
import media.qimeng.app.core.model.StatsRangeOption
import media.qimeng.app.core.model.TrendPoint
import media.qimeng.app.core.ui.component.Dimens
import media.qimeng.app.core.ui.component.QimengSegPill
import media.qimeng.app.core.ui.component.formatBytesHumanReadable

/**
 * 数据统计页（任务I I3 复刻：GUIDE_UI §数据统计页 L203-224，协议内可达成部分）：
 * - 时间范围三档胶囊 7天/30天/全部（R10 裁决回改，四档 90 天档废止），**全局联动**：
 *   数字卡窗口三指标/趋势图随档位重拉（窗口指标=趋势桶求和，同源同请求）；
 * - 总览数字卡两行 6 指标（L209-211）：第一行窗口值（总浏览次数/总播放次数/总浏览时长），
 *   第二行库存静态值（总文件数/总占用空间）+ 平均浏览次数（**协议缺口 #31a 冻结：分母
 *   「窗口内有浏览的文件数」无端点，UI 显示「—」占位，不做**）；
 * - 浏览趋势卡（L213）：点击数据点高亮+数值气泡（Vico DefaultCartesianMarker，
 *   rememberTrendValueMarker + rememberToggleOnTap）；点击卡片/右上「分类型趋势 ›」进统计详情页；
 * - 分布统计小入口卡（L214）：纯文字卡，点击进分布统计详情（来源维度 #31b 冻结，详情只做类型库存）；
 * - 常看文件/常看作者与标签两卡（L215-216）协议缺口 #31c/d 冻结，**不渲染**（不留死入口）。
 */
@Composable
fun StatsScreen(
    onOpenDetail: (mode: StatsDetailMode, range: StatsRangeOption) -> Unit,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // 数值气泡 marker：跨档位复用一个实例即可（值格式器只依赖后缀，与档位无关）
    val trendMarker = rememberTrendValueMarker(valueSuffix = MARKER_VALUE_SUFFIX_VIEWS)
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
        item {
            TrendCard(
                points = state.trends,
                loading = state.trendsLoading,
                empty = state.trendsEmpty,
                marker = trendMarker,
                onOpenTypeTrend = { onOpenDetail(StatsDetailMode.TYPE_TREND, state.selectedRange) },
            )
        }
        item {
            DistributionEntryCard(
                onOpen = { onOpenDetail(StatsDetailMode.DISTRIBUTION, state.selectedRange) },
            )
        }
        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

/**
 * 总览数字卡两行 6 指标（GUIDE_UI L209-211）：第一行=窗口聚合值（随档位联动），
 * 第二行=库存静态值 + 平均浏览次数冻结占位。
 */
@Composable
private fun OverviewCards(state: StatsUiState) {
    val overview = state.overview
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCell(
                title = "总浏览次数",
                value = if (state.trendsLoading) LOADING_TEXT else state.windowViews.toDisplayText(),
                modifier = Modifier.weight(1f),
            )
            MetricCell(
                title = "总播放次数",
                value = if (state.trendsLoading) LOADING_TEXT else state.windowPlays.toDisplayText(),
                modifier = Modifier.weight(1f),
            )
            MetricCell(
                title = "总浏览时长",
                value = if (state.trendsLoading) LOADING_TEXT else formatDurationSeconds(state.windowSeconds),
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCell(
                title = "总文件数",
                value = overview?.totalFiles?.toDisplayText() ?: staticPlaceholder(state.overviewLoading),
                modifier = Modifier.weight(1f),
            )
            MetricCell(
                title = "总占用空间",
                value = overview?.let { formatBytesHumanReadable(it.totalSizeBytes) }
                    ?: staticPlaceholder(state.overviewLoading),
                modifier = Modifier.weight(1f),
            )
            // 平均浏览次数（GUIDE_UI L210：窗口总浏览 ÷ 窗口内有浏览的文件数）——分母无协议端点
            // （REPLICATION_GAPS §4-#31a 冻结），显示「—」占位不删格，保住两行 6 格形态
            MetricCell(title = "平均浏览次数", value = FROZEN_PLACEHOLDER_TEXT, modifier = Modifier.weight(1f))
        }
    }
}

/** 库存格占位（加载中/加载失败置「—」，同窗口格语言） */
private fun staticPlaceholder(loading: Boolean): String =
    if (loading) LOADING_TEXT else FROZEN_PLACEHOLDER_TEXT

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
 * 趋势卡（GUIDE_UI L213）：浏览次数（TrendPoint.viewCount）逐桶折线。
 * - 点击数据点高亮 + 数值气泡：marker = [rememberTrendValueMarker]，交互 = toggleOnTap
 *   （点击显示/再点隐藏；图表区内点击被 Vico 消费，卡片其余区域点击进详情）；
 * - 点击卡片或右上「分类型趋势 ›」→ 统计详情页分类型趋势模式（GUIDE_UI L213）。
 * 渐变面积 + 折线 + 数据点，渲染层走 Vico（QimengTrendLineChart，ADR-0018）；
 * 空数据时显示规格文案「暂无趋势数据」。
 */
@Composable
private fun TrendCard(
    points: List<TrendPoint>,
    loading: Boolean,
    empty: Boolean,
    marker: CartesianMarker,
    onOpenTypeTrend: () -> Unit,
) {
    Surface(
        onClick = onOpenTypeTrend,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "浏览趋势", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = TYPE_TREND_ENTRY_TEXT,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            when {
                loading -> Text(text = LOADING_TEXT, style = MaterialTheme.typography.bodyMedium)
                empty -> Text(
                    text = TREND_EMPTY_TEXT,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> QimengTrendLineChart(
                    series = listOf(QimengTrendSeries(values = points.map { it.viewCount })),
                    seriesColors = listOf(MaterialTheme.colorScheme.primary),
                    xLabels = points.map { it.label },
                    marker = marker,
                    markerController = CartesianMarkerController.Companion.rememberToggleOnTap(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(TREND_CHART_HEIGHT_DP.dp),
                )
            }
        }
    }
}

/**
 * 分布统计小入口卡（GUIDE_UI L214 纯文字卡）：
 * 主文案「类型与来源的库存构成」+ 右侧「查看详情 ›」，点击进分布统计详情。
 * 注：来源（常规/COS）维度统计协议缺口 #31b 冻结，详情页只呈现类型库存部分。
 */
@Composable
private fun DistributionEntryCard(onOpen: () -> Unit) {
    Surface(
        onClick = onOpen,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "类型与来源的库存构成", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "查看详情 ›",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 趋势空态文案（GUIDE_UI §数据统计页规格原文） */
private const val TREND_EMPTY_TEXT = "暂无趋势数据"

/** 趋势图固定高度（一屏内不挤压列表；纯展示尺寸） */
private const val TREND_CHART_HEIGHT_DP = 200

/** 加载中占位（数字卡各格） */
private const val LOADING_TEXT = "加载中…"

/** 冻结/失败占位（平均浏览次数 #31a 冻结显示「—」） */
private const val FROZEN_PLACEHOLDER_TEXT = "—"

/** 趋势卡右上入口文案（GUIDE_UI L213「分类型趋势 ›」） */
private const val TYPE_TREND_ENTRY_TEXT = "分类型趋势 ›"
