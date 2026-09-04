package media.qimeng.app.navigation

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import media.qimeng.app.R
import media.qimeng.app.core.ui.icon.AlbumIcon
import media.qimeng.app.core.ui.icon.AllFilesIcon
import media.qimeng.app.core.ui.icon.HomeIcon
import media.qimeng.app.core.ui.icon.ProfileIcon
import media.qimeng.app.core.ui.icon.StatsIcon

/**
 * 底部五 Tab 目的地（顺序冻结：首页/全部/相册/数据/我的，规格 = 旧仓库 GUIDE_UI §导航结构）。
 * 路由字符串是导航契约的一部分，单测锁定其唯一性与顺序（TopLevelDestinationTest）。
 */
enum class TopLevelDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    HOME("home", R.string.tab_home, HomeIcon),
    ALL("all", R.string.tab_all, AllFilesIcon),
    ALBUM("album", R.string.tab_album, AlbumIcon),
    STATS("stats", R.string.tab_stats, StatsIcon),
    SETTINGS("settings", R.string.tab_settings, ProfileIcon),
}
