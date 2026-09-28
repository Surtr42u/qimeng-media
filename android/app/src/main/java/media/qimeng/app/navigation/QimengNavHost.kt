package media.qimeng.app.navigation

import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
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
import media.qimeng.app.feature.detail.AssetEditRoutes
import media.qimeng.app.feature.detail.AssetEditScreen
import media.qimeng.app.feature.detail.DetailRoutes
import media.qimeng.app.feature.detail.DetailScreen
import media.qimeng.app.feature.favorite.FavoriteScreen
import media.qimeng.app.feature.history.HistoryScreen
import media.qimeng.app.feature.home.HomeScreen
import media.qimeng.app.feature.login.LoginScreen
import media.qimeng.app.feature.manage.AuthorTxtImportScreen
import media.qimeng.app.feature.manage.BackupScreen
import media.qimeng.app.feature.manage.DataManageScreen
import media.qimeng.app.feature.manage.LibraryManageScreen
import media.qimeng.app.feature.manage.ThumbnailCacheScreen
import media.qimeng.app.feature.search.SearchScreen
import media.qimeng.app.feature.settings.InboxSettingsScreen
import media.qimeng.app.feature.settings.ServerSettingsScreen
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

    /** 覆盖页面：服务器设置（U10-4：设置页「服务器」入口行 → 地址修改/本机模式/换址说明子页） */
    const val SERVER = "server"

    /**
     * 覆盖页面：上传收件箱与归档设置（2026-09-25 暂存区重做：收件箱路径选择子页，路径
     * 持久化后作为上传页「从收件箱导入」的扫描源；2026-09-28 归档文件夹批：子页扩归档
     * 文件夹双设定并改由数据管理 hub 进入——路由串不变，仅入口行搬家）
     */
    const val INBOX = "inbox"

    /**
     * 覆盖页面：数据管理 hub（U10-6：我的页「数据管理」合并入口 → 上传文件/库管理
     * 二级分类入口；上传页复用既有 [UPLOAD] 路由不搬家）
     */
    const val DATA_MANAGE = "dataManage"

    /** 覆盖页面：库管理（U10-6：数据管理 hub → 库列表/注册媒体目录，信息架构对齐 Web 文件管理页） */
    const val LIBRARY_MANAGE = "libraryManage"

    /** 覆盖页面：作者 TXT 导入（U10-6b：数据管理 hub → 片段列表/导入/移除/重放，
     *  对齐 Web 文件管理页 TxtAuthorImportCard） */
    const val AUTHOR_TXT_IMPORT = "authorTxtImport"

    /** 覆盖页面：备份导入导出（U10-6b：数据管理 hub → 全量备份导出/qimeng_backup.json 幂等导入恢复） */
    const val BACKUP = "backup"

    /** 覆盖页面：缩略图缓存（2026-09-16 用户反馈：缩略图生成进度 + 缓存上限合并页，
     *  自我的页「缓存区」退役迁入，经数据管理 hub 二级入口可达） */
    const val THUMBNAIL_CACHE = "thumbnail_cache"
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
 * ## Tab 常驻层（2026-09-13 根治「Tab 切换闪烁/残留」，用户两次真机反馈）
 *
 * Tab 保活语义（GUIDE_UI §导航结构：旧版 show/hide 全 Tab 存活、切换不重建）。此前纯
 * Navigation 方案（saveState/restoreState + launchSingleTop 官方底部导航模式）只保**状态**
 * 不保**组合**：NavHost 换 destination = 旧屏出树 + 新屏全量组合，瞬时转场（2026-09-12
 * 置 None；任务U9 2026-09-14 起 fade*(snap())，None 在转场滞留时两页全不透明同屏=叠层
 * 残留，见 NavHost 转场注释）只消灭动画叠影，交换窗口仍在——新屏组合期间旧屏滞留（残留）
 * 或露背景（闪烁），壳层代码零改动下窗口依旧可见（真机 R8 包 + 高刷放大，Y5 强制最高刷）。旧版
 * （QimengMedia MainActivity）Fragment 常驻 add、切换 = hide 全部 + show 目标，零组合成本
 * 零交换窗口。本版对齐该机制，内容区改双载体：
 *
 * 1. **常驻层**：NavHost 之下（zIndex(-1)）的常驻容器。四个 Tab 屏**懒驻留**——首次点击才
 *    进 [visitedTabs]（对齐旧版 fragmentCache 首访才 add），此后永不离树；非当前 Tab 用
 *    graphicsLayer alpha=0（保持组合不绘制）+ clearAndSetSemantics（a11y 不可达，对齐旧版
 *    hide 的 GONE 语义）+ 触摸死层（见 [blockTouches]，防非交互区命中穿透）三重隔离压在
 *    当前 Tab（zIndex=1）之下。每屏包 SaveableStateProvider(route)（rememberSaveableStateHolder）
 *    保 rememberSaveable 状态。
 * 2. **跳板**：NavHost 内四 Tab 路由只留空壳、不渲染真实屏，仅承担 Tab 的路由/返回栈/
 *    saveState/restoreState 语义与 pushed 路由的载体（detail/search/... 照旧由 NavHost
 *    渲染真实屏，既有转场与不透明覆盖不动）。Tab 点击链路 [navigateTopLevel] 原样保留
 *    （防抖、popUpTo、launchSingleTop、restoreState 全不动），仅同步登记常驻层——
 *    **切换 = 常驻层可见性翻转，零屏离树、零重组成本**。
 *
 * 底栏（NavigationBar）与其显隐逻辑零改动（仍按 NavHost currentRoute 判定）；X1 的
 * isDetailDestination 条件 modifier 在 NavHost 上原样生效（常驻层约束链与其无关，恒为
 * padding+consume 官方范式——Tab 屏原本就只在非 detail 约束下可见）。
 *
 * ### 状态单源
 * NavHost currentRoute 是唯一事实源：LaunchedEffect 单向收敛进常驻层（覆盖系统返回回 Tab、
 * 进程恢复后栈顶为非 home Tab 等场景）；点击时乐观先行（与 navigate 同一重组帧原子生效，
 * 若等 currentRoute 回流再翻则慢一帧=1 帧残留），乐观值与回流值恒等，同步为 no-op。
 *
 * ### 常驻层 owner（per-tab，2026-09-14 任务U8 根修）
 * 每个驻留 Tab 屏包自己的 CompositionLocalProvider：LocalLifecycleOwner /
 * LocalViewModelStoreOwner provide **该 Tab 自己的 NavBackStackEntry**（由本函数四 Tab
 * 空壳跳板在组合时登记进 [tabEntries]，restoreState 重建 entry 实例时壳重组自动刷新）。
 *
 * 为什么不能统一 provide 起始目的地（home）的 entry（U3 初版实现，任务U8 根因）：
 * Navigation Compose 语义下**非栈顶 entry 的生命周期恒为 CREATED**（<STARTED）——用户在
 * 相册/数据/设置 Tab 时 home entry 正在栈底，四屏共用的 owner 全部低于 STARTED，
 * `collectAsStateWithLifecycle` 集体停摆：点击确实进了 ViewModel（状态已更新）但 UI
 * 永不重组，直到切回首页 tab（home entry RESUMED）才把攒下的状态一次画出——即任务U8
 * 的「相册点击无响应/迟到显示」与「统计主页首载卡死>2min 自愈」两症状的同根因
 * （真机 QM_TOUCH 日志 19 条 ALBUM_STATE 快照中 17 条紧贴 SHELL route=home 事件确证）。
 *
 * per-tab 语义 = 恢复常驻层改造前（Tab 屏由 NavHost 渲染）的原生行为：置顶 RESUMED 实时
 * 收集、隐藏 CREATED 暂停收集、pop 返回 RESUMED 恢复。HomeScreen 持 home entry（ON_RESUME
 * 观察「点赞后返回自动重排」语义与 U3 前逐帧一致）；Tab ViewModel 落各自 entry 的
 * ViewModelStore（popUpTo 恒 saveState=true 故跨 Tab 切换存活；登出随导航图销毁全清，
 * 不落 Activity 作用域跨会话残留——U3 接管的两个初衷都保留）。entry 尚未登记的帧
 * （进程恢复后未重访的 Tab）回落 home entry：隐藏屏暂停/回首页收集，行为安全。
 *
 * ### 内存/性能代价（与旧版常驻 Fragment 同款，有意为之）
 * 四屏驻留后其 ViewModel/状态收集照旧运行（collectAsStateWithLifecycle 以 start entry 为
 * owner，≥STARTED 即收集）；懒驻留保证冷启动只组合首 Tab。驻留屏的测量/布局照常参与
 * （旧版隐藏 Fragment 视图 GONE 免布局；此处为保滚动位置不重排，接受常驻布局差量）。
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
    // X1 壳层特化判定（2026-09-12 任务X）：当前目的地是否 detail 路由。destination.route
    // 是路由模式串（"detail/{assetId}"），与 [DetailRoutes.DETAIL_ROUTE] 精确等值即可覆盖
    // 全部详情实例——兄弟滑切 push 叠栈走同一路由模式，详情页没有更深层后代路由；中文 id
    // 的 authorCollection 等路由只是详情的「来路」，详情入栈后栈顶目的地必为 detail，
    // 无需 pattern 匹配
    val isDetailDestination = currentRoute == DetailRoutes.DETAIL_ROUTE
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

    // ── 常驻层状态（机制见本函数 KDoc §Tab 常驻层；2026-09-13 根治 Tab 切换闪烁/残留）──
    // 已驻留 Tab，只增不减（对齐旧版 fragmentCache 首访才 add、此后常驻）。rememberSaveable：
    // 进程恢复/旋转后直接恢复驻留集与当前 Tab，恢复帧即显示正确 Tab（与 NavHost 返回栈
    // 恢复同源一致）；登出（主壳离树）随之丢弃，重登录全新开始。
    val visitedTabs = rememberSaveable(
        saver = listSaver<SnapshotStateList<String>, String>(
            save = { it.toList() },
            restore = { it.toMutableStateList() },
        ),
    ) { mutableStateListOf(TopLevelDestination.HOME.route) }
    // 常驻层当前 Tab（可见性翻转依据）。单源=NavHost currentRoute（同步见下方 LaunchedEffect）；
    // 点击时乐观先行（与 navigate 同一重组帧原子生效，值恒与 currentRoute 回流值一致）
    var currentTabRoute by rememberSaveable { mutableStateOf(TopLevelDestination.HOME.route) }
    // 各驻留 Tab 的 rememberSaveable 状态仓（按 route 分键；登出随主壳丢弃）
    val stateHolder = rememberSaveableStateHolder()
    // per-tab owner 数据源（任务U8 根修，机制见本函数 KDoc §常驻层 owner）：Tab 路由 → 该
    // Tab 的 NavBackStackEntry，由 NavHost 内四 Tab 空壳跳板组合时登记。mutableStateMap：
    // restoreState 重建 entry 实例时壳重组刷新映射、常驻层同帧感知换 owner。不可 Saveable
    // （entry 不可序列化）；进程恢复后未重访 Tab 的映射缺席 → 常驻层回落 home entry（安全档）
    val tabEntries = remember { mutableStateMapOf<String, NavBackStackEntry>() }

    /** Tab 登记：首访入列（同帧组合）+ 可见性翻转（bottomBar 点击与 currentRoute 同步共用） */
    fun visitTab(route: String) {
        if (route !in visitedTabs) visitedTabs.add(route)
        currentTabRoute = route
    }

    // 状态单源同步（KDoc §状态单源）：NavHost currentRoute → 常驻层单向收敛。兜底系统返回
    // 回到某 Tab、进程恢复后栈顶为非 home Tab 等场景；点击路径经此为 no-op（乐观值=回流值）
    LaunchedEffect(currentRoute) {
        val route = currentRoute
        if (route != null && route in topLevelRoutes) {
            visitTab(route)
        }
    }

    // pushed 路由在顶（覆盖屏/详情）：常驻层与 NavHost 之间需不透明幕帘（见内容区注释）
    val overlayRouteShowing = currentRoute != null && currentRoute !in topLevelRoutes

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
                                        // 常驻层先行登记/翻转（与下方 navigate 同一重组帧原子生效；
                                        // 若等 currentRoute 回流再翻则慢一帧=1 帧残留）。乐观值与
                                        // currentRoute 回流值恒等，LaunchedEffect 同步为 no-op
                                        visitTab(destination.route)
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
            // 动画根因。旧版 Fragment show/hide 无转场，故四处转场为瞬时切换；覆盖页/详情页
            // 进出同样瞬时（旧版同为无转场观感）。与防抖双保险，防叠加。
            // 任务U9（2026-09-14）：None → fade*（snap()）——「无动画属性」≠「零时长」：
            // None 下进出两页在整个转场存续期都以全不透明同屏，转场只要因任何原因滞留
            // >1 帧（navigation 2.10 转场内部状态、release 包慢帧），就呈现用户实测的
            // 「退出后旧页内容叠层残留 1~2s 才消失」（release 包逐帧实证）。snap() 第一帧
            // 即把退出页 alpha 硬置 0/进入页置 1：无论转场滞留多久都无叠影，视觉仍是
            // 瞬时交换，L2 拍板口径不变。
            enterTransition = { fadeIn(snap()) },
            exitTransition = { fadeOut(snap()) },
            popEnterTransition = { fadeIn(snap()) },
            popExitTransition = { fadeOut(snap()) },
            // X1 根修（2026-09-12 任务X，问题1/2/3/4 总根因）：detail 路由内容区不再被
            // innerPadding 钉位。旧版详情页「始终 edge-to-edge 全屏布局，系统栏显隐不触发
            // 布局」（GUIDE_UI L162/L272-275）；钉位架构下沉浸切换会经 Scaffold innerPadding
            // 随 inset 收缩整页位移、舞台盒正下方内容露出（拖出文件名/退出跳动观感）。
            // 特化仅此一路由：NavHost 全屏铺开，insets 由详情页 chrome 自管
            // （statusBars/navigationBarsPadding）；其余路由维持 padding+consume 官方范式，
            // 逐像素不变。consumeWindowInsets（任务G3 双重留白清偿）：主壳 Scaffold 无
            // topBar，innerPadding 的 top=状态栏高；不消费则覆盖页内嵌的 QimengTopBar（M3
            // TopAppBar 默认 windowInsets=statusBars）会再自留一段状态栏高度——标题上方
            // 两倍空白。padding 后消费=Scaffold 官方范式，嵌套组件读到已消耗的 insets 归零
            modifier = if (isDetailDestination) {
                Modifier
            } else {
                Modifier
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
            },
        ) {
            // ── 四 Tab 路由 = 空壳跳板（机制见本函数 KDoc §Tab 常驻层）──
            // 真实屏不再由 NavHost 渲染：NavHost 换 destination 的「旧屏出树+新屏全量组合」
            // 交换窗口正是 Tab 切换闪烁/残留的根因；空壳让 Tab↔Tab 切换退化为空壳到空壳，
            // 真实屏的显示翻转由常驻层完成，全程零屏离树。NavHost 仍保留 Tab 路由：承担
            // 路由/返回栈/saveState/restoreState 语义，底栏显隐仍按 currentRoute 判定。
            composable(TopLevelDestination.HOME.route) { entry ->
                // 空壳：HomeScreen 真身在常驻层 [ResidentTabScreen] 按 route 渲染；
                // 壳组合即登记 entry（per-tab owner，任务U8 根修）
                tabEntries[TopLevelDestination.HOME.route] = entry
            }
            composable(TopLevelDestination.ALL.route) { entry ->
                // 空壳：AllScreen 真身同上
                tabEntries[TopLevelDestination.ALL.route] = entry
            }
            composable(TopLevelDestination.STATS.route) { entry ->
                // 空壳：StatsScreen 真身同上
                tabEntries[TopLevelDestination.STATS.route] = entry
            }
            composable(TopLevelDestination.SETTINGS.route) { entry ->
                // 空壳：SettingsScreen 真身同上
                tabEntries[TopLevelDestination.SETTINGS.route] = entry
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
                    onOpenAsset = { assetId ->
                        navController.navigate(DetailRoutes.detailRoute(assetId))
                    },
                )
            }
            composable(
                route = Routes.FAVORITE,
            ) {
                FavoriteScreen(
                    onBack = { navController.popBackStack() },
                    onOpenAsset = { assetId ->
                        navController.navigate(DetailRoutes.detailRoute(assetId))
                    },
                )
            }
            composable(
                route = Routes.HISTORY,
            ) {
                HistoryScreen(
                    onBack = { navController.popBackStack() },
                    onOpenAsset = { assetId ->
                        navController.navigate(DetailRoutes.detailRoute(assetId))
                    },
                )
            }
            composable(
                route = Routes.AUTHORS,
            ) {
                AuthorScreen(
                    onBack = { navController.popBackStack() },
                    // 行点击进作者集合页（任务G G1b 接线：Web /app/collection/author/{name}
                    // 等价物；路由带 id+名字双参数，见 AuthorCollectionRoutes 注释）
                    onAuthorClick = { authorId, displayName ->
                        navController.navigate(
                            AuthorCollectionRoutes.authorCollectionRoute(authorId, displayName),
                        )
                    },
                )
            }
            // 作者集合页（任务G G1b）：路由契约单源在 feature:author（DetailRoutes 同范式，
            // feature 禁依赖 :app，壳层反向引用合法）；路由参数由页面 ViewModel 经
            // SavedStateHandle 读取，此处无需展开 arguments。
            composable(
                route = AuthorCollectionRoutes.AUTHOR_COLLECTION_ROUTE,
            ) {
                AuthorCollectionScreen(
                    onBack = { navController.popBackStack() },
                    onOpenAsset = { assetId ->
                        navController.navigate(DetailRoutes.detailRoute(assetId))
                    },
                )
            }
            composable(Routes.UPLOAD) {
                UploadScreen(
                    sharedUris = sharedUris,
                    onSharedConsumed = onSharedConsumed,
                    onDone = { navController.popBackStack() },
                )
            }
            // 服务器设置子页（U10-4）：设置页「服务器」入口行进本页；pushed 覆盖页——
            // 底栏隐藏与幕帘由既有 currentRoute 机制自动生效，无需额外处理
            composable(Routes.SERVER) {
                ServerSettingsScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            // 下载收件箱设置子页（2026-09-25 暂存区重做）：设置页「下载收件箱」入口行进本页；
            // pushed 覆盖页——底栏隐藏与幕帘由既有 currentRoute 机制自动生效
            composable(Routes.INBOX) {
                InboxSettingsScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            // 数据管理 hub（U10-6）：我的页「数据管理」合并入口二级页；上传行复用既有
            // Routes.UPLOAD 页（不搬路由），库管理/作者 TXT 导入/备份导入导出（U10-6b）
            // /缩略图缓存（2026-09-16 用户反馈）走各自新增子页；
            // 上传收件箱与归档（2026-09-28 归档文件夹批）：自我页入口行迁入本 hub，
            // 路由复用 Routes.INBOX 不搬家
            composable(Routes.DATA_MANAGE) {
                DataManageScreen(
                    onBack = { navController.popBackStack() },
                    onOpenUpload = { navController.navigate(Routes.UPLOAD) },
                    onOpenLibraryManage = { navController.navigate(Routes.LIBRARY_MANAGE) },
                    onOpenAuthorTxt = { navController.navigate(Routes.AUTHOR_TXT_IMPORT) },
                    onOpenBackup = { navController.navigate(Routes.BACKUP) },
                    onOpenThumbCache = { navController.navigate(Routes.THUMBNAIL_CACHE) },
                    onOpenInbox = { navController.navigate(Routes.INBOX) },
                )
            }
            // 库管理子页（U10-6）：库表 + 注册媒体目录表单（Web 文件管理页对齐物）
            composable(Routes.LIBRARY_MANAGE) {
                LibraryManageScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            // 作者 TXT 导入子页（U10-6b）：片段列表 + 选 TXT 导入 + 移除/重放
            // （Web TxtAuthorImportCard 对齐物）
            composable(Routes.AUTHOR_TXT_IMPORT) {
                AuthorTxtImportScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            // 备份导入导出子页（U10-6b）：全量备份导出 + qimeng_backup.json 幂等导入恢复
            // （Web BackupCard 对齐物，DOMAIN_RULES §10）
            composable(Routes.BACKUP) {
                BackupScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            // 缩略图缓存子页（2026-09-16 用户反馈）：缩略图生成进度 + 缓存上限档位合并页
            // （自我的页「缓存区」QuotaCard 迁入 feature:manage；pushed 覆盖页，底栏隐藏
            // 与幕帘由既有 currentRoute 机制自动生效）
            composable(Routes.THUMBNAIL_CACHE) {
                ThumbnailCacheScreen(
                    onBack = { navController.popBackStack() },
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
            // 详情页（M4-3）：滑切换件的返回栈语义=popUpTo 换顶、栈深恒 1（见 onOpenAsset
            // 内注释；任务Z Z5 对齐旧版单实例语义。launchSingleTop 不适用：换件必须生成
            // 全新 entry，W7 沉浸交接单依赖新实例 rememberSaveable 初值链消费）。
            // 进出转场继承 NavHost 顶层 fade*（snap()）瞬时语义（任务U9：None 改 snap 根修
            // 「退出后旧页叠层残留」，L2 拍板「无内容转场」口径同样覆盖 pushed 路由）。
            composable(
                route = DetailRoutes.DETAIL_ROUTE,
            ) { entry ->
                DetailScreen(
                    assetId = entry.arguments?.getString(DetailRoutes.KEY_ASSET_ID).orEmpty(),
                    onBack = { navController.popBackStack() },
                    onOpenAsset = { assetId ->
                        // 兄弟资产滑动换件：批次清单就是当前清单（任务W W3 推荐栏退役后
                        // 壳层只管导航，无换批语义）。
                        // 任务Z Z5（2026-09-12 用户反馈 #4「旧版左右滑动完依旧可以直接返回
                        // 主页，新版是返回上一个」）：旧版=单 Fragment moveBy 原地换内容，按
                        // 返回恒 closeDetail→popBackStack 回来路页（MediaDetailFragment.kt
                        // :232-236/:757-769）；新版此前 push 叠栈=滑 N 次栈里 N 个 detail 实例、
                        // 返回回上一个，与旧版相悖（GUIDE_UI L278「浏览历史栈」经调研在旧代码
                        // 中并无实现，该规格行过期记档）。popUpTo(模式串, inclusive)=先弹掉
                        // 栈顶当前 detail 再 push 新实例，栈深恒 1：返回=pop 回来路页（首页/
                        // 搜索/作者集合…），逐字对齐旧版，顺带根治滑切叠栈增长。popUpTo(route)
                        // 对模式串的匹配已核 navigation 2.9.8 字节码：NavDestinationImpl
                        // .hasRoute 首分支=destination.route 精确等值（与 X1 isDetailDestination
                        // 同机制），与实例参数无关，栈顶 detail 必命中；万一未命中仅日志降级
                        // 「Ignoring popBackStack」且 navigate 照常 push，无崩溃风险。
                        // W7 沉浸交接单不受影响：换件目标仍是全新 entry，rememberSaveable
                        // 初值链照常消费置位；返回恢复的旧实例不走该链（W7 原语义）。
                        navController.navigate(DetailRoutes.detailRoute(assetId)) {
                            popUpTo(DetailRoutes.DETAIL_ROUTE) { inclusive = true }
                        }
                    },
                    // 作者 Sheet「进入作者主页」进作者集合页（任务G G1b 接线；原始名不带 ·COS 后缀）
                    onOpenAuthor = { authorId, displayName ->
                        navController.navigate(
                            AuthorCollectionRoutes.authorCollectionRoute(authorId, displayName),
                        )
                    },
                    // 作者 Sheet「编辑作者与来源」进资产编辑页（2026-09-25 上传挂靠退役批）
                    onEditAsset = { assetId ->
                        navController.navigate(AssetEditRoutes.assetEditRoute(assetId))
                    },
                )
            }
            // 资产编辑页（2026-09-25）：路由契约单源在 feature:detail（DetailRoutes 同范式），
            // assetId 参数由 AssetEditViewModel 经 SavedStateHandle 读取，此处无需展开 arguments。
            // pushed 覆盖页——底栏隐藏与幕帘由既有 currentRoute 机制自动生效
            composable(AssetEditRoutes.ASSET_EDIT_ROUTE) {
                AssetEditScreen(
                    onBack = { navController.popBackStack() },
                    // 保存成功返回与手动返回同一条 pop 路径（语义分开供页面注入）
                    onSaved = { navController.popBackStack() },
                )
            }
        }

        // ── 常驻层（机制见本函数 KDoc §Tab 常驻层）──
        // 声明在 NavHost 之后但 zIndex(-1) 压其下：z 序由 zIndex 决定，与声明序无关；
        // 声明序只用于保证下方 owner 解析时 NavHost 已组合（graph 于首个组合内置好，
        // navigation 2.8+ Ieb7be）。空壳 Tab 全透明放行 → 当前 Tab 可见可点；
        // pushed 屏不透明自覆盖（透明根容器由幕帘补底）。
        //
        // 常驻层 owner（KDoc §常驻层 owner，任务U8 根修）：**per-tab**——每个驻留 Tab 屏
        // provide 自己的 NavBackStackEntry（[tabEntries] 由四 Tab 空壳跳板登记；U3 初版
        // 统一 provide home entry 的实现在非首页 Tab 置顶时因 home entry=CREATED 停摆全部
        // 状态收集，即「相册点击无响应/统计首载卡死」根因，详见 KDoc）。entry 尚未登记的
        // 帧（进程恢复后未重访的 Tab）回落 home entry；graph 未就绪（residentEntry==null）
        // 的帧不组合 Tab 屏：绝不允许任何一帧在 Activity owner 下建 Tab VM（否则 owner 换手
        // 后 VM 双实例+泄漏）
        val currentEntryId: String? = navController.currentBackStackEntry?.id
        // Suppress 依据：lint 只认 NavBackStackEntry 对象本体作 key；本处持有的是 start 目的地
        // entry（popUpTo(start){saveState} 永不弹出、id 恒定），String id 作 key 与对象 key
        // 语义等价，无「entry 被弹后悬持失效」风险（该风险正是此 lint 的守护对象）
        @Suppress("UnrememberedGetBackStackEntry")
        val residentEntry: NavBackStackEntry? = remember(currentEntryId) {
            if (currentEntryId != null) {
                navController.getBackStackEntry(TopLevelDestination.HOME.route)
            } else {
                null
            }
        }
        // 约束链 = 原 NavHost 非 detail 分支的 modifier 逐字迁移（padding → consume 同序）：
        // 常驻 Tab 屏的布局约束与改前在 NavHost 内容区时完全一致（同宽高、同 padding/insets
        // 消耗链——本任务最大的坑，逐行对照迁移）；NavHost 自身的 isDetailDestination 条件
        // modifier 原样保留，与常驻层互不影响（Tab 屏原本就只在非 detail 约束下可见过）
        Box(
            modifier = Modifier
                .zIndex(-1f)
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            // graph 未就绪（residentEntry==null）的帧不组合 Tab 屏：见上方 owner 注释。
            // 正常路径（首个组合内 graph 已内置）恒非空，此门控不可见
            if (residentEntry != null) {
                visitedTabs.forEach { tabRoute ->
                    key(tabRoute) {
                        // per-tab owner 落位：本 Tab 的 entry，缺登记帧回落 home entry
                        // （隐藏屏暂停收集/回首页收集，安全档——见上方 owner 注释）
                        val tabOwner = tabEntries[tabRoute] ?: residentEntry
                        val isCurrentTab = tabRoute == currentTabRoute
                        CompositionLocalProvider(
                            LocalLifecycleOwner provides tabOwner,
                            LocalViewModelStoreOwner provides tabOwner,
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .zIndex(if (isCurrentTab) 1f else 0f)
                                    .then(
                                        if (isCurrentTab) {
                                            Modifier
                                        } else {
                                            // 隐藏 Tab 三重隔离：不绘制（alpha=0）+ a11y 不可达
                                            // （对齐旧版 hide 的 GONE）+ 触摸死层（下方兜底）
                                            Modifier
                                                .graphicsLayer { alpha = 0f }
                                                .clearAndSetSemantics { }
                                        }
                                    ),
                            ) {
                                stateHolder.SaveableStateProvider(tabRoute) {
                                    ResidentTabScreen(route = tabRoute, navController = navController)
                                }
                                // 触摸死层：Compose 命中测试中「无 pointer input 的节点」不拦截触摸——
                                // 当前 Tab 的非交互区（如顶栏留白）下压会命中隐藏 Tab 的可交互节点
                                // （幽灵点击/滚动）。死层盖在隐藏屏之上（声明在后=子级 z 更高）全量吞
                                // 事件，隐藏屏由此不可点（「双重保险不可点」的落点）
                                if (!isCurrentTab) {
                                    Box(modifier = Modifier.fillMaxSize().blockTouches())
                                }
                            }
                        }
                    }
                }
            }
            // 幕帘：pushed 路由（detail/search/...）在顶时垫在 NavHost 与常驻层之间。
            // ① 视觉：部分 pushed 屏根容器无背景（如 SearchScreen 的裸 Column），改前透出
            //   的是 Scaffold 背景色；常驻层就位后透出的会变成当前 Tab 屏——幕帘以 Scaffold
            //   同款 background 色补底，逐像素保真。② 输入：pushed 屏非交互区下压的触摸
            //   全量吞掉，防穿透到常驻层造成幽灵滚动（zIndex=2 盖过当前 Tab 的 zIndex=1）
            if (overlayRouteShowing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(2f)
                        .background(MaterialTheme.colorScheme.background)
                        .blockTouches(),
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
    // 修复E（2026-09-14）：launchSingleTop——标签行连点时同路由压栈双实例（返回须退两层），
    // 单顶把重复导航收敛到既有栈顶实例
    onOpenTagSearch = { tag -> navController.navigate(Routes.searchRoute(tag)) { launchSingleTop = true } },
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

/**
 * 常驻层按 route 渲染的 Tab 真身。回调接线逐字自原 NavHost 四个 Tab composable 迁移
 * （2026-09-13 常驻层改造，屏与跳转目标均不动）；NavHost 内同路由为空壳跳板，本函数仅
 * 由常驻层调用。
 */
@Composable
private fun ResidentTabScreen(route: String, navController: NavHostController) {
    when (route) {
        TopLevelDestination.HOME.route -> HomeScreen(
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
                // 任务J J1 详情页跳转链（GUIDE_UI L218-224）：常看文件条目→详情页（批次
                // 上下文已由 StatsViewModel.enterDetail 写入）、作者条目→作者集合页、
                // 标签条目→搜索页携词（query 编码见 Routes.searchRoute）
                onOpenAsset = links.onOpenAsset,
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
private fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        while (true) {
            val event = awaitPointerEvent()
            event.changes.forEach { change -> change.consume() }
            if (event.changes.none { it.pressed }) break
        }
    }
}
