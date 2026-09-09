package media.qimeng.app.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务L L3 #37 首页↔排行榜卡半屏修复：pager↔chip 同步两个决策函数的契约单测。
 * 动画打断/落定回写等 UI 时序本身是 Compose pager 运行时行为，以实机高频验收代验；
 * 这里锁定纯函数口径——对齐判定必须把「卡半屏中间偏移」与「浮点残差落定」区分开
 * （前者触发重试、后者不触发，防重试死循环）；回写门控必须在滚动在途时拒绝回写。
 */
class HomePagerSyncLogicTest {

    // ---------- isPagerAlignedWithTab（拍板 A：settled 对齐 + 可重试） ----------

    @Test
    fun `对齐判定 - 页面到位且零偏移_判定对齐`() {
        assertTrue(isPagerAlignedWithTab(currentPage = 1, pageOffsetFraction = 0f, targetTabOrdinal = 1))
    }

    @Test
    fun `对齐判定 - 页面未到位_判定未对齐`() {
        assertFalse(isPagerAlignedWithTab(currentPage = 0, pageOffsetFraction = 0f, targetTabOrdinal = 1))
    }

    @Test
    fun `对齐判定 - 卡半屏中间偏移_判定未对齐并触发重试`() {
        // 卡死现场复刻：动画被打断后 currentPage 已等于目标但 fraction≈中间值——
        // 旧实现只看 currentPage 判「已对齐」即永久卡死，新判定必须识别为未对齐
        assertFalse(isPagerAlignedWithTab(currentPage = 1, pageOffsetFraction = 0.5f, targetTabOrdinal = 1))
        assertFalse(isPagerAlignedWithTab(currentPage = 1, pageOffsetFraction = -0.4f, targetTabOrdinal = 1))
    }

    @Test
    fun `对齐判定 - 浮点残差在容差内_判定对齐_防重试死循环`() {
        assertTrue(isPagerAlignedWithTab(currentPage = 2, pageOffsetFraction = 1e-7f, targetTabOrdinal = 2))
    }

    // ---------- pagerPageForTabSync（拍板 B：isScrollInProgress 门控防回环劫持） ----------

    @Test
    fun `回写门控 - 滚动在途_发null不回写VM`() {
        // 程序化翻页途经中间页时 currentPage 会先变成中间页，在途回写即中途劫持 switchTab
        assertNull(pagerPageForTabSync(isScrollInProgress = true, currentPage = 1))
    }

    @Test
    fun `回写门控 - 落定后_回写当前页`() {
        assertEquals(0, pagerPageForTabSync(isScrollInProgress = false, currentPage = 0))
        assertEquals(2, pagerPageForTabSync(isScrollInProgress = false, currentPage = 2))
    }
}
