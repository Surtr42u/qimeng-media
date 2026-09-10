package media.qimeng.app.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收藏变更指纹单测（任务V V1 审查 P2 清偿，2026-09-10；镜像 [LikeMutationTrackerTest]
 * 同款口径）：初值指纹零版本；每次变更版本单调递增+时间戳刷新；指纹相等=无新变更。
 * 上报点在 DetailViewModel.toggleFavorite（详情侧），此处只锁 tracker 本体行为。
 */
class FavoriteMutationTrackerTest {

    @Test
    fun `初始指纹为零版本 - 未发生变更时无新版可报`() {
        val tracker = FavoriteMutationTracker()
        val fp = tracker.fingerprint()
        assertEquals(0L, fp.favoriteVersion)
        assertEquals(0L, fp.lastMutatedAtMs)
    }

    @Test
    fun `收藏变更 - 版本单调递增且时间戳刷新`() {
        val tracker = FavoriteMutationTracker()
        val before = tracker.fingerprint()

        tracker.onFavoriteMutated()
        val afterFirst = tracker.fingerprint()
        assertEquals(before.favoriteVersion + 1, afterFirst.favoriteVersion)
        assertTrue(afterFirst.lastMutatedAtMs > 0)
        assertNotEquals(before, afterFirst)

        tracker.onFavoriteMutated()
        val afterSecond = tracker.fingerprint()
        assertEquals(afterFirst.favoriteVersion + 1, afterSecond.favoriteVersion) // 连续收藏变更继续递增
        assertTrue(afterSecond.lastMutatedAtMs >= afterFirst.lastMutatedAtMs)
    }

    @Test
    fun `指纹对比 - 同一快照无变化 跨变更快照有变化`() {
        val tracker = FavoriteMutationTracker()
        val baseline = tracker.fingerprint()
        assertEquals(tracker.fingerprint(), baseline) // 未变更期间指纹稳定（纯浏览返回保持原样的判定半边）

        tracker.onFavoriteMutated()
        assertNotEquals(baseline, tracker.fingerprint()) // 收藏后指纹变化（返回静默重拉的触发半边）
    }
}
