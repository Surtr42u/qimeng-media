package media.qimeng.app.core.data.events

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import media.qimeng.app.core.model.ViewEventKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ViewEventQueue 行为锁定（M4-4 冻结口径）：
 * 入队 FIFO 环形上限淘汰 / drain 串行（并发=1）/ 先删后发时序 / 毒丸连败丢弃 /
 * 可重试失败保留回队。DAO 与出网全用 fake（禁 MockK；DAO 默认方法语义按接口契约复刻）。
 */
class ViewEventQueueTest {

    // ---------- 测试替身 ----------

    /** 全局操作序号源（先删后发时序断言用：deleteByIds 与 send 各领序号，小者先发生） */
    private class OpSequence {
        var next = 0L
        fun tick(): Long = ++next
    }

    /** 内存 DAO：按接口语义复刻（insert 自增 id / 淘汰保最新 / 取件按 id 升序+即删） */
    private class FakeDao(private val seq: OpSequence = OpSequence()) : PendingViewEventDao {
        val rows = mutableListOf<PendingViewEventEntity>()
        var nextId = 1L
        var failInsert = false

        /** rowId → 删除时刻的全局序号（先删后发时序断言用） */
        val deleteSeq = mutableMapOf<Long, Long>()

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

        override suspend fun selectOldest(limit: Int): List<PendingViewEventEntity> =
            rows.sortedBy { it.id }.take(limit)

        override suspend fun deleteByIds(ids: List<Long>) {
            ids.forEach { deleteSeq[it] = seq.tick() }
            rows.removeAll { it.id in ids }
        }

        override suspend fun count(): Int = rows.size
    }

    /** 假发送器：可编程每行裁决；记录发送序与并发峰值（串行/时序断言用） */
    private class FakeSender(
        private val decide: (PendingViewEventEntity) -> ViewEventSendResult = { ViewEventSendResult.Http(202) },
        private val delayMs: Long = 0,
        private val seq: OpSequence = OpSequence(),
    ) : ViewEventSender {
        val sent = mutableListOf<PendingViewEventEntity>()
        var inFlight = 0
        var maxInFlight = 0

        /** rowId → 发送时刻的全局序号（先删后发时序断言用） */
        val sendSeq = mutableMapOf<Long, Long>()

        override suspend fun send(event: PendingViewEventEntity): ViewEventSendResult {
            sent += event
            sendSeq[event.id] = seq.tick()
            inFlight++
            if (inFlight > maxInFlight) maxInFlight = inFlight
            if (delayMs > 0) delay(delayMs)
            inFlight--
            return decide(event)
        }
    }

    private fun eventOf(kind: ViewEventKind = ViewEventKind.OPEN) = PendingViewEventEntity(
        assetId = "a", kind = kind.name, startedAt = 1L, durationMs = 0L,
        sessionId = "s", createdAt = 1L,
    )

    // ---------- 用例 ----------

