package media.qimeng.app.navigation

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import media.qimeng.app.R
import media.qimeng.app.core.ui.icon.AlbumIcon
import media.qimeng.app.core.ui.icon.HomeIcon
import media.qimeng.app.core.ui.icon.ProfileIcon
import media.qimeng.app.core.ui.icon.StatsIcon

/**
 * 底部四 Tab 目的地（2026-09-05 夜用户拍板「导航四化」：首页/相册/数据/我的）。
 * 「相册」= 原「全部」更名（route `all` 与页面规格不变，只改 label 与图标语义），
 * 原「相册」Tab（ALBUM 目的地）删除——其 COS 能力由相册页分区胶囊（全部/常规/COS）承接。
 * 路由字符串是导航契约的一部分，单测锁定其唯一性与顺序（TopLevelDestinationTest）。
 */
enum class TopLevelDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    HOME("home", R.string.tab_home, HomeIcon),
    ALL("all", R.string.tab_all, AlbumIcon),
    STATS("stats", R.string.tab_stats, StatsIcon),
    SETTINGS("settings", R.string.tab_settings, ProfileIcon),
}
