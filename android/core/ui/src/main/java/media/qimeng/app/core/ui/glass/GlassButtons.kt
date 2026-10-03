package media.qimeng.app.core.ui.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import media.qimeng.app.core.ui.theme.QimengDimens
import media.qimeng.app.core.ui.theme.QimengShapes

/** 玻璃图标钮默认边长（对齐旧版 40dp 胶囊钮的既有布局档） */
private val GLASS_ICON_BUTTON_SIZE = 40.dp

/** 玻璃图标钮按压缩放（小控件用深一档，触感更明确） */
private const val GLASS_ICON_PRESSED_SCALE = 0.94f

/**
 * 玻璃胶囊图标钮（ADR-0031）：小尺寸玻璃面 + 图标 + spring 按压缩放。
 * 顶栏动作钮/入口钮的统一形态（单源，禁止 feature 再自绘 Surface 胶囊钮）。
 *
 * 不走 M3 IconButton：内建 48dp 最小触达会把 40dp 胶囊撑大（QimengSegPill 同款成因，
 * 见其 KDoc）；按压缩放即反馈，不叠 ripple。
 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = GLASS_ICON_BUTTON_SIZE,
    iconSize: Dp = QimengDimens.IconDefaultSize,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    val interactionSource = rememberPressScaleSource()
    GlassSurface(
        shape = QimengShapes.pill,
        modifier = modifier
            .size(size)
            .pressScale(interactionSource, pressedScale = GLASS_ICON_PRESSED_SCALE)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
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
