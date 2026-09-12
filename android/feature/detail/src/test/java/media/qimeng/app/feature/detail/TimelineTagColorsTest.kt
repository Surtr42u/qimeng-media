package media.qimeng.app.feature.detail

import androidx.compose.ui.graphics.Color
import media.qimeng.app.feature.detail.video.TimelineTagEntity
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

    @Test
    fun `服务端color不再参与显示决策 - 实体无serverColor字段（2026-09-12拍板对齐旧版）`() {
        // S1a：消费侧断开——TimelineTagEntity 已删 serverColor 字段，芯片恒按 TimelineTagColors
        // 前缀档取色；若该字段回流即意味着服务端色重新接入显示链，用反射锁死防回归。
        // （协议镜像 core model TimelineTag.color 保留不动，映射层透传断言归 SdkDetailMappersTest。）
        val fieldNames = TimelineTagEntity::class.java.declaredFields.map { it.name }
        assertFalse("serverColor 应已删除（S1a 拍板对齐旧版），不应回流实体", fieldNames.contains("serverColor"))
    }
}
