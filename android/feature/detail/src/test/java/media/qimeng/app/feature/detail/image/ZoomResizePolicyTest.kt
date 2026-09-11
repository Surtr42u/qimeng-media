package media.qimeng.app.feature.detail.image

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 容器尺寸变化自愈重居中判定单测（任务W W2，冻结件例外三件套之一，先例=V2
 * SiblingSwipePolicyTest）：锁定 [shouldRecenterOnResize] 口径——仅基态且非手势期
 * 才允许容器变化触发 resetZoom；用户缩放态与手势期一律不动（缩放不可被容器变化销毁）。
 */
class ZoomResizePolicyTest {

    @Test
    fun `基态非手势期 - 重居中`() {
        assertTrue(shouldRecenterOnResize(isGestureActive = false, normalizedScale = 1f))
    }

    @Test
    fun `用户放大态 - 绝不重置`() {
        assertFalse(shouldRecenterOnResize(isGestureActive = false, normalizedScale = 1.8f))
        assertFalse(shouldRecenterOnResize(isGestureActive = false, normalizedScale = 5f))
    }

    @Test
    fun `手势期 双指或拖拽进行中 - 不抢状态机`() {
        assertFalse(shouldRecenterOnResize(isGestureActive = true, normalizedScale = 1f))
        assertFalse(shouldRecenterOnResize(isGestureActive = true, normalizedScale = 1.8f))
    }
}
