package media.qimeng.app.feature.detail.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缩放沉浸阈值判定单测（2026-09-13 缩放沉浸例外增补，冻结件三件套之一，先例=W2
 * ZoomResizePolicyTest）：锁定 [isZoomImmersive] 口径——normalizedScale 严格大于
 * [ZoomImageView.ZOOM_IMMERSIVE_THRESHOLD]（1.05f）即放大态。类内三处既有 1.05f 字面量
 * 谓词（onScroll 未放大态分支 / onFling 切件禁用 / UP 慢拖切件门控）已收口到此单源；
 * 阈值常量值另用字面量断言锁定，防静默改口径（变更必须显式过本文件）。
 */
class ZoomImmersionPolicyTest {

    @Test
    fun `基态与缩下限 - 不触发沉浸`() {
        assertFalse(isZoomImmersive(1f))
        assertFalse(isZoomImmersive(0.5f))
    }

    @Test
    fun `阈值 1_05 边界两侧 - 严格大于才触发`() {
        // 恰在阈值上=未放大（严格大于语义，与旧字面量 !(scale <= 1.05f) 逐位等价）
        assertFalse(isZoomImmersive(1.05f))
        assertTrue(isZoomImmersive(1.05f + 0.001f))
    }

    @Test
    fun `阈值常量锁定 1_05f - 防静默改口径`() {
        assertEquals(1.05f, ZoomImageView.ZOOM_IMMERSIVE_THRESHOLD, 0f)
    }

    @Test
    fun `放大态与缩上限 - 触发沉浸`() {
        assertTrue(isZoomImmersive(1.8f)) // 双击放大档
        assertTrue(isZoomImmersive(5f)) // MAX_SCALE 上限
    }
}
