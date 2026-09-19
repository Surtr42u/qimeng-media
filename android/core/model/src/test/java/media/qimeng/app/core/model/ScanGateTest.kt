package media.qimeng.app.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫描充电门判定锁定（批C 任务Q C-3，PROJECT_PLAN M6 性能项）。
 * 冻结语义：仅本机模式生效；连 NAS 常规模式不受限；设置关=用户显式解除约束；充电中直接扫。
 */
class ScanGateTest {

    @Test
    fun `本机模式设置开未充电时推迟`() {
        assertTrue(ScanGate.shouldDeferScan(isCharging = false, settingOn = true, isLocalMode = true))
    }

    @Test
    fun `充电中直接扫`() {
        assertFalse(ScanGate.shouldDeferScan(isCharging = true, settingOn = true, isLocalMode = true))
    }

    @Test
    fun `非本机模式不受限`() {
        assertFalse(ScanGate.shouldDeferScan(isCharging = false, settingOn = true, isLocalMode = false))
    }

    @Test
    fun `设置关时直接扫`() {
        assertFalse(ScanGate.shouldDeferScan(isCharging = false, settingOn = false, isLocalMode = true))
    }
}
