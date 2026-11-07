package media.qimeng.app.core.data.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 领取前本地前置校验单测（2026-11-08 task-21 P2-1）：
 * 登录会话/维护根/所有文件访问任一缺失都必须阻塞，且发生在 claim 之前（Runner 只在
 * [DeviceApplyPreconditions.Ready] 时发起列表/领取）。
 */
class DeviceApplyPreconditionsTest {

    @Test
    fun `登录会话维护根与权限齐备才就绪`() {
        assertEquals(
            PreconditionState.Ready,
            DeviceApplyPreconditions.evaluate(
                sessionLoggedIn = true,
                maintenanceRoot = "/storage/emulated/0/qimeng",
                hasAllFilesAccess = true,
            ),
        )
    }

    @Test
    fun `未登录优先提示登录且不消耗任务`() {
        val blocked = DeviceApplyPreconditions.evaluate(
            sessionLoggedIn = false,
            maintenanceRoot = "/storage/emulated/0/qimeng",
            hasAllFilesAccess = true,
        )
        assertTrue(blocked is PreconditionState.Blocked)
        val message = (blocked as PreconditionState.Blocked).message
        assertTrue(message.contains("登录会话"))
        assertTrue(message.contains("待领取"))
        // 登录缺失优先于目录/权限提示，用户先修复最前置条件。
        assertFalseBlocked(
            DeviceApplyPreconditions.evaluate(
                sessionLoggedIn = false,
                maintenanceRoot = null,
                hasAllFilesAccess = false,
            ),
            "维护目录",
        )
    }

    @Test
    fun `维护根未持久化时阻塞并提示授权入口`() {
        val blocked = DeviceApplyPreconditions.evaluate(
            sessionLoggedIn = true,
            maintenanceRoot = null,
            hasAllFilesAccess = true,
        )
        assertTrue(blocked is PreconditionState.Blocked)
        assertTrue((blocked as PreconditionState.Blocked).message.contains("维护目录"))
    }

    @Test
    fun `缺少所有文件访问时阻塞并提示系统授权`() {
        val blocked = DeviceApplyPreconditions.evaluate(
            sessionLoggedIn = true,
            maintenanceRoot = "/storage/emulated/0/qimeng",
            hasAllFilesAccess = false,
        )
        assertTrue(blocked is PreconditionState.Blocked)
        assertTrue((blocked as PreconditionState.Blocked).message.contains("所有文件访问"))
    }

    private fun assertFalseBlocked(state: PreconditionState, forbidden: String) {
        assertTrue(state is PreconditionState.Blocked)
        val message = (state as PreconditionState.Blocked).message
        assertTrue("不应包含 $forbidden: $message", !message.contains(forbidden))
    }
}
