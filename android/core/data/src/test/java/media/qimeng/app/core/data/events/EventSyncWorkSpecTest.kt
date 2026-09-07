package media.qimeng.app.core.data.events

import androidx.work.ListenableWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行为补传工作契约锁定（M4-4）：unique 名与上传链隔离 / 周期间隔=系统 15min 下限 /
 * worker 终局恒 success（队列自持久化，恢复语义由三通道覆盖，不叠 WorkManager retry）。
 */
class EventSyncWorkSpecTest {

    @Test
    fun `unique名 - 与上传链 qm-upload-serial-queue 隔离`() {
        val others = setOf("qm-upload-serial-queue")
        assertTrue(EventSyncWorkSpec.UNIQUE_SYNC_NOW_NAME !in others)
        assertTrue(EventSyncWorkSpec.UNIQUE_SYNC_PERIODIC_NAME !in others)
        assertTrue(EventSyncWorkSpec.UNIQUE_SYNC_NOW_NAME != EventSyncWorkSpec.UNIQUE_SYNC_PERIODIC_NAME)
    }

    @Test
    fun `周期兜底间隔 - 等于 WorkManager 系统下限（15min 钳制）`() {
        assertEquals(androidx.work.PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS, EventSyncWorkSpec.PERIODIC_INTERVAL_MILLIS)
    }

    @Test
    fun `drain摘要到终局 - 恒 success（含全失败与有积压的情形）`() {
        listOf(
            ViewEventQueue.DrainSummary(sent = 0, dropped = 0, keptForRetry = 0),
            ViewEventQueue.DrainSummary(sent = 3, dropped = 1, keptForRetry = 0),
            ViewEventQueue.DrainSummary(sent = 0, dropped = 0, keptForRetry = 7),
        ).forEach { summary ->
            // Result.Success 无 equals（对象恒等），按类型断言（UploadWorkSpecTest 同款）
            assertTrue(
                EventSyncWorkSpec.drainSummaryToResult(summary) is ListenableWorker.Result.Success,
            )
        }
    }
}
