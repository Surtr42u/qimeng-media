package media.qimeng.app.feature.stats

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.point
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.component.shapeComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.compose.common.shape.rounded
import com.patrykandpatrick.vico.core.cartesian.CartesianChart
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.LineCartesianLayerModel
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.core.cartesian.marker.CartesianMarkerController
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import kotlin.math.roundToInt

/** 单条系列数据（Vico 一次事务 add 对应一条系列；values 与 x 轴下标 0..n-1 对齐） */
data class QimengTrendSeries(val values: List<Number>)

/**
 * 通用折线趋势图（Vico 2.x compose-m3 封装，ADR-0018）——用户 2026-09-08「不要自绘」拍板的
 * 渲染层替换件，旧 Canvas 自绘 TrendChart 退役。
 *
 * 可复用形态（任务I I3 统计详情页多系列趋势图的复用约定，本封装不写死单系列）：
 * - 系列数可配：[series] 每项 = 一条折线（Vico 事务内逐条 add，天然多系列）；
 * - 系列颜色可配：[seriesColors] 与 [series] 按序一一对应（数量不足直接抛错，防静默串色）；
 * - marker / persistentMarkers 参数位已预留：I3「点击数据点高亮 + 数值气泡」时直接传入
 *   DefaultCartesianMarker / persistentMarkers 实现（I3 已实装：rememberTrendValueMarker），
 *   本封装不内嵌气泡内容（各调用方按语义组值）。
 *
 * 视觉口径（2026-09-13 用户终裁对齐旧版截图，旧 Canvas 版 LineChartView 参数同源）：
 * 平涂面积（主色 [AREA_FILL_ALPHA] 恒定透明度，旧版 LinearGradient 双端同色+paint alpha=40
 * 的等效平涂——「渐变面积」旧口径废止）+ 折线 + 空心数据点（外圈主色环 + 内圈卡底色，
 * 旧版 pointPaint/pointFillPaint 同构）+ 4 条水平网格线（旧版 GRID_LINE_COUNT=3 的
 * startAxis guideline 近似，Vico 刻度取整值位）+ X 轴标签次色（旧 qmColorTextSecondary 档）；
 * X 轴标签防重叠抽稀由 Vico ItemPlacer 内置（替代旧 Canvas labelStep 手工截断）；
 * 不画 Y 轴标签（旧版无轴文字）。
 *
 * @param series 折线系列列表（空列表不渲染任何系列）
 * @param seriesColors 每条系列的主色（折线/数据点圆环/面积平涂同色系）
 * @param xLabels X 轴标签，与各系列数据点下标一一对应（超出部分 Vico 自动抽稀）
 * @param marker 悬浮 marker（I3：点击数据点高亮 + 数值气泡），null = 无
 * @param persistentMarkers 持久 marker（预留：常驻标记能力口），null = 无
 * @param markerController marker 显隐交互控制器（I3：调用方传
 *   CartesianMarkerController.Companion.rememberToggleOnTap() = 点击显示/再点隐藏，
 *   对齐 GUIDE_UI「点击数据点高亮」）；null = 库默认按压显隐（与 H2 行为一致）
 */
