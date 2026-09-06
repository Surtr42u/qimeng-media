package media.qimeng.app.feature.detail.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 已看完判定单测（M4-3 3d 冻结口径）：null 组合、恰好等于、略小、略大四类边界全锁。
 */
class WatchStateTest {

    @Test
    fun `两者皆null - 未看完起点0`() {
        val state = WatchState.of(lastPositionSeconds = null, durationMs = null)
        assertFalse(state.watched)
        assertEquals(0.0, state.resumeStartSeconds, 0.0)
    }

    @Test
    fun `进度null时长有值 - 未看完起点0`() {
        val state = WatchState.of(lastPositionSeconds = null, durationMs = 600_000L)
        assertFalse(state.watched)
        assertEquals(0.0, state.resumeStartSeconds, 0.0)
    }

    @Test
    fun `时长null进度有值 - 未看完起点等于进度`() {
        val state = WatchState.of(lastPositionSeconds = 37.5, durationMs = null)
        assertFalse(state.watched)
        assertEquals(37.5, state.resumeStartSeconds, 0.0)
    }

    @Test
    fun `进度恰好等于时长 - 已看完且起点归0`() {
        // durationMs = 600_000 → 600.0s；lastPositionSeconds = 600.0 恰好相等 → 看完
        val state = WatchState.of(lastPositionSeconds = 600.0, durationMs = 600_000L)
        assertTrue(state.watched)
        assertEquals(0.0, state.resumeStartSeconds, 0.0)
    }

    @Test
    fun `进度略小于时长 - 未看完起点等于进度`() {
        val state = WatchState.of(lastPositionSeconds = 599.999, durationMs = 600_000L)
        assertFalse(state.watched)
        assertEquals(599.999, state.resumeStartSeconds, 1e-9)
    }

    @Test
    fun `进度略大于时长 - 已看完起点归0`() {
        val state = WatchState.of(lastPositionSeconds = 600.5, durationMs = 600_000L)
        assertTrue(state.watched)
        assertEquals(0.0, state.resumeStartSeconds, 0.0)
    }

    @Test
    fun `时长为0进度为0 - 恰好相等按冻结公式判已看完`() {
        // 冻结公式不特判 0 时长退化情形：0 >= 0 成立 → 看完（与协议口径字面一致）
        val state = WatchState.of(lastPositionSeconds = 0.0, durationMs = 0L)
        assertTrue(state.watched)
    }
}
