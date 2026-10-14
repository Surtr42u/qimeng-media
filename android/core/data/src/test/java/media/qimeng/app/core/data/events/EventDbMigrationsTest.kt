package media.qimeng.app.core.data.events

import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

/** Migration 的 SQL 合约测试（JVM，无需启动 Android Runtime）。 */
class EventDbMigrationsTest {

    @Test
    fun `2到3迁移只为待发事件增加带安全默认值的身份列`() {
        val statements = mutableListOf<String>()
        EventDbMigrations.MIGRATION_2_3.migrate(recordingDatabase(statements))

        assertEquals(
            listOf(
                "ALTER TABLE pending_view_events ADD COLUMN fileName TEXT NOT NULL DEFAULT ''",
                "ALTER TABLE pending_view_events ADD COLUMN relPath TEXT NOT NULL DEFAULT ''",
            ),
            statements,
        )
    }

    @Test
    fun `1到2迁移只增加兼容旧队列的三列`() {
        val statements = mutableListOf<String>()
        EventDbMigrations.MIGRATION_1_2.migrate(recordingDatabase(statements))

        assertEquals(
            listOf(
                "ALTER TABLE pending_view_events ADD COLUMN clientEventId TEXT NOT NULL DEFAULT ''",
                "ALTER TABLE pending_view_events ADD COLUMN terminal INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE pending_view_events ADD COLUMN nextAttemptAt INTEGER NOT NULL DEFAULT 0",
            ),
            statements,
        )
    }

    @Test
    fun `迁移链按版本连续且没有跳过历史版本`() {
        assertEquals(listOf(1 to 2, 2 to 3), EventDbMigrations.ALL.map { it.startVersion to it.endVersion })
    }

    private fun recordingDatabase(statements: MutableList<String>): SupportSQLiteDatabase =
        Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            if (method.name == "execSQL") {
                statements += args?.get(0) as String
            }
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
        } as SupportSQLiteDatabase
}
