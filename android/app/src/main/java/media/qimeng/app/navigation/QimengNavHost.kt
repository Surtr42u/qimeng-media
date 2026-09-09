package media.qimeng.app.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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

    /**
     * 搜索携词导航地址（任务J J1 接线：统计常看标签条目→搜索页携词，GUIDE_UI §统计详情页）：
     * query 必须 URL 编码——标签可含中文/空格/&/# 等，裸拼会劈裂 query 或吞掉后续参数。
     */
    fun searchRoute(query: String): String = "$SEARCH_NAV?$KEY_SEARCH_QUERY=${encodeQueryValue(query)}"

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
    // 上一次实际执行顶层导航的时间戳（任务L L2 防抖基准；双击回顶不重置此值，
    // 使「回顶后立刻切 Tab」仍受防抖保护）
    var lastNavigateTimeMs by remember { mutableLongStateOf(0L) }
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
                                // 任务L L2：决策抽纯函数 [resolveTabTapAction]——双击回顶优先
                                // （不受防抖限制），防抖窗内忽略导航，窗后首击放行
                                when (
                                    resolveTabTapAction(
                                        isCurrentRoute = currentRoute == destination.route,
                                        nowMs = now,
                                        lastTapMs = lastTabTapTimeMs,
                                        lastNavigateMs = lastNavigateTimeMs,
                                    )
                                ) {
                                    TabTapAction.ScrollToTop ->
                                        TabScrollController.requestScrollToTop(destination.route)
                                    TabTapAction.Navigate -> {
                                        lastNavigateTimeMs = now
                                        navigateTopLevel(navController, destination)
                                    }
                                    TabTapAction.Ignore -> Unit
                                }
                                lastTabTapTimeMs = now
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
            // 任务L L2（拍板 #2「NavHost 顶层切换确保无 enter/exit 转场动画叠影」）：
            // Navigation Compose 2.7+ 默认转场为 crossfade（新页 fadeIn 220ms 延迟 90ms 叠着
            // 旧页 fadeOut）——快速切 Tab 时新旧两页同屏，正是用户「叠屏/延迟消失」观感的
            // 动画根因。旧版 Fragment show/hide 无转场，故四处转场全置 None（瞬时切换）；
            // 覆盖页/详情页进出同样瞬时（旧版同为无转场观感）。与防抖双保险，防叠加。
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
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
                // 统计族跳转回调组单源（RES R1 去重，构造见 [statsNavLinks]）
                val links = statsNavLinks(navController)
                StatsScreen(
                    // 趋势卡/分布入口卡点击进统计详情页（任务I I3；GUIDE_UI §数据统计页 L213-214，
                    // 携带当前时间范围——GUIDE §交互设计「进入详情携带当前时间范围」）
                    onOpenDetail = { mode, range ->
                        navController.navigate(StatsDetailRoutes.statsDetailRoute(mode, range))
                    },
                    // 任务J J1 详情页跳转链（GUIDE_UI L218-224）：常看文件条目→详情页（批次
                    // 上下文已由 StatsViewModel.enterDetail 写入）、作者条目→作者集合页、
                    // 标签条目→搜索页携词（query 编码见 Routes.searchRoute）
                    onOpenAsset = links.onOpenAsset,
                    onOpenAuthor = links.onOpenAuthor,
                    onOpenTagSearch = links.onOpenTagSearch,
                )
            }
            composable(TopLevelDestination.SETTINGS.route) {
                SettingsScreen(
                    onOpenFavorite = { navController.navigate(Routes.FAVORITE) },
                    onOpenHistory = { navController.navigate(Routes.HISTORY) },
                    onOpenAuthors = { navController.navigate(Routes.AUTHORS) },
                    onOpenUpload = { navController.navigate(Routes.UPLOAD) },
                    // RES R2：总览卡 Top5 行直达作者集合页（清偿 I4「待壳层共享窗口」挂账）
                    onOpenAuthorCollection = { authorId, displayName ->
                        navController.navigate(AuthorCollectionRoutes.authorCollectionRoute(authorId, displayName))
                    },
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
                // 统计族跳转回调组单源（RES R1 去重，构造见 [statsNavLinks]）
                val links = statsNavLinks(navController)
                StatsDetailScreen(
                    onBack = { navController.popBackStack() },
                    // 任务J J1 详情页跳转链（GUIDE_UI L218-224）：seconds 榜条目→详情页
                    // （批次上下文已由 StatsDetailViewModel.enterDetail 写入 Top20 快照）、
                    // 作者条目→作者集合页、标签条目→搜索页携词
                    onOpenAsset = links.onOpenAsset,
                    onOpenAuthor = links.onOpenAuthor,
                    onOpenTagSearch = links.onOpenTagSearch,
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

/**
 * 统计族（统计页/统计详情页）详情跳转回调组（RES R1 去重）：两页的三回调接线原本逐字重复，
 * 收敛为参数对象单源构造——后续统计族新增跳转链只改 [statsNavLinks] 一处。
 */
private data class StatsNavLinks(
    val onOpenAsset: (assetId: String) -> Unit,
    val onOpenAuthor: (authorId: String, displayName: String) -> Unit,
    val onOpenTagSearch: (tag: String) -> Unit,
)

/** 以 navController 构造统计族跳转回调组（路由串单源：DetailRoutes/AuthorCollectionRoutes/Routes） */
private fun statsNavLinks(navController: NavHostController): StatsNavLinks = StatsNavLinks(
    onOpenAsset = { assetId -> navController.navigate(DetailRoutes.detailRoute(assetId)) },
    onOpenAuthor = { authorId, displayName ->
        navController.navigate(AuthorCollectionRoutes.authorCollectionRoute(authorId, displayName))
    },
    onOpenTagSearch = { tag -> navController.navigate(Routes.searchRoute(tag)) },
)

/**
 * query 值百分号编码（[Routes.searchRoute] 专用；RFC 3986 unreserved 之外一律 %XX）。
 * 为什么自持编码器而不用 android.net.Uri.encode：与 feature:author 的 encodeRouteSegment
 * 同款理由——行为确定性优先（Uri.encode 的默认保留集含 &/= 等 query 结构字符的版本行为
 * 不做记忆依赖），且本地 JVM 单测跑在 android.jar stub 上平台 API 不可用。不复用
 * encodeRouteSegment 本体：internal 跨模块不可见，query/路径段语义略异——两处注释互指，
 * 标签词表变更时同步自查。internal 供同模块单测锁定（encodeRouteSegment 同款处置）。
 */
internal fun encodeQueryValue(value: String): String = buildString {
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val c = byte.toInt() and 0xFF
        val unreserved = c < QUERY_ASCII_BOUNDARY &&
            (c.toChar().isLetterOrDigit() || c.toChar() in QUERY_UNRESERVED_SYMBOLS)
        if (unreserved) append(c.toChar()) else {
            append('%')
            append(QUERY_HEX_DIGITS[c ushr 4])
            append(QUERY_HEX_DIGITS[c and 0xF])
        }
    }
}

/** ASCII 单字节边界（≥128 的多字节 UTF-8 序列成分，恒编码） */
private const val QUERY_ASCII_BOUNDARY = 0x80

/** RFC 3986 unreserved 符号集（字母数字之外；与 feature:author encodeRouteSegment 同集） */
private const val QUERY_UNRESERVED_SYMBOLS = "-_.~"

private const val QUERY_HEX_DIGITS = "0123456789ABCDEF"

/** 双击回顶判定窗口（GUIDE_UI §导航结构：400ms 内同一 Tab 二击）。internal 供单测锁定 */
internal const val DOUBLE_TAP_WINDOW_MS = 400L

/**
 * 顶层导航防抖窗（任务L L2，拍板 #2：150~250ms 窗口内只认一次，取中档 200ms）。
 * 用户实测（#37 同源反馈）：快速连点不同 Tab 时每次点击都触发 saveState/restoreState
 * 重建链，观感为「叠屏/延迟消失」——窗口内忽略后续点击，只认窗后首击。
 * 与 [DOUBLE_TAP_WINDOW_MS] 的关系：双击回顶判定先行且不受此窗限制（同 Tab 二击
 * 200~400ms 区间仍能回顶），防抖只拦「导航」不拦「回顶」。internal 供单测锁定
 */
internal const val TAB_NAVIGATE_DEBOUNCE_MS = 200L

/** Tab 点击决策结果（[resolveTabTapAction] 纯函数输出；壳层 onClick 按分支执行） */
internal sealed interface TabTapAction {
    /** 双击回顶：广播 [TabScrollController.requestScrollToTop]，不导航不重置防抖计时 */
    data object ScrollToTop : TabTapAction

    /** 执行顶层导航（更新防抖时间戳） */
    data object Navigate : TabTapAction

    /** 防抖窗内忽略本次点击 */
    data object Ignore : TabTapAction
}

/**
 * Tab 点击决策纯函数（任务L L2；抽出便于 JVM 单测锁定时序语义）。
 * 优先级：双击回顶 > 防抖导航 > 忽略——双击回顶不受防抖窗限制（拍板 #2「语义保留且优先」）。
 *
 * @param isCurrentRoute 点击的 Tab 是否就是当前所在 Tab（双击回顶的必要条件）
 * @param nowMs 本次点击时刻
 * @param lastTapMs 上一次任意 Tab 点击时刻（双击窗基准；任意 Tab 共享，与既有行为一致）
 * @param lastNavigateMs 上一次**实际执行**顶层导航的时刻（防抖窗基准；回顶不重置）
 */
internal fun resolveTabTapAction(
    isCurrentRoute: Boolean,
    nowMs: Long,
    lastTapMs: Long,
    lastNavigateMs: Long,
): TabTapAction = when {
    isCurrentRoute && nowMs - lastTapMs <= DOUBLE_TAP_WINDOW_MS -> TabTapAction.ScrollToTop
    nowMs - lastNavigateMs >= TAB_NAVIGATE_DEBOUNCE_MS -> TabTapAction.Navigate
    else -> TabTapAction.Ignore
}

/** 四 Tab 切换统一走此函数：单顶 + 保存/恢复状态（见主壳注释的保活语义说明） */
private fun navigateTopLevel(navController: NavHostController, destination: TopLevelDestination) {
    navController.navigate(destination.route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
