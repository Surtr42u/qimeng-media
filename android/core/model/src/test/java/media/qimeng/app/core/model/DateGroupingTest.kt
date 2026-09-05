package media.qimeng.app.core.model

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Test

/** dateLabel（DOMAIN_RULES §8 逐字口径：今天/昨天/周X/yyyy-MM-dd/未知日期）与分组纯函数 */
class DateGroupingTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        Calendar.getInstance().apply {
            set(year, month - 1, day, hour, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** 固定「现在」= 2026-09-09（周三），避免测试跨日漂移 */
    private val now = at(2026, 9, 9)

    @Test
    fun `今天`() {
        assertEquals("今天", dateLabel(at(2026, 9, 9, 3), now))
    }

    @Test
    fun `昨天`() {
        assertEquals("昨天", dateLabel(at(2026, 9, 8), now))
    }

    @Test
    fun `距今 2 到 6 天显示周X`() {
        // 2026-09-07=周一、09-04=周五、09-03=周四
        assertEquals("周一", dateLabel(at(2026, 9, 7), now))
        assertEquals("周五", dateLabel(at(2026, 9, 4), now))
        assertEquals("周四", dateLabel(at(2026, 9, 3), now))
    }

    @Test
    fun `更早显示 yyyy-MM-dd`() {
        assertEquals("2026-08-31", dateLabel(at(2026, 8, 31), now))
        assertEquals("2025-01-02", dateLabel(at(2025, 1, 2), now))
    }

    @Test
    fun `无时间归未知日期`() {
        assertEquals("未知日期", dateLabel(null, now))
        assertEquals("未知日期", dateLabel(-1L, now))
    }

    @Test
    fun `分组同标签归并 组间按组首时间降序 未知日期恒最后`() {
        val assets = listOf(
            asset("a-old", at(2026, 8, 1)),
            asset("b-today", at(2026, 9, 9, 9)),
            asset("c-old", at(2026, 8, 15)),
            asset("d-today", at(2026, 9, 9, 8)),
            asset("e-unknown", null),
            asset("f-yesterday", at(2026, 9, 8)),
        )
        val sections = assets.groupByDateLabel(now) { it.modifiedAtMs }
        assertEquals(listOf("今天", "昨天", "2026-08-15", "2026-08-01", "未知日期"), sections.map { it.label })
        assertEquals(listOf("b-today", "d-today"), sections.first().items.map { it.id })
        // 组内保持列表原序
        assertEquals(listOf("c-old"), sections[2].items.map { it.id })
    }

    private fun asset(id: String, modifiedAtMs: Long?): MediaAsset = MediaAsset(
        id = id,
        fileName = "$id.jpg",
        title = id,
        mediaType = MediaKind.IMAGE,
        thumbUrl = null,
        source = null,
        characters = emptyList(),
        isFavorite = false,
        authorNames = emptyList(),
        modifiedAtMs = modifiedAtMs,
        durationMs = null,
        viewCount = null,
        playCount = null,
        lastViewedAtMs = null,
    )
}
