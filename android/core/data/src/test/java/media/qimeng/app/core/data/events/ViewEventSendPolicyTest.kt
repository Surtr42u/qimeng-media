package media.qimeng.app.core.data.events

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 发送失败分类表（ViewEventSendPolicy）逐行锁定（M4-4 冻结口径）。
 * 表驱动：每一行 = 冻结失败语义表的一条，新增/改判必须先改表注释再改此处。
 */
class ViewEventSendPolicyTest {

    private data class Row(val description: String, val result: ViewEventSendResult, val expected: ViewEventSendVerdict)

    private val table = listOf(
        Row("202=协议声明的唯一成功码 → 删行收敛", ViewEventSendResult.Http(202), ViewEventSendVerdict.CONFIRMED),
        Row("400=终局丢弃（载荷不被接受）", ViewEventSendResult.Http(400), ViewEventSendVerdict.DISCARD),
        Row("401=终局丢弃（鉴权失效）", ViewEventSendResult.Http(401), ViewEventSendVerdict.DISCARD),
        Row("403=终局丢弃（禁止）", ViewEventSendResult.Http(403), ViewEventSendVerdict.DISCARD),
        Row("404=终局丢弃（路由/资产不存在）", ViewEventSendResult.Http(404), ViewEventSendVerdict.DISCARD),
        Row("429=保留重试（限流）", ViewEventSendResult.Http(429), ViewEventSendVerdict.RETRY),
        Row("408=保留重试（请求超时）", ViewEventSendResult.Http(408), ViewEventSendVerdict.RETRY),
        Row("500=保留重试（服务端暂态）", ViewEventSendResult.Http(500), ViewEventSendVerdict.RETRY),
        Row("502=保留重试（网关）", ViewEventSendResult.Http(502), ViewEventSendVerdict.RETRY),
        Row("503=保留重试（不可用）", ViewEventSendResult.Http(503), ViewEventSendVerdict.RETRY),
        Row("599=保留重试（5xx 段上界）", ViewEventSendResult.Http(599), ViewEventSendVerdict.RETRY),
        // 协议只声明 202：非声明的 2xx 不信任为确认（重发由服务端会话级去重兜底，宁少计不虚增）
        Row("200=非声明确认 → 保守重试", ViewEventSendResult.Http(200), ViewEventSendVerdict.RETRY),
        Row("204=非声明确认 → 保守重试", ViewEventSendResult.Http(204), ViewEventSendVerdict.RETRY),
        Row("409=未声明 4xx → 保守重试", ViewEventSendResult.Http(409), ViewEventSendVerdict.RETRY),
        Row("IO 无状态码 → 保守重试", ViewEventSendResult.IoError, ViewEventSendVerdict.RETRY),
    )

    @Test
    fun `失败分类表 - 全行逐条判定`() {
        table.forEach { row ->
            assertEquals(row.description, row.expected, ViewEventSendPolicy.classify(row.result))
        }
    }

    @Test
    fun `毒丸阈值 - 冻结口径为3`() {
        assertEquals(3, ViewEventSendPolicy.MAX_CONSECUTIVE_FAILURES)
    }
}
