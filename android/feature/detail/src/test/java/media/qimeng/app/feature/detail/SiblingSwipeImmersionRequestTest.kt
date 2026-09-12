package media.qimeng.app.feature.detail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滑切沉浸交接单语义单测（任务W W7）：锁定 request/consume 一次性消费口径——
 * 未置位不命中、置位恰被消费一次、消费后不残留（防孤儿置位把后续正常入口
 * （网格进详情=排版态）误染成沉浸）。裸 object 无 Android/Compose 依赖，
 * JVM 单测直调（先例 SiblingSwipePolicyTest）。
 *
 * 任务S S2（2026-09-13）扩展 handoff-ack 口径：consume 命中置「交接在途」标记，
 * 旧屏 onDispose 经 consumeHandoffAndClear 查询命中一次后读后即清（防标记残留
 * 误跳 show 导致列表页丢栏）。
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

    // ===== 任务S S2 handoff-ack（W7 第二百一十笔白条根修）：以下锁交接在途标记口径 =====

    @Test
    fun `consume命中置交接在途 - 旧屏onDispose查询首次命中二次不命中读后即清`() {
        SiblingSwipeImmersionRequest.consume() // 防御：清掉同进程前序用例可能残留的标志
        SiblingSwipeImmersionRequest.request()
        assertTrue(SiblingSwipeImmersionRequest.consume()) // 新屏组合期命中 → 置交接在途
        assertTrue(SiblingSwipeImmersionRequest.consumeHandoffAndClear()) // 旧屏 onDispose 命中并清零
        assertFalse(SiblingSwipeImmersionRequest.consumeHandoffAndClear()) // 读后即清：二次查询不得命中
    }

    @Test
    fun `未置位消费不置交接在途 - 正常入口离场onDispose照常恢复show`() {
        SiblingSwipeImmersionRequest.consume() // 防御：清掉同进程前序用例可能残留的标志
        assertFalse(SiblingSwipeImmersionRequest.consume())
        // 未命中 consume 不产生交接：详情→作者页 / pop 回列表等正常离场必须走 show() 恢复
        assertFalse(SiblingSwipeImmersionRequest.consumeHandoffAndClear())
    }

    @Test
    fun `交接在途清零后不残留 - 后续正常离场onDispose不得再误命中`() {
        SiblingSwipeImmersionRequest.consume() // 防御：清掉同进程前序用例可能残留的标志
        // 模拟完整滑切交接：源屏置位 → 新屏组合期命中（置标记）→ 旧屏 onDispose 查询命中并清零
        SiblingSwipeImmersionRequest.request()
        assertTrue(SiblingSwipeImmersionRequest.consume())
        assertTrue(SiblingSwipeImmersionRequest.consumeHandoffAndClear())
        // 标记已清零：后续新离场（详情→作者页→返回 / pop 回列表）查询不得再命中，
        // 否则误跳 show() 导致列表页丢系统栏
        assertFalse(SiblingSwipeImmersionRequest.consumeHandoffAndClear())
    }
}
