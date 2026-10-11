package media.qimeng.app.core.data.events

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 打点队列库的迁移链（任务L L5）。
 *
 * MIGRATION_1_2（本地优先改版）：pending_view_events 增三列——
 * - clientEventId：协议幂等键；存量行 DEFAULT '' 由 drain 懒回填（不回填伪造历史 id）；
 * - terminal：终局标记；存量行 DEFAULT 0 = 继续按可重试处理；
 * - nextAttemptAt：退避到期时刻；存量行 DEFAULT 0 = 立即可发。
 *
 * 为什么走正式迁移而不是破坏性重建：旧口径下队列几乎恒空（先删后发），但改版
 * 起队列是用户数据的唯一暂存（发送成功才删），炸库=丢用户浏览记录，与「本地优先
 * 不丢」的立意直接冲突——三列都是 ADD COLUMN DEFAULT，老库无损升级。
 *
 * MIGRATION_2_3（ADR-0037 跨库浏览事件兜底重映射）：pending_view_events 增两列——
 * - fileName：事件产生时的展示文件名（含扩展名），存量行 DEFAULT '' = 无身份不参与重映射；
 * - relPath：库内相对路径（消歧用），存量行 DEFAULT '' 同上。
 * 为什么同样走正式迁移：两条都是 ADD COLUMN DEFAULT，老库无损升级；队列里的行是
 * 用户数据唯一暂存（发送成功才删），破坏性重建=丢未上传的浏览记录（与 1→2 同立意）。
 */
object EventDbMigrations {

    /** 1→2：本地优先改版三列（见类注释） */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE ${PendingViewEventEntity.TABLE_NAME} " +
                    "ADD COLUMN clientEventId TEXT NOT NULL DEFAULT ''",
            )
            db.execSQL(
                "ALTER TABLE ${PendingViewEventEntity.TABLE_NAME} " +
                    "ADD COLUMN terminal INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE ${PendingViewEventEntity.TABLE_NAME} " +
                    "ADD COLUMN nextAttemptAt INTEGER NOT NULL DEFAULT 0",
            )
        }
    }

    /** 2→3：ADR-0037 文件身份两列（见类注释） */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE ${PendingViewEventEntity.TABLE_NAME} " +
                    "ADD COLUMN fileName TEXT NOT NULL DEFAULT ''",
            )
            db.execSQL(
                "ALTER TABLE ${PendingViewEventEntity.TABLE_NAME} " +
                    "ADD COLUMN relPath TEXT NOT NULL DEFAULT ''",
            )
        }
    }

    /** 全部历史迁移（Room 建库时按版本递补；新增版本在此追加） */
    val ALL = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
