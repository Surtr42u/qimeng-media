package media.qimeng.app.feature.stats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.layer.point
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.component.shapeComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.compose.common.shape.rounded
import com.patrykandpatrick.vico.compose.common.shader.verticalGradient
import com.patrykandpatrick.vico.core.cartesian.CartesianChart
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.LineCartesianLayerModel
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import com.patrykandpatrick.vico.core.common.shader.ShaderProvider
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
 *   DefaultCartesianMarker / persistentMarkers 实现，本封装不内嵌交互（本批零交互）。
 *
 * 视觉口径（旧版 GUIDE_UI §数据统计页趋势卡，Vico 能力内近似）：渐变面积 + 折线 + 数据点；
 * X 轴标签防重叠抽稀由 Vico ItemPlacer 内置（替代旧 Canvas labelStep 手工截断到 6 个）；
 * 不画 Y 轴（旧版仅右上角最大值参考标签，读数辅助非规格硬项，不复刻）。
 *
 * @param series 折线系列列表（空列表不渲染任何系列）
 * @param seriesColors 每条系列的主色（折线/数据点/面积渐变同色系）
 * @param xLabels X 轴标签，与各系列数据点下标一一对应（超出部分 Vico 自动抽稀）
 * @param marker 悬浮 marker（I3 预留：点击数据点高亮 + 数值气泡），null = 无
 * @param persistentMarkers 持久 marker（I3 预留：常驻标记能力口），null = 无
 */
@Composable
fun QimengTrendLineChart(
    series: List<QimengTrendSeries>,
    seriesColors: List<Color>,
    xLabels: List<String>,
    modifier: Modifier = Modifier,
    marker: CartesianMarker? = null,
    persistentMarkers: (CartesianChart.PersistentMarkerScope.(ExtraStore) -> Unit)? = null,
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
    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(
                lineProvider = LineCartesianLayer.LineProvider.series(
                    seriesColors.take(series.size).map { color ->
                        LineCartesianLayer.rememberLine(
                            fill = LineCartesianLayer.LineFill.single(fill(color)),
                            // 渐变面积下探到基线（splitY 默认 0），顶部主色半透明、底部全透明
                            areaFill = LineCartesianLayer.AreaFill.single(
                                fill = fill(
                                    ShaderProvider.verticalGradient(
                                        arrayOf(color.copy(alpha = AREA_TOP_ALPHA), Color.Transparent),
                                    ),
                                ),
                            ),
                            pointProvider = LineCartesianLayer.PointProvider.single(
                                LineCartesianLayer.point(
                                    component = shapeComponent(
                                        fill = fill(color),
                                        shape = CorneredShape.rounded(POINT_CORNER_PERCENT),
                                    ),
                                    size = POINT_SIZE_DP.dp,
                                ),
                            ),
                        )
                    },
                ),
            ),
            bottomAxis = HorizontalAxis.rememberBottom(
                // 旧版为无轴线的悬浮日期标签：隐藏轴线/刻度/参考线，只留标签
                line = null,
                tick = null,
                guideline = null,
                valueFormatter = CartesianValueFormatter { _, value, _ ->
                    // x 用点序号 0..n-1，查表还原日期标签；越界（动画插值期小数）取整截断
                    xLabels.getOrNull(value.roundToInt()).orEmpty()
                },
            ),
            marker = marker,
            persistentMarkers = persistentMarkers,
        ),
        modelProducer = modelProducer,
        modifier = modifier,
    )
}

/** 面积渐变顶部透明度（视觉调参：压暗到不抢折线，对齐旧 Canvas 版 AREA_ALPHA=0.25） */
private const val AREA_TOP_ALPHA = 0.25f

/** 数据点直径（dp）——旧版 Canvas 点半径 3dp 的等效直径 */
private const val POINT_SIZE_DP = 6

/** 数据点圆角百分比（CorneredShape.rounded(50) 对方形边界 = 正圆） */
private const val POINT_CORNER_PERCENT = 50
