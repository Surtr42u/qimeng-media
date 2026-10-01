package media.qimeng.app.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight

/**
 * 「流光玻璃」排印（ADR-0031）：字号档保持 M3 基线（各页既有 titleLarge.copy 字号覆盖不受
 * 影响），**标题族字重整体提为 SemiBold**——玻璃语言里标题是「受光面」，中字重在大圆角
 * 深色底上偏虚；正文/标签族不动（可读性优先，不追排印实验）。
 * 旧「对齐旧版字重」的 Y3 裁决随旧视觉语言一并退役（ADR-0031 设计语言级重做，功能守恒
 * 只约束页面与交互语义，不约束像素级复刻）。
 * 字体栈仍走系统默认（PingFang SC/微软雅黑，无需自定义 fontFamily 的既有口径延续）。
 */
val QimengTypography = Typography(
    displayLarge = Typography().displayLarge.copy(fontWeight = FontWeight.Bold),
    displayMedium = Typography().displayMedium.copy(fontWeight = FontWeight.Bold),
    displaySmall = Typography().displaySmall.copy(fontWeight = FontWeight.SemiBold),
    headlineLarge = Typography().headlineLarge.copy(fontWeight = FontWeight.Bold),
    headlineMedium = Typography().headlineMedium.copy(fontWeight = FontWeight.Bold),
    headlineSmall = Typography().headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Typography().titleSmall.copy(fontWeight = FontWeight.SemiBold),
)
