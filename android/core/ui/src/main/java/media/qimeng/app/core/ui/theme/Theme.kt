package media.qimeng.app.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * 绮梦影库 Material 3 Expressive 主题——「流光玻璃」设计语言（ADR-0031，2026-10-02）。
 * 色板唯一来源 = [QimengBrandColors]（暗色第一基准的三元强调色体系，媒体消费暗色优先；
 * 主题仍跟随系统，深浅两套同族设计，不新增主题开关功能=功能守恒）。
 *
 * 相对旧「中性极简灰」主题的结构变化：
 * - [MaterialExpressiveTheme] 取代 [androidx.compose.material3.MaterialTheme]：接入
 *   M3 Expressive 的 [MotionScheme.expressive] spring 物理动效（material3 1.5.0-alpha28
 *   公开 API，官方 m3.material.io「M3 expressive motion theming」口径）——M3 组件
 *   （FilterChip/按钮/指示器等）的内置动画自动获得 spring 物理；自绘动画规范见
 *   glass/Motion.kt。
 * - 形状阶梯加大（卡 16→18dp 档、面板 24dp、底栏全圆），见 [qimengShapes]；
 * - 旧 surfaceTint=纯白保平面卡的手段不再需要（玻璃语言靠描边/高光分层），夜间恢复
 *   M3 默认 tonal 抬升语义（surfaceTint=primary 的 M3 基线行为）。
 * 未列出的 error/scrim 槽位保持 M3 基线默认（灰系时代的既有口径延续：色板不覆盖错误色）。
 * 动态色彩（Material You 取色）继续默认关闭——品牌一致性优先。
 */
private val LightColorScheme = lightColorScheme(
    primary = QimengBrandColors.PrimaryLight,
    onPrimary = QimengBrandColors.OnPrimaryLight,
    primaryContainer = QimengBrandColors.PrimaryContainerLight,
    onPrimaryContainer = QimengBrandColors.OnPrimaryContainerLight,
    inversePrimary = QimengBrandColors.PrimaryDark,
    secondary = QimengBrandColors.SecondaryLight,
    onSecondary = QimengBrandColors.OnSecondaryLight,
    secondaryContainer = QimengBrandColors.SecondaryContainerLight,
    onSecondaryContainer = QimengBrandColors.OnSecondaryContainerLight,
    tertiary = QimengBrandColors.TertiaryLight,
    onTertiary = QimengBrandColors.OnTertiaryLight,
    tertiaryContainer = QimengBrandColors.TertiaryContainerLight,
    onTertiaryContainer = QimengBrandColors.OnTertiaryContainerLight,
    background = QimengBrandColors.BgLight,
    onBackground = QimengBrandColors.TextPrimaryLight,
    surface = QimengBrandColors.SurfaceLight,
    onSurface = QimengBrandColors.TextPrimaryLight,
    surfaceVariant = QimengBrandColors.ContainerLight,
    onSurfaceVariant = QimengBrandColors.TextSecondaryLight,
    surfaceTint = QimengBrandColors.PrimaryLight,
    surfaceBright = QimengBrandColors.ContainerLowestLight,
    surfaceDim = QimengBrandColors.ContainerHighLight,
    surfaceContainerLowest = QimengBrandColors.ContainerLowestLight,
    surfaceContainerLow = QimengBrandColors.ContainerLowLight,
    surfaceContainer = QimengBrandColors.ContainerLight,
    surfaceContainerHigh = QimengBrandColors.ContainerHighLight,
    surfaceContainerHighest = QimengBrandColors.ContainerHighestLight,
    inverseSurface = QimengBrandColors.TextPrimaryLight,
    inverseOnSurface = QimengBrandColors.SurfaceLight,
    outline = QimengBrandColors.OutlineLight,
    outlineVariant = QimengBrandColors.OutlineVariantLight,
)

private val DarkColorScheme = darkColorScheme(
    primary = QimengBrandColors.PrimaryDark,
    onPrimary = QimengBrandColors.OnPrimaryDark,
    primaryContainer = QimengBrandColors.PrimaryContainerDark,
    onPrimaryContainer = QimengBrandColors.OnPrimaryContainerDark,
    inversePrimary = QimengBrandColors.PrimaryLight,
    secondary = QimengBrandColors.SecondaryDark,
    onSecondary = QimengBrandColors.OnSecondaryDark,
    secondaryContainer = QimengBrandColors.SecondaryContainerDark,
    onSecondaryContainer = QimengBrandColors.OnSecondaryContainerDark,
    tertiary = QimengBrandColors.TertiaryDark,
    onTertiary = QimengBrandColors.OnTertiaryDark,
    tertiaryContainer = QimengBrandColors.TertiaryContainerDark,
    onTertiaryContainer = QimengBrandColors.OnTertiaryContainerDark,
    background = QimengBrandColors.BgDark,
    onBackground = QimengBrandColors.TextPrimaryDark,
    surface = QimengBrandColors.SurfaceDark,
    onSurface = QimengBrandColors.TextPrimaryDark,
    surfaceVariant = QimengBrandColors.ContainerDark,
    onSurfaceVariant = QimengBrandColors.TextSecondaryDark,
    surfaceTint = QimengBrandColors.PrimaryDark,
    surfaceBright = QimengBrandColors.ContainerHighestDark,
    surfaceDim = QimengBrandColors.BgDark,
    surfaceContainerLowest = QimengBrandColors.BgDark,
    surfaceContainerLow = QimengBrandColors.ContainerLowDark,
    surfaceContainer = QimengBrandColors.ContainerDark,
    surfaceContainerHigh = QimengBrandColors.ContainerHighDark,
    surfaceContainerHighest = QimengBrandColors.ContainerHighestDark,
    inverseSurface = QimengBrandColors.TextPrimaryDark,
    inverseOnSurface = QimengBrandColors.SurfaceDark,
    outline = QimengBrandColors.OutlineDark,
    outlineVariant = QimengBrandColors.OutlineVariantDark,
)

/** 「流光玻璃」形状阶梯：大圆角语言（ADR-0031）——小组件 10 / 控件 14 / 卡 18 / 面板 24 / 大面板 28 */
private val qimengShapes = androidx.compose.material3.Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** 当前主题是否暗色（玻璃/氛围件按亮度判定，避免再次依赖 isSystemInDarkTheme 直查） */
@Composable
fun isQimengDarkTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

@Composable
fun QimengTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialExpressiveTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        // M3 Expressive spring 物理动效（ADR-0031 动效规范第一层）；motionScheme 传 null 会
        // 回落 M3 标准动效，必须显式给 expressive
        motionScheme = MotionScheme.expressive(),
        shapes = qimengShapes,
        typography = QimengTypography,
        content = content,
    )
}
