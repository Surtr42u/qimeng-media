package media.qimeng.app.navigation

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import media.qimeng.app.core.ui.component.TabScrollController
import media.qimeng.app.core.ui.theme.QimengBrandColors
import media.qimeng.app.feature.all.AllScreen
import media.qimeng.app.feature.author.AuthorCollectionRoutes
import media.qimeng.app.feature.author.AuthorCollectionScreen
import media.qimeng.app.feature.author.AuthorScreen
import media.qimeng.app.feature.detail.DetailRoutes
import media.qimeng.app.feature.detail.DetailScreen
import media.qimeng.app.feature.favorite.FavoriteScreen
import media.qimeng.app.feature.history.HistoryScreen
import media.qimeng.app.feature.home.HomeScreen
import media.qimeng.app.feature.login.LoginScreen
import media.qimeng.app.feature.search.SearchScreen
import media.qimeng.app.feature.settings.SettingsScreen
import media.qimeng.app.feature.stats.StatsDetailRoutes
import media.qimeng.app.feature.stats.StatsDetailScreen
import media.qimeng.app.feature.stats.StatsScreen
import media.qimeng.app.feature.upload.UploadScreen
import media.qimeng.app.session.MainViewModel
import media.qimeng.app.session.SessionState

/** 导航路由契约（壳层独占；feature 只暴露 Screen+回调。例外：详情路由串/参数键单源在
 *  feature:detail 的 [DetailRoutes]——feature 禁依赖 :app，壳层反向引用此处合法） */
object Routes {
    /**
     * 覆盖页面：搜索（首页搜索框进入；GUIDE_UI §导航结构 入栈隐藏底栏）。
     * 任务I I3 加可选携词参数（GUIDE_UI §统计详情页「标签 → 搜索页携词跳转」管道）：
     * 未带词的入口仍 navigate 到 [SEARCH_NAV]（可选参数缺省走 defaultValue）。
     */
    const val SEARCH = "search?q={q}"

    /** 搜索页无参导航地址（q 可选参数缺省空串=入口态，行为与旧无参路由一致） */
    const val SEARCH_NAV = "search"

