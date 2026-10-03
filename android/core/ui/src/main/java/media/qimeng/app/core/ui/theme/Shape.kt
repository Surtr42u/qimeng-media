package media.qimeng.app.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 「流光玻璃」玻璃件形状 token（ADR-0031）：与 MaterialTheme.shapes 阶梯并行的玻璃专用档
 * ——玻璃面板/底栏/胶囊的圆角不走 M3 组件阶梯（组件阶梯供 M3 标准件），玻璃件统一从此
 * 取形，保证全 App 玻璃件圆角单源。
 */
object QimengShapes {
    /** 小面板（卡片内嵌玻璃条） */
    val inset: Shape = RoundedCornerShape(16.dp)

    /** 标准玻璃面板（卡片/区块容器） */
    val panel: Shape = RoundedCornerShape(24.dp)

    /** 大面板（底部弹层/全宽悬浮条） */
    val sheet: Shape = RoundedCornerShape(28.dp)

    /** 胶囊（底栏/分段容器等全圆） */
    val pill: Shape = RoundedCornerShape(100.dp)
}
