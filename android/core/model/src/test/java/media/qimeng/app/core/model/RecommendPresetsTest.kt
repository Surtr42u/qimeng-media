package media.qimeng.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 推荐偏好四预设（DOMAIN_RULES §1.3 预设表逐字锁定）与当前项高亮匹配。
 * 预设表标注「逐字遵守」：此处任一数值断言失败 = 有人动了 DOMAIN_RULES 或预设枚举，必须先对表。
 */
class RecommendPresetsTest {

    @Test
    fun `均衡推荐逐维对表`() {
        val values = RecommendPreset.BALANCED.toPrefsValues()
        assertEquals(RecommendPrefsValues(0.22, 0.15, 0.10, 0.15, 0.05, 0.20, 0.05, 0.03, 0.30), values)
    }

    @Test
    fun `高记忆流行逐维对表`() {
        val values = RecommendPreset.MEMORY_POPULAR.toPrefsValues()
        assertEquals(RecommendPrefsValues(0.15, 0.10, 0.20, 0.25, 0.10, 0.05, 0.05, 0.05, 0.10), values)
    }

    @Test
    fun `深度探索逐维对表`() {
        val values = RecommendPreset.DEEP_EXPLORATION.toPrefsValues()
        assertEquals(RecommendPrefsValues(0.30, 0.20, 0.05, 0.05, 0.02, 0.30, 0.02, 0.05, 0.40), values)
    }

    @Test
    fun `新鲜优先逐维对表`() {
        val values = RecommendPreset.FRESH_FIRST.toPrefsValues()
        assertEquals(RecommendPrefsValues(0.10, 0.05, 0.05, 0.10, 0.02, 0.25, 0.20, 0.03, 0.35), values)
    }

    @Test
    fun `当前值命中预设则高亮该档`() {
        RecommendPreset.entries.forEach { preset ->
            assertEquals(preset, preset.toPrefsValues().matchPreset())
        }
    }

    @Test
    fun `自定义值不命中任何预设返回null`() {
        val custom = RecommendPreset.BALANCED.toPrefsValues().copy(discovery = 0.21)
        assertNull(custom.matchPreset())
    }

    @Test
    fun `四预设文案与顺序`() {
        assertEquals(listOf("均衡推荐", "高记忆流行", "深度探索", "新鲜优先"), RecommendPreset.entries.map { it.label })
    }

    @Test
    fun `均衡档固定权重合计为设计值095`() {
        // DOMAIN_RULES §1.1 的「固定权重合计 0.95」只描述均衡档（默认设计权重）；
        // 其余三档合计本就不同（探索 0.99/新鲜 0.80，预设表值如此），不做统一断言。
        val v = RecommendPreset.BALANCED.toPrefsValues()
        val sum = v.tagRelevance + v.tagCollection + v.engagement + v.recency +
            v.likeScore + v.discovery + v.freshness + v.browseDepth
        assertTrue(Math.abs(sum - 0.95) < 1e-9)
    }
}
