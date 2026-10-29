package media.qimeng.app.navigation

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.navigation.NavHostController
import media.qimeng.app.feature.all.AllScreen
import media.qimeng.app.feature.detail.DetailRoutes
import media.qimeng.app.feature.home.HomeScreen
import media.qimeng.app.feature.settings.SettingsScreen
import media.qimeng.app.feature.stats.StatsDetailRoutes
import media.qimeng.app.feature.stats.StatsScreen

/**
 * 常驻层按 route 渲染的 Tab 真身。回调接线逐字自原 NavHost 四个 Tab composable 迁移
 * （2026-09-13 常驻层改造，屏与跳转目标均不动）；NavHost 内同路由为空壳跳板，本函数仅
 * 由常驻层调用。
 */
@Composable
internal fun ResidentTabScreen(
    route: String,
    navController: NavHostController,
    // 性能联动（2026-10-03 帧实测批）：首页顶行真采样跟随坞材质档传入（玻璃档才捕获）
    glassEnabled: Boolean,
) {
    when (route) {
        TopLevelDestination.HOME.route -> HomeScreen(
            glassEnabled = glassEnabled,
            onOpenSearch = { navController.navigate(Routes.SEARCH_NAV) },
            onOpenAsset = { assetId ->
                navController.navigate(DetailRoutes.detailRoute(assetId))
            },
        )
        TopLevelDestination.ALL.route -> AllScreen(
            onOpenAsset = { assetId ->
                navController.navigate(DetailRoutes.detailRoute(assetId))
            },
        )
        TopLevelDestination.STATS.route -> {
            // 统计族跳转回调组单源（RES R1 去重，构造见 [statsNavLinks]）
            val links = statsNavLinks(navController)
            StatsScreen(
                // 趋势卡/分布入口卡点击进统计详情页（任务I I3；GUIDE_UI §数据统计页
                // L213-214，携带当前时间范围——GUIDE §交互设计「进入详情携带当前时间范围」）
                onOpenDetail = { mode, range ->
                    navController.navigate(StatsDetailRoutes.statsDetailRoute(mode, range))
                },
                // 作者与标签仍跳转现有集合/搜索页。
                onOpenAuthor = links.onOpenAuthor,
                onOpenTagSearch = links.onOpenTagSearch,
            )
        }
        TopLevelDestination.SETTINGS.route -> SettingsScreen(
            onOpenFavorite = { navController.navigate(Routes.FAVORITE) },
            onOpenHistory = { navController.navigate(Routes.HISTORY) },
            // X5 批 2026-09-12：作者总览改收藏同款入口行，onOpenAuthors=唯一作者入口；
            // 原总览卡 Top5 直达作者集合页的参数随内嵌卡退役，行级直达
            // 统一走 AuthorScreen 的 onAuthorClick（Routes.AUTHORS 组合内）
            onOpenAuthors = { navController.navigate(Routes.AUTHORS) },
            // U10-6：原「上传文件」行升级为「数据管理」合并入口行（上传/注册媒体目录/
            // 库管理进 hub 二级页；上传页本身复用 Routes.UPLOAD 不搬路由）
            onOpenDataManage = { navController.navigate(Routes.DATA_MANAGE) },
            // 2026-09-28 归档文件夹批：原 onOpenInbox（下载收件箱行）随入口迁入数据管理
            // hub 退役——接线改在上方 Routes.DATA_MANAGE 组合处
            // U10-4：「服务器」入口行 → 地址修改/本机模式/换址说明子页
            onOpenServerDetail = { navController.navigate(Routes.SERVER) },
        )
        else -> Unit // 不可达：route 恒来自 visitedTabs（仅含 Tab 路由）；兜底防脏键
    }
}

/**
 * 触摸死层 modifier：把落在本层的触摸整段（按下→移动→抬起）全量消费。服务「保持组合但
 * 不可交互」的隔离场景（常驻层隐藏 Tab 的兜底层、pushed 屏幕帘）——命中测试层面它带
 * pointer input 节点，触摸沿 z 序命中即止（不再下探）；事件层面 consume 保证无漏网。
 * 只承接「上层没接住的穿透触摸」，这类触摸在改前同样无处可去（落到 Scaffold 背景），
 * 吞掉即行为等价。
 */
internal fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        while (true) {
            val event = awaitPointerEvent()
            event.changes.forEach { change -> change.consume() }
            if (event.changes.none { it.pressed }) break
        }
    }
}
