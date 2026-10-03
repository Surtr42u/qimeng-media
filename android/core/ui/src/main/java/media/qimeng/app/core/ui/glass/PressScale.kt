package media.qimeng.app.core.ui.glass

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/** 按压缩放的默认最小值（0.96=「按下去一点」的玻璃微交互；0.92 时代属旧胶囊语言） */
private const val PRESS_SCALE_MIN = 0.96f

/**
 * 按压微交互（ADR-0031 动效规范）：按下 spring 缩到 [pressedScale]、松开 spring 回弹。
 * graphicsLayer 块内延迟读取缩放值——动画推进只走 draw 阶段，不触发重组（QimengSegPill
 * 同款机制，此处收口为全 App 微交互单源）。
 *
 * 用法：与自持 interactionSource 的 clickable 搭配（indication=null 时缩放即反馈；
 * 有 ripple 场景也可叠加）。禁止在 feature 内再手写同款 animateFloatAsState 按压缩放。
 */
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = PRESS_SCALE_MIN,
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "qimengPressScale",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** 便捷入口：组件内部自持 interactionSource 时的组合用法（返回 source 供 clickable 复用） */
@Composable
fun rememberPressScaleSource(): MutableInteractionSource = remember { MutableInteractionSource() }
