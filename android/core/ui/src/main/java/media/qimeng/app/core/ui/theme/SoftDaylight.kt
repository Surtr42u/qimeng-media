package media.qimeng.app.core.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * 日间背景保持均匀纯白，避免冷灰渐变与有色投影使整页显脏。
 * 内容卡只保留低强度中性阴影；使用现有 Compose 原生 API，无新依赖。
 * 不改变尺寸、点击区与滚动语义；深色仍保持既有材质。
 */
object SoftDaylight {
    private val Shade = Color.Black.copy(alpha = 0.06f)
    private val ShadowRadius = 8.dp
    private val ShadowOffset = DpOffset(0.dp, 2.dp)

    internal val cardShadow = Shadow(radius = ShadowRadius, color = Shade, offset = ShadowOffset)
}

/** 与窗口和主题同源的均匀底色，不再铺设有色渐变。 */
@Composable
fun Modifier.softDaylightBackground(): Modifier = background(MaterialTheme.colorScheme.background)

/** 外部光影须在 Surface 的裁切之前绘制；调用方日间关闭 elevation，避免叠出硬边。 */
@Composable
fun Modifier.softDaylightShadow(
    shape: Shape = RoundedCornerShape(QimengDimens.CardCornerRadius),
): Modifier = if (isQimengDarkTheme()) {
    this
} else {
    dropShadow(shape, SoftDaylight.cardShadow)
}
