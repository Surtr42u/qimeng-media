package media.qimeng.app.core.data.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceApplyPolicyTest {

    private val sha = "a".repeat(64)

    private fun summary(
        id: String = "task-1",
        target: String = "phone-main",
        status: DeviceTaskStatus = DeviceTaskStatus.PENDING,
    ) = DeviceApplySummary(
        id = id,
        targetDeviceId = target,
        status = status,
        protocolVersion = DeviceApplyPolicy.PROTOCOL_VERSION,
        rulesFingerprint = sha,
    )

    private fun segment(
        kind: DeviceSegmentKind = DeviceSegmentKind.MASTER_TXT,
        bytes: Long = 12,
        sha256: String = sha,
    ) = DeviceApplySegment(kind = kind, bytes = bytes, sha256 = sha256)

    private fun detail(
        target: String = "phone-main",
        status: DeviceTaskStatus = DeviceTaskStatus.CLAIMED,
        protocolVersion: Int = DeviceApplyPolicy.PROTOCOL_VERSION,
        fingerprint: String = sha,
        segments: List<DeviceApplySegment> = listOf(segment()),
    ) = DeviceApplyDetail(
        id = "task-1",
        targetDeviceId = target,
        status = status,
        protocolVersion = protocolVersion,
        rulesFingerprint = fingerprint,
        segments = segments,
    )

    @Test
    fun `设备标签只接受协议字符集与长度`() {
        assertTrue(DeviceApplyPolicy.isValidTargetDeviceId("phone-main"))
        assertTrue(DeviceApplyPolicy.isValidTargetDeviceId("Phone_1.2:3"))
        assertTrue(DeviceApplyPolicy.isValidTargetDeviceId("a".repeat(100)))
        assertFalse(DeviceApplyPolicy.isValidTargetDeviceId(""))
        assertFalse(DeviceApplyPolicy.isValidTargetDeviceId(" phone"))
        assertFalse(DeviceApplyPolicy.isValidTargetDeviceId("a".repeat(101)))
        assertFalse(DeviceApplyPolicy.isValidTargetDeviceId("手机"))
        assertFalse(DeviceApplyPolicy.isValidTargetDeviceId("phone main"))
    }

    @Test
    fun `目标筛选只保留本机且 pending 的摘要`() {
        val items = listOf(
            summary(id = "a", target = "phone-main", status = DeviceTaskStatus.PENDING),
            summary(id = "b", target = "phone-b", status = DeviceTaskStatus.PENDING),
            summary(id = "c", target = "phone-main", status = DeviceTaskStatus.CLAIMED),
            summary(id = "d", target = "phone-main", status = DeviceTaskStatus.APPLIED),
        )
        assertEquals(listOf("a"), DeviceApplyPolicy.pendingForDevice(items, "phone-main").map { it.id })
        assertTrue(DeviceApplyPolicy.pendingForDevice(items, "phone-x").isEmpty())
    }

    @Test
    fun `合法领取态任务通过整包校验`() {
        assertTrue(DeviceApplyPolicy.validateTask(detail(), "phone-main").ok)
    }

    @Test
    fun `整包校验拒绝目标不符未领取协议版本指纹与段形状异常`() {
        assertFalse(DeviceApplyPolicy.validateTask(detail(target = "phone-b"), "phone-main").ok)
        assertFalse(DeviceApplyPolicy.validateTask(detail(status = DeviceTaskStatus.PENDING), "phone-main").ok)
        assertFalse(DeviceApplyPolicy.validateTask(detail(protocolVersion = 2), "phone-main").ok)
        assertFalse(DeviceApplyPolicy.validateTask(detail(fingerprint = "ABC"), "phone-main").ok)
        assertFalse(DeviceApplyPolicy.validateTask(detail(segments = emptyList()), "phone-main").ok)
        assertFalse(
            DeviceApplyPolicy.validateTask(
                detail(
                    segments = listOf(
                        segment(DeviceSegmentKind.MASTER_TXT),
                        segment(DeviceSegmentKind.MASTER_TXT, bytes = 2),
                    ),
                ),
                "phone-main",
            ).ok,
        )
        assertFalse(
            DeviceApplyPolicy.validateTask(
                detail(segments = listOf(segment(bytes = 0))),
                "phone-main",
            ).ok,
        )
        assertFalse(
            DeviceApplyPolicy.validateTask(
                detail(segments = listOf(segment(sha256 = "not-a-sha"))),
                "phone-main",
            ).ok,
        )
    }

    @Test
    fun `段摘要与规则指纹必须是 64 位小写十六进制`() {
        assertTrue(DeviceApplyPolicy.isValidSha256("0123456789abcdef".repeat(4)))
        assertFalse(DeviceApplyPolicy.isValidSha256("0123456789ABCDEF".repeat(4)))
        assertFalse(DeviceApplyPolicy.isValidSha256("a".repeat(63)))
        assertFalse(DeviceApplyPolicy.isValidRulesFingerprint(""))
    }

    @Test
    fun `校验失败的消息可进入回传且合法任务无消息`() {
        val invalid = DeviceApplyPolicy.validateTask(detail(protocolVersion = 9), "phone-main")
        assertFalse(invalid.ok)
        assertTrue(invalid.message!!.contains("协议版本"))
        assertNull(DeviceApplyPolicy.validateTask(detail(), "phone-main").message)
    }

    @Test
    fun `远端需要网络约束而本机回环放宽`() {
        assertTrue(DeviceApplyWorkSpec.requiresConnectedNetwork(localMode = false))
        assertFalse(DeviceApplyWorkSpec.requiresConnectedNetwork(localMode = true))
        assertTrue(DeviceApplyWorkSpec.shouldRetry(0))
        assertTrue(DeviceApplyWorkSpec.shouldRetry(DeviceApplyWorkSpec.MAX_RETRIES - 1))
        assertFalse(DeviceApplyWorkSpec.shouldRetry(DeviceApplyWorkSpec.MAX_RETRIES))
    }
}
