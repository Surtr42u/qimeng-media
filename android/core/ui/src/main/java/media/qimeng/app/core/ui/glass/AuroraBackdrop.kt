package media.qimeng.app.core.ui.glass

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Brush
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import media.qimeng.app.core.ui.theme.isQimengDarkTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 漂移整周期时长（ms）：24s 极慢漂移——肉眼可感的「活」，又远低于可察觉的转速上限 */
private const val AURORA_DRIFT_PERIOD_MS = 24_000

/** 夜基底渐变两档（近黑蓝紫夜色；与主题 background 同族，梯度差刻意极小=氛围而非色块） */
private val NIGHT_BASE_TOP = Color(0xFF0B0E1A)
private val NIGHT_BASE_BOTTOM = Color(0xFF0E1120)

/** 昼基底渐变两档（冷瓷白；深浅主题各自成套，禁共用——浅底上铺夜色是事故） */
private val DAY_BASE_TOP = Color(0xFFF5F6FC)
private val DAY_BASE_BOTTOM = Color(0xFFECF0F9)

/** 辉光团半径档（占画布短边比例）：三团各自的大小（径向渐变天然软边=免 blur 的景深来源） */
private const val BLOB_RADIUS_LARGE = 0.72f
private const val BLOB_RADIUS_MEDIUM = 0.55f
private const val BLOB_RADIUS_SMALL = 0.42f

/** 漂移振幅（占画布短边比例）：辉光团中心的游移范围（再大就出「滚动」感了） */
private const val BLOB_DRIFT_AMPLITUDE = 0.10f

/** 辉光团静态锚点（画布比例坐标 x,y）：左上鸢尾 / 右中极光青 / 左下霞粉 / 右上霓紫补层 */
private val BLOB_ANCHORS = listOf(
    0.16f to 0.10f,
    0.92f to 0.42f,
    0.10f to 0.88f,
    0.80f to 0.02f,
)

/** 各辉光团的漂移相位差（rad）：错峰游移避免四团同步「呼吸」的机械感 */
private val BLOB_PHASES = listOf(0.0f, 1.6f, 3.1f, 4.6f)

/**
 * 极光氛围底（ADR-0031「流光玻璃」的景深来源）：深色基底纵向渐变 + 四团径向渐变辉光
 * 极慢漂移。玻璃面板（[GlassSurface]）覆于其上即成磨砂观感——辉光本体是自绘内容，
 * 不需 backdrop 采样，也不依赖 `Modifier.blur`，API 26 起观感一致（降级策略见
 * GlassColors KDoc）。置于壳层 Scaffold 内容最底（zIndex=-2），全页面共享同一氛围。
 *
 * 性能：单一 InfiniteTransition 驱动，t 在 Canvas（draw 阶段）内读取——漂移只触发
 * draw 无效化，不重组；径向渐变为 GPU 常规负载，移动端全帧率无压力。
 */
@Composable
fun AuroraBackdrop(modifier: Modifier = Modifier) {
    val glows = glowPalette()
    val transition = rememberInfiniteTransition(label = "auroraDrift")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = AURORA_DRIFT_PERIOD_MS, easing = LinearEasing),
        ),
        label = "auroraT",
    )
    // 基底随主题切换（浅色主题冷瓷白梯度，暗色主题夜色梯度——见常量注释）
    val baseTop = if (isQimengDarkTheme()) NIGHT_BASE_TOP else DAY_BASE_TOP
    val baseBottom = if (isQimengDarkTheme()) NIGHT_BASE_BOTTOM else DAY_BASE_BOTTOM
    Canvas(modifier = modifier) {
        // 基底：纵向微梯度（同主题两档色差极小=氛围而非色块）
        drawRect(brush = Brush.verticalGradient(listOf(baseTop, baseBottom)))
        val shortSide = minOf(size.width, size.height)
        val amplitude = shortSide * BLOB_DRIFT_AMPLITUDE
        glows.forEachIndexed { index, color ->
            val (ax, ay) = BLOB_ANCHORS[index]
            val phase = BLOB_PHASES[index]
            val angle = t * 2f * PI.toFloat() + phase
            val center = Offset(
                x = size.width * ax + cos(angle) * amplitude,
                y = size.height * ay + sin(angle * 0.8f) * amplitude,
            )
            val radius = shortSide * when (index) {
                0 -> BLOB_RADIUS_LARGE
                1 -> BLOB_RADIUS_MEDIUM
                2 -> BLOB_RADIUS_MEDIUM
                else -> BLOB_RADIUS_SMALL
            }
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color, Color.Transparent),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }
    }
}
