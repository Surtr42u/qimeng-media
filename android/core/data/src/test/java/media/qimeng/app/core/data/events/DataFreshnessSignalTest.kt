package media.qimeng.app.core.data.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 数据新鲜度信号汇单测（ADR-0029 客户端半，2026-10-01）：三类变更事件各自计数、互不
 * 串扰；onOpen（含重连）三计数一起 bump（对齐 Web 端「重连后重新校验」语义）；快照
 * 稳定直至下一次 bump。纯 JVM 零依赖（信号汇不触网络不触时钟，全由消费端注入事件）。
 */
class DataFreshnessSignalTest {

    @Test
    fun `初始快照为零值 - 未收到任何事件时三门全新鲜基线`() {
        val signal = DataFreshnessSignal()
        assertEquals(FreshnessSnapshot.ZERO, signal.snapshot())
    }

    @Test
    fun `三类事件各自计数 - library_favorite_like 互不串扰`() {
        val signal = DataFreshnessSignal()

        signal.onLibraryChanged()
        assertEquals(FreshnessSnapshot(library = 1, favorite = 0, like = 0), signal.snapshot())

        signal.onFavoriteChanged()
        assertEquals(FreshnessSnapshot(library = 1, favorite = 1, like = 0), signal.snapshot())

        signal.onLikeChanged()
        assertEquals(FreshnessSnapshot(library = 1, favorite = 1, like = 1), signal.snapshot())

        // 同类重复事件继续递增（幂等 no-op 事件照发的服务端口径下，多 bump 只会多拉一次）
        signal.onFavoriteChanged()
        assertEquals(FreshnessSnapshot(library = 1, favorite = 2, like = 1), signal.snapshot())
    }

    @Test
    fun `流建立成功三计数一起bump - 首次连接与重连同语义`() {
        val signal = DataFreshnessSignal()

        // 首次连接 onOpen：全量重新校验（多拉一次幂等无害）
        signal.onStreamOpened()
        assertEquals(FreshnessSnapshot(library = 1, favorite = 1, like = 1), signal.snapshot())

        // 事件照常累加
        signal.onFavoriteChanged()
        assertEquals(FreshnessSnapshot(library = 1, favorite = 2, like = 1), signal.snapshot())

        // 断线重连再次 onOpen：三门一起 bump——断线窗口错过的事件以门整体失效补偿
        signal.onStreamOpened()
        assertEquals(FreshnessSnapshot(library = 2, favorite = 3, like = 2), signal.snapshot())
    }

    @Test
    fun `快照取值稳定 - 两次读取之间无 bump 则相等 有 bump 则变化`() {
        val signal = DataFreshnessSignal()
        val before = signal.snapshot()
        assertEquals(before, signal.snapshot())

        signal.onLibraryChanged()
        assertNotEquals(before, signal.snapshot())
    }
}
