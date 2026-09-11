package media.qimeng.app.feature.detail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 舞台盒沉浸几何冻结单源单测（任务W W2，2026-09-11）：锁定 [stageImmersiveViewportHeightPx]
 * 「chrome 显隐全程（含栏动画中间态）盒高恒定在可见态稳定值」口径——修复前盒高直接跟
 * 壳层内容区高（BoxWithConstraints.maxHeight），栏显隐经 Scaffold innerPadding 抖动盒高，
 * ZoomImageView 松手 clampTranslation 按瞬态盒高再居中 + preserveScreenPosition 不感知纯
 * 尺寸收缩 → 一个隐/显周期后图片持久偏移 +31.5px（emulator-5562 实测，=nav inset 63 之半）。
 * 各用例坐标系：1080x2400、可见态 status=128/nav=63、稳定盒高 2209。
 */
class StageViewportHeightTest {

    @Test
    fun `系统栏可见稳定态 - 等于壳层内容区高（G1a 口径不变）`() {
        assertEquals(
            2209,
            stageImmersiveViewportHeightPx(
                liveContentHeightPx = 2209,
                liveStatusBarTopPx = 128,
                liveNavBarBottomPx = 63,
                maxSeenStatusBarTopPx = 128,
                maxSeenNavBarBottomPx = 63,
            ),
        )
    }

    @Test
    fun `沉浸稳定态 两inset归零设备 - 恒定在可见态值`() {
        // 修复前：内容区涨到 2400 → 盒高跟涨 → 居中基准漂移。修复后恒 2209
        assertEquals(
            2209,
            stageImmersiveViewportHeightPx(
                liveContentHeightPx = 2400,
                liveStatusBarTopPx = 0,
                liveNavBarBottomPx = 0,
                maxSeenStatusBarTopPx = 128,
                maxSeenNavBarBottomPx = 63,
            ),
        )
    }

    @Test
    fun `沉浸稳定态 status回读滞留设备 API35实测 - 恒定在可见态值`() {
        // emulator-5562 实测：hide 后 status 回读恒 128 不归零（K2 记档）、nav 归 0
        // → 内容高 2272。修复前松手 clamp 按 2272 居中（实测图片顶 960.5）后盒高落回
        // 2209 不再补偿 → 持久偏移 31.5px。修复后恒 2209
        assertEquals(
            2209,
            stageImmersiveViewportHeightPx(
                liveContentHeightPx = 2272,
                liveStatusBarTopPx = 128,
                liveNavBarBottomPx = 0,
                maxSeenStatusBarTopPx = 128,
                maxSeenNavBarBottomPx = 63,
            ),
        )
    }

    @Test
    fun `栏显隐动画中间态 inset部分回传 - 恒定在可见态值`() {
        // show() 动画期：status 回到 64、nav 回到 32，内容高 2304——max 压回稳定值
        assertEquals(
            2209,
            stageImmersiveViewportHeightPx(
                liveContentHeightPx = 2304,
                liveStatusBarTopPx = 64,
                liveNavBarBottomPx = 32,
                maxSeenStatusBarTopPx = 128,
                maxSeenNavBarBottomPx = 63,
            ),
        )
    }

    @Test
    fun `真实inset增长 字号或分屏 - 即时采纳新稳定值`() {
        // maxSeen 单调上探后：字号线变化（128→160）采纳，盒高相应变化（一次到位）
        assertEquals(
            2177,
            stageImmersiveViewportHeightPx(
                liveContentHeightPx = 2177,
                liveStatusBarTopPx = 160,
                liveNavBarBottomPx = 63,
                maxSeenStatusBarTopPx = 160,
                maxSeenNavBarBottomPx = 63,
            ),
        )
    }

    @Test
    fun `无系统栏形态 - 盒高等于内容区高`() {
        assertEquals(
            2400,
            stageImmersiveViewportHeightPx(
                liveContentHeightPx = 2400,
                liveStatusBarTopPx = 0,
                liveNavBarBottomPx = 0,
                maxSeenStatusBarTopPx = 0,
                maxSeenNavBarBottomPx = 0,
            ),
        )
    }
}
