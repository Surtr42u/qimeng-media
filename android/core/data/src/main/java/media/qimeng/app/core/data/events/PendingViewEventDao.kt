package media.qimeng.app.core.data.events

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

/**
 * 待补传浏览事件 DAO（任务L L5「本地优先」口径）。
 *
 * 与 M4-4 版的关键差异：**取件不再即删**——行留在库里，发送拿到 2xx 确认后才
 * [deleteByIds]（发送成功再删，拍板 #7；clientEventId 服务端幂等保证删前重发不双计）。
 * 退避与终局都写在行上（[reschedule]/[markTerminal]），跨进程重启存活。
 */
@Dao
interface PendingViewEventDao {

    /** 追加一行，返回自增 id */
    @Insert
    suspend fun insert(entity: PendingViewEventEntity): Long

    /**
     * FIFO 淘汰超出上限的最旧行（id 越大越新）。为什么用 NOT IN 子查询而不是先 SELECT 后
     * DELETE：淘汰必须与 insert 同事务原子完成（「入队+环形上限淘汰同一事务」冻结口径），
     * 单条语句免二次往返。注意淘汰**不分终局与否**：终局行也占环形上限额度——
     * 取舍是本地存储有界优先（终局行可经导出抢救，无限堆积则伤存储），见 ViewEventQueue 注释。
     */
    @Query(
        "DELETE FROM ${PendingViewEventEntity.TABLE_NAME} WHERE id NOT IN " +
            "(SELECT id FROM ${PendingViewEventEntity.TABLE_NAME} ORDER BY id DESC LIMIT :limit)",
    )
    suspend fun evictBeyondLimit(limit: Int)

    /** 入队 + 环形上限淘汰，同一事务（先 count 判满再淘汰，未达上限免整表排序扫描） */
    @Transaction
    suspend fun enqueueWithinLimit(entity: PendingViewEventEntity, limit: Int) {
        insert(entity)
        if (count() > limit) evictBeyondLimit(limit)
    }

    /**
     * 取件（只读）：到期、未终局的行按 id 升序（FIFO）取一批。**不删除**——
     * 删除只发生在发送拿到 2xx 之后（[deleteByIds]），进程死亡最多重复发送
     * （服务端 clientEventId 幂等吸收），绝不丢行。
     */
    @Query(
        "SELECT * FROM ${PendingViewEventEntity.TABLE_NAME} " +
            "WHERE terminal = 0 AND nextAttemptAt <= :now ORDER BY id ASC LIMIT :limit",
    )
    suspend fun selectDue(now: Long, limit: Int): List<PendingViewEventEntity>

    /** 发送确认（2xx）后删行——「发送成功再删」的唯一删除入口 */
    @Query("DELETE FROM ${PendingViewEventEntity.TABLE_NAME} WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** 可重试失败：写入退避到期时刻（nextAttemptAt 之前 drain 不再取本行） */
    @Query(
        "UPDATE ${PendingViewEventEntity.TABLE_NAME} SET nextAttemptAt = :nextAttemptAt " +
            "WHERE id = :id",
    )
    suspend fun reschedule(id: Long, nextAttemptAt: Long)

    /** 终局失败（4xx）：标记后不再重试，行保留供导出 */
    @Query(
        "UPDATE ${PendingViewEventEntity.TABLE_NAME} SET terminal = 1 WHERE id = :id",
    )
    suspend fun markTerminal(id: Long)

    /** 1→2 迁移存量行的幂等键懒回填（drain 发送前发现空值时调用一次，此后不复变） */
    @Query(
        "UPDATE ${PendingViewEventEntity.TABLE_NAME} SET clientEventId = :clientEventId " +
            "WHERE id = :id",
    )
    suspend fun updateClientEventId(id: Long, clientEventId: String)

    /** 全量行（导出未上传 JSON 用：可重试行 + 终局行都算「未上传」） */
    @Query("SELECT * FROM ${PendingViewEventEntity.TABLE_NAME} ORDER BY id ASC")
    suspend fun listAll(): List<PendingViewEventEntity>

    /** 待上传存量（未终局行；终局行不算「待上传」——它们已放弃自动补传） */
    @Query("SELECT COUNT(*) FROM ${PendingViewEventEntity.TABLE_NAME} WHERE terminal = 0")
    suspend fun countPending(): Int

    /** 全量行数（环形上限判满用，含终局行——终局行占额度） */
    @Query("SELECT COUNT(*) FROM ${PendingViewEventEntity.TABLE_NAME}")
    suspend fun count(): Int
}
