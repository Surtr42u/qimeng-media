package media.qimeng.app.feature.detail.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 进度节流策略单测（M4-3 3d）：时间源注入假时钟，无 delay。
 * 锁定：首次放行 / 间隔内吞掉 / 到点放行用最新 observe 值 / 强制放行 / 重置。
 */
class ProgressThrottlePolicyTest {

    private var now = 0L

    private fun policy() = ProgressThrottlePolicy(nowMs = { now })

    @Test
    fun `从未observe过不放行`() {
        val p = policy()
        assertNull(p.consumeReportable())
    }

    @Test
    fun `首次observe后立即放行`() {
        val p = policy()
        p.observe(12.0)
        assertEquals(12.0, p.consumeReportable()!!, 0.0)
    }

    @Test
    fun `间隔内吞掉返回null`() {
        val p = policy()
        p.observe(10.0)
        assertEquals(10.0, p.consumeReportable()!!, 0.0) // t=0 放行
        now = ProgressThrottlePolicy.PROGRESS_REPORT_INTERVAL_MS - 1 // t=4999：窗口内
        p.observe(20.0)
        assertNull(p.consumeReportable())
    }

    @Test
    fun `到点放行且用最新observe值`() {
        val p = policy()
        p.observe(10.0)
        assertEquals(10.0, p.consumeReportable()!!, 0.0) // t=0 首报
        now = 1_000L
        p.observe(20.0) // 窗口内，将被吞
        now = 2_000L
        p.observe(25.0) // 窗口内最新值
        now = ProgressThrottlePolicy.PROGRESS_REPORT_INTERVAL_MS // t=5000：距首报恰 5s
        assertEquals(25.0, p.consumeReportable()!!, 0.0) // 放行取最新 25，不是被吞的 10/20
    }

    @Test
    fun `恰好等于间隔即放行 - 4999吞5000放`() {
        val p = policy()
        p.observe(1.0)
        assertEquals(1.0, p.consumeReportable()!!, 0.0)
        now = 4_999L
        assertNull(p.consumeReportable())
        now = 5_000L
        p.observe(2.0)
        assertEquals(2.0, p.consumeReportable()!!, 0.0)
    }

    @Test
    fun `强制放行无视窗口且消耗强制标记`() {
        val p = policy()
        p.observe(30.0)
        assertEquals(30.0, p.consumeReportable()!!, 0.0) // t=0
        p.observe(31.0)
        p.force() // 暂停/离开立即补报
        assertEquals(31.0, p.consumeReportable()!!, 0.0) // t 仍为 0 也放行
        // 强制标记一次性：紧接再消费仍受窗口约束
        p.observe(32.0)
        assertNull(p.consumeReportable())
    }

    @Test
    fun `重置回到初态 - 强制标记与窗口一并清除`() {
        val p = policy()
        p.observe(1.0)
        assertEquals(1.0, p.consumeReportable()!!, 0.0)
        p.force()
        p.reset()
        now = 100L
        assertNull(p.consumeReportable()) // reset 连最新位置一并清空（换资产旧位置不得外泄）
        p.observe(9.0)
        assertEquals(9.0, p.consumeReportable()!!, 0.0) // 首次必放行，不受此前窗口影响
    }
}
