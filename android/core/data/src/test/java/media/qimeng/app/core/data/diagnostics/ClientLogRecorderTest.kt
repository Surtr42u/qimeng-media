package media.qimeng.app.core.data.diagnostics

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 客户端异常上报队列语义测试：截断/容量丢最旧/切块/失败即弃——与服务端 clientlogs.go
 * 校验口径和 Web lib/client-logs.ts 策略互为锚定的行为在此锁定。
 */
class ClientLogRecorderTest {

    private class FakeSender : ClientLogSender {
        val batches = mutableListOf<List<PendingClientLog>>()
        var succeed = true

        override suspend fun send(events: List<PendingClientLog>): Boolean {
            batches += events
            return succeed
        }
    }

    private fun recorder(sender: FakeSender) =
        ClientLogRecorder(sender, UnconfinedTestDispatcher())

    private fun entry(n: Int) = PendingClientLog(
        ts = n.toLong(),
        level = ClientLogLevel.WARN,
        message = "m$n",
        stack = null,
        page = null,
    )

    @Test
    fun `flush drains queue in chunks of protocol batch max`() {
        val sender = FakeSender()
        val rec = recorder(sender)
        repeat(51) { rec.submit(ClientLogLevel.WARN, "m$it", null, null) }
        kotlinx.coroutines.runBlocking { rec.flushNow() }
        assertEquals(listOf(50, 1), sender.batches.map { it.size })
        assertEquals(51, sender.batches.sumOf { it.size })
    }

    @Test
    fun `queue capacity drops oldest beyond server ring size`() {
        val sender = FakeSender()
        val rec = recorder(sender)
        repeat(ClientLogRecorder.QUEUE_CAPACITY + 50) {
            rec.submit(ClientLogLevel.WARN, "m$it", null, null)
        }
        kotlinx.coroutines.runBlocking { rec.flushNow() }
        val all = sender.batches.flatten()
        assertEquals(ClientLogRecorder.QUEUE_CAPACITY, all.size)
        // 丢最旧：留存的是第 50 条起的最新 200 条
        assertEquals("m50", all.first().message)
        assertEquals("m249", all.last().message)
    }

    @Test
    fun `failed send drops batch without retry`() {
        val sender = FakeSender().apply { succeed = false }
        val rec = recorder(sender)
        repeat(3) { rec.submit(ClientLogLevel.ERROR, "m$it", null, null) }
        kotlinx.coroutines.runBlocking { rec.flushNow() }
        assertEquals(1, sender.batches.size) // 尝试过一次
        // 队列已被清空（失败即弃）：再 flush 不再出网
        kotlinx.coroutines.runBlocking { rec.flushNow() }
        assertEquals(1, sender.batches.size)
    }

    @Test
    fun `message and stack truncated to protocol and defense limits`() {
        val sender = FakeSender()
        val rec = recorder(sender)
        val longMessage = "x".repeat(ClientLogRecorder.MESSAGE_MAX_RUNES + 500)
        val longStack = "s".repeat(ClientLogRecorder.STACK_MAX_CHARS + 500)
        rec.submit(ClientLogLevel.ERROR, longMessage, longStack, "test")
        kotlinx.coroutines.runBlocking { rec.flushNow() }
        val sent = sender.batches.single().single()
        assertEquals(ClientLogRecorder.MESSAGE_MAX_RUNES, sent.message.length)
        assertEquals(ClientLogRecorder.STACK_MAX_CHARS, sent.stack!!.length)
    }

    @Test
    fun `level wire values match protocol enum`() {
        assertEquals("error", ClientLogLevel.ERROR.wire)
        assertEquals("warn", ClientLogLevel.WARN.wire)
        assertEquals("info", ClientLogLevel.INFO.wire)
        // 协议三枚举（openapi ClientLogEntry.level）逐一映射，无遗漏
        assertEquals(3, ClientLogLevel.entries.size)
        assertTrue(ClientLogLevel.entries.all { level ->
            listOf("error", "warn", "info").contains(level.wire)
        })
    }
}
