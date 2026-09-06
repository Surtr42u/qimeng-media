package media.qimeng.app.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import media.qimeng.app.core.ui.component.TabScrollController
import media.qimeng.app.feature.all.AllScreen
import media.qimeng.app.feature.author.AuthorScreen
import media.qimeng.app.feature.detail.DetailRoutes
import media.qimeng.app.feature.detail.DetailScreen
import media.qimeng.app.feature.favorite.FavoriteScreen
import media.qimeng.app.feature.history.HistoryScreen
import media.qimeng.app.feature.home.HomeScreen
import media.qimeng.app.feature.login.LoginScreen
import media.qimeng.app.feature.search.SearchScreen
import media.qimeng.app.feature.settings.SettingsScreen
import media.qimeng.app.feature.stats.StatsScreen
import media.qimeng.app.feature.upload.UploadScreen
import media.qimeng.app.session.MainViewModel
import media.qimeng.app.session.SessionState

/** 导航路由契约（壳层独占；feature 只暴露 Screen+回调。例外：详情路由串/参数键单源在
 *  feature:detail 的 [DetailRoutes]——feature 禁依赖 :app，壳层反向引用此处合法） */
object Routes {
    /** 覆盖页面：搜索（首页搜索框进入；GUIDE_UI §导航结构 入栈隐藏底栏） */
    const val SEARCH = "search"

    /** 覆盖页面：收藏（我的页入口行；M4-6 完整我的页前的临时入口） */
    const val FAVORITE = "favorite"

    /** 覆盖页面：浏览历史 */
    const val HISTORY = "history"

    /** 覆盖页面：作者管理 */
    const val AUTHORS = "authors"

    /** 覆盖页面：上传（M4-5；入口 = 系统分享接收 / 后续设置页入口，不进底栏） */
    const val UPLOAD = "upload"
}

/**
 * 壳根：登录态分支（M4-1）——未登录渲染登录页，已登录进四 Tab 主壳，Loading 渲染空白防误闪。
 * 401 的跳登录也在这里发生：MainViewModel 把 SessionEventBus 事件并入会话状态，LoggedOut 一出现
 * 登录页即替换主壳（拦截器不做导航，导航属壳层关注点——M4-1 冻结口径）。
 * 切换不经 Navigation 返回栈：登录页与主壳是互斥根状态而非可互相跳转的页面。
 */
@Composable
fun QimengNavRoot(modifier: Modifier = Modifier) {
    val mainViewModel: MainViewModel = hiltViewModel()
    val sessionState by mainViewModel.sessionState.collectAsStateWithLifecycle()
    val pendingShareUris by mainViewModel.pendingShareUris.collectAsStateWithLifecycle()
    when (sessionState) {
        SessionState.Loading -> Surface(modifier = modifier.fillMaxSize()) {}
        SessionState.LoggedOut -> LoginScreen(modifier = modifier)
        SessionState.LoggedIn -> QimengNavHost(
            modifier = modifier,
            sharedUris = pendingShareUris,
            onSharedConsumed = mainViewModel::consumeSharedUris,
        )
    }
}

/**
 * 主壳导航：底部四 Tab（导航四化）+ 覆盖页面（搜索/收藏/历史/作者）+ NavHost（仅登录后可达）。
 *
 * Tab 保活语义（GUIDE_UI §导航结构：旧版 show/hide 全 Tab 存活、切换不重建）在 Navigation
 * Compose 下的等价实现 = saveState/restoreState + launchSingleTop（Android 官方底部导航模式）。
 *
 * 双击当前 Tab 回顶：400ms 内同一 Tab 二击 → TabScrollController 广播，列表页收集后
 * scrollToItem(0) 精确回顶（GUIDE_UI §导航结构）。
 *
 * 覆盖页面入栈隐藏底部导航、返回恢复（GUIDE_UI §导航结构）：按当前路由切换 bottomBar。
 */
@Composable
fun QimengNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    sharedUris: List<String> = emptyList(),
    onSharedConsumed: () -> Unit = {},
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // 双击回顶的上一击时间戳（400ms 窗口；壳层计时，列表页只听广播）
    var lastTabTapTimeMs by remember { mutableLongStateOf(0L) }

    // 系统分享接收（M4-5）：未消费的分享 URI 存在即进上传流（登录后才可达——本组合在 LoggedIn 分支）
    LaunchedEffect(sharedUris) {
        if (sharedUris.isNotEmpty()) {
            navController.navigate(Routes.UPLOAD) { launchSingleTop = true }
        }
    }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            // 覆盖页面隐藏底栏（返回自动恢复）
            if (currentRoute == null || currentRoute in topLevelRoutes) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                val now = System.currentTimeMillis()
                                val isDoubleClick =
                                    currentRoute == destination.route &&
                                        now - lastTabTapTimeMs <= DOUBLE_TAP_WINDOW_MS
                                lastTabTapTimeMs = now
                                if (isDoubleClick) {
                                    TabScrollController.requestScrollToTop(destination.route)
                                } else {
                                    navigateTopLevel(navController, destination)
                                }
                            },
                            icon = { Icon(imageVector = destination.icon, contentDescription = null) },
                            label = { Text(text = stringResource(destination.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.HOME.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(TopLevelDestination.HOME.route) {
                HomeScreen(
                    onOpenSearch = { navController.navigate(Routes.SEARCH) },
                    onOpenAsset = { assetId -> navController.navigate(DetailRoutes.detailRoute(assetId)) },
                )
            }
            composable(TopLevelDestination.ALL.route) { AllScreen() }
            composable(TopLevelDestination.STATS.route) { StatsScreen() }
            composable(TopLevelDestination.SETTINGS.route) {
                SettingsScreen(
                    onOpenFavorite = { navController.navigate(Routes.FAVORITE) },
                    onOpenHistory = { navController.navigate(Routes.HISTORY) },
                    onOpenAuthors = { navController.navigate(Routes.AUTHORS) },
                    onOpenUpload = { navController.navigate(Routes.UPLOAD) },
                )
            }
            composable(Routes.SEARCH) { SearchScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.FAVORITE) { FavoriteScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.HISTORY) { HistoryScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.AUTHORS) { AuthorScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.UPLOAD) {
                UploadScreen(
                    sharedUris = sharedUris,
                    onSharedConsumed = onSharedConsumed,
                    onDone = { navController.popBackStack() },
                )
            }
            // 详情页（M4-3）：不设 launchSingleTop——详情→详情（推荐栏跳转）保留返回栈，
            // 返回键回到上一个资产（旧版内部浏览历史栈的导航层等价语义）
            composable(DetailRoutes.DETAIL_ROUTE) { entry ->
                DetailScreen(
                    assetId = entry.arguments?.getString(DetailRoutes.KEY_ASSET_ID).orEmpty(),
                    onBack = { navController.popBackStack() },
                    onOpenAsset = { assetId, _ ->
                        // 批次清单已由 DetailViewModel.upNextJump 换成推荐栏清单，壳层只管导航
                        navController.navigate(DetailRoutes.detailRoute(assetId))
                    },
                )
            }
        }
    }
}

/** 顶层路由集合（底栏可见性判定用） */
private val topLevelRoutes = TopLevelDestination.entries.map { it.route }.toSet()

/** 双击回顶判定窗口（GUIDE_UI §导航结构：400ms 内同一 Tab 二击） */
private const val DOUBLE_TAP_WINDOW_MS = 400L

/** 四 Tab 切换统一走此函数：单顶 + 保存/恢复状态（见主壳注释的保活语义说明） */
private fun navigateTopLevel(navController: NavHostController, destination: TopLevelDestination) {
    navController.navigate(destination.route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