    /** 搜索携词参数键（[SEARCH] 路由占位符） */
    const val KEY_SEARCH_QUERY = "q"

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
    // 底栏选中指示器色（F 批 2026-09-09 接线）：旧版 styles.xml BottomNavigationView
    // ActiveIndicator = qm_primary_soft 浅色 #123A3A3A / 夜间 #1AC8C8C8（Color.kt 具名 token
    // 早已备好但从未接线）——此前默认 indicator=secondaryContainer(=ChipBg) 与底栏背景色差
    // 仅 2/255，选中胶囊肉眼不可见（用户反馈「没做的圆润边角」）；选中 icon/label 同步取
    // onSurface（旧版选中图标=主色深灰，默认 onSecondaryContainer 次级灰观感偏淡）
    val darkTheme = isSystemInDarkTheme()
    val indicatorColor = if (darkTheme) QimengBrandColors.PrimarySoftDark else QimengBrandColors.PrimarySoftLight

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
                            // F 批：指示器/选中色接线（见上方 darkTheme 处注释），未选色保持
                            // M3 默认（onSurfaceVariant=旧版次级灰）
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = indicatorColor,
                                selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.HOME.route,
            // consumeWindowInsets（任务G3 双重留白清偿）：主壳 Scaffold 无 topBar，innerPadding
            // 的 top=状态栏高；不消费则覆盖页内嵌的 QimengTopBar（M3 TopAppBar 默认
            // windowInsets=statusBars）会再自留一段状态栏高度——标题上方两倍空白。
            // padding 后消费=Scaffold 官方范式，嵌套组件读到已消耗的 insets 归零
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            composable(TopLevelDestination.HOME.route) {
                HomeScreen(
                    onOpenSearch = { navController.navigate(Routes.SEARCH_NAV) },
                    onOpenAsset = { assetId -> navController.navigate(DetailRoutes.detailRoute(assetId)) },
                )
            }
            composable(TopLevelDestination.ALL.route) {
                AllScreen(
                    onOpenAsset = { assetId -> navController.navigate(DetailRoutes.detailRoute(assetId)) },
                )
            }
            composable(TopLevelDestination.STATS.route) {
                StatsScreen(
                    // 趋势卡/分布入口卡点击进统计详情页（任务I I3；GUIDE_UI §数据统计页 L213-214，
                    // 携带当前时间范围——GUIDE §交互设计「进入详情携带当前时间范围」）
                    onOpenDetail = { mode, range ->
                        navController.navigate(StatsDetailRoutes.statsDetailRoute(mode, range))
                    },
                )
            }
            composable(TopLevelDestination.SETTINGS.route) {
                SettingsScreen(
                    onOpenFavorite = { navController.navigate(Routes.FAVORITE) },
                    onOpenHistory = { navController.navigate(Routes.HISTORY) },
                    onOpenAuthors = { navController.navigate(Routes.AUTHORS) },
                    onOpenUpload = { navController.navigate(Routes.UPLOAD) },
                )
            }
            composable(
                route = Routes.SEARCH,
                arguments = listOf(
                    navArgument(Routes.KEY_SEARCH_QUERY) {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                SearchScreen(
                    initialQuery = entry.arguments?.getString(Routes.KEY_SEARCH_QUERY)
                        ?.takeUnless { it.isBlank() },
                    onBack = { navController.popBackStack() },
                    onOpenAsset = { assetId -> navController.navigate(DetailRoutes.detailRoute(assetId)) },
                )
            }
            composable(Routes.FAVORITE) {
                FavoriteScreen(
                    onBack = { navController.popBackStack() },
                    onOpenAsset = { assetId -> navController.navigate(DetailRoutes.detailRoute(assetId)) },
                )
            }
            composable(Routes.HISTORY) {
                HistoryScreen(
                    onBack = { navController.popBackStack() },
                    onOpenAsset = { assetId -> navController.navigate(DetailRoutes.detailRoute(assetId)) },
                )
            }
            composable(Routes.AUTHORS) {
                AuthorScreen(
                    onBack = { navController.popBackStack() },
                    // 行点击进作者集合页（任务G G1b 接线：Web /app/collection/author/{name}
                    // 等价物；路由带 id+名字双参数，见 AuthorCollectionRoutes 注释）
                    onAuthorClick = { authorId, displayName ->
                        navController.navigate(AuthorCollectionRoutes.authorCollectionRoute(authorId, displayName))
                    },
                )
            }
            // 作者集合页（任务G G1b）：路由契约单源在 feature:author（DetailRoutes 同范式，
            // feature 禁依赖 :app，壳层反向引用合法）；路由参数由页面 ViewModel 经
            // SavedStateHandle 读取，此处无需展开 arguments
            composable(AuthorCollectionRoutes.AUTHOR_COLLECTION_ROUTE) {
                AuthorCollectionScreen(
                    onBack = { navController.popBackStack() },
                    onOpenAsset = { assetId -> navController.navigate(DetailRoutes.detailRoute(assetId)) },
                )
            }
            composable(Routes.UPLOAD) {
                UploadScreen(
                    sharedUris = sharedUris,
                    onSharedConsumed = onSharedConsumed,
                    onDone = { navController.popBackStack() },
                )
            }
            // 统计详情页（任务I I3）：路由契约单源在 feature:stats（DetailRoutes/AuthorCollectionRoutes
            // 同范式），mode/range 参数由页面 ViewModel 经 SavedStateHandle 读取，此处无需展开 arguments
            composable(StatsDetailRoutes.STATS_DETAIL_ROUTE) {
                StatsDetailScreen(
                    onBack = { navController.popBackStack() },
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
                    // 作者卡名字点击进作者集合页（任务G G1b 接线；原始名不带 ·COS 后缀）
                    onOpenAuthor = { authorId, displayName ->
                        navController.navigate(AuthorCollectionRoutes.authorCollectionRoute(authorId, displayName))
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
