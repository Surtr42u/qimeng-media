package media.qimeng.app.feature.detail.playback

import media.qimeng.app.core.model.ViewEventKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行为打点组装器单测（M4-3 3d）：open 进入一次 / play 起播一次 / dwell 走状态机分段
 * 逐条累加（服务端 dwell 不去重，出处见 DwellSessionTracker 类注释）/ 销毁后一切调用无害。
 */
class DirectAnalyticsReporterTest {

    private var now = 0L
    private val emissions = mutableListOf<DirectAnalyticsReporter.ViewEventEmission>()

    private fun reporter(
        assetId: String = "asset-1",
        sessionId: String = "session-1",
    ) = DirectAnalyticsReporter(
        assetId = assetId,
        sessionIdProvider = { sessionId },
        nowMs = { now },
        report = { emissions += it },
    )

    @Test
    fun `进入详情页发open一次 - 重复进入不重复发`() {
        val r = reporter()
        now = 100L
        r.onDetailEntered()
        now = 200L
        r.onDetailEntered()
        val opens = emissions.filter { it.kind == ViewEventKind.OPEN }
        assertEquals(1, opens.size)
        assertEquals(100L, opens[0].startedAtMs)
        assertEquals("asset-1", opens[0].assetId)
        assertEquals("session-1", opens[0].sessionId)
        assertNull(opens[0].dwellSeconds)
    }

    @Test
    fun `起播发play一次 - 重复触发不重复发`() {
        val r = reporter()
        r.onPlayStarted()
        now = 50L
        r.onPlayStarted()
        val plays = emissions.filter { it.kind == ViewEventKind.PLAY }
        assertEquals(1, plays.size)
        assertEquals(0L, plays[0].startedAtMs)
    }

    @Test
    fun `dwell正常停留离开恰一条且带seconds`() {
        val r = reporter()
        now = 1_000L
        r.onDetailEntered()
        now = 61_000L
        r.onDetailLeft()
        val dwells = emissions.filter { it.kind == ViewEventKind.DWELL }
        assertEquals(1, dwells.size)
        assertEquals(60L, dwells[0].dwellSeconds)
        assertEquals(1_000L, dwells[0].startedAtMs)
    }

    @Test
    fun `destroy时兜底flush dwell - 此后一切调用无害`() {
        val r = reporter()
        now = 0L
        r.onDetailEntered()
        r.onPlayStarted()
        now = 20_000L
        r.destroy()
        val before = emissions.size
        assertEquals(3, before) // open + play + dwell 兜底
        assertTrue(emissions.any { it.kind == ViewEventKind.DWELL && it.dwellSeconds == 20L })
        // 销毁后全部入口 no-op，不再产生任何打点
        r.onDetailEntered()
        r.onPlayStarted()
        r.onPaused()
        r.onResumed()
        r.onDetailLeft()
        r.destroy()
        assertEquals(before, emissions.size)
    }

    @Test
    fun `暂停后resume再离开 - dwell两段累加各段秒数正确`() {
        // 服务端 dwell 不去重逐条累加（engagement.go）：pause flush 第一段并结束会话，
        // resume 开新段，leave flush 第二段——两段 seconds 各自独立。
        val r = reporter()
        now = 0L
        r.onDetailEntered()
        now = 10_000L
        r.onPaused() // 第一段 10s
        now = 15_000L
        r.onResumed() // 开新段
        now = 40_000L
        r.onDetailLeft() // 第二段 25s
        val dwells = emissions.filter { it.kind == ViewEventKind.DWELL }
        assertEquals(2, dwells.size)
        assertEquals(10L, dwells[0].dwellSeconds)
        assertEquals(25L, dwells[1].dwellSeconds)
    }

    @Test
    fun `空停留销毁 - 不产生dwell`() {
        val r = reporter()
        r.destroy() // 未 enter 过：flush 无害
        assertEquals(0, emissions.count { it.kind == ViewEventKind.DWELL })
    }
}
