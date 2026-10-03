package media.qimeng.app.core.ui.glass

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import media.qimeng.app.core.ui.theme.QimengDimens
import media.qimeng.app.core.ui.theme.QimengShapes

/** 真采样胶囊的磨砂模糊半径（与坞 FROSTED 档同值 22dp——同引擎同语言） */
private val CAPSULE_BLUR_RADIUS = 22.dp

/** 真采样胶囊的 scrim 透明度（与坞 FROSTED 档同值 0.62：磨砂档「读得清」优先） */
private const val CAPSULE_SCRIM_ALPHA = 0.62f

/** 真采样胶囊受光发丝描边宽度（GlassSurface/坞 GLASS_EDGE_WIDTH 同档 1dp） */
private val CAPSULE_EDGE_WIDTH = 1.dp

/**
 * 真采样玻璃胶囊面（2026-10-03 质感对齐批）：与悬浮坞同一 Backdrop 库管线
 * （vibrancy+blur 采样 [backdrop] + 主题 scrim + 受光发丝描边），供「兄弟节点采样」
 * 架构的胶囊消费——采样源（[Modifier.qimengBackdropSource]）必须挂在本组件所在
 * 捕获层的**兄弟子树**上（坞=内容层外采样同构；挂自身祖先/自身子树=库的 SIGSEGV
 * 反面教材，禁止）。
 *
 * API ≤30（无 RenderEffect）自动降级 [GlassSurface] 静态玻璃（与坞 Pseudo 档同策略）。
 * 库类型不出本包（QimengBackdrop 封装边界），公共签名只见 [QimengBackdropState]。
 *
 * @param backdrop 采样源（兄弟子树挂载 qimengBackdropSource 而来）
 * @param shape 胶囊形状（默认全圆胶囊）
 */
@Composable
fun BackdropGlassPanel(
    backdrop: QimengBackdropState,
    modifier: Modifier = Modifier,
    shape: Shape = QimengShapes.pill,
    content: @Composable BoxScope.() -> Unit,
) {
    val glass = glassColors()
    val scrim = MaterialTheme.colorScheme.surface.copy(alpha = CAPSULE_SCRIM_ALPHA)
    // API ≤30 无 RenderEffect：降级静态玻璃（与坞 Pseudo 档同策略），签名与用法不变
    if (!backdropRenderEffectAvailable) {
        GlassSurface(shape = shape, modifier = modifier, content = content)
        return
    }
    Box(
        modifier = modifier.drawBackdrop(
            backdrop = backdrop.backdrop,
            shape = { shape },
            effects = capsuleEffects(),
            // 受光边唯一来源=自画发丝描边（与坞 GlassDockBody 同口径，关库默认环防双描边）
            highlight = { null },
            onDrawSurface = { capsuleSurface(scrim, glass) },
        ),
        content = content,
    )
}

/** 真采样玻璃胶囊图标钮：[BackdropGlassPanel] 的 40dp 图标钮形态（GlassIconButton 同构） */
@Composable
fun BackdropGlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    backdrop: QimengBackdropState,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    iconSize: Dp = QimengDimens.IconDefaultSize,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    val interaction = rememberPressScaleSource()
    Box(modifier = modifier.size(size)) {
        BackdropGlassPanel(
            backdrop = backdrop,
            modifier = Modifier
                .size(size)
                .pressScale(interaction, pressedScale = 0.94f)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = tint,
                    modifier = Modifier.size(iconSize),
                )
            }
        }
    }
}

/** 胶囊采样特效：vibrancy + 磨砂模糊（坞 FROSTED 档同族；小件不上 lens 折射防畸变） */
private fun capsuleEffects(): BackdropEffectScope.() -> Unit = {
    vibrancy()
    blur(CAPSULE_BLUR_RADIUS.toPx())
}

/** 胶囊表面两笔：主题 scrim + 受光发丝描边（坞 GlassDockBody onDrawSurface 同谱） */
private fun DrawScope.capsuleSurface(scrim: Color, glass: GlassColors) {
    drawRect(scrim)
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(glass.edgeTop, glass.edgeBottom),
            startY = 0f,
            endY = size.height,
        ),
        cornerRadius = CornerRadius(size.height / 2f),
        style = Stroke(width = CAPSULE_EDGE_WIDTH.toPx()),
    )
}

/** API ≤30 无 RenderEffect：drawBackdrop 不可用档的哨兵（降级 GlassSurface） */
internal val backdropRenderEffectAvailable: Boolean = Build.VERSION.SDK_INT >= 31
