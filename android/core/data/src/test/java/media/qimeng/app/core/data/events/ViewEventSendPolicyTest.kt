package media.qimeng.app.core.data.events

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 发送失败分类表（ViewEventSendPolicy）逐行锁定（任务L L5「本地优先」口径）。
 * 表驱动：每一行 = 失败语义表的一条，新增/改判必须先改表注释再改此处。
 * 与 M4-4 版的差异：2xx 全段=确认（幂等键使信任 2xx 安全）、DISCARD 不再删行
 * （终局标记保留供导出）、毒丸丢弃废止（RETRY 永不放弃，退避封顶 15min）。
 */
class ViewEventSendPolicyTest {

    private data class Row(val description: String, val result: ViewEventSendResult, val expected: ViewEventSendVerdict)

    private val table = listOf(
        Row("202=协议声明的成功码 → 确认删行", ViewEventSendResult.Http(202), ViewEventSendVerdict.CONFIRMED),
        Row("200=非声明 2xx → 确认（幂等键兜底不双计，避免无底洞重试）", ViewEventSendResult.Http(200), ViewEventSendVerdict.CONFIRMED),
        Row("204=非声明 2xx → 确认（同上）", ViewEventSendResult.Http(204), ViewEventSendVerdict.CONFIRMED),
        Row("299=2xx 段上界 → 确认", ViewEventSendResult.Http(299), ViewEventSendVerdict.CONFIRMED),
        Row("400=终局标记（载荷不被接受，行保留供导出）", ViewEventSendResult.Http(400), ViewEventSendVerdict.DISCARD),
        Row("401=终局标记（鉴权失效）", ViewEventSendResult.Http(401), ViewEventSendVerdict.DISCARD),
        Row("403=终局标记（禁止）", ViewEventSendResult.Http(403), ViewEventSendVerdict.DISCARD),
        Row("404=终局标记（路由/资产不存在）", ViewEventSendResult.Http(404), ViewEventSendVerdict.DISCARD),
        Row("429=退避重试（限流）", ViewEventSendResult.Http(429), ViewEventSendVerdict.RETRY),
        Row("408=退避重试（请求超时）", ViewEventSendResult.Http(408), ViewEventSendVerdict.RETRY),
        Row("500=退避重试（服务端暂态）", ViewEventSendResult.Http(500), ViewEventSendVerdict.RETRY),
        Row("502=退避重试（网关）", ViewEventSendResult.Http(502), ViewEventSendVerdict.RETRY),
        Row("503=退避重试（不可用）", ViewEventSendResult.Http(503), ViewEventSendVerdict.RETRY),
        Row("599=退避重试（5xx 段上界）", ViewEventSendResult.Http(599), ViewEventSendVerdict.RETRY),
        Row("409=未声明 4xx → 保守重试（不轻信终局）", ViewEventSendResult.Http(409), ViewEventSendVerdict.RETRY),
        Row("IO 无状态码 → 保守重试", ViewEventSendResult.IoError, ViewEventSendVerdict.RETRY),
    )

    @Test
    fun `失败分类表 - 全行逐条判定`() {
        table.forEach { row ->
            assertEquals(row.description, row.expected, ViewEventSendPolicy.classify(row.result))
        }
    }

    @Test
    fun `协议成功码常量 - 与 openapi 声明同步（协议侧改动须同步此处）`() {
        assertEquals(202, ViewEventSendPolicy.HTTP_ACCEPTED)
    }

    @Test
    fun `退避参数 - 基础档30秒、上限对齐周期兜底15分钟`() {
        assertEquals(30_000L, ViewEventSendPolicy.RETRY_BACKOFF_BASE_MS)
        assertEquals(EventSyncWorkSpec.PERIODIC_INTERVAL_MILLIS, ViewEventSendPolicy.RETRY_BACKOFF_MAX_MS)
        // 首败即有基础档退避（防「立即同步」连点/写入触发对故障端点的狂打）
        assertEquals(ViewEventSendPolicy.RETRY_BACKOFF_BASE_MS, ViewEventSendPolicy.backoffDelayMs(1))
    }
}
