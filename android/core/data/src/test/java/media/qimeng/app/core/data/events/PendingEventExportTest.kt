package media.qimeng.app.core.data.events

import media.qimeng.app.core.model.ViewEventKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 未上传事件导出 JSON 锁定（任务L L5）：数组元素必须是**可直接 POST /events/view**
 * 的合法请求体——协议字段名/小写枚举/UTC 时间/dwell 秒数/幂等键全部按协议口径出。
 * 手动 POST 与 App 自动补传携带同一 clientEventId，服务端幂等使两条路径结果一致
 * （验收项「导出再 POST 一致」的客户端半边由本组用例锁定）。
 */
class PendingEventExportTest {

    @Test
    fun `dwell 行导出 - 带秒数与幂等键，kind 协议小写`() {
        val row = PendingViewEventEntity(
            id = 1,
            assetId = "00000000-0000-0000-0000-000000000001",
            kind = ViewEventKind.DWELL.name,
            startedAt = 1_700_000_000_456L,
            durationMs = 7_350L,
            sessionId = "sess-1",
            createdAt = 1L,
            clientEventId = "00000000-0000-0000-0000-00000000cba1",
        )
        val json = listOf(row).toPendingExportJson()

        assertTrue(json.contains("\"kind\": \"dwell\"") || json.contains("\"kind\":\"dwell\""))
        assertTrue(json.contains("\"seconds\": 7.35") || json.contains("\"seconds\":7.35"))
        assertTrue(json.contains("\"clientEventId\": \"00000000-0000-0000-0000-00000000cba1\"") || json.contains("\"clientEventId\":\"00000000-0000-0000-0000-00000000cba1\""))
        assertTrue(json.contains("\"sessionId\": \"sess-1\"") || json.contains("\"sessionId\":\"sess-1\""))
        // startedAt = epochMs 的 UTC RFC3339 形态（2023-11-14T22:13:20.456Z）
        assertTrue("startedAt 应为 UTC ISO 时刻: $json", json.contains("2023-11-14T22:13:20.456Z"))
    }

    @Test
    fun `open 行导出 - 不带 seconds 字段`() {
        val row = PendingViewEventEntity(
            id = 1,
            assetId = "00000000-0000-0000-0000-000000000002",
            kind = ViewEventKind.OPEN.name,
            startedAt = 1_700_000_000_000L,
            durationMs = 0L,
            sessionId = "sess-2",
            createdAt = 1L,
            clientEventId = "00000000-0000-0000-0000-00000000cba2",
        )
        val json = listOf(row).toPendingExportJson()

        assertTrue(json.contains("\"kind\": \"open\"") || json.contains("\"kind\":\"open\""))
        assertTrue("open 不应带 seconds: $json", !json.contains("seconds"))
    }

    @Test
    fun `多行导出 - 数组形且保序，空队列为空数组`() {
        val rows = listOf(
            PendingViewEventEntity(
                id = 1, assetId = "a1", kind = ViewEventKind.OPEN.name, startedAt = 1L,
                durationMs = 0L, sessionId = "s", createdAt = 1L, clientEventId = "id-1",
            ),
            PendingViewEventEntity(
                id = 2, assetId = "a2", kind = ViewEventKind.PLAY.name, startedAt = 2L,
                durationMs = 0L, sessionId = "s", createdAt = 1L, clientEventId = "id-2",
            ),
        )
        val json = rows.toPendingExportJson()
        assertTrue(json.trimStart().startsWith("["))
        assertTrue(json.indexOf("id-1") < json.indexOf("id-2")) // FIFO 保序
        assertEquals("[]", emptyList<PendingViewEventEntity>().toPendingExportJson())
    }
}
