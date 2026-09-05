package media.qimeng.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 推荐流分批切片（拍板：一次拉满 200、~20 条分批、批次尽换 seed） */
class RecommendPagingTest {

    @Test
    fun `常量与拍板口径一致`() {
        assertEquals(200, RecommendPaging.PULL_LIMIT)
        assertEquals(20, RecommendPaging.BATCH_SIZE)
        assertEquals(6, RecommendPaging.PRELOAD_DISTANCE)
    }

    @Test
    fun `揭示按 20 条递进 到达拉取上限封顶`() {
        assertEquals(20, RecommendPaging.nextReveal(revealed = 0, pulled = 200))
        assertEquals(40, RecommendPaging.nextReveal(revealed = 20, pulled = 200))
        assertEquals(200, RecommendPaging.nextReveal(revealed = 180, pulled = 200))
    }

    @Test
    fun `全部揭示后返回 null 表示需要换 seed 重拉`() {
        assertNull(RecommendPaging.nextReveal(revealed = 200, pulled = 200))
    }

    @Test
    fun `拉取量不足一批时封顶在拉取量`() {
        assertEquals(7, RecommendPaging.nextReveal(revealed = 0, pulled = 7))
        assertNull(RecommendPaging.nextReveal(revealed = 7, pulled = 7))
    }

    @Test
    fun `距底 6 项内触发加载`() {
        assertTrue(RecommendPaging.shouldLoadMore(visibleLastIndex = 13, revealed = 20))
        assertFalse(RecommendPaging.shouldLoadMore(visibleLastIndex = 5, revealed = 20))
    }
}
