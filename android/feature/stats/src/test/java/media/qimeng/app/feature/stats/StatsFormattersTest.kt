package media.qimeng.app.feature.stats

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 统计页纯函数单测（任务I I3）：数值气泡文本拼装（单系列/多系列含系列名/空目标）、
 * 时长格式化（数字卡「总浏览时长」四档）。零 Android/Compose 依赖（色只作 ARGB Int 键）。
 */
class StatsFormattersTest {

    // 任意固定 ARGB（只作 map 键，色彩语义无关）
    private val colorImage = 0xFF3F51B5.toInt()
    private val colorVideo = 0xFFE91E63.toInt()

    @Test
    fun `单系列气泡只出数值`() {
        val text = formatBubbleText(
            points = listOf(12.0 to colorImage),
            namesByColorArgb = emptyMap(),
            suffix = "次",
        )
        assertEquals("12次", text)
    }

    @Test
    fun `多系列气泡逐系列拼名`() {
        val text = formatBubbleText(
            points = listOf(12.0 to colorImage, 3.0 to colorVideo),
            namesByColorArgb = mapOf(colorImage to "图片", colorVideo to "视频"),
            suffix = "次",
        )
        assertEquals("图片 12次 · 视频 3次", text)
    }

    @Test
    fun `非整数值保留一位小数`() {
        assertEquals("1.5次", formatPointValue(1.5, "次"))
        assertEquals("12次", formatPointValue(12.0, "次"))
        assertEquals("0次", formatPointValue(0.0, "次"))
    }

    @Test
    fun `空目标气泡为空串`() {
        assertEquals("", formatBubbleText(points = emptyList(), namesByColorArgb = emptyMap(), suffix = "次"))
    }

    @Test
    fun `时长四档格式化`() {
        assertEquals("59秒", formatDurationSeconds(59))
        // 分档边界：<60 秒 / <3600 秒 / <86400 秒 / 其余
        assertEquals("1分钟", formatDurationSeconds(60))
        assertEquals("59分钟", formatDurationSeconds(3600 - 1))
        assertEquals("1小时", formatDurationSeconds(3600))
        assertEquals("1.5小时", formatDurationSeconds(5400))
        // 小时档四舍五入到 0.1h（23h59m59s 显示 24小时——显示格式四舍五入口径）
        assertEquals("24小时", formatDurationSeconds(23 * 3600 + 3599))
        assertEquals("2天", formatDurationSeconds(2 * 86400))
        assertEquals("1.5天", formatDurationSeconds(36 * 3600))
    }

    @Test
    fun `千分位显示`() {
        assertEquals("1,234", 1234.toDisplayText())
        assertEquals("1,234,567", 1234567L.toDisplayText())
    }
}
