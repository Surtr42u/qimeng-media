package media.qimeng.app.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.luminance

/**
 * 绮梦影库 Material 3 主题。
 * 色板唯一来源 = 旧仓库 QimengMedia `app/src/main/res/values/colors.xml`（浅色）+
 * `values-night/colors.xml`（深色）的中性极简灰系（详见 [QimengBrandColors] 注释）；
 * 角色映射依据旧版 `values/themes.xml` / `values-night/themes.xml` 的实际用途：
 * colorPrimary→primary、colorOnPrimary(@color/qm_bg)→onPrimary、colorSurface(@color/qm_surface)→surface、
 * colorOnSurface(@color/qm_text_primary)→onSurface/onBackground、qmColorBg→background、
 * qmColorSurfaceSoft→surfaceVariant、qmColorTextSecondary→onSurfaceVariant、
 * qmColorChipBg（未选中胶囊底）→secondaryContainer、qmColorDivider→outlineVariant。
 * 旧版无描边设计（胶囊 chipStrokeWidth=0dp、卡片纯色无框），outline 取次级文字灰近似
 * （无旧版 token，近似值——M3 outline 需中灰可辨识）。
 * 未列出的 error/scrim 槽位保持 M3 基线默认（旧版同样未定制错误色，灰系不覆盖错误色）。
 * 动态色彩（Material You 取色）默认关闭——旧版观感一致性优先；是否开放随 M4-6 设置页批次由用户拍板。
 */
private val LightColorScheme = lightColorScheme(
    primary = QimengBrandColors.PrimaryLight,
    onPrimary = QimengBrandColors.BgLight,
    primaryContainer = QimengBrandColors.PrimaryContainerLight,
    onPrimaryContainer = QimengBrandColors.TextPrimaryLight,
    inversePrimary = QimengBrandColors.PrimaryDark,
    secondary = QimengBrandColors.AccentLight,
    onSecondary = QimengBrandColors.BgLight,
    secondaryContainer = QimengBrandColors.ChipBgLight,
    onSecondaryContainer = QimengBrandColors.TextSecondaryLight,
    tertiary = QimengBrandColors.AccentLight,
    onTertiary = QimengBrandColors.BgLight,
    tertiaryContainer = QimengBrandColors.SurfaceSoftLight,
    onTertiaryContainer = QimengBrandColors.TextSecondaryLight,
    background = QimengBrandColors.BgLight,
    onBackground = QimengBrandColors.TextPrimaryLight,
    surface = QimengBrandColors.SurfaceLight,
    onSurface = QimengBrandColors.TextPrimaryLight,
    surfaceVariant = QimengBrandColors.SurfaceSoftLight,
    onSurfaceVariant = QimengBrandColors.TextSecondaryLight,
    // 旧版为平面纯色分层（白卡浮于微灰底，无海拔着色）；surfaceTint 与 surface 同色使
    // tonalElevation 视觉无效，保住旧版纯白卡片观感（无旧版 token，近似值）
    surfaceTint = QimengBrandColors.SurfaceLight,
    surfaceBright = QimengBrandColors.SurfaceLight,
    // 浅色灰阶从亮到暗只有 白/FAFAFA/F2F2F4/F0F0F2/ECECEE 五档，以下槽位按亮度依次落档，
    // surfaceDim 与 surfaceContainerHighest 同档合并（旧版灰阶档位有限，无更暗 token）
    surfaceDim = QimengBrandColors.DividerLight,
    surfaceContainerLowest = QimengBrandColors.SurfaceLight,
    surfaceContainerLow = QimengBrandColors.BgLight,
    surfaceContainer = QimengBrandColors.SurfaceSoftLight,
    surfaceContainerHigh = QimengBrandColors.ChipBgLight,
    surfaceContainerHighest = QimengBrandColors.DividerLight,
    inverseSurface = QimengBrandColors.TextPrimaryLight,
    inverseOnSurface = QimengBrandColors.SurfaceLight,
    outline = QimengBrandColors.TextSecondaryLight,
    outlineVariant = QimengBrandColors.DividerLight,
)

private val DarkColorScheme = darkColorScheme(
    primary = QimengBrandColors.PrimaryDark,
    onPrimary = QimengBrandColors.BgDark,
    primaryContainer = QimengBrandColors.PrimaryContainerDark,
    onPrimaryContainer = QimengBrandColors.TextPrimaryDark,
    inversePrimary = QimengBrandColors.PrimaryLight,
    secondary = QimengBrandColors.AccentDark,
    onSecondary = QimengBrandColors.BgDark,
    secondaryContainer = QimengBrandColors.ChipBgDark,
    onSecondaryContainer = QimengBrandColors.TextSecondaryDark,
    tertiary = QimengBrandColors.AccentDark,
    onTertiary = QimengBrandColors.BgDark,
    tertiaryContainer = QimengBrandColors.SurfaceSoftDark,
    onTertiaryContainer = QimengBrandColors.TextSecondaryDark,
    background = QimengBrandColors.BgDark,
    onBackground = QimengBrandColors.TextPrimaryDark,
    surface = QimengBrandColors.SurfaceDark,
    onSurface = QimengBrandColors.TextPrimaryDark,
    surfaceVariant = QimengBrandColors.SurfaceSoftDark,
    onSurfaceVariant = QimengBrandColors.TextSecondaryDark,
    // 夜间「略抬起」方向与 M3 tonal elevation 一致，surfaceTint 取夜色主色浅灰（旧版默认行为即 primary）
    surfaceTint = QimengBrandColors.PrimaryDark,
    // 夜间灰阶从暗到亮只有 1A/24/2E/38 四档，以下槽位按亮度依次落档，
    // surfaceContainerHigh 与 surfaceContainer 同档合并（旧版夜间胶囊底即 2E2E2E，无独立档）
    surfaceBright = QimengBrandColors.DividerDark,
    surfaceDim = QimengBrandColors.BgDark,
    surfaceContainerLowest = QimengBrandColors.BgDark,
    surfaceContainerLow = QimengBrandColors.SurfaceDark,
    surfaceContainer = QimengBrandColors.SurfaceSoftDark,
    surfaceContainerHigh = QimengBrandColors.ChipBgDark,
    surfaceContainerHighest = QimengBrandColors.DividerDark,
    inverseSurface = QimengBrandColors.TextPrimaryDark,
    inverseOnSurface = QimengBrandColors.SurfaceDark,
    outline = QimengBrandColors.TextSecondaryDark,
    outlineVariant = QimengBrandColors.DividerDark,
)

@Composable
fun QimengTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = QimengTypography,
        content = content,
    )
}

/** 当前主题是否暗色（玻璃件按生效色板亮度判定，与 QimengTheme 实际渲染同源，禁直查 isSystemInDarkTheme） */
@Composable
fun isQimengDarkTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f
