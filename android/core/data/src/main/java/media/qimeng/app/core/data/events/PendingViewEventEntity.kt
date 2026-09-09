package media.qimeng.app.core.data.events

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 待补传浏览事件行（任务L L5「本地优先」口径，推翻 M4-4 先删后发行的表结构演进）：
 *
 * ```
 * pending_view_events(
 *   id AUTOINCREMENT PK / assetId TEXT / kind TEXT / startedAt INTEGER epochMs /
 *   durationMs INTEGER / sessionId TEXT / createdAt INTEGER /
 *   clientEventId TEXT NOT NULL DEFAULT '' / terminal INTEGER NOT NULL DEFAULT 0 /
 *   nextAttemptAt INTEGER NOT NULL DEFAULT 0
 * )
 * ```
 *
 * 字段口径：
 * - [kind] 存 [media.qimeng.app.core.model.ViewEventKind] 的 name（open/play/dwell 的
 *   大写枚举名），只由本端写入；出网/导出时转协议小写（[toSdkReport]/导出映射单点）；
 * - [clientEventId]：**协议幂等键**（ViewEventReport.clientEventId，任务L L5）——入队即
 *   生成 UUID 并随本行持久，重试/补传/导出再 POST 携带**同一** id，服务端唯一索引幂等
 *   （同 id 重发 202 但不双计），这是「发送成功（2xx）才删行」的安全前提。DEFAULT '' 只
 *   服务 1→2 迁移的存量行：drain 时发现空值即懒回填生成并落库
 *   （[PendingViewEventDao.updateClientEventId]），此后不再变化；
 * - [terminal]：终局失败标记（4xx 请求不被接受，重发结局相同）——**不再重试但保留供
 *   导出**（用户可手动 POST 给 NAS 合并，用户原话 #25）；不删行的取舍：4xx 行是用户
 *   数据不是垃圾，删行=静默丢数据（旧「毒丸丢弃」口径就此废止）；
 * - [nextAttemptAt]：退避重试的下一次允许时刻（epochMs）。RETRY 裁决后写入
 *   now+退避值（[ViewEventSendPolicy.backoffDelayMs]），drain 只取到期行——退避状态
 *   持久化到行上（而非进程内存）：进程死亡重启后退避依然生效，不会对故障端点立刻
 *   狂打；1→2 迁移存量行 DEFAULT 0 = 立即可发；
 * - [durationMs]：dwell 停留毫秒（出网时 ms→秒无损换算）；open/play **恒 0**；
 * - [startedAt]/[createdAt]：epochMilli（出网时 startedAt 转 UTC OffsetDateTime——
 *   客户端本地时区只用于展示，打点存绝对时刻）；
 * - 无「重试次数」列：连败计数只驱动退避指数，存活在 [ViewEventQueue] 进程内存，
 *   重启清零=退避回基础档（下限保护仍在：backoffDelayMs 永远 ≥ 基础档）。
 */
@Entity(tableName = PendingViewEventEntity.TABLE_NAME)
data class PendingViewEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val assetId: String,
    val kind: String,
    val startedAt: Long,
    val durationMs: Long,
    val sessionId: String,
    val createdAt: Long,
    /** 协议幂等键（任务L L5）；'' = 1→2 迁移存量行，drain 懒回填后不复变 */
    val clientEventId: String = "",
    /** 终局失败标记（4xx）：true = 不再重试、保留供导出（Room 存 Boolean→INTEGER） */
    val terminal: Boolean = false,
    /** 退避重试的最早允许时刻（epochMs）；0 = 立即可发 */
    val nextAttemptAt: Long = 0,
) {
    companion object {
        /** 表名（冻结口径单一来源：DAO @Query 与本实体共用） */
        const val TABLE_NAME = "pending_view_events"
    }
}
