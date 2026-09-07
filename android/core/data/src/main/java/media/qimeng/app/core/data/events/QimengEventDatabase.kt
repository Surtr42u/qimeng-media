package media.qimeng.app.core.data.events

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * 行为上报离线队列专用库（M4-4）。为什么与未来的其他本地表分库：本库只装可丢弃的
 * 打点积压（宁少计不虚增），与客户端偏好（DataStore）语义域不同；独立库=独立文件，
 * 清队列/排查不牵连其他持久化。schema 随版本导出入库（core/data/schemas/，KSP arg 通道）。
 */
@Database(
    entities = [PendingViewEventEntity::class],
    version = QimengEventDatabase.VERSION,
    exportSchema = true,
)
abstract class QimengEventDatabase : RoomDatabase() {

    abstract fun pendingViewEventDao(): PendingViewEventDao

    companion object {
        /** schema 版本（首版=1；只加不改不删，演进走迁移） */
        const val VERSION = 1

        /** 库文件名（与上传链/偏好 DataStore 物理隔离） */
        const val NAME = "qimeng_events.db"
    }
}
