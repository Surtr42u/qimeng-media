package media.qimeng.app.feature.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import media.qimeng.app.core.model.StatsRangeOption
import media.qimeng.app.core.model.TrendPoint
import media.qimeng.app.core.ui.component.Dimens
import media.qimeng.app.core.ui.component.formatBytesHumanReadable

/**
 * 数据统计页（M4-6，C1/C2/C3 拍板口径）：
 * - 数字卡 6 指标静态（/stats/overview，不随时段联动——overview 无 range 参数，C2）；
 * - 时段四档胶囊 7天/30天/90天/全部（对齐 Web，C1；30 天档 = range=day，见 StatsRange 注释）；
 * - 趋势卡渐变面积 + 折线 + 数据点（Compose Canvas 自绘，C3 拍板；点击数据点气泡与
 *   分类型详情页维持砍掉）。交互规格 = 旧项目 GUIDE_UI §数据统计页。
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
                    FilterChip(
                        selected = state.selectedRange == option,
                        onClick = { viewModel.selectRange(option) },
                        label = { Text(text = option.label) },
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
 * 渐变面积 + 折线 + 数据点 + 稀疏 X 轴标签（labelStep 截断到最多 6 个，防长窗口糊屏）。
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
                else -> TrendChart(
                    points = points,
                    lineColor = MaterialTheme.colorScheme.primary,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TrendChart(points: List<TrendPoint>, lineColor: Color, labelColor: Color) {
    val textMeasurer = rememberTextMeasurer()
    // 桶值不变时避免重组期重复计算极值（Canvas 每帧重绘读这组缓存值）
    val maxValue = remember(points) { points.maxOf { it.viewCount }.coerceAtLeast(1) }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(TREND_CHART_HEIGHT_DP.dp),
    ) {
        val padLeft = 8.dp.toPx()
        val padRight = 8.dp.toPx()
        val padTop = 12.dp.toPx()
        val padBottom = 24.dp.toPx()
        val chartWidth = size.width - padLeft - padRight
        val chartHeight = size.height - padTop - padBottom

        // x 均分（单点居中）；y 线性映射到 [padTop, padTop + chartHeight]
        val stepX = if (points.size <= 1) 0f else chartWidth / (points.size - 1)
        val offsets = points.mapIndexed { index, point ->
            Offset(
                x = padLeft + stepX * index,
                y = padTop + chartHeight * (1f - point.viewCount.toFloat() / maxValue),
            )
        }

        // 渐变面积（规格 §数据统计页趋势卡：渐变面积 + 折线）
        val areaPath = Path().apply {
            moveTo(offsets.first().x, padTop + chartHeight)
            offsets.forEach { lineTo(it.x, it.y) }
            lineTo(offsets.last().x, padTop + chartHeight)
            close()
        }
        drawPath(
            path = areaPath,
            brush = Brush.verticalGradient(
                colors = listOf(lineColor.copy(alpha = AREA_ALPHA), Color.Transparent),
                startY = padTop,
                endY = padTop + chartHeight,
            ),
        )
        drawPath(
            path = Path().apply {
                moveTo(offsets.first().x, offsets.first().y)
                offsets.drop(1).forEach { lineTo(it.x, it.y) }
            },
            color = lineColor,
            style = Stroke(width = LINE_WIDTH_DP.dp.toPx()),
        )
        offsets.forEach { drawCircle(color = lineColor, radius = DOT_RADIUS_DP.dp.toPx(), center = it) }

        // X 轴稀疏标签（labelStep = 装下最多 MAX_X_LABELS 个的步长；末桶必画）
        val labelStep = if (points.size <= MAX_X_LABELS) 1 else (points.size + MAX_X_LABELS - 1) / MAX_X_LABELS
        points.forEachIndexed { index, point ->
            if (index % labelStep == 0 || index == points.lastIndex) {
                val measured = textMeasurer.measure(point.label)
                val x = (offsets[index].x - measured.size.width / 2f)
                    .coerceIn(0f, size.width - measured.size.width)
                drawText(
                    textLayoutResult = measured,
                    color = labelColor,
                    topLeft = Offset(x = x, y = size.height - measured.size.height),
                )
            }
        }

        // Y 轴上限参考值（右上角，读数辅助）
        val maxLabel = textMeasurer.measure(maxValue.toDisplayText())
        drawText(
            textLayoutResult = maxLabel,
            color = labelColor,
            topLeft = Offset(x = size.width - maxLabel.size.width, y = 0f),
        )
    }
}

/** 数值展示（千分位；Locale 显式 US 保证分组符稳定不随设备语言漂移） */
private fun Int.toDisplayText(): String = String.format(Locale.US, "%,d", this)

private fun Long.toDisplayText(): String = String.format(Locale.US, "%,d", this)

/** 趋势空态文案（GUIDE_UI §数据统计页规格原文） */
private const val TREND_EMPTY_TEXT = "暂无趋势数据"

/** 趋势图固定高度（一屏内不挤压列表；纯展示尺寸） */
private const val TREND_CHART_HEIGHT_DP = 200

/** X 轴标签最多个数（超出按步长抽稀） */
private const val MAX_X_LABELS = 6

/** 面积渐变顶部透明度（视觉调参：压暗到不抢折线） */
private const val AREA_ALPHA = 0.25f

/** 折线宽度（dp） */
private const val LINE_WIDTH_DP = 2

/** 数据点半径（dp） */
private const val DOT_RADIUS_DP = 3
