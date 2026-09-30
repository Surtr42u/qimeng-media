package media.qimeng.app.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收藏变更指纹单测（任务V V1 审查 P2 清偿，2026-09-10；镜像 [LikeMutationTrackerTest]
 * 同款口径）：初值指纹零版本；每次变更版本单调递增+时间戳刷新；指纹相等=无新变更。
 * 上报点在 DetailViewModel.toggleFavorite（详情侧），此处只锁 tracker 本体行为。
 *
 * 2026-10-01 跳过门 TTL 化追加：列表拉取新鲜度（[STALE_AFTER_MS] 判定半边）——
 * 时钟经 [FavoriteMutationTracker.clockMs] 注入假钟，推进全确定；「指纹未变且未过
 * TTL 跳过 / 超 TTL 重拉」的跳过门整体行为在消费点（FavoriteViewModelTest 锁定）。
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

    // ---------- 2026-10-01 跳过门 TTL 化：列表拉取新鲜度（假钟推进全确定） ----------

    @Test
    fun `列表拉取新鲜度 - 从未成功拉取不新鲜 成功打点后TTL内新鲜 恰越STALE_AFTER_MS转陈旧`() {
        val tracker = FavoriteMutationTracker()
        var fakeNow = 1_000_000L
        tracker.clockMs = { fakeNow }

        // 从未成功拉取（0 哨兵，如首载失败）：恒不新鲜——放行 ON_RESUME 兜一次重拉
        assertFalse(tracker.isListFetchFresh())

        tracker.noteListFetchCompleted()
        assertTrue(tracker.isListFetchFresh())

        fakeNow += STALE_AFTER_MS - 1 // TTL 内最后一刻（判定为严格小于）
        assertTrue(tracker.isListFetchFresh())

        fakeNow += 1 // 恰好等于 STALE_AFTER_MS：转陈旧
        assertFalse(tracker.isListFetchFresh())
    }

    @Test
    fun `再次成功拉取重置TTL计时 - 陈旧后重新打点恢复新鲜`() {
        val tracker = FavoriteMutationTracker()
        var fakeNow = 1_000_000L
        tracker.clockMs = { fakeNow }

        tracker.noteListFetchCompleted()
        fakeNow += STALE_AFTER_MS + 1
        assertFalse(tracker.isListFetchFresh()) // 超时陈旧

        tracker.noteListFetchCompleted() // 新一轮成功拉取：计时起点重置（含翻页追加打点同语义）
        assertTrue(tracker.isListFetchFresh())
    }
}
