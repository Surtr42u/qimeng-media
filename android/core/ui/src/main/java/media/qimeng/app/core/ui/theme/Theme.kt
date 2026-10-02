package media.qimeng.app.core.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import media.qimeng.app.core.model.ThemeColorPreset

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
 * 主题色彩预设（2026-10-03 主题色彩批）：[ThemeColorPreset] 六档，预设只覆写强调色三族
 * （primary/secondary/tertiary 的 主件/on/容器/on容器 共 12 槽），中性面共用基座；DYNAMIC
 * 档走 material3 官方动态取色（Material You 壁纸取色，API 31+，低于 31 回落 AURORA 基座
 * ——口径与 SDK 判定收敛在 [resolveQimengColorScheme] 装配单点，注释记档）。
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

/**
 * 「流光玻璃」形状阶梯：大圆角语言（ADR-0031）——小组件 10 / 控件 14 / 卡 18 / 大卡 20 /
 * 大面板 28（M3 五槽位的真实取值；面板 24dp 属玻璃件 token QimengShapes.panel，不在本阶梯）。
 * 刻意不引用 QimengShapes.*：玻璃件形状单源在 Shape.kt（玻璃件圆角不走 M3 组件阶梯，
 * 见其 KDoc），两阶梯仅 28dp（extraLarge↔sheet）同值，属独立取值而非映射关系——
 * 评审卫生项 5b「改引用 QimengShapes」经核对不成立（5 处裸 dp 仅 1 处同值，替换必改
 * 视觉数值或造成假耦合），故保留具名数值原样，此注释为核对记档。
 */
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
    // 主题色彩预设（2026-10-03 主题色彩批）：默认极光=现行鸢尾板，既有调用不传即零变化
    themeColorPreset: ThemeColorPreset = ThemeColorPreset.DEFAULT,
    content: @Composable () -> Unit,
) {
    MaterialExpressiveTheme(
        colorScheme = resolveQimengColorScheme(darkTheme, themeColorPreset, LocalContext.current),
        // M3 Expressive spring 物理动效（ADR-0031 动效规范第一层）；motionScheme 传 null 会
        // 回落 M3 标准动效，必须显式给 expressive
        motionScheme = MotionScheme.expressive(),
        shapes = qimengShapes,
        typography = QimengTypography,
        content = content,
    )
}

/**
 * 动态取色的最低 SDK：Material You 壁纸动态取色 API 31（Android 12）起提供，
 * material3 的 dynamicLight/DarkColorScheme 在更低版本调用无意义——低于此档回落
 * AURORA 基座（老设备用户选「动态取色」得到默认品牌观感，设置页文案已注明跟随壁纸）。
 */
private const val DYNAMIC_COLOR_MIN_SDK = Build.VERSION_CODES.S

/**
 * (日夜, 预设) → colorScheme 的装配单点（SDK_INT 判定收敛在此一处，禁止散落别处）：
 * - DYNAMIC 且 API 31+：走 material3 官方壁纸取色（浅/深由系统动态板各自给出）；
 * - DYNAMIC 但 API < 31：回落 AURORA 基座（与默认档同观感），注释记档的既定降级口径；
 * - 其余预设：基座（现版日夜板）.copy 覆写强调色 12 槽——inversePrimary 取同预设
 *   对侧日夜的主色（沿用基座「互为对方主色」的既有语义）。
 */
private fun resolveQimengColorScheme(
    dark: Boolean,
    preset: ThemeColorPreset,
    context: Context,
): ColorScheme {
    if (preset == ThemeColorPreset.DYNAMIC && Build.VERSION.SDK_INT >= DYNAMIC_COLOR_MIN_SDK) {
        return if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    // DYNAMIC 在低版本/非动态路径统一按 AURORA 处理（themePresetRoles 的 DYNAMIC 分支
    // 同口径回落，双保险防绕过装配层直调）
    val effectivePreset =
        if (preset == ThemeColorPreset.DYNAMIC) ThemeColorPreset.DEFAULT else preset
    val base = if (dark) DarkColorScheme else LightColorScheme
    val roles = themePresetRoles(effectivePreset, dark)
    val inverseRoles = themePresetRoles(effectivePreset, !dark)
    return base.copy(
        primary = roles.primary,
        onPrimary = roles.onPrimary,
        primaryContainer = roles.primaryContainer,
        onPrimaryContainer = roles.onPrimaryContainer,
        inversePrimary = inverseRoles.primary,
        secondary = roles.secondary,
        onSecondary = roles.onSecondary,
        secondaryContainer = roles.secondaryContainer,
        onSecondaryContainer = roles.onSecondaryContainer,
        tertiary = roles.tertiary,
        onTertiary = roles.onTertiary,
        tertiaryContainer = roles.tertiaryContainer,
        onTertiaryContainer = roles.onTertiaryContainer,
        // surfaceTint 随主色联动：基座显式设了 surfaceTint=primary（M3 tonal 抬升语义），
        // 换预设后不同步会在可抬升表面漏出旧鸢尾色（AURORA 档两值恒等，零变化）
        surfaceTint = roles.primary,
    )
}
