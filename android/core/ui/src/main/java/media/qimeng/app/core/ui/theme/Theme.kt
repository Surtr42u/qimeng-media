package media.qimeng.app.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * 绮梦影库 Material 3 主题。
 * 色板锚定 web/src/styles/prototype.css 品牌主色 #4250af 系（详见 [QimengBrandColors] 注释）。
 * 动态色彩（Material You 取色）默认关闭——品牌一致性优先；是否开放随 M4-6 设置页批次由用户拍板。
 */
private val LightColorScheme = lightColorScheme(
    primary = QimengBrandColors.PrimaryLight,
    onPrimary = QimengBrandColors.OnPrimaryLight,
    primaryContainer = QimengBrandColors.PrimaryContainerLight,
    onPrimaryContainer = QimengBrandColors.AccentDeep,
    secondary = QimengBrandColors.AccentDeep,
    onSecondary = QimengBrandColors.OnPrimaryLight,
    background = QimengBrandColors.BackgroundLight,
    onBackground = QimengBrandColors.TextMainLight,
    surface = QimengBrandColors.BackgroundLight,
    onSurface = QimengBrandColors.TextMainLight,
    surfaceVariant = QimengBrandColors.HoverBgLight,
    onSurfaceVariant = QimengBrandColors.TextMidLight,
    outline = QimengBrandColors.FaintLight,
    outlineVariant = QimengBrandColors.BorderLight,
)

private val DarkColorScheme = darkColorScheme(
    primary = QimengBrandColors.PrimaryDark,
    onPrimary = QimengBrandColors.OnPrimaryDark,
    primaryContainer = QimengBrandColors.AccentDeep,
    onPrimaryContainer = QimengBrandColors.PrimaryContainerDark,
    secondary = QimengBrandColors.PrimaryDark,
    onSecondary = QimengBrandColors.OnPrimaryDark,
    background = QimengBrandColors.BackgroundDark,
    onBackground = QimengBrandColors.TextMainDark,
    surface = QimengBrandColors.BackgroundDark,
    onSurface = QimengBrandColors.TextMainDark,
    surfaceVariant = QimengBrandColors.HoverBgDark,
    onSurfaceVariant = QimengBrandColors.TextMidDark,
    outline = QimengBrandColors.FaintDark,
    outlineVariant = QimengBrandColors.BorderDark,
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
