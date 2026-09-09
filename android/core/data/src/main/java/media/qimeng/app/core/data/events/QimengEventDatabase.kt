package media.qimeng.app.core.data.events

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * 行为打点离线队列专用库。为什么与未来的其他本地表分库：本库装的是浏览打点积压
 * （任务L L5 起是用户数据的本地暂存，发送成功才删），与客户端偏好（DataStore）语义域
 * 不同；独立库=独立文件，清队列/排查不牵连其他持久化。schema 随版本导出入库
 * （core/data/schemas/，KSP arg 通道）。
 */
@Database(
    entities = [PendingViewEventEntity::class],
    version = QimengEventDatabase.VERSION,
    exportSchema = true,
)
abstract class QimengEventDatabase : RoomDatabase() {

    abstract fun pendingViewEventDao(): PendingViewEventDao

    companion object {
        /** schema 版本（2 = 任务L L5 本地优先三列；演进只加迁移，见 EventDbMigrations） */
        const val VERSION = 2

        /** 库文件名（与上传链/偏好 DataStore 物理隔离） */
        const val NAME = "qimeng_events.db"
    }
}
