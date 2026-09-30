package media.qimeng.app.core.data.prefetch

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 预取跳过判定门纯函数锁定（2026-09-30 revision 跳过批）：四条规则各一分支 +
 * 回退防御分支——缺省永远偏 FULL（多拉一轮永远安全，少拉才丢增量）。
 */
class PrefetchRevisionGateTest {

    @Test
    fun `服务端revision为null降级全量`() {
        // 端点失败/旧服务端 404：没有可信依据，照旧全量（无论有无历史记录）
        assertEquals(PrefetchRoundDecision.FULL, PrefetchRevisionGate.decide(serverRevision = null, lastDoneRevision = 7L))
        assertEquals(PrefetchRoundDecision.FULL, PrefetchRevisionGate.decide(serverRevision = null, lastDoneRevision = null))
    }

    @Test
    fun `无上一轮完成记录首次全量`() {
        assertEquals(PrefetchRoundDecision.FULL, PrefetchRevisionGate.decide(serverRevision = 7L, lastDoneRevision = null))
    }

    @Test
    fun `修订号一致整轮跳过`() {
        assertEquals(PrefetchRoundDecision.SKIP, PrefetchRevisionGate.decide(serverRevision = 7L, lastDoneRevision = 7L))
    }

    @Test
    fun `修订号变化跑全量`() {
        assertEquals(PrefetchRoundDecision.FULL, PrefetchRevisionGate.decide(serverRevision = 8L, lastDoneRevision = 7L))
    }

    @Test
    fun `服务端revision回退按变更处理走全量`() {
        // 单调递增被服务端语义保证，回退只可能来自异常服务端；判 FULL 是安全方向
        assertEquals(PrefetchRoundDecision.FULL, PrefetchRevisionGate.decide(serverRevision = 6L, lastDoneRevision = 7L))
    }
}
