package media.qimeng.app.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 批次邻位解析纯逻辑单测（M4-3 3a 修补，拍板③：详情页左右滑切换相邻资产）。
 * 本批只供逻辑层（DetailViewModel.moveBy 基于 [MediaBatchIndex.assetIdAt]），
 * UI 手势接线在 3b——行为在此锁死：邻位返回目标 id，越界/缺位返回 null 不环绕。
 */
class MediaBatchIndexTest {

    @Test
    fun `assetIdAt - 前进与后退返回邻位`() {
        val batch = MediaBatchIndex()
        batch.ids = listOf("a", "b", "c")
        assertEquals("b", batch.assetIdAt(0, 1)) // a +1
        assertEquals("c", batch.assetIdAt(1, 1)) // b +1
        assertEquals("a", batch.assetIdAt(1, -1)) // b -1
        assertEquals("c", batch.assetIdAt(0, 2)) // 任意幅度偏移
    }

    @Test
    fun `assetIdAt - 首尾越界返回null`() {
        val batch = MediaBatchIndex()
        batch.ids = listOf("a", "b", "c")
        assertNull(batch.assetIdAt(0, -1)) // 滑过首
        assertNull(batch.assetIdAt(2, 1)) // 滑过尾
        assertNull(batch.assetIdAt(1, -5))
        assertNull(batch.assetIdAt(1, 5))
    }

    @Test
    fun `assetIdAt - index缺位与空批次返回null`() {
        val batch = MediaBatchIndex()
        batch.ids = listOf("a", "b", "c")
        assertNull(batch.assetIdAt(-1, 1)) // 当前资产不在批次内（indexOf=-1）
        assertNull(batch.assetIdAt(3, 0)) // index 超上界
        assertNull(MediaBatchIndex().assetIdAt(0, 1)) // 空批次（无批次上下文）
    }
}
