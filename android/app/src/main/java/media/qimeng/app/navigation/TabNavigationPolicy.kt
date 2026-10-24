package media.qimeng.app.navigation

import androidx.navigation.NavGraph.Companion.findStartDestination
import media.qimeng.app.core.ui.component.TabScrollController
import androidx.navigation.NavHostController

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
internal fun navigateTopLevel(navController: NavHostController, destination: TopLevelDestination) {
    navController.navigate(destination.route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
