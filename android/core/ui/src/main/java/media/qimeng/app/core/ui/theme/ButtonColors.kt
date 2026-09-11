package media.qimeng.app.core.ui.theme

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.luminance

/**
 * 实底按钮（M3 [androidx.compose.material3.Button]）禁用态容器色统一入口（任务W W6 #49）。
 *
 * 为什么收口：M3 默认禁用容器 = onSurface 12% alpha 合成的大面积低 alpha 纯色，
 * 夜间深底 + 模拟器 GPU 下逐帧呈现随机抖动横带（任务V 第 196 笔 screenrecord+ffmpeg
 * 定性=真实渲染噪声非采集伪影，W6 #49 用户拍板修复）。
 *
 * 为什么只在夜间换色：日间同 12% 合成干净无横带，按「日间零回归」硬约束日间保持
 * M3 默认原样；夜间换 [MaterialTheme.colorScheme.surfaceContainerHighest]（夜间档
 * #383838 不透明，亮度与原 12% 合成结果≈#373837 几乎等值）——不透明消除 dither
 * 抖动面，观感几乎零位移；且与启用态主色（夜间 #C8C8C8）对比明确、禁用文字仍
 * 38% alpha，enabled=false 禁用语义可辨。
 *
 * 新增「可禁用的实底按钮」用点必须引用本函数，禁止裸调 ButtonDefaults.buttonColors()
 * 留下夜间噪点面；TextButton/OutlinedButton 禁用容器为透明无此问题，不必走此入口。
 */
@Composable
fun qimengFilledButtonColors(): ButtonColors {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (dark) {
        ButtonDefaults.buttonColors(
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    } else {
        ButtonDefaults.buttonColors()
    }
}
