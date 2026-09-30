package media.qimeng.app.core.data.prefetch

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 预取跳过判定门纯函数锁定（2026-09-30 revision 跳过批；同日撞号修订扩为五分支）：
 * 缺省永远偏 FULL（多拉一轮永远安全，少拉才丢增量）。「服务器键不等→FULL」是换端
 * 撞号缺陷的回归锁——两端修订号独立计数器且播种基线相同，revision 相等也不得 SKIP。
 */
class PrefetchRevisionGateTest {

    /** 两个不同的服务器标识（测试合成值：NAS 地址与本地端回环预设形态） */
    private val nasKey = "http://nas.local:8080"
    private val localKey = "http://127.0.0.1:18430"

    private fun record(serverKey: String, revision: Long) =
        PrefetchRevisionRecord(serverKey = serverKey, revision = revision)

    @Test
    fun `服务端revision为null降级全量`() {
        // 端点失败/旧服务端 404：没有可信依据，照旧全量（无论有无历史记录）
        assertEquals(
            PrefetchRoundDecision.FULL,
            PrefetchRevisionGate.decide(serverRevision = null, record = record(nasKey, 7L), serverKey = nasKey),
        )
        assertEquals(
            PrefetchRoundDecision.FULL,
            PrefetchRevisionGate.decide(serverRevision = null, record = null, serverKey = nasKey),
        )
    }

    @Test
    fun `无上一轮完成记录首次全量`() {
        // record=null：首次 / 已失效（清池）/ 存量旧版键迁移，都按无记录走全量
        assertEquals(
            PrefetchRoundDecision.FULL,
            PrefetchRevisionGate.decide(serverRevision = 7L, record = null, serverKey = nasKey),
        )
    }

    @Test
    fun `服务器键不等换端走全量`() {
        // 缺陷 1 回归锁：两端独立计数器撞号（revision 恰好相等）不得 SKIP，否则换端后永不预取
        assertEquals(
            PrefetchRoundDecision.FULL,
            PrefetchRevisionGate.decide(serverRevision = 7L, record = record(localKey, 7L), serverKey = nasKey),
        )
        // 反向换端同理
        assertEquals(
            PrefetchRoundDecision.FULL,
            PrefetchRevisionGate.decide(serverRevision = 7L, record = record(nasKey, 7L), serverKey = localKey),
        )
    }

    @Test
    fun `服务器键相等且修订号一致整轮跳过`() {
        assertEquals(
            PrefetchRoundDecision.SKIP,
            PrefetchRevisionGate.decide(serverRevision = 7L, record = record(nasKey, 7L), serverKey = nasKey),
        )
    }

    @Test
    fun `修订号变化跑全量`() {
        assertEquals(
            PrefetchRoundDecision.FULL,
            PrefetchRevisionGate.decide(serverRevision = 8L, record = record(nasKey, 7L), serverKey = nasKey),
        )
    }

    @Test
    fun `服务端revision回退按变更处理走全量`() {
        // 单调递增被服务端语义保证，回退只可能来自异常服务端；判 FULL 是安全方向
        assertEquals(
            PrefetchRoundDecision.FULL,
            PrefetchRevisionGate.decide(serverRevision = 6L, record = record(nasKey, 7L), serverKey = nasKey),
        )
    }
}
