package media.qimeng.app.core.ui.component

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 组件层早期尺寸 token（M4-0 占位期产物）。
 * 任务 H1 清偿：原 QimengPlaceholderPage 组合函数全仓零调用（M4-0 遗留死代码）已删除；
 * 本 object 的 [ScreenPadding] 仍被 feature/login、feature/settings、feature/stats 引用，
 * 保留在原包名下（改并入 theme/QimengDimens 会牵动 feature 调用点，超出 H1 范围）。
 */
object Dimens {
    /** 页面四周统一留白 */
    val ScreenPadding = 16.dp
}
