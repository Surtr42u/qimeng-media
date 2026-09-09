package media.qimeng.app.core.data.events

import java.time.ZoneOffset
import java.util.UUID
import media.qimeng.app.core.model.ViewEventKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 队列行 → SDK 上报体映射锁定（M4-4 冻结口径）：
 * startedAt epochMs→UTC OffsetDateTime 无损 / dwell 秒数 ms→movePointLeft(3) 无损 /
 * open/play durationMs 恒 0 且不带 seconds。
 */
class PendingViewEventMappersTest {

    private fun entity(
        kind: String,
        durationMs: Long,
        startedAt: Long = STARTED_AT_MS,
    ) = PendingViewEventEntity(
        id = 7,
        assetId = ASSET_ID,
        kind = kind,
        startedAt = startedAt,
        durationMs = durationMs,
        sessionId = SESSION_ID,
        createdAt = 1L,
    )

    @Test
    fun `dwell - startedAt epochMs 转 UTC OffsetDateTime 无损`() {
        val report = entity(kind = ViewEventKind.DWELL.name, durationMs = 5000L).toSdkReport()
        // 无损：epochMilli 往返一致（时区差造成的秒级偏移都会在此暴露）
        assertEquals(STARTED_AT_MS, report.startedAt.toInstant().toEpochMilli())
        // 统一 UTC（客户端本地时区只用于展示，打点存绝对时刻）
        assertEquals(ZoneOffset.UTC, report.startedAt.offset)
    }

    @Test
    fun `dwell - durationMs 毫秒转秒 movePointLeft(3) 无损`() {
        // 7350ms → 7.350：毫秒精度不截断（冻结口径禁用整秒截断换算）
        val report = entity(kind = ViewEventKind.DWELL.name, durationMs = 7350L).toSdkReport()
        assertEquals(7.350, report.seconds!!, 1e-9)
    }

    @Test
    fun `dwell - 整秒值换算不变形`() {
        val report = entity(kind = ViewEventKind.DWELL.name, durationMs = 5000L).toSdkReport()
        assertEquals(5.0, report.seconds!!, 1e-9)
    }

    @Test
    fun `open - durationMs 恒0且不带 seconds`() {
        val report = entity(kind = ViewEventKind.OPEN.name, durationMs = 0L).toSdkReport()
        assertNull(report.seconds)
        assertEquals(STARTED_AT_MS, report.startedAt.toInstant().toEpochMilli())
    }

    @Test
    fun `play - durationMs 恒0且不带 seconds`() {
        val report = entity(kind = ViewEventKind.PLAY.name, durationMs = 0L).toSdkReport()
        assertNull(report.seconds)
    }

    @Test
    fun `标识字段 - assetId 解析 UUID 且 sessionId 透传`() {
        val report = entity(kind = ViewEventKind.OPEN.name, durationMs = 0L).toSdkReport()
        assertEquals(UUID.fromString(ASSET_ID), report.assetId)
        assertEquals(SESSION_ID, report.sessionId)
    }

    private companion object {
        const val ASSET_ID = "00000000-0000-0000-0000-000000000001"
        const val SESSION_ID = "session-d5-test"
        const val STARTED_AT_MS = 1_700_000_000_123L
    }
}
