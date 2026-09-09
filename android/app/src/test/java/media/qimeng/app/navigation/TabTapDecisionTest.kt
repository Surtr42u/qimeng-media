package media.qimeng.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tab 点击防抖决策纯函数单测（任务L L2，拍板 #2）：
 * 锁定时序语义——双击回顶（400ms 窗）优先于防抖（200ms 窗）；防抖只拦导航不拦回顶；
 * 防抖基准=上次实际导航时刻（回顶不重置）。纯 JVM，无 Android/Compose 依赖。
 */
class TabTapDecisionTest {

    /** 决策 shortcut：默认非当前 Tab（不同 Tab 点击场景），双击场景显式传 isCurrentRoute=true */
    private fun decide(
        nowMs: Long,
        lastTapMs: Long,
        lastNavigateMs: Long,
        isCurrentRoute: Boolean = false,
    ): TabTapAction = resolveTabTapAction(
        isCurrentRoute = isCurrentRoute,
        nowMs = nowMs,
        lastTapMs = lastTapMs,
        lastNavigateMs = lastNavigateMs,
    )

    @Test
    fun `首击放行导航（时间戳零基准）`() {
        assertEquals(TabTapAction.Navigate, decide(nowMs = 1_000, lastTapMs = 0, lastNavigateMs = 0))
    }

    @Test
    fun `同 Tab 400ms 内二击回顶`() {
        assertEquals(
            TabTapAction.ScrollToTop,
            decide(nowMs = 1_300, lastTapMs = 1_000, lastNavigateMs = 1_000, isCurrentRoute = true),
        )
    }

    @Test
    fun `同 Tab 401ms 二击不再回顶 走导航`() {
        assertEquals(
            TabTapAction.Navigate,
            decide(nowMs = 1_401, lastTapMs = 1_000, lastNavigateMs = 0, isCurrentRoute = true),
        )
    }

    @Test
    fun `双击回顶优先于防抖 150ms 二击也是回顶不是忽略`() {
        assertEquals(
            TabTapAction.ScrollToTop,
            decide(nowMs = 1_150, lastTapMs = 1_000, lastNavigateMs = 1_000, isCurrentRoute = true),
        )
    }

    @Test
    fun `不同 Tab 防抖窗内忽略`() {
        assertEquals(TabTapAction.Ignore, decide(nowMs = 1_199, lastTapMs = 1_199, lastNavigateMs = 1_000))
    }

    @Test
    fun `不同 Tab 恰满 200ms 放行（窗口边界为大于等于）`() {
        assertEquals(TabTapAction.Navigate, decide(nowMs = 1_200, lastTapMs = 1_100, lastNavigateMs = 1_000))
    }

    @Test
    fun `快速连点序列 200ms 窗内只认一次导航`() {
        // 模拟连点 10 击：仅窗后首击放行。基准=上次实际导航时刻，故放行点为
        // 1000（首击）、1200（1000+200 恰满边界）、1400（1200+200），其余全部 Ignore
        var lastTap = 0L
        var lastNavigate = 0L
        val taps = longArrayOf(1_000, 1_050, 1_100, 1_150, 1_200, 1_250, 1_300, 1_350, 1_400, 1_450)
        val decisions = taps.map { t ->
            val d = decide(nowMs = t, lastTapMs = lastTap, lastNavigateMs = lastNavigate)
            lastTap = t
            if (d == TabTapAction.Navigate) lastNavigate = t
            d
        }
        assertEquals(
            listOf(
                TabTapAction.Navigate, // 1000 首击
                TabTapAction.Ignore,   // 1050
                TabTapAction.Ignore,   // 1100
                TabTapAction.Ignore,   // 1150
                TabTapAction.Navigate, // 1200 恰满 200ms 边界放行
                TabTapAction.Ignore,   // 1250
                TabTapAction.Ignore,   // 1300
                TabTapAction.Ignore,   // 1350
                TabTapAction.Navigate, // 1400 恰满 200ms 边界放行
                TabTapAction.Ignore,   // 1450
            ),
            decisions,
        )
    }

    @Test
    fun `回顶后立刻切 Tab 仍受防抖保护（回顶不重置导航基准）`() {
        // 在 B 上 t=1500 导航到位；t=1600 双击 B 回顶（ScrollToTop 不更新 lastNavigate）；
        // t=1650 点 C：距上次导航仅 150ms → Ignore
        var lastTap = 0L
        var lastNavigate = 0L
        lastTap = 1_500; lastNavigate = 1_500 // B 导航
        assertEquals(
            TabTapAction.ScrollToTop,
            decide(nowMs = 1_600, lastTapMs = lastTap, lastNavigateMs = lastNavigate, isCurrentRoute = true),
        )
        lastTap = 1_600
        assertEquals(TabTapAction.Ignore, decide(nowMs = 1_650, lastTapMs = lastTap, lastNavigateMs = lastNavigate))
    }
}