    @Test
    fun `入队FIFO淘汰 - 超冻结上限5000丢最旧`() = runTest {
        val dao = FakeDao()
        val queue = ViewEventQueue(dao, FakeSender())
        repeat(ViewEventQueue.MAX_QUEUE_SIZE + 1) {
            queue.enqueue("a", ViewEventKind.OPEN, startedAtMs = 1L, durationMs = 0L, sessionId = "s")
        }

        // 冻结口径直接实测：上限=5000（非缩小的仿真值），恰好淘汰最旧 1 行
        assertEquals(ViewEventQueue.MAX_QUEUE_SIZE, dao.rows.size)
        assertEquals(2L, dao.rows.minOf { it.id })     // id 1（最旧）被淘汰
        assertEquals(ViewEventQueue.MAX_QUEUE_SIZE + 1L, dao.rows.maxOf { it.id })
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

    @Test
    fun `drain成功 - 202确认后队列清空`() = runTest {
        val dao = FakeDao()
        val queue = ViewEventQueue(dao, FakeSender())
        queue.enqueue("a", ViewEventKind.OPEN, 1L, 0L, "s")
        queue.enqueue("a", ViewEventKind.DWELL, 1L, 5000L, "s")

        val summary = queue.drain()

        assertEquals(2, summary.sent)
        assertEquals(0, summary.dropped)
        assertEquals(0, summary.keptForRetry)
        assertEquals(0, queue.pendingCount())
    }

    @Test
    fun `先删后发 - 每行取件删除的全局序号先于其发送序号（at-most-once 时序）`() = runTest {
        val seq = OpSequence()
        val dao = FakeDao(seq)
        val sender = FakeSender(seq = seq)
        val queue = ViewEventQueue(dao, sender)
        repeat(3) { queue.enqueue("a", ViewEventKind.OPEN, 1L, 0L, "s") }

        queue.drain()

        // 硬时序断言（审查清偿弱断言）：若实现改成「先发后删」（at-most-once 变
        // at-least-once），deleteSeq[id] > sendSeq[id] 必红；@Transaction takeOldest 的
        // SQL 原子性由 Room 编译期校验 + 模拟器对账实测覆盖
        assertEquals(3, sender.sent.size)
        sender.sent.forEach { event ->
            val deletedAt = dao.deleteSeq[event.id]
            val sentAt = sender.sendSeq[event.id]
            assertTrue("rowId=${event.id} delete序号=$deletedAt 应先于 send序号=$sentAt", deletedAt != null && sentAt != null && deletedAt < sentAt)
        }
        assertEquals(0, queue.pendingCount())
    }

    @Test
    fun `多批循环 - 超单批50行分轮取件清空，RETRY回队与新批交错终止`() = runTest {
        val dao = FakeDao()
        // 120 行 = 3 批（50+50+20）+ 回队行补 1 批；前 10 行 DWELL 首发必败（IoError 回队尾
        // 换新 id > 120），重发成功——锁定批满再取一轮的循环推进与 RETRY 行不滞留不重复计
        val sender = FakeSender(decide = { event ->
            if (event.kind == ViewEventKind.DWELL.name && event.id <= 120L) ViewEventSendResult.IoError
            else ViewEventSendResult.Http(202)
        })
        val queue = ViewEventQueue(dao, sender)
        repeat(10) { queue.enqueue("a", ViewEventKind.DWELL, 1L, 5000L, "s") } // id 1..10
        repeat(110) { queue.enqueue("a", ViewEventKind.OPEN, 1L, 0L, "s") }    // id 11..120

        val summary = queue.drain()

        assertEquals(120, summary.sent)          // 全部最终收敛（10 DWELL 第二次发送 + 110 OPEN）
        assertEquals(10, summary.keptForRetry)   // DWELL 各回队一次
        assertEquals(0, queue.pendingCount())    // 终止性：队列清空
        assertEquals(130, sender.sent.size)      // 总发送 = 120 成功 + 10 失败重试，无重复计
    }

    @Test
    fun `drain串行 - 并发触发时发送并发峰值恒为1`() = runTest {
        val dao = FakeDao()
        val sender = FakeSender(delayMs = 10) // 虚拟时间下制造发送窗口，放大并发竞争
        val queue = ViewEventQueue(dao, sender)
        repeat(4) { queue.enqueue("a", ViewEventKind.OPEN, 1L, 0L, "s") }

        val first = launch { queue.drain() }
        val second = launch { queue.drain() }
        first.join()
        second.join()

        assertEquals(4, sender.sent.size)     // 4 行都只发一次（重试保留不重复计）
        assertEquals(1, sender.maxInFlight)   // Mutex 串行：任何时刻至多 1 条在发
    }

    @Test
    fun `毒丸 - 5xx连败达阈值丢弃且不阻塞后续事件补传`() = runTest {
        val dao = FakeDao()
        val sender = FakeSender(decide = { event ->
            if (event.kind == ViewEventKind.DWELL.name) ViewEventSendResult.Http(500)
            else ViewEventSendResult.Http(202)
        })
        val queue = ViewEventQueue(dao, sender)
        queue.enqueue("a", ViewEventKind.DWELL, 1L, 8000L, "s")   // 毒丸
        queue.enqueue("a", ViewEventKind.OPEN, 1L, 0L, "s")       // 正常行（排在毒丸之后）

        // 三轮补传：连败 1 → 2 → 达阈值 3 丢弃（计数跨 drain 存活在进程内存）
        val first = queue.drain()
        val second = queue.drain()
        val third = queue.drain()

        assertEquals(1, first.keptForRetry)               // 第 1 轮：毒丸回队保留
        assertEquals(1, second.keptForRetry)              // 第 2 轮：再保留
        assertEquals(1, third.dropped)                    // 第 3 轮：连败达 3 丢弃
        assertEquals(0, queue.pendingCount())             // 丢弃后不再滞留
        // 毒丸被发 3 次（3 轮各 1 次）后弃；正常行只发 1 次即收敛
        assertEquals(3, sender.sent.count { it.kind == ViewEventKind.DWELL.name })
        assertEquals(1, sender.sent.count { it.kind == ViewEventKind.OPEN.name })
    }

    @Test
    fun `保留重试 - 可重试失败回队尾且行内容原样`() = runTest {
        val dao = FakeDao()
        val sender = FakeSender(decide = { ViewEventSendResult.IoError })
        val queue = ViewEventQueue(dao, sender)
        queue.enqueue("a", ViewEventKind.DWELL, startedAtMs = 42L, durationMs = 1234L, sessionId = "s")

        val summary = queue.drain()

        assertEquals(0, summary.sent)
        assertEquals(1, summary.keptForRetry)
        val row = dao.rows.single()
        assertEquals(42L, row.startedAt)
        assertEquals(1234L, row.durationMs)
        assertEquals(ViewEventKind.DWELL.name, row.kind)
    }

    @Test
    fun `终局丢弃 - 4xx 不回队（重发无意义）`() = runTest {
        val dao = FakeDao()
        val sender = FakeSender(decide = { ViewEventSendResult.Http(401) })
        val queue = ViewEventQueue(dao, sender)
        queue.enqueue("a", ViewEventKind.OPEN, 1L, 0L, "s")

        val summary = queue.drain()

        assertEquals(0, summary.sent)
        assertEquals(1, summary.dropped)
        assertEquals(0, summary.keptForRetry)
        assertEquals(0, queue.pendingCount())
        assertEquals(1, sender.sent.size) // 只试一次，不重试
    }

    @Test
    fun `空队列 - drain 幂等无害`() = runTest {
        val queue = ViewEventQueue(FakeDao(), FakeSender())
        val summary = queue.drain()
        assertEquals(0, summary.sent)
        assertEquals(0, summary.dropped)
        assertEquals(0, summary.keptForRetry)
    }
}
