package media.qimeng.app.feature.stats

import android.text.Layout
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.core.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.core.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.core.cartesian.marker.LineCartesianLayerMarkerTarget
import com.patrykandpatrick.vico.core.common.Fill
import com.patrykandpatrick.vico.core.common.Insets
import com.patrykandpatrick.vico.core.common.component.Component
import com.patrykandpatrick.vico.core.common.component.ShapeComponent
import com.patrykandpatrick.vico.core.common.component.TextComponent
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import com.patrykandpatrick.vico.core.common.shape.MarkerCorneredShape

/**
 * 趋势图数值气泡 marker（GUIDE_UI §数据统计页「点击数据点高亮并显示数值气泡」，任务I I3；
 * 渲染层 = Vico [DefaultCartesianMarker]（官方 2.5.1 API，非自绘——用户 2026-09-08 拍板），
 * 实装到 [QimengTrendLineChart] 的 marker 参数位，交互控制器由调用方配
 * rememberToggleOnTap（点击显示/再点隐藏，经 QimengTrendLineChart markerController 参数位）。
 *
 * 气泡形态对照官方 sample（圆角描边底 label 组件 + 系列色圆点 indicator）：
 * - 单系列：气泡只出数值（如「12次」）；
 * - 多系列：同 x 各系列拼接、带系列名（GUIDE_UI §统计详情页「点击气泡含系列名」），
 *   系列名以折线色反查 [seriesNamesByColor]（Vico Point.color = 系列色 ARGB Int，逐点携带）。
 *
 * @param seriesNamesByColor 系列色 → 系列名（单系列传空 map）
 * @param valueSuffix 数值单位后缀（浏览趋势 = "次"）
 */
@Composable
internal fun rememberTrendValueMarker(
    seriesNamesByColor: Map<Color, String> = emptyMap(),
    valueSuffix: String = MARKER_VALUE_SUFFIX_VIEWS,
): CartesianMarker {
    // ShapeComponent 全位置实参（fill/shape/margins/strokeFill/strokeThicknessDp）——
    // margins 与 strokeThicknessDp 是 protected 属性，命名实参在类外不可引用；
    // 气泡底 = 圆角带指向小尾的 MarkerCorneredShape（Corner 必填，尾默认朝下指向数据点）
    val labelBackground = ShapeComponent(
        Fill(MaterialTheme.colorScheme.surface.toArgb()),
        MarkerCorneredShape(CorneredShape.Corner.Rounded),
        Insets.Zero,
        Fill(MaterialTheme.colorScheme.outlineVariant.toArgb()),
        LABEL_STROKE_THICKNESS_DP,
    )
    val label = rememberTextComponent(
        color = MaterialTheme.colorScheme.onSurface,
        textSize = LABEL_TEXT_SIZE_SP.sp,
        textAlignment = Layout.Alignment.ALIGN_CENTER,
        padding = Insets(horizontalDp = LABEL_PADDING_HORIZONTAL_DP, verticalDp = LABEL_PADDING_VERTICAL_DP),
        background = labelBackground,
        minWidth = TextComponent.MinWidth.fixed(LABEL_MIN_WIDTH_DP),
    )
    // 指示圆点：点击高亮 = 系列色实心圆（百分比圆角 50 与折线 pointProvider 数据点同形态）
    val indicator: (Color) -> Component = { color ->
        ShapeComponent(Fill(color.toArgb()), CorneredShape.rounded(INDICATOR_ROUND_PERCENT))
    }
    return rememberDefaultCartesianMarker(
        label = label,
        valueFormatter = remember(seriesNamesByColor, valueSuffix) {
            DefaultCartesianMarker.ValueFormatter { _, targets ->
                formatBubbleText(
                    points = targets.flatMap { target ->
                        (target as? LineCartesianLayerMarkerTarget)
                            ?.points
                            ?.map { point -> point.entry.y to point.color }
                            .orEmpty()
                    },
                    namesByColorArgb = seriesNamesByColor.mapKeys { (color, _) -> color.toArgb() },
                    suffix = valueSuffix,
                )
            }
        },
        indicator = indicator,
        indicatorSize = INDICATOR_SIZE_DP.dp,
    )
}

/** 单点数值 → 气泡片段（整数不带小数；非整保留 1 位。纯函数，JVM 单测锁定） */
internal fun formatPointValue(y: Double, suffix: String): String {
    val whole = y.toLong()
    return if (y == whole.toDouble()) {
        "$whole$suffix"
    } else {
        String.format(java.util.Locale.US, "%.1f%s", y, suffix)
    }
}

/**
 * 一次点击的气泡全文（纯函数，JVM 单测锁定）：多系列逐系列「名称 数值」拼接，
 * 无名系列只出数值；无可显示目标（动画插值期空 targets）返回空串不出气泡。
 */
internal fun formatBubbleText(
    points: List<Pair<Double, Int>>,
    namesByColorArgb: Map<Int, String>,
    suffix: String,
): String = points.joinToString(separator = BUBBLE_SEPARATOR) { (y, colorArgb) ->
    val name = namesByColorArgb[colorArgb]
    val value = formatPointValue(y, suffix)
    if (name.isNullOrEmpty()) value else "$name $value"
}

/** 气泡内多系列分隔符 */
internal const val BUBBLE_SEPARATOR = " · "

/** 浏览趋势气泡数值后缀（GUIDE_UI §数据统计页趋势卡口径=浏览次数） */
internal const val MARKER_VALUE_SUFFIX_VIEWS = "次"

/** 气泡 label 字号（视觉调参，大致相似口径） */
private const val LABEL_TEXT_SIZE_SP = 12

/** 气泡底描边粗细 dp（MarkerCorneredShape 默认自带指向小尾） */
private const val LABEL_STROKE_THICKNESS_DP = 1f

/** 气泡内边距 dp */
private const val LABEL_PADDING_HORIZONTAL_DP = 8f
private const val LABEL_PADDING_VERTICAL_DP = 4f

/** 气泡最小宽度 dp（官方 sample 同款防一位数气泡过窄抖动） */
private const val LABEL_MIN_WIDTH_DP = 40f

/** 指示圆点直径 dp */
private val INDICATOR_SIZE_DP = 12

/** 指示圆点圆角百分比（50 = 正圆，与 QimengTrendLineChart 数据点同参数） */
private const val INDICATOR_ROUND_PERCENT = 50
