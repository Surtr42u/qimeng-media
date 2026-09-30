package media.qimeng.app.core.data.prefetch

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预取抽样核对门纯函数锁定（2026-09-30 缓存漂移修复）：降级阈值边界（含 20% 整除
 * 边界与空样本恒维持 SKIP）+ 抽样条数上限/去重 + 固定种子确定性。
 */
class PrefetchSampleGateTest {

    // —— shouldDowngradeToFull 边界 ——

    @Test
    fun `缺失低于阈值维持跳过`() {
        // sampled=50：缺失 9（18%）< 20% → 容忍（LRU 对最旧条目的少量修剪属正常）
        assertFalse(PrefetchSampleGate.shouldDowngradeToFull(sampled = 50, missing = 9))
    }

    @Test
    fun `缺失达到阈值降级全量`() {
        // sampled=50：缺失 10 恰为 20%（整数运算 10*100 >= 50*20）→ 降级
        assertTrue(PrefetchSampleGate.shouldDowngradeToFull(sampled = 50, missing = 10))
    }

    @Test
    fun `空样本恒维持跳过`() {
        // sampled=0：空库轮/无样本维持 SKIP（无片可缺；真实变更轮末会重新写样本）
        assertFalse(PrefetchSampleGate.shouldDowngradeToFull(sampled = 0, missing = 0))
        assertFalse(PrefetchSampleGate.shouldDowngradeToFull(sampled = 0, missing = 1))
    }

    @Test
    fun `小样本整除边界缺失一成即降级`() {
        // sampled=5：缺失 1 恰为 20% 整除边界 → 降级
        assertTrue(PrefetchSampleGate.shouldDowngradeToFull(sampled = 5, missing = 1))
    }

    // —— pickSample ——

    @Test
    fun `不超过上限全量保留`() {
        val urls = (1..30).map { "url-$it" }
        assertEquals(urls.toSet(), PrefetchSampleGate.pickSample(urls))
    }

    @Test
    fun `超过上限恰好五十条且不重复且来自全库`() {
        val urls = (1..1000).map { "url-$it" }
        val pool = urls.toSet()
        val picked = PrefetchSampleGate.pickSample(urls)
        assertEquals(PrefetchSampleGate.SAMPLE_SIZE, picked.size)
        assertTrue(picked.all { it in pool })
    }

    @Test
    fun `固定种子抽样确定性`() {
        val urls = (1..100).map { "url-$it" }
        assertEquals(
            PrefetchSampleGate.pickSample(urls, Random(42)),
            PrefetchSampleGate.pickSample(urls, Random(42)),
        )
    }
}
