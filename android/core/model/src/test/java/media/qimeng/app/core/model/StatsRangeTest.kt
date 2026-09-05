package media.qimeng.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 统计页四档 → `/stats/trends` range 参数映射（C1 拍板）。
 * 锁点：30 天档传 `day`（**近 30 天逐日**，不是单日——DOMAIN_RULES §5 窗口表的命名陷阱）。
 */
class StatsRangeTest {

    @Test
    fun `四档映射 7d_day_90d_all`() {
        assertEquals("7d", StatsRangeOption.SEVEN_DAYS.apiRange)
        assertEquals("day", StatsRangeOption.THIRTY_DAYS.apiRange)
        assertEquals("90d", StatsRangeOption.NINETY_DAYS.apiRange)
        assertEquals("all", StatsRangeOption.ALL.apiRange)
    }

    @Test
    fun `day 陷阱档必须是近30天语义的载体`() {
        // 若未来有人把 THIRTY_DAYS 的映射改成看似合理的 "30d"（协议里不存在），
        // 此测试强制失败提醒改回，并回去核对 openapi range 枚举与 DOMAIN_RULES §5。
        assertEquals("day", StatsRangeOption.THIRTY_DAYS.apiRange)
    }

    @Test
    fun `UI 文案四档与默认档`() {
        assertEquals(listOf("7天", "30天", "90天", "全部"), StatsRangeOption.entries.map { it.label })
        assertEquals(StatsRangeOption.SEVEN_DAYS, DEFAULT_STATS_RANGE)
    }
}
