package media.qimeng.app.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import media.qimeng.app.core.ui.theme.QimengBrandColors
import media.qimeng.app.core.ui.theme.isQimengDarkTheme

/**
 * CLASSIC 材质档的底栏：主线现行 M3 [NavigationBar] 逐字迁移（2026-10-03 悬浮坞改造，
 * 视觉/行为零回归的保底回退档——选它即回到改前观感）。唯一变化 = 宿主从 Scaffold
 * bottomBar 槽改为悬浮层（坞显隐条件与点击链路逐字共用）。
 *
 * 选中指示器色（F 批 2026-09-09 接线，逐字保留）：旧版 styles.xml BottomNavigationView
 * ActiveIndicator = qm_primary_soft 浅色 #123A3A3A / 夜间 #1AC8C8C8（QimengBrandColors
 * 兼容别名 token，新色板退役旧灰板后仅为本档保留）——此前默认 indicator=secondaryContainer
 * (=ChipBg) 与底栏背景色差仅 2/255，选中胶囊肉眼不可见（用户反馈「没做的圆润边角」）；
 * 选中 icon/label 同步取 onSurface（旧版选中图标=主色深灰，默认 onSecondaryContainer
 * 次级灰观感偏淡）。
 */
@Composable
internal fun ClassicBottomBar(
    currentRoute: String?,
    onSelect: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 暗色判定走主题解析单源 isQimengDarkTheme()（colorScheme 背景亮度，评审必修 2）：
    // 本批起有手动外观模式（MainActivity 解析 AppearanceMode 后定深浅），本组件若绕开
    // 主题直查系统夜间档，手动浅色 × 系统深色组合下会用夜间指示器色画在浅色坞上
    //（PrimarySoftDark 不可见）——isQimengDarkTheme 与 QimengTheme 实际生效的色板同源
    val darkTheme = isQimengDarkTheme()
    val indicatorColor =
        if (darkTheme) QimengBrandColors.PrimarySoftDark else QimengBrandColors.PrimarySoftLight
    NavigationBar(modifier = modifier) {
        TopLevelDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = currentRoute == destination.route,
                onClick = { onSelect(destination) },
                icon = { Icon(imageVector = destination.icon, contentDescription = null) },
                label = { Text(text = stringResource(destination.labelRes)) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = indicatorColor,
                    selectedIconColor = MaterialTheme.colorScheme.onSurface,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }
    }
}
