package media.qimeng.app.core.ui.glass

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import media.qimeng.app.core.ui.theme.isQimengDarkTheme

/**
 * 玻璃面板配色规范（ADR-0031「流光玻璃」唯一来源）。
 *
 * 玻璃质感四要素（自建实现，零新依赖）：
 * 1. **fill** 半透明基色——面板的「体」，深浅主题各给 普通档/加厚档（fillStrong，底栏等
 *    需要压住下方滚动内容的场合）；
 * 2. **edge** 受光描边——上亮下暗的 1dp 渐变描边，模拟玻璃边缘的环境受光（Liquid Glass
 *    的关键识别特征）；
 * 3. **sheen** 顶部高光——面板上部纵向渐隐的白纱，模拟曲面的镜面反光；
 * 4. **shadow** 深色投影——把面板从背景上「抬」起来，与半透明体一起构成景深。
 *
 * 降级策略（ADR-0031，与 API 级别无关地成立）：本体系不依赖 backdrop 采样模糊——
 * 官方 Compose 无 backdrop-blur API（已核，2026-10-02）；「被磨砂的景深」由
 * [AuroraBackdrop] 自绘辉光承担（玻璃面板覆于其上即成磨砂观感），`Modifier.blur`
 * 只用于自有内容（如头图氛围层），API < 31 上它自动 no-op，面板回落「半透明纯色层+
 * 阴影」且观感完整——fill alpha 的取值保证无任何模糊时文字对比度仍达标。
 */
@Immutable
data class GlassColors(
    /** 普通档面板体（卡片/胶囊容器） */
    val fill: Color,
    /** 加厚档面板体（底栏/悬浮条等压住滚动内容的场合） */
    val fillStrong: Color,
    /** 受光描边·上端 */
    val edgeTop: Color,
    /** 受光描边·下端 */
    val edgeBottom: Color,
    /** 顶部高光纱 */
    val sheen: Color,
    /** 投影色（黑/深靛 @ 低 alpha） */
    val shadow: Color,
)

/** 暗色玻璃：靛夜体 + 白纱受光（alpha 取值经暗底对比度自查，正文在面板上仍 ≥4.5:1） */
private val DarkGlass = GlassColors(
    fill = Color(0xFF1B2033).copy(alpha = 0.66f),
    fillStrong = Color(0xFF141828).copy(alpha = 0.88f),
    edgeTop = Color(0xFFFFFFFF).copy(alpha = 0.22f),
    edgeBottom = Color(0xFFFFFFFF).copy(alpha = 0.05f),
    sheen = Color(0xFFFFFFFF).copy(alpha = 0.10f),
    shadow = Color(0xFF04050A).copy(alpha = 0.45f),
)

/** 浅色玻璃：白瓷体 + 白高光受光（下缘转冷灰紫，保持面板轮廓可辨） */
private val LightGlass = GlassColors(
    fill = Color(0xFFFFFFFF).copy(alpha = 0.66f),
    fillStrong = Color(0xFFFDFDFF).copy(alpha = 0.90f),
    edgeTop = Color(0xFFFFFFFF).copy(alpha = 0.95f),
    edgeBottom = Color(0xFF8F96B8).copy(alpha = 0.30f),
    sheen = Color(0xFFFFFFFF).copy(alpha = 0.55f),
    shadow = Color(0xFF3A4160).copy(alpha = 0.16f),
)

/** 主题感知的玻璃配色入口（玻璃件唯一取色口，禁止 feature 直引 DarkGlass/LightGlass） */
@Composable
fun glassColors(): GlassColors = if (isQimengDarkTheme()) DarkGlass else LightGlass

/** 辉光层透明度：暗色主题辉光更饱和、浅色收敛（避免昼间发腻） */
@Composable
fun glowIntensity(): Float = if (isQimengDarkTheme()) 1f else GLOW_LIGHT_SCALE

/** 浅色主题辉光衰减系数（辉光色 alpha 统一乘子） */
private const val GLOW_LIGHT_SCALE = 0.55f

/** 主题感知的辉光基础色组（AuroraBackdrop 消费；顺序=鸢尾/极光青/霞粉/霓紫） */
@Composable
internal fun glowPalette(): List<Color> = listOf(
    MaterialTheme.colorScheme.primary.copy(alpha = 0.30f) * glowIntensity(),
    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.22f) * glowIntensity(),
    Color(0xFFE85C9A).copy(alpha = 0.16f) * glowIntensity(),
    Color(0xFF7C4DE8).copy(alpha = 0.20f) * glowIntensity(),
)

/** Color × 系数的轻量运算（仅用于辉光 alpha 衰减，不走完整色彩空间转换） */
private operator fun Color.times(scale: Float): Color = copy(alpha = alpha * scale)
