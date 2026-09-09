package media.qimeng.app.core.data.events

import android.util.Log
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import media.qimeng.app.core.model.ViewEventKind

/**
 * 行为上报离线队列（任务L L5「本地优先」口径的核心执行体；推翻 M4-4 先删后发/毒丸）：
 *
 * - **写**：[enqueue] 追加一枚事件 + FIFO 环形上限淘汰（[MAX_QUEUE_SIZE]），同一事务
 *   （DAO [PendingViewEventDao.enqueueWithinLimit]）；**入队即生成 clientEventId**
 *   （协议幂等键，随行持久，重试/导出携带同一 id）；写失败原样抛异常，由调用方决定
 *   静默口径（DetailViewModel：写队列失败才静默——打点尽力而为，不影响浏览主链路）。
 * - **读（补传）**：[drain] 取到期行**只读取件**（DAO [PendingViewEventDao.selectDue]），
 *   逐条发送：**2xx 确认才删行**（发送成功再删，拍板 #7）——进程死亡最多重复发送，
 *   服务端按 clientEventId 唯一索引幂等吸收，绝不双计也绝不丢。RETRY 行写退避到期
 *   时刻（[ViewEventSendPolicy.backoffDelayMs]）留在队列，下轮触发再试；4xx 行终局
 *   标记（不再重试、保留供导出，[PendingEventExport]）。全程 [drainMutex] 串行
 *   （并发=1）：即时（qm-event-sync-now）与周期（qm-event-sync-periodic）是两条
 *   unique 链，可能被 WorkManager 同时拉起，靠 Mutex 保证同一时刻只有一个 drain。
 * - **环形上限口径**：[MAX_QUEUE_SIZE]=5000 FIFO 淘汰最旧，**终局行也占额度**——
 *   取舍：本地存储有界优先（上限同时是无界增长保险丝），终局行可经导出抢救，
 *   无限堆积则伤存储；淘汰=少计可接受（与打点尽力而为的定位一致）。
 * - **dwell 单条语义原样保留**：写入侧（SdkDetailRepository/DetailViewModel）保证
 *   一次停留恰好入队一条 dwell，本队列不加不减。
 *
 * 终止性：drain 每轮要么删行/终局（不可逆进度），要么给 RETRY 行写入未来的
 * nextAttemptAt（selectDue 不再取到），循环必然终止；补传途中新入队的事件由下一轮
 * 取件自然带上，不丢。
 */
/**
 * 时钟端口：drain 取件（selectDue 的 now）与退避到期时刻计算读取。抽接口的为什么——
 * JVM 单测要可控时钟；裸注入 `() -> Long` 无法过 Dagger 装配（MissingBinding）。
 */
interface EventClock {
    fun now(): Long
}

/** 系统时钟实现（Hilt 装配默认值；单测注入可控假时钟） */
@Singleton
class SystemEventClock @Inject constructor() : EventClock {
    override fun now(): Long = System.currentTimeMillis()
}

