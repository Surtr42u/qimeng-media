package media.qimeng.app.feature.detail.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * dwell 停留会话单测（M4-3 3d）：锁定「按后台切换分段、逐条累加（服务端 dwell 不去重）」——
 * 真实口径出处 engagement.go / GUIDE_API.md（见 DwellSessionTracker 类注释；openapi:951
 * 笼统去重注释有误导，勘误已上报待拍板）。
 */
class DwellSessionTrackerTest {

    private var now = 0L
    private val emissions = mutableListOf<Pair<Long, Long>>() // startedAtMs to seconds

    private fun tracker() = DwellSessionTracker(nowMs = { now }) { start, seconds ->
        emissions += start to seconds
    }

    @Test
    fun `未enter时flush无害`() {
        val t = tracker()
        t.flush()
        t.leave()
        t.dispose()
        assertEquals(0, emissions.size)
    }

    @Test
    fun `正常停留离开恰好一条 - seconds为停留秒数截断`() {
        val t = tracker()
        now = 1_000L
        t.enter()
        now = 66_500L
        t.leave()
        assertEquals(1, emissions.size)
        assertEquals(1_000L, emissions[0].first)
        // 65_500ms → 截断为 65 秒（长整型除法，非四舍五入）
        assertEquals(65L, emissions[0].second)
    }

    @Test
    fun `重复flush幂等 - 只发直到下次enter前的一条`() {
        val t = tracker()
        now = 0L
        t.enter()
        now = 10_000L
        t.flush()
        t.flush()
        t.flush()
        now = 20_000L
        t.leave()
        assertEquals(1, emissions.size)
        assertEquals(10L, emissions[0].second)
    }

    @Test
    fun `暂停后resume再离开 - 两段两条各自秒数正确`() {
        // 口径：服务端 dwell 不去重、逐条累加（engagement.go「停留时长每次都有效」）——
        // 暂停 flush 第一段并结束会话，resume 开新段，离开 flush 第二段，秒数各自独立。
        val t = tracker()
        now = 0L
        t.enter()
        now = 30_000L
        t.pause() // 第一段：30s（startedAt=0）
        now = 40_000L
        t.resume() // 开新段（新 startedAt=40_000）
        now = 130_000L
        t.leave() // 第二段：90s
        assertEquals(2, emissions.size)
        assertEquals(0L, emissions[0].first)
        assertEquals(30L, emissions[0].second)
        assertEquals(40_000L, emissions[1].first)
        assertEquals(90L, emissions[1].second)
    }

    @Test
    fun `resume未enter过则忽略`() {
        // 从未 enter（无会话、未挂起）：resume 不得凭空开段
        val t = tracker()
        now = 10_000L
        t.resume()
        t.dispose()
        assertEquals(0, emissions.size)
    }

    @Test
    fun `暂停后未resume直接离开只报一段 - leave后再resume不凭空开新段`() {
        val t = tracker()
        now = 0L
        t.enter()
        now = 20_000L
        t.pause() // 第一段 20s，会话已结束
        now = 50_000L
        t.leave() // 无第二段可 flush（幂等）
        now = 80_000L
        t.resume() // 已离开：无害 no-op
        assertEquals(1, emissions.size)
        assertEquals(20L, emissions[0].second)
    }

    @Test
    fun `活动期内重复enter幂等 - 不重置计时不开新会话`() {
        val t = tracker()
        now = 0L
        t.enter()
        now = 10_000L
        t.enter() // 幂等 no-op
        now = 20_000L
        t.leave()
        assertEquals(1, emissions.size)
        assertEquals(20L, emissions[0].second) // 自首次 enter 起算
    }

    @Test
    fun `离开后再enter是新停留 - 新会话新计时`() {
        val t = tracker()
        now = 0L
        t.enter()
        now = 5_000L
        t.leave() // 第一条停留
        now = 100_000L
        t.enter()
        now = 103_000L
        t.leave() // 第二条停留（新 startedAt）
        assertEquals(2, emissions.size)
        assertEquals(5L, emissions[0].second)
        assertEquals(100_000L, emissions[1].first)
        assertEquals(3L, emissions[1].second)
    }

    @Test
    fun `resume未flush过的停留 - 离开时上报全程时长`() {
        // resume 未紧跟 pause 时为无害 no-op（不会重置/新开段）；没有 pause 的停留
        // 由 leave 一次性上报，且时长覆盖 enter→leave 全程。
        val t = tracker()
        now = 0L
        t.enter()
        now = 45_000L
        t.resume() // 无 pause 在前的 resume 无副作用
        now = 61_000L
        t.leave()
        assertEquals(1, emissions.size)
        assertEquals(61L, emissions[0].second)
    }

    @Test
    fun `连续两次pause后resume仍开新段 - 计时不丢段`() {
        // 回归锁定（2026-09-07 审查 P3）：第二次 pause 时 session 已为 null，不得清掉
        // 第一次 pause 置好的挂起位——否则 resume 变 no-op，pause→pause→resume 场景
        // （生命周期重复回调）丢一段计时。
        val t = tracker()
        now = 0L
        t.enter()
        now = 30_000L
        t.pause() // 第一段：30s（startedAt=0）
        t.pause() // 第二次 pause：无会话可结束，不得影响挂起位
        now = 40_000L
        t.resume() // 仍应开新段（startedAt=40_000）
        now = 100_000L
        t.leave() // 第二段：60s
        assertEquals(2, emissions.size)
        assertEquals(0L, emissions[0].first)
        assertEquals(30L, emissions[0].second)
        assertEquals(40_000L, emissions[1].first)
        assertEquals(60L, emissions[1].second)
    }
}
