package media.qimeng.app.core.data.device

import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import media.qimeng.sdk.models.AiClient
import media.qimeng.sdk.models.AiClientScope

/**
 * 本机设备令牌的复用匹配（幂等 create/rotate 路径的纯逻辑）：
 * 只复用标签/设备绑定/scope 都正确且未撤销未过期的客户端。
 */
class LocalDeviceTokenMatchingTest {

    private val now: OffsetDateTime = OffsetDateTime.parse("2026-11-08T00:00:00Z")

    private fun client(
        id: String = "client-1",
        label: String = LocalDeviceTokenMatching.labelFor("phone-main"),
        deviceIds: List<String> = listOf("phone-main"),
        scopes: List<AiClientScope> = listOf(AiClientScope.AiScopeDeviceApply),
        revokedAt: OffsetDateTime? = null,
        expiresAt: OffsetDateTime? = null,
    ) = AiClient(
        id = id,
        label = label,
        scopes = scopes,
        deviceIds = deviceIds,
        createdAt = now.minusDays(1),
        expiresAt = expiresAt,
        revokedAt = revokedAt,
    )

    @Test
    fun `命中同名同设备且未过期的device_apply客户端`() {
        val match = LocalDeviceTokenMatching.findReusable(
            listOf(client()),
            "phone-main",
            now,
        )
        assertEquals("client-1", match?.id)
    }

    @Test
    fun `标签设备绑定或scope不匹配时不复用`() {
        assertNull(
            LocalDeviceTokenMatching.findReusable(
                listOf(client(label = "android-local-phone-b")),
                "phone-main",
                now,
            ),
        )
        assertNull(
            LocalDeviceTokenMatching.findReusable(
                listOf(client(deviceIds = listOf("phone-b"))),
                "phone-main",
                now,
            ),
        )
        assertNull(
            LocalDeviceTokenMatching.findReusable(
                listOf(client(scopes = listOf(AiClientScope.AiScopeRead))),
                "phone-main",
                now,
            ),
        )
    }

    @Test
    fun `已撤销或已过期的客户端不复用`() {
        assertNull(
            LocalDeviceTokenMatching.findReusable(
                listOf(client(revokedAt = now.minusMinutes(1))),
                "phone-main",
                now,
            ),
        )
        assertNull(
            LocalDeviceTokenMatching.findReusable(
                listOf(client(expiresAt = now)),
                "phone-main",
                now,
            ),
        )
        // 永不过期（expiresAt=null）仍可复用。
        assertEquals(
            "client-1",
            LocalDeviceTokenMatching.findReusable(
                listOf(client(expiresAt = null)),
                "phone-main",
                now,
            )?.id,
        )
    }

    @Test
    fun `多个可复用客户端时取服务端返回顺序中的最新一条`() {
        val match = LocalDeviceTokenMatching.findReusable(
            listOf(client(id = "newest"), client(id = "older")),
            "phone-main",
            now,
        )
        assertEquals("newest", match?.id)
    }

    @Test
    fun `标签前缀不超过服务端120字符上限`() {
        val longest = "a".repeat(DeviceApplyPolicy.MAX_TARGET_DEVICE_LENGTH)
        assertEquals(14 + longest.length, LocalDeviceTokenMatching.labelFor(longest).length)
    }
}
