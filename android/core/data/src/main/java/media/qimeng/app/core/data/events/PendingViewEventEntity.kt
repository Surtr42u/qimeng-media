package media.qimeng.app.core.data.events

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 待补传浏览事件行（M4-4 行为上报离线队列，表名/字段=主会话冻结口径，禁私自扩列）：
 *
 * ```
 * pending_view_events(
 *   id AUTOINCREMENT PK / assetId TEXT / kind TEXT / startedAt INTEGER epochMs /
 *   durationMs INTEGER / sessionId TEXT / createdAt INTEGER
 * )
 * ```
 *
 * 字段口径：
 * - [kind] 存 [media.qimeng.app.core.model.ViewEventKind] 的 name（open/play/dwell），只由本端写入；
 * - [durationMs]：dwell 停留毫秒（出网时按冻结口径 ms→秒 `movePointLeft(3)` 无损换算）；
 *   open/play **恒 0**（协议不带 seconds）；
 * - [startedAt]/[createdAt]：epochMilli（出网时 startedAt 转 UTC OffsetDateTime——客户端本地
 *   时区只用于展示，打点存绝对时刻，见 SdkDetailMappers 同口径注释）；
 * - 无「重试次数」列：毒丸连败计数存活在 [ViewEventQueue] 进程内存（at-most-once 口径的
 *   一部分——进程重启清零等于给毒丸重置机会，宁可少计不虚增，见其类注释）。
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
) {
    companion object {
        /** 表名（冻结口径单一来源：DAO @Query 与本实体共用） */
        const val TABLE_NAME = "pending_view_events"
    }
}
