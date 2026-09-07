package media.qimeng.app.core.data.events

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

/**
 * 待补传浏览事件 DAO（M4-4）。两个 @Transaction 组合方法（入队+淘汰、取件+删除）把
 * 「写成功即入队可补传」与「先删后发」两条冻结口径锁在同一事务里，SQL 细节不出本接口。
 */
@Dao
interface PendingViewEventDao {

    /** 追加一行，返回自增 id（重试回队也走这里：copy(id=0) 即重取新 id 排到队尾） */
    @Insert
    suspend fun insert(entity: PendingViewEventEntity): Long

    /**
     * FIFO 淘汰超出上限的最旧行（id 越大越新）。为什么用 NOT IN 子查询而不是先 SELECT 后
     * DELETE：淘汰必须与 insert 同事务原子完成（冻结口径「入队+环形上限淘汰同一事务」），
     * 单条语句免二次往返。被淘汰=少计可接受（宁少计不虚增）。
     */
    @Query(
        "DELETE FROM ${PendingViewEventEntity.TABLE_NAME} WHERE id NOT IN " +
            "(SELECT id FROM ${PendingViewEventEntity.TABLE_NAME} ORDER BY id DESC LIMIT :limit)",
    )
    suspend fun evictBeyondLimit(limit: Int)

    /** 入队 + 环形上限淘汰，同一事务（冻结口径） */
    @Transaction
    suspend fun enqueueWithinLimit(entity: PendingViewEventEntity, limit: Int) {
        insert(entity)
        evictBeyondLimit(limit)
    }

    @Query("SELECT * FROM ${PendingViewEventEntity.TABLE_NAME} ORDER BY id ASC LIMIT :limit")
    suspend fun selectOldest(limit: Int): List<PendingViewEventEntity>

    @Query("DELETE FROM ${PendingViewEventEntity.TABLE_NAME} WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /**
     * 取件：事务内「查出最旧一批 + 立即删除」——先删后发（at-most-once 冻结口径：
     * 宁少计不虚增，进程死亡最多丢已取件未发出的行，绝不重复计）。
     */
    @Transaction
    suspend fun takeOldest(limit: Int): List<PendingViewEventEntity> {
        val rows = selectOldest(limit)
        if (rows.isNotEmpty()) deleteByIds(rows.map { it.id })
        return rows
    }

    /** 队列存量（worker 日志/诊断用） */
    @Query("SELECT COUNT(*) FROM ${PendingViewEventEntity.TABLE_NAME}")
    suspend fun count(): Int
}
