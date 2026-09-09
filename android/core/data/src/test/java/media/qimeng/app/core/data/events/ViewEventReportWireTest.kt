package media.qimeng.app.core.data.events

import media.qimeng.app.core.model.ViewEventKind
import media.qimeng.app.core.data.repository.SdkDetailMappers
import media.qimeng.sdk.infrastructure.Serializer
import media.qimeng.sdk.models.ProgressUpdate
import media.qimeng.sdk.models.ViewEventReport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打点/进度上报体的 wire 序列化验收（协议批 2026-09-09，#24/#26 根修）：
 * openapi 小数字段 format:double 后 SDK 走原生 Double，moshi 序列化为
 * JSON **数字**——根修前 BigDecimalAdapter 产出带引号字符串（`"18.0"`），
 * 服务端 gen 解码即 400，dwell/progress 自 M4-3 起从未落库。本测试锁定
 * 「不带引号」这一 wire 契约，回归即说明协议面又漂回字符串形态。
 */
class ViewEventReportWireTest {

    @Test
    fun `dwell seconds 序列化为 JSON 数字不带引号`() {
        val report = SdkDetailMappers.toViewEventReport(
            "11111111-1111-1111-1111-111111111111",
            ViewEventKind.DWELL,
            startedAtMs = 1_000L,
            sessionId = "wire-1",
            clientEventId = "00000000-0000-0000-0000-00000000cba1",
            dwellSeconds = 18L,
        )
        val json = Serializer.moshi.adapter(ViewEventReport::class.java).toJson(report)
        assertTrue("seconds 应为 JSON 数字，实际: $json", json.contains("\"seconds\":18.0"))
        assertFalse("seconds 不得为带引号字符串，实际: $json", json.contains("\"seconds\":\""))
    }

    @Test
    fun `clientEventId 幂等键随 wire 透传（任务L L5）`() {
        val report = SdkDetailMappers.toViewEventReport(
            "11111111-1111-1111-1111-111111111111",
            ViewEventKind.OPEN,
            startedAtMs = 1_000L,
            sessionId = "wire-1",
            clientEventId = "00000000-0000-0000-0000-00000000cba1",
            dwellSeconds = null,
        )
        val json = Serializer.moshi.adapter(ViewEventReport::class.java).toJson(report)
        assertTrue("clientEventId 应在请求体，实际: $json", json.contains("\"clientEventId\":\"00000000-0000-0000-0000-00000000cba1\""))
    }

    @Test
    fun `progress positionSeconds 序列化为 JSON 数字不带引号`() {
        val update = SdkDetailMappers.toProgressUpdate(125.75)
        val json = Serializer.moshi.adapter(ProgressUpdate::class.java).toJson(update)
        assertTrue("positionSeconds 应为 JSON 数字，实际: $json", json.contains("\"positionSeconds\":125.75"))
        assertFalse("positionSeconds 不得为带引号字符串，实际: $json", json.contains("\"positionSeconds\":\""))
    }
}
