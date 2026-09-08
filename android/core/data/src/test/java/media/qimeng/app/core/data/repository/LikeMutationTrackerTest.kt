package media.qimeng.app.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 点赞变更指纹单测（任务I I1，GUIDE_UI §下拉刷新 L89「点赞后返回自动重排」感知基座）。
 * 行为锁定：初值指纹零版本；每次变更版本单调递增+时间戳刷新；指纹相等=无新变更；
 * 上报点在 detail 侧（I7 批接线），此处只锁 tracker 本体行为。
 */
class LikeMutationTrackerTest {

    @Test
    fun `初始指纹为零版本 - 未发生变更时无新版可报`() {
        val tracker = LikeMutationTracker()
        val fp = tracker.fingerprint()
        assertEquals(0L, fp.likeVersion)
        assertEquals(0L, fp.lastMutatedAtMs)
    }

    @Test
    fun `点赞变更 - 版本单调递增且时间戳刷新`() {
        val tracker = LikeMutationTracker()
        val before = tracker.fingerprint()

        tracker.onLikeMutated()
        val afterFirst = tracker.fingerprint()
        assertEquals(before.likeVersion + 1, afterFirst.likeVersion)
        assertTrue(afterFirst.lastMutatedAtMs > 0)
        assertNotEquals(before, afterFirst)

        tracker.onLikeMutated()
        val afterSecond = tracker.fingerprint()
        assertEquals(afterFirst.likeVersion + 1, afterSecond.likeVersion) // 连续点赞继续递增
        assertTrue(afterSecond.lastMutatedAtMs >= afterFirst.lastMutatedAtMs)
    }

    @Test
    fun `指纹对比 - 同一快照无变化 跨变更快照有变化`() {
        val tracker = LikeMutationTracker()
        val baseline = tracker.fingerprint()
        assertEquals(tracker.fingerprint(), baseline) // 未变更期间指纹稳定（浏览退出保持原样的判定半边）

        tracker.onLikeMutated()
        assertNotEquals(baseline, tracker.fingerprint()) // 点赞后指纹变化（返回自动重排的触发半边）
    }
}
