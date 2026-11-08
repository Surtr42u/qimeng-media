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
    fun `领取服务端地址为空时沿用当前登录服务端`() {
        assertEquals(
            "http://127.0.0.1:18430",
            DeviceApplyPolicy.resolveDeviceServiceUrl("http://127.0.0.1:18430", ""),
        )
        assertEquals(
            "http://192.0.2.10:8420",
            DeviceApplyPolicy.resolveDeviceServiceUrl("http://192.0.2.10:8420/", "   "),
        )
        assertNull(DeviceApplyPolicy.resolveDeviceServiceUrl("", ""))
        assertNull(DeviceApplyPolicy.resolveDeviceServiceUrl("  ", ""))
    }

    @Test
    fun `领取服务端地址覆盖补齐协议并规范化尾斜杠`() {
        assertEquals(
            "http://192.0.2.10:8420",
            DeviceApplyPolicy.resolveDeviceServiceUrl(
                "http://127.0.0.1:18430",
                " 192.0.2.10:8420/ ",
            ),
        )
        assertEquals(
            "https://nas.example:8443",
            DeviceApplyPolicy.resolveDeviceServiceUrl(
                "http://127.0.0.1:18430",
                "https://nas.example:8443/",
            ),
        )
    }

    @Test
    fun `领取服务端地址非法或带子路径时返回 null`() {
        val current = "http://127.0.0.1:18430"
        assertNull(DeviceApplyPolicy.resolveDeviceServiceUrl(current, "ftp://192.0.2.10:8420"))
        assertNull(DeviceApplyPolicy.resolveDeviceServiceUrl(current, "http://192.0.2.10:8420/api"))
        assertNull(DeviceApplyPolicy.resolveDeviceServiceUrl(current, "http://"))
        assertNull(DeviceApplyPolicy.resolveDeviceServiceUrl(current, "nas example"))
    }

    @Test
    fun `设置页空地址通过其余按 http(s) 校验`() {
        assertTrue(DeviceApplyPolicy.isValidDeviceServiceUrl(""))
        assertTrue(DeviceApplyPolicy.isValidDeviceServiceUrl("   "))
        assertTrue(DeviceApplyPolicy.isValidDeviceServiceUrl("192.0.2.10:8420"))
        assertTrue(DeviceApplyPolicy.isValidDeviceServiceUrl("https://nas.example:8443"))
        assertFalse(DeviceApplyPolicy.isValidDeviceServiceUrl("ftp://192.0.2.10:8420"))
        assertFalse(DeviceApplyPolicy.isValidDeviceServiceUrl("http://192.0.2.10:8420/api"))
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
    fun `可恢复摘要同时接受 pending 与未过期 claimed`() {
        val now = 1_000L
        val items = listOf(
            summary(id = "pending", status = DeviceTaskStatus.PENDING).copy(expiresAtMillis = now + 10),
            summary(id = "claimed", status = DeviceTaskStatus.CLAIMED).copy(expiresAtMillis = now + 10),
            // 已到期（边界相等也算过期）不领取。
            summary(id = "expired-claimed", status = DeviceTaskStatus.CLAIMED).copy(expiresAtMillis = now),
            summary(id = "expired-pending", status = DeviceTaskStatus.PENDING).copy(expiresAtMillis = now - 1),
            // 终态一律跳过，避免重复应用。
            summary(id = "applied", status = DeviceTaskStatus.APPLIED).copy(expiresAtMillis = now + 10),
            summary(id = "failed", status = DeviceTaskStatus.FAILED).copy(expiresAtMillis = now + 10),
            summary(id = "other", target = "phone-b", status = DeviceTaskStatus.CLAIMED)
                .copy(expiresAtMillis = now + 10),
        )
        assertEquals(
            listOf("pending", "claimed"),
            DeviceApplyPolicy.resumeOrClaimForDevice(items, "phone-main", now).map { it.id },
        )
        // 保持服务端返回顺序（createdAt 倒序），不重排。
        assertTrue(
            DeviceApplyPolicy.resumeOrClaimForDevice(items.asReversed(), "phone-main", now)
                .map { it.id } == listOf("claimed", "pending"),
        )
    }

    @Test
    fun `默认到期时间视为不过期兼容旧构造点`() {
        val item = summary(id = "legacy", status = DeviceTaskStatus.CLAIMED)
        assertEquals(Long.MAX_VALUE, item.expiresAtMillis)
        assertEquals(
            listOf("legacy"),
            DeviceApplyPolicy.resumeOrClaimForDevice(listOf(item), "phone-main", Long.MAX_VALUE - 1).map { it.id },
        )
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
