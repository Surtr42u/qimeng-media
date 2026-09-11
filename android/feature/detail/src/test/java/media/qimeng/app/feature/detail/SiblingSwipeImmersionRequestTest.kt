package media.qimeng.app.feature.detail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滑切沉浸交接单语义单测（任务W W7）：锁定 request/consume 一次性消费口径——
 * 未置位不命中、置位恰被消费一次、消费后不残留（防孤儿置位把后续正常入口
 * （网格进详情=排版态）误染成沉浸）。裸 object 无 Android/Compose 依赖，
 * JVM 单测直调（先例 SiblingSwipePolicyTest）。
 */
class SiblingSwipeImmersionRequestTest {

    @Test
    fun `未置位消费不命中 - 网格正常进详情落排版态`() {
        SiblingSwipeImmersionRequest.consume() // 防御：清掉同进程前序用例可能残留的标志
        assertFalse(SiblingSwipeImmersionRequest.consume())
    }

    @Test
    fun `置位后消费命中 - 沉浸态滑切目标保持全屏`() {
        SiblingSwipeImmersionRequest.request()
        assertTrue(SiblingSwipeImmersionRequest.consume())
    }

    @Test
    fun `消费即清零 - 一次性语义不残留到下一次进入`() {
        SiblingSwipeImmersionRequest.request()
        assertTrue(SiblingSwipeImmersionRequest.consume())
        assertFalse(SiblingSwipeImmersionRequest.consume())
    }

    @Test
    fun `重复置位幂等 - 连续快滑两次同语义`() {
        SiblingSwipeImmersionRequest.request()
        SiblingSwipeImmersionRequest.request()
        assertTrue(SiblingSwipeImmersionRequest.consume())
        assertFalse(SiblingSwipeImmersionRequest.consume())
    }
}
