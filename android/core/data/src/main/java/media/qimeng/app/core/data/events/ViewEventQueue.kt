package media.qimeng.app.core.data.events

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import media.qimeng.app.core.model.ViewEventKind

/**
 * 行为上报离线队列（M4-4 冻结口径的核心执行体）：
 *
 * - **写**：[enqueue] 追加一枚事件 + FIFO 环形上限淘汰（[MAX_QUEUE_SIZE]），同一事务
 *   （DAO [PendingViewEventDao.enqueueWithinLimit]）；写失败原样抛异常，由调用方决定
 *   静默口径（DetailViewModel：写队列失败才静默——打点尽力而为，不影响浏览主链路）。
 * - **读（补传）**：[drain] 逐批「事务内取件删除 → 再逐条发送」——**先删后发**
 *   at-most-once（宁少计不虚增，任务书 §1 拍板写死）：进程死亡最多丢已取件的行，绝不重复计。
 *   全程 [drainMutex] 串行（并发=1）：即时（qm-event-sync-now）与周期（qm-event-sync-periodic）
 *   是两条 unique 链，可能被 WorkManager 同时拉起，靠 Mutex 保证同一时刻只有一个 drain。
 * - **失败处置**：每条发送结果经 [ViewEventSendPolicy.classify] 表驱动裁决——CONFIRMED 收敛 /
 *   DISCARD 终局丢弃 / RETRY 回队尾保留；同一事件连败达 [ViewEventSendPolicy.MAX_CONSECUTIVE_FAILURES]
 *   视为毒丸丢弃+日志。连败计数存活在本进程内存（表结构冻结无重试列）：重启清零等于给
 *   毒丸重置机会，方向仍是「宁少计不虚增」，可接受。
 *
 * 终止性：drain 每轮要么让一批行收敛/丢弃（不可逆进度），要么给批内全部 RETRY 行 +1 连败
 * （上限 [ViewEventSendPolicy.MAX_CONSECUTIVE_FAILURES]），故循环必然终止；补传途中新入队的
 * 事件由下一轮取件自然带上，不丢。
 */
@Singleton
class ViewEventQueue @Inject constructor(
    private val dao: PendingViewEventDao,
    private val sender: ViewEventSender,
) {

    /** 串行闸门：即时链与周期链并发拉起时，drain 仍严格并发=1（冻结口径） */
    private val drainMutex = Mutex()

    /** rowId → 连败计数（进程内存活；重试回队换新 id 时随迁，见 [drain]） */
    private val consecutiveFailures = mutableMapOf<Long, Int>()

    /**
     * 入队（写成功即返回，不等出网）。durationMs 口径见 [PendingViewEventEntity]：
     * open/play 传 0，dwell 传停留毫秒。DB 写失败抛原异常（静默语义收敛在调用方）。
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
                createdAt = System.currentTimeMillis(),
            ),
            limit = MAX_QUEUE_SIZE,
        )
    }

    /** 队列存量（日志/诊断用） */
    suspend fun pendingCount(): Int = dao.count()

    /**
     * 补传一轮：清空队列（或把不可达的留在队里）。返回摘要供 worker 打证据日志。
     */
    suspend fun drain(): DrainSummary = drainMutex.withLock {
        var sent = 0
        var droppedTerminal = 0
        var droppedPoison = 0
        var keptForRetry = 0
        while (true) {
            val batch = dao.takeOldest(DRAIN_BATCH_SIZE) // 先删（事务内取件即删除）
            if (batch.isEmpty()) break
            for (event in batch) {                       // 后发（at-most-once 时序）
                val sendResult = sender.send(event)
                when (ViewEventSendPolicy.classify(sendResult)) {
                    ViewEventSendVerdict.CONFIRMED -> {
                        consecutiveFailures.remove(event.id)
                        sent++
                    }

                    ViewEventSendVerdict.DISCARD -> {
                        consecutiveFailures.remove(event.id)
                        droppedTerminal++
                        Log.w(
                            EventSyncWorkSpec.LOG_TAG,
                            "终局丢弃 rowId=${event.id} kind=${event.kind} " +
                                "code=${(sendResult as? ViewEventSendResult.Http)?.statusCode}（4xx 请求不被接受，重发无意义）",
                        )
                    }

                    ViewEventSendVerdict.RETRY -> {
                        val failures = (consecutiveFailures[event.id] ?: 0) + 1
                        if (failures >= ViewEventSendPolicy.MAX_CONSECUTIVE_FAILURES) {
                            // 毒丸：连败达阈值丢弃（宁少计不虚增），计数随行清掉
                            consecutiveFailures.remove(event.id)
                            droppedPoison++
                            Log.w(
                                EventSyncWorkSpec.LOG_TAG,
                                "毒丸丢弃 rowId=${event.id} kind=${event.kind} " +
                                    "连败=$failures/${ViewEventSendPolicy.MAX_CONSECUTIVE_FAILURES}",
                            )
                        } else {
                            // 保留重试：原行回队尾（copy(id=0) 取新 id），连败计数随迁到新 id
                            val newId = dao.insert(event.copy(id = 0))
                            consecutiveFailures.remove(event.id)
                            consecutiveFailures[newId] = failures
                            keptForRetry++
                        }
                    }
                }
            }
            if (batch.size < DRAIN_BATCH_SIZE) break
        }
        val summary = DrainSummary(
            sent = sent,
            dropped = droppedTerminal + droppedPoison,
            keptForRetry = keptForRetry,
        )
        if (summary != DRAIN_SUMMARY_NOTHING) {
            Log.i(
                EventSyncWorkSpec.LOG_TAG,
                "drain sent=${summary.sent} dropped=${summary.dropped} " +
                    "kept=${summary.keptForRetry} pending=${dao.count()}",
            )
        }
        summary
    }

    /** 一轮补传摘要（worker 日志/单测断言用） */
    data class DrainSummary(val sent: Int, val dropped: Int, val keptForRetry: Int)

    companion object {
        /**
         * 队列环形上限（条）。冻结口径：MAX_QUEUE_SIZE=5000，FIFO 淘汰最旧——
         * 被淘汰=少计可接受（宁少计不虚增）；上限同时是本地存储的无界增长保险丝。
         */
        const val MAX_QUEUE_SIZE = 5000

        /**
         * 单轮取件批大小（条）：无批量端点（协议仅单条 POST /events/view），逐条出网；
         * 50 = 一个事务取件/删除的批量与单次 drain 占用时长（Mutex 独占）的折中值。
         */
        const val DRAIN_BATCH_SIZE = 50

        /** 全空队列的 drain 结果（日志降噪：空轮不打日志） */
        private val DRAIN_SUMMARY_NOTHING = DrainSummary(sent = 0, dropped = 0, keptForRetry = 0)
    }
}
