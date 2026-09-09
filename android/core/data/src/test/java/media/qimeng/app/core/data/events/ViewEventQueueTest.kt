package media.qimeng.app.core.data.events

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import media.qimeng.app.core.model.ViewEventKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ViewEventQueue 行为锁定（任务L L5「本地优先」口径；推翻 M4-4 先删后发/毒丸）：
 * 入队即生成幂等键 / FIFO 环形上限淘汰 / drain 串行（并发=1）/ **发送成功（2xx）才删** /
 * IO·5xx 保留退避重试（同 id 重发）/ 4xx 终局标记保留供导出 / 幂等键懒回填。
 * DAO 与出网全用 fake（禁 MockK；DAO 默认方法语义按接口契约复刻）。
 */
class ViewEventQueueTest {

    // ---------- 测试替身 ----------

    /** 可控时钟（退避到期时刻断言用；drain 经构造注入读取） */
    private class FakeClock(var current: Long = 1_000_000L) : EventClock {
        override fun now(): Long = current
        fun advanceBy(ms: Long) {
            current += ms
        }
    }

    /** 内存 DAO：按接口语义复刻（insert 自增 id / 淘汰保最新 / selectDue 只读不删） */
    private class FakeDao : PendingViewEventDao {
        val rows = mutableListOf<PendingViewEventEntity>()
        var nextId = 1L
        var failInsert = false

        override suspend fun insert(entity: PendingViewEventEntity): Long {
            if (failInsert) throw IllegalStateException("db boom")
            val id = nextId++
            rows += entity.copy(id = id)
            return id
        }

        override suspend fun evictBeyondLimit(limit: Int) {
            if (rows.size <= limit) return // 快路径：未超限不做排序扫描（5000 行量级下保单测速度）
            val keepIds = rows.sortedByDescending { it.id }.take(limit).map { it.id }.toSet()
            rows.removeAll { it.id !in keepIds }
        }

        override suspend fun selectDue(now: Long, limit: Int): List<PendingViewEventEntity> =
            rows.filter { !it.terminal && it.nextAttemptAt <= now }.sortedBy { it.id }.take(limit)

        override suspend fun deleteByIds(ids: List<Long>) {
            rows.removeAll { it.id in ids }
        }

        override suspend fun reschedule(id: Long, nextAttemptAt: Long) {
            val i = rows.indexOfFirst { it.id == id }
            if (i >= 0) rows[i] = rows[i].copy(nextAttemptAt = nextAttemptAt)
        }

        override suspend fun markTerminal(id: Long) {
            val i = rows.indexOfFirst { it.id == id }
            if (i >= 0) rows[i] = rows[i].copy(terminal = true)
        }

        override suspend fun updateClientEventId(id: Long, clientEventId: String) {
            val i = rows.indexOfFirst { it.id == id }
            if (i >= 0) rows[i] = rows[i].copy(clientEventId = clientEventId)
        }

        override suspend fun listAll(): List<PendingViewEventEntity> = rows.sortedBy { it.id }

        override suspend fun countPending(): Int = rows.count { !it.terminal }

        override suspend fun count(): Int = rows.size
    }

    /** 假发送器：可编程每行裁决；记录发送史与并发峰值（串行/同 id 断言用） */
    private class FakeSender(
        private val decide: (List<PendingViewEventEntity>) -> ViewEventSendResult = { ViewEventSendResult.Http(202) },
    ) : ViewEventSender {
        val sent = mutableListOf<PendingViewEventEntity>()
        var inFlight = 0
        var maxInFlight = 0

        override suspend fun send(event: PendingViewEventEntity): ViewEventSendResult {
            sent += event
            inFlight++
            if (inFlight > maxInFlight) maxInFlight = inFlight
            inFlight--
            return decide(sent)
        }
    }

    // ---------- 入队 ----------

    @Test
    fun `入队即生成幂等键 - 每行 UUID 且互不相同`() = runTest {
        val dao = FakeDao()
        val queue = ViewEventQueue(dao, FakeSender())
        queue.enqueue("a", ViewEventKind.OPEN, startedAtMs = 1L, durationMs = 0L, sessionId = "s")
        queue.enqueue("a", ViewEventKind.DWELL, startedAtMs = 2L, durationMs = 5_000L, sessionId = "s")

        val ids = dao.rows.map { it.clientEventId }
        assertEquals(2, ids.size)
        ids.forEach { assertTrue("应为合法 UUID: $it", it.matches(UUID_REGEX)) }
        assertNotEquals(ids[0], ids[1])
    }

