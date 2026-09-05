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
import androidx.compose.runtime.getValue
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
import media.qimeng.app.feature.album.AlbumScreen
import media.qimeng.app.feature.all.AllScreen
import media.qimeng.app.feature.home.HomeScreen
import media.qimeng.app.feature.login.LoginScreen
import media.qimeng.app.feature.settings.SettingsScreen
import media.qimeng.app.feature.stats.StatsScreen
import media.qimeng.app.session.MainViewModel
import media.qimeng.app.session.SessionState

/**
 * 壳根：登录态分支（M4-1）——未登录渲染登录页，已登录进五 Tab 主壳，Loading 渲染空白防误闪。
 * 401 的跳登录也在这里发生：MainViewModel 把 SessionEventBus 事件并入会话状态，LoggedOut 一出现
 * 登录页即替换主壳（拦截器不做导航，导航属壳层关注点——M4-1 冻结口径）。
 * 切换不经 Navigation 返回栈：登录页与主壳是互斥根状态而非可互相跳转的页面。
 */
@Composable
fun QimengNavRoot(modifier: Modifier = Modifier) {
    val mainViewModel: MainViewModel = hiltViewModel()
    val sessionState by mainViewModel.sessionState.collectAsStateWithLifecycle()
    when (sessionState) {
        SessionState.Loading -> Surface(modifier = modifier.fillMaxSize()) {}
        SessionState.LoggedOut -> LoginScreen(modifier = modifier)
        SessionState.LoggedIn -> QimengNavHost(modifier = modifier)
    }
}

/**
 * 主壳导航：底部五 Tab + NavHost（仅登录后可达）。
 *
 * Tab 保活语义（GUIDE_UI §导航结构：旧版 show/hide 全 Tab 存活、切换不重建）在 Navigation
 * Compose 下的等价实现 = saveState/restoreState + launchSingleTop（Android 官方底部导航模式）：
 * 切 Tab 保存并恢复各 Tab 的返回栈与页面状态，滚动位置/输入不丢；占位页阶段无列表，
 * 「双击当前 Tab 回顶部」依赖列表实现，随 M4-2 列表族批次落地。
 *
 * GUIDE_UI 的覆盖页面（详情/作者/搜索等）入栈时隐藏底部导航——M4-0 无覆盖页面，
 * 后续批次接入时在此按当前路由切换 bottomBar 可见性。
 */
@Composable
fun QimengNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar {
                TopLevelDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = { navigateTopLevel(navController, destination) },
                        icon = { Icon(imageVector = destination.icon, contentDescription = null) },
                        label = { Text(text = stringResource(destination.labelRes)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.HOME.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(TopLevelDestination.HOME.route) { HomeScreen() }
            composable(TopLevelDestination.ALL.route) { AllScreen() }
            composable(TopLevelDestination.ALBUM.route) { AlbumScreen() }
            composable(TopLevelDestination.STATS.route) { StatsScreen() }
            composable(TopLevelDestination.SETTINGS.route) { SettingsScreen() }
        }
    }
}

/** 五 Tab 切换统一走此函数：单顶 + 保存/恢复状态（见主壳注释的保活语义说明）。 */
private fun navigateTopLevel(navController: NavHostController, destination: TopLevelDestination) {
    navController.navigate(destination.route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
