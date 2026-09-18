package media.qimeng.app.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 局域网权限请求判定（任务P P4b）：版本门与授权态的组合口径锁定。 */
class LocalNetworkAccessPolicyTest {

    @Test
    fun `Android 17 及以上且未授权_需要请求`() {
        assertTrue(shouldRequestLocalNetworkPermission(sdkInt = 37, granted = false))
        assertTrue(shouldRequestLocalNetworkPermission(sdkInt = 38, granted = false))
    }

    @Test
    fun `Android 17 以下_永不请求`() {
        assertFalse(shouldRequestLocalNetworkPermission(sdkInt = 26, granted = false))
        assertFalse(shouldRequestLocalNetworkPermission(sdkInt = 36, granted = false))
    }

    @Test
    fun `已授权_永不重复请求`() {
        assertFalse(shouldRequestLocalNetworkPermission(sdkInt = 37, granted = true))
        assertFalse(shouldRequestLocalNetworkPermission(sdkInt = 40, granted = true))
    }
}