    @Test
    fun `入队FIFO淘汰 - 超上限5000丢最旧且幂等键随行存活`() = runTest {
        val dao = FakeDao()
        val queue = ViewEventQueue(dao, FakeSender())
        repeat(ViewEventQueue.MAX_QUEUE_SIZE + 1) {
            queue.enqueue("a", ViewEventKind.OPEN, startedAtMs = 1L, durationMs = 0L, sessionId = "s")
        }

        // 冻结口径直接实测：上限=5000（非缩小的仿真值），恰好淘汰最旧 1 行
        assertEquals(ViewEventQueue.MAX_QUEUE_SIZE, dao.rows.size)
        assertEquals(2L, dao.rows.minOf { it.id })     // id 1（最旧）被淘汰
        assertEquals(ViewEventQueue.MAX_QUEUE_SIZE + 1L, dao.rows.maxOf { it.id })
        assertEquals(ViewEventQueue.MAX_QUEUE_SIZE, dao.rows.count { it.clientEventId.matches(UUID_REGEX) })
    }

    @Test
    fun `写入失败 - 原样抛异常（静默语义=写队列失败才静默，由调用方收口）`() = runTest {
        val dao = FakeDao().apply { failInsert = true }
        val queue = ViewEventQueue(dao, FakeSender())
        var thrown = false
        try {
            queue.enqueue("a", ViewEventKind.OPEN, startedAtMs = 1L, durationMs = 0L, sessionId = "s")
        } catch (expected: IllegalStateException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    // ---------- drain：发送成功才删 ----------

    @Test
    fun `drain成功 - 202确认后删行队列清空（发送成功才删）`() = runTest {
        val dao = FakeDao()
        val sender = FakeSender()
        val queue = ViewEventQueue(dao, sender)
        queue.enqueue("a", ViewEventKind.OPEN, 1L, 0L, "s")
        queue.enqueue("a", ViewEventKind.DWELL, 1L, 5000L, "s")

        val summary = queue.drain()

        assertEquals(2, summary.sent)
        assertEquals(0, summary.dropped)
        assertEquals(0, summary.keptForRetry)
        assertEquals(0, queue.pendingCount())
        assertEquals(0, dao.rows.size)                       // 2xx 之后才删
        assertEquals(2, sender.sent.count { it.clientEventId.matches(UUID_REGEX) }) // 出网体带幂等键
    }

    @Test
    fun `发送失败 - 行保留在队列且内容原样（失败也有本地的）`() = runTest {
        val dao = FakeDao()
        val queue = ViewEventQueue(dao, FakeSender(decide = { ViewEventSendResult.IoError }))
        queue.enqueue("a", ViewEventKind.DWELL, startedAtMs = 42L, durationMs = 1234L, sessionId = "s")

        val summary = queue.drain()

        assertEquals(0, summary.sent)
        assertEquals(1, summary.keptForRetry)
        val row = dao.rows.single()
        assertEquals(42L, row.startedAt)
        assertEquals(1234L, row.durationMs)
        assertEquals(ViewEventKind.DWELL.name, row.kind)
        assertFalse(row.terminal)
    }

    // ---------- drain：退避重试 ----------

    @Test
    fun `退避重试 - IO失败写退避到期时刻，到期前不取件，到期后同id重发成功`() = runTest {
        val clock = FakeClock(current = 1_000_000L)
        val dao = FakeDao()
        var fail = true
        val sender = FakeSender(decide = { if (fail) ViewEventSendResult.IoError else ViewEventSendResult.Http(202) })
        val queue = ViewEventQueue(dao, sender, clock = clock)
        queue.enqueue("a", ViewEventKind.DWELL, 1L, 1234L, "s")
        val originalId = dao.rows.single().clientEventId

        val first = queue.drain()
        assertEquals(1, first.keptForRetry)
        assertEquals(clock.current + ViewEventSendPolicy.RETRY_BACKOFF_BASE_MS, dao.rows.single().nextAttemptAt)

        // 退避未到期：drain 取不到件（不狂打故障端点）
        clock.advanceBy(ViewEventSendPolicy.RETRY_BACKOFF_BASE_MS - 1)
        val premature = queue.drain()
        assertEquals(0, premature.sent)
        assertEquals(0, premature.keptForRetry)
        assertEquals(1, sender.sent.size)

        // 到期后重发：同一行同一幂等键（服务端幂等的前提），成功后删行
        clock.advanceBy(1)
        fail = false
        val second = queue.drain()
        assertEquals(1, second.sent)
        assertEquals(originalId, sender.sent[1].clientEventId) // 同 id 重发
        assertEquals(0, queue.pendingCount())
    }

    @Test
    fun `退避档位 - 连败指数增长且封顶15分钟（纯函数表）`() {
        assertEquals(30_000L, ViewEventSendPolicy.backoffDelayMs(1))
        assertEquals(60_000L, ViewEventSendPolicy.backoffDelayMs(2))
        assertEquals(120_000L, ViewEventSendPolicy.backoffDelayMs(3))
        assertEquals(240_000L, ViewEventSendPolicy.backoffDelayMs(4))
        assertEquals(
            ViewEventSendPolicy.RETRY_BACKOFF_MAX_MS,
            ViewEventSendPolicy.backoffDelayMs(20),
        )
        // 溢出护栏：超大连败不炸不回绕
        assertEquals(ViewEventSendPolicy.RETRY_BACKOFF_MAX_MS, ViewEventSendPolicy.backoffDelayMs(Int.MAX_VALUE))
    }

    @Test
    fun `多批循环 - 超单批50行分轮清空，RETRY退避行到期后收敛`() = runTest {
        val clock = FakeClock()
        val dao = FakeDao()
        // DWELL 行（id 1..10）只在各自首发时失败一次（IoError 退避），重发成功
        val failedOnce = mutableSetOf<Long>()
        val sender = FakeSender(decide = { history ->
            val current = history.last()
            if (current.kind == ViewEventKind.DWELL.name && current.id !in failedOnce) {
                failedOnce.add(current.id)
                ViewEventSendResult.IoError
            } else {
                ViewEventSendResult.Http(202)
            }
        })
        val queue = ViewEventQueue(dao, sender, clock = clock)
        repeat(10) { queue.enqueue("a", ViewEventKind.DWELL, 1L, 5000L, "s") } // id 1..10
        repeat(110) { queue.enqueue("a", ViewEventKind.OPEN, 1L, 0L, "s") }    // id 11..120

        val first = queue.drain()
        assertEquals(110, first.sent)          // OPEN 全收敛；DWELL 首轮全退避
        assertEquals(10, first.keptForRetry)
        assertEquals(10, dao.rows.size)        // 退避行留在队列（本地优先不丢）

        clock.advanceBy(ViewEventSendPolicy.RETRY_BACKOFF_MAX_MS)
        val second = queue.drain()
        assertEquals(10, second.sent)          // DWELL 到期重发成功
        assertEquals(0, queue.pendingCount())  // 终止性：队列清空
        // 总发送 = 110 OPEN 各 1 次 + 10 DWELL 各 2 次（首发退避 + 重发成功），无重复计
        assertEquals(130, sender.sent.size)
    }

    // ---------- drain：终局标记 ----------

    @Test
    fun `终局4xx - 标记后不再重试但行保留供导出（不删行）`() = runTest {
        val clock = FakeClock()
        val dao = FakeDao()
        val sender = FakeSender(decide = { ViewEventSendResult.Http(401) })
        val queue = ViewEventQueue(dao, sender, clock = clock)
        queue.enqueue("a", ViewEventKind.OPEN, 1L, 0L, "s")
        queue.enqueue("a", ViewEventKind.OPEN, 2L, 0L, "s")

        val summary = queue.drain()

        assertEquals(0, summary.sent)
        assertEquals(2, summary.dropped)
        assertEquals(0, summary.keptForRetry)
        assertEquals(2, dao.rows.size)                       // 保留
        assertTrue(dao.rows.all { it.terminal })             // 全部终局标记
        assertEquals(0, queue.pendingCount())                // 不算待上传
        // 后续 drain 永不再取终局行（拨多轮也不重发）
        clock.advanceBy(10 * ViewEventSendPolicy.RETRY_BACKOFF_MAX_MS)
        repeat(3) {
            val again = queue.drain()
            assertEquals(0, again.sent + again.keptForRetry + again.dropped)
        }
        assertEquals(2, sender.sent.size)                    // 各只发一次
    }

    // ---------- drain：幂等键 ----------

    @Test
    fun `迁移存量行 - 幂等键懒回填一次落库，重试复用同一id`() = runTest {
        val clock = FakeClock()
        val dao = FakeDao()
        var fail = true
        val sender = FakeSender(decide = { if (fail) ViewEventSendResult.IoError else ViewEventSendResult.Http(202) })
        val queue = ViewEventQueue(dao, sender, clock = clock)
        // 模拟 1→2 迁移存量行：clientEventId=''（DEFAULT ''）
        dao.insert(
            PendingViewEventEntity(
                assetId = "a", kind = ViewEventKind.OPEN.name, startedAt = 1L, durationMs = 0L,
                sessionId = "s", createdAt = 1L, clientEventId = "",
            ),
        )

        queue.drain()
        val backfilled = dao.rows.single().clientEventId
        assertTrue("回填后应为 UUID: $backfilled", backfilled.matches(UUID_REGEX))

        clock.advanceBy(ViewEventSendPolicy.RETRY_BACKOFF_MAX_MS)
        fail = false
        queue.drain()
        assertEquals(backfilled, sender.sent[1].clientEventId) // 重发复用同一 id
        assertEquals(0, queue.pendingCount())
    }

    // ---------- drain：串行 ----------

    @Test
    fun `drain串行 - 并发触发时发送并发峰值恒为1`() = runTest {
        val dao = FakeDao()
        val sender = FakeSender()
        val queue = ViewEventQueue(dao, sender)
        repeat(4) { queue.enqueue("a", ViewEventKind.OPEN, 1L, 0L, "s") }

        val first = launch { queue.drain() }
        val second = launch { queue.drain() }
        first.join()
        second.join()

        assertEquals(4, sender.sent.size)     // 4 行各恰好发一次（失败保留≠重复计）
        assertEquals(1, sender.maxInFlight)   // Mutex 串行：任何时刻至多 1 条在发
        assertEquals(0, queue.pendingCount())
    }

    // ---------- 导出 ----------

    @Test
    fun `导出 - 全量未上传行序列化为可直接POST的JSON数组`() = runTest {
        val dao = FakeDao()
        val queue = ViewEventQueue(dao, FakeSender())
        queue.enqueue(
            "00000000-0000-0000-0000-000000000001", ViewEventKind.OPEN,
            startedAtMs = 1_700_000_000_123L, durationMs = 0L, sessionId = "sess-1",
        )
        queue.enqueue(
            "00000000-0000-0000-0000-000000000002", ViewEventKind.DWELL,
            startedAtMs = 1_700_000_000_456L, durationMs = 7_350L, sessionId = "sess-1",
        )

        // 一行终局：终局行也属于「未上传」，导出必须带上（用户手动补传的兜底）
        dao.markTerminal(dao.rows.last().id)

        val export = queue.exportPending()

        assertEquals(2, export.count)
        assertTrue(export.json.trimStart().startsWith("["))
        assertTrue(export.json.contains("\"clientEventId\""))
        assertTrue(export.json.contains("\"kind\":\"open\""))          // 协议小写
        assertTrue(export.json.contains("\"kind\":\"dwell\""))
        assertTrue(export.json.contains("\"seconds\":7.35"))           // ms→秒无损
        assertFalse(export.json.contains("OPEN"))                      // 不得漏大写枚举名
    }

    @Test
    fun `空队列 - drain 幂等无害且导出为空数组`() = runTest {
        val queue = ViewEventQueue(FakeDao(), FakeSender())
        val summary = queue.drain()
        assertEquals(0, summary.sent)
        assertEquals(0, summary.dropped)
        assertEquals(0, summary.keptForRetry)
        assertEquals("[]", queue.exportPending().json)
    }

    companion object {
        private val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}
