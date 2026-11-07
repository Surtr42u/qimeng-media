package media.qimeng.app.core.data.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceApplyAuthorizationTest {

    @Test
    fun `合法标签与令牌才就绪`() {
        assertEquals(
            DeviceApplyAuthorization.AuthorizationState.Ready,
            DeviceApplyAuthorization.check("phone-main", "device-token"),
        )
        // trim 后同一口径（UI 保存与 Worker 执行共用）。
        assertEquals(
            DeviceApplyAuthorization.AuthorizationState.Ready,
            DeviceApplyAuthorization.check(" phone-main ", " device-token "),
        )
    }

    @Test
    fun `无设备令牌时明确说明会话 token 不能用于设备级授权`() {
        val state = DeviceApplyAuthorization.check("phone-main", "")
        assertTrue(state is DeviceApplyAuthorization.AuthorizationState.Missing)
        val message = (state as DeviceApplyAuthorization.AuthorizationState.Missing).message
        assertTrue(message.contains("设备 AI 令牌"))
        assertTrue(message.contains("登录会话 token 不能用于设备级授权"))
        assertTrue(message.contains("ai-client create"))
    }

    @Test
    fun `缺少或非法设备标签单独提示`() {
        val missingTarget = DeviceApplyAuthorization.check("", "device-token")
        assertTrue(missingTarget is DeviceApplyAuthorization.AuthorizationState.Missing)
        assertTrue(
            (missingTarget as DeviceApplyAuthorization.AuthorizationState.Missing).message
                .contains("设备标签"),
        )
        val invalid = DeviceApplyAuthorization.check("手机", "device-token")
        assertTrue(invalid is DeviceApplyAuthorization.AuthorizationState.Missing)
    }
}
