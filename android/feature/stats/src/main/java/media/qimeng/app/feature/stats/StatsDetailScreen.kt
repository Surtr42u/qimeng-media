package media.qimeng.app.feature.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberToggleOnTap
import com.patrykandpatrick.vico.core.cartesian.marker.CartesianMarkerController
import media.qimeng.app.core.model.StatsRangeOption
import media.qimeng.app.core.model.detailTitleSuffix
import media.qimeng.app.core.ui.component.Dimens
import media.qimeng.app.core.ui.component.QimengEmptyState
import media.qimeng.app.core.ui.component.QimengRankCard
import media.qimeng.app.core.ui.component.QimengTopBar

/**
 * 统计详情页（任务I I3；GUIDE_UI §统计详情页 L225-234 协议内可达成部分，StatsDetailRoutes）：
 * - 顶栏动态标题 = 模式标题 + 时间范围后缀（「· 近7天/近30天/全部」，StatsRange.detailTitleSuffix）；
 * - TYPE_TREND：「类型浏览趋势」卡——图片/视频/动图多系列折线（复用 QimengTrendLineChart 的
 *   series+seriesColors，mediaType 单值逐类型取数拼系列），调用点自组图例行（两/三色圆点+文字，
 *   H3 收官记档的轻方案，不扩封装）；点击气泡含系列名（rememberTrendValueMarker）。
 *   「来源浏览趋势」卡协议缺口 #31b 冻结不渲染；
 * - DISTRIBUTION：「类型分布对比」卡——overview 类型库存，QimengRankCard 形态 +
 *   相对第一名的进度条（GUIDE_UI L229）+ 前三名排名数字高亮（L247 同节；来源维度 #31b 冻结）；
 * - 空态「暂无数据」（L247）。
 * 跳转链记档（REPLICATION_GAPS §3.3 裁定 7）：GUIDE L218-224 的文件/作者/标签条目跳转依赖
 * 冻结模式（常看文件/常看作者标签 #31c/d），类型分布行=聚合值无单文件落点——协议内无可达成
 * 跳转目标，本页条目不设点击；Search 路由 initialQuery 管道已就位（壳层+SearchScreen）。
 */
@Composable
fun StatsDetailScreen(
    onBack: () -> Unit,
    viewModel: StatsDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(modifier = Modifier.fillMaxSize()) {
        QimengTopBar(
            title = "${viewModel.mode.title} · ${viewModel.range.detailTitleSuffix}",
            onBack = onBack,
        )
        when {
            state.loading -> Text(
                text = LOADING_TEXT,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = 16.dp),
            )
            state.loadFailed || state.isEmpty -> QimengEmptyState(text = EMPTY_TEXT)
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = Dimens.ScreenPadding,
                    vertical = Dimens.ScreenPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(Dimens.ScreenPadding),
            ) {
                if (viewModel.mode == StatsDetailMode.TYPE_TREND) {
                    item {
                        TypeTrendCard(
                            series = state.typeSeries,
                            labels = state.trendLabels,
                        )
                    }
                } else {
                    item { TypeDistributionCard(entries = state.distribution) }
                }
            }
        }
    }
}

/**
 * 「类型浏览趋势」卡：多系列折线 + 调用点自组图例行。
 * 系列色 = M3 scheme primary/tertiary/error 三档（图片/视频/动图固定按序取色，
 * 图例与折线同源同一色表，气泡系列名反查同表）。
 */
@Composable
private fun TypeTrendCard(series: List<TypeTrendSeries>, labels: List<String>) {
    val seriesColors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.error,
    )
    // 气泡系列名：折线色 → 系列名（Point.color 逐点携带系列色，反查同表）
    val marker = rememberTrendValueMarker(
        seriesNamesByColor = series.mapIndexedNotNull { index, typeSeries ->
            seriesColors.getOrNull(index)?.let { color -> color to typeSeries.name }
        }.toMap(),
        valueSuffix = MARKER_VALUE_SUFFIX_VIEWS,
    )
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "类型浏览趋势", style = MaterialTheme.typography.titleMedium)
            // 图例行（调用点自组：色点+系列名；封装无图例能力，H3 记档的轻方案）
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                series.forEachIndexed { index, typeSeries ->
                    LegendEntry(
                        color = seriesColors[index % seriesColors.size],
                        name = typeSeries.name,
                    )
                }
            }
            QimengTrendLineChart(
                series = series.map { QimengTrendSeries(values = it.values) },
                seriesColors = seriesColors,
                xLabels = labels,
                marker = marker,
                markerController = CartesianMarkerController.Companion.rememberToggleOnTap(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DETAIL_TREND_CHART_HEIGHT_DP.dp),
            )
        }
    }
}

/** 图例单项：系列色圆点 + 系列名 */
@Composable
private fun LegendEntry(color: Color, name: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .size(LEGEND_DOT_SIZE_DP.dp)
                .background(color = color, shape = CircleShape),
        )
        Text(text = name, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * 「类型分布对比」卡（分布模式）：QimengRankCard 榜单卡形态，逐行类型库存 +
 * 相对第一名的进度条（GUIDE_UI L229）+ 前三名排名数字主题色高亮（L248）。
 * 跳转链记档见类 KDoc——分布行无协议内跳转落点，行不可点击。
 */
@Composable
private fun TypeDistributionCard(entries: List<TypeStockEntry>) {
    QimengRankCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = "类型分布对比", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        val maxCount = entries.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
        entries.forEachIndexed { index, entry ->
            RankRow(
                rank = index + 1,
                name = entry.name,
                count = entry.count,
                progress = entry.count.toFloat() / maxCount,
            )
        }
    }
}

/** 排行行：排名数字（前三名主题色高亮）+ 类型名 + 数值 + 相对第一名进度条 */
@Composable
private fun RankRow(rank: Int, name: String, count: Int, progress: Float) {
    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = rank.toString(),
                style = MaterialTheme.typography.titleSmall,
                color = if (rank <= TOP_RANK_HIGHLIGHT) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = count.toDisplayText(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )
    }
}

/** 详情页趋势图固定高度（GUIDE_UI：详情页折线图 240dp 档） */
private const val DETAIL_TREND_CHART_HEIGHT_DP = 240

/** 图例色点直径（视觉调参） */
private const val LEGEND_DOT_SIZE_DP = 8

/** 排名前三名高亮阈值（GUIDE_UI §交互设计「排行榜前三名排名数字高亮（主题色）」） */
private const val TOP_RANK_HIGHLIGHT = 3

/** 加载中文案 */
private const val LOADING_TEXT = "加载中…"

/** 空态文案（GUIDE_UI L247「暂无数据」） */
private const val EMPTY_TEXT = "暂无数据"