@Singleton
class ViewEventQueue @Inject constructor(
    private val dao: PendingViewEventDao,
    private val sender: ViewEventSender,
    private val clock: EventClock = SystemEventClock(),
) {

    /** 串行闸门：即时链与周期链并发拉起时，drain 仍严格并发=1 */
    private val drainMutex = Mutex()

    /**
     * rowId → 连败计数（进程内存活；只驱动退避档位指数，退避到期时刻本身持久在
     * 行上）：重启清零 = 退避回基础档，不影响「不丢」语义。
     */
    private val consecutiveFailures = mutableMapOf<Long, Int>()

    /**
     * 入队（写成功即返回，不等出网）。clientEventId 在此生成并随行持久——单一
     * 生成点：调用方（仓库/VM）不感知幂等键，重试/导出天然复用同一 id。
     * durationMs 口径见 [PendingViewEventEntity]：open/play 传 0，dwell 传停留毫秒。
     * DB 写失败抛原异常（静默语义收敛在调用方）。
     */
    suspend fun enqueue(
        assetId: String,
        kind: ViewEventKind,
        startedAtMs: Long,
        durationMs: Long,
        sessionId: String,
    ) {
        dao.enqueueWithinLimit(
            entity = PendingViewEventEntity(
                assetId = assetId,
                kind = kind.name,
                startedAt = startedAtMs,
                durationMs = durationMs,
                sessionId = sessionId,
                createdAt = clock.now(),
                clientEventId = UUID.randomUUID().toString(),
            ),
            limit = MAX_QUEUE_SIZE,
        )
    }

    /** 待上传存量（未终局行数；设置页「浏览数据同步」行与日志用） */
    suspend fun pendingCount(): Int = dao.countPending()

    /**
     * 导出未上传 JSON（用户原话 #25「可以把安卓本地的直接传给 nas 合并」）：
     * 可重试行 + 终局行都算未上传，元素可直接作 POST /events/view 请求体
     * （同一 clientEventId，手动 POST 与自动补传幂等同源）。
     */
    suspend fun exportPending(): PendingExport {
        val rows = dao.listAll()
        return PendingExport(json = rows.toPendingExportJson(), count = rows.size)
    }

    /** 导出产物：JSON 文本 + 条数（设置页反馈文案用） */
    data class PendingExport(val json: String, val count: Int)

    /**
     * 补传一轮：2xx 删行 / RETRY 退避留行 / 4xx 终局留行。返回摘要供 worker 打证据日志。
     */
    suspend fun drain(): DrainSummary = drainMutex.withLock {
        var sent = 0
        var droppedTerminal = 0
        var keptForRetry = 0
        while (true) {
            val batch = dao.selectDue(clock.now(), DRAIN_BATCH_SIZE) // 只读取件，删除在确认之后
            if (batch.isEmpty()) break
            for (event in batch) {
                val verdict = ViewEventSendPolicy.classify(sender.send(event.ensureClientEventId()))
                when (verdict) {
                    ViewEventSendVerdict.CONFIRMED -> {
                        dao.deleteByIds(listOf(event.id))  // 发送成功（2xx）才删
                        consecutiveFailures.remove(event.id)
                        sent++
                    }

                    ViewEventSendVerdict.DISCARD -> {
                        // 终局标记：不再重试但不删行（保留供导出，旧毒丸丢弃口径废止）
                        dao.markTerminal(event.id)
                        consecutiveFailures.remove(event.id)
                        droppedTerminal++
                        Log.w(
                            EventSyncWorkSpec.LOG_TAG,
                            "终局标记 rowId=${event.id} kind=${event.kind}（4xx 重发无意义，行保留供导出）",
                        )
                    }

                    ViewEventSendVerdict.RETRY -> {
                        val failures = (consecutiveFailures[event.id] ?: 0) + 1
                        consecutiveFailures[event.id] = failures
                        val delayMs = ViewEventSendPolicy.backoffDelayMs(failures)
                        dao.reschedule(event.id, clock.now() + delayMs)
                        keptForRetry++
                        Log.i(
                            EventSyncWorkSpec.LOG_TAG,
                            "退避重试 rowId=${event.id} 连败=$failures 下次=${delayMs}ms 后",
                        )
                    }
                }
            }
            if (batch.size < DRAIN_BATCH_SIZE) break
        }
        val summary = DrainSummary(
            sent = sent,
            dropped = droppedTerminal,
            keptForRetry = keptForRetry,
        )
        if (summary != DRAIN_SUMMARY_NOTHING) {
            Log.i(
                EventSyncWorkSpec.LOG_TAG,
                "drain sent=${summary.sent} terminal=${summary.dropped} " +
                    "kept=${summary.keptForRetry} pending=${dao.countPending()}",
            )
        }
        summary
    }

    /**
     * 幂等键懒回填：1→2 迁移存量行 clientEventId=''（无伪造历史 id），发送前生成
     * 一次并落库——此后重试/导出复用同一 id；新行入队即带 id，此处直通。
     */
    private suspend fun PendingViewEventEntity.ensureClientEventId(): PendingViewEventEntity {
        if (clientEventId.isNotBlank()) return this
        val generated = UUID.randomUUID().toString()
        dao.updateClientEventId(id, generated)
        return copy(clientEventId = generated)
    }

    /** 一轮补传摘要（worker 日志/单测断言用）。dropped=本轮新终局标记的行数（不删行） */
    data class DrainSummary(val sent: Int, val dropped: Int, val keptForRetry: Int)

    companion object {
        /**
         * 队列环形上限（条）。MAX_QUEUE_SIZE=5000，FIFO 淘汰最旧；终局行占额度
         * （取舍见类注释「环形上限口径」），上限同时是本地存储的无界增长保险丝。
         */
        const val MAX_QUEUE_SIZE = 5000

        /**
         * 单轮取件批大小（条）：无批量端点（协议仅单条 POST /events/view），逐条出网；
         * 50 = 单次 drain 取件批量与占用时长（Mutex 独占）的折中值。
         */
        const val DRAIN_BATCH_SIZE = 50

        /** 全空队列的 drain 结果（日志降噪：空轮不打日志） */
        private val DRAIN_SUMMARY_NOTHING = DrainSummary(sent = 0, dropped = 0, keptForRetry = 0)
    }
}
