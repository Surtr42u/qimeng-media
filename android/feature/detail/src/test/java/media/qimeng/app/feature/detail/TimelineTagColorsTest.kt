package media.qimeng.app.feature.detail

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间轴标签前缀配色单测（M4-3 3d）：❤→红、⭐→金、无前缀→默认色，深浅成对。
 * （androidx.compose.ui.graphics.Color 为纯 Kotlin 值类，JVM 单测可直接断言相等。）
 */
class TimelineTagColorsTest {

    private val default = Color(0xFF999999)

    @Test
    fun `心形前缀红色系 - 深浅成对`() {
        assertEquals(TimelineTagColors.HeartLight, TimelineTagColors.colorFor("❤ 名场面", false, default))
        assertEquals(TimelineTagColors.HeartDark, TimelineTagColors.colorFor("❤ 名场面", true, default))
    }

    @Test
    fun `星形前缀金色系 - 深浅成对`() {
        assertEquals(TimelineTagColors.StarLight, TimelineTagColors.colorFor("⭐ 高光", false, default))
        assertEquals(TimelineTagColors.StarDark, TimelineTagColors.colorFor("⭐ 高光", true, default))
    }

    @Test
    fun `变体选择符序列同命中 - 前缀startsWith语义`() {
        // ❤️ = ❤ + U+FE0F（变体选择符）：startsWith 天然兼容
        assertEquals(TimelineTagColors.HeartLight, TimelineTagColors.colorFor("❤️ 哭死", false, default))
    }

    @Test
    fun `无前缀与普通名返回默认色`() {
        assertEquals(default, TimelineTagColors.colorFor(" OP", false, default))
        assertEquals(default, TimelineTagColors.colorFor("普通标签", true, default))
        assertEquals(default, TimelineTagColors.colorFor("", false, default))
        // 前缀在中间不算：前缀语义=开头
        assertEquals(default, TimelineTagColors.colorFor("超❤爱", false, default))
    }

    @Test
    fun `前缀判定hasColorPrefix`() {
        assertTrue(TimelineTagColors.hasColorPrefix("❤ a"))
        assertTrue(TimelineTagColors.hasColorPrefix("⭐ b"))
        assertFalse(TimelineTagColors.hasColorPrefix("c"))
        assertFalse(TimelineTagColors.hasColorPrefix(""))
    }
}