@Composable
fun QimengTrendLineChart(
    series: List<QimengTrendSeries>,
    seriesColors: List<Color>,
    xLabels: List<String>,
    modifier: Modifier = Modifier,
    marker: CartesianMarker? = null,
    persistentMarkers: (CartesianChart.PersistentMarkerScope.(ExtraStore) -> Unit)? = null,
    markerController: CartesianMarkerController? = null,
) {
    require(seriesColors.size >= series.size) {
        "seriesColors (${seriesColors.size}) 少于 series (${series.size})：系列必须逐条配色，禁止静默回落默认色"
    }
    val modelProducer = remember { CartesianChartModelProducer() }
    LaunchedEffect(series) {
        modelProducer.runTransaction {
            // 一次 add = 一条系列（Vico 约定）：多条系列按 add 顺序与 lineProvider 系列序对齐
            series.forEach { line -> add(LineCartesianLayerModel.partial { series(line.values) }) }
        }
    }
    // 空心点内圈与网格线取色（旧版 pointFillPaint=背景色/gridPaint=divider 的主题槽位等价）：
    // 图表恒铺在 surfaceVariant 统计卡上（StatsScreen/StatsDetailScreen 三个调用点同底），
    // 内圈随卡底色才能呈现「镂空」观感
    val pointInnerColor = MaterialTheme.colorScheme.surfaceVariant
    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(
                lineProvider = LineCartesianLayer.LineProvider.series(
                    seriesColors.take(series.size).map { color ->
                        LineCartesianLayer.rememberLine(
                            fill = LineCartesianLayer.LineFill.single(fill(color)),
                            // 平涂面积下探到基线（splitY 默认 0）：恒定 [AREA_FILL_ALPHA] 透明度，
                            // 对齐旧版「渐变 Shader 双端同色 + paint alpha」的实测观感（非渐变）
                            areaFill = LineCartesianLayer.AreaFill.single(
                                fill = fill(color.copy(alpha = AREA_FILL_ALPHA)),
                            ),
                            pointProvider = LineCartesianLayer.PointProvider.single(
                                LineCartesianLayer.point(
                                    // 空心点 = 主色描边环 + 卡底色内圈（旧版外圈 4dp/内圈 2dp 同构）
                                    component = shapeComponent(
                                        fill = fill(pointInnerColor),
                                        shape = CorneredShape.rounded(POINT_CORNER_PERCENT),
                                        strokeFill = fill(color),
                                        strokeThickness = POINT_RING_THICKNESS,
                                    ),
                                    size = POINT_SIZE_DP.dp,
                                ),
                            ),
                        )
                    },
                ),
            ),
            startAxis = VerticalAxis.rememberStart(
                // 旧版 4 条水平网格线的近似：隐藏轴线/刻度/标签，只留 guideline（刻度取整值位）
                line = null,
                tick = null,
                label = null,
                guideline = rememberLineComponent(
                    fill = fill(MaterialTheme.colorScheme.outlineVariant),
                    thickness = GRID_LINE_THICKNESS,
                ),
            ),
            bottomAxis = HorizontalAxis.rememberBottom(
                // 旧版为无轴线的悬浮日期标签：隐藏轴线/刻度/参考线，只留标签（次色 10sp 档）
                line = null,
                tick = null,
                guideline = null,
                label = rememberTextComponent(color = MaterialTheme.colorScheme.onSurfaceVariant),
                valueFormatter = CartesianValueFormatter { _, value, _ ->
                    // x 用点序号 0..n-1，查表还原日期标签；越界（动画插值期小数）取整截断
                    xLabels.getOrNull(value.roundToInt()).orEmpty()
                },
            ),
            marker = marker,
            persistentMarkers = persistentMarkers,
            markerController = markerController ?: remember { CartesianMarkerController.showOnPress() },
        ),
        modelProducer = modelProducer,
        modifier = modifier,
    )
}

/** 面积平涂透明度（旧 Canvas 版 areaPaint alpha=40/255 逐字同源，恒定不渐变） */
private val AREA_FILL_ALPHA = 40f / 255f

/** 数据点外径（dp）——旧版 Canvas 点半径 4dp 的等效直径 */
private const val POINT_SIZE_DP = 8

/** 数据点圆环厚度（dp）——旧版内圈半径 2dp（外 4dp-内 2dp）的等效描边档 */
private val POINT_RING_THICKNESS = 2.dp

/** 数据点圆角百分比（CorneredShape.rounded(50) 对方形边界 = 正圆） */
private const val POINT_CORNER_PERCENT = 50

/** 水平网格线厚度（旧版 gridPaint strokeWidth=1px 的近似档） */
private val GRID_LINE_THICKNESS = 1.dp
