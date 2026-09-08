package media.qimeng.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 统计页三档 → `/stats/trends` range 参数映射（任务I I3 回改：三档对齐 GUIDE_UI §数据统计页，
 * 覆盖旧 C1 四档拍板）。锁点：30 天档传 `day`（**近 30 天逐日**，不是单日——DOMAIN_RULES §5
 * 窗口表的命名陷阱）。
 */
class StatsRangeTest {

    @Test
    fun `三档映射 7d_day_all`() {
        assertEquals("7d", StatsRangeOption.SEVEN_DAYS.apiRange)
        assertEquals("day", StatsRangeOption.THIRTY_DAYS.apiRange)
        assertEquals("all", StatsRangeOption.ALL.apiRange)
    }

    @Test
    fun `day 陷阱档必须是近30天语义的载体`() {
        // 若未来有人把 THIRTY_DAYS 的映射改成看似合理的 "30d"（协议里不存在），
        // 此测试强制失败提醒改回，并回去核对 openapi range 枚举与 DOMAIN_RULES §5。
        assertEquals("day", StatsRangeOption.THIRTY_DAYS.apiRange)
    }

    @Test
    fun `90 天档已废止不再出现在枚举`() {
        // I3 回改锁点：任务G 对齐 Web 的 90d 档被 2026-09-08「完全复刻」拍板回退，
        // 若有人加回（如 NINETY_DAYS）此测试失败提醒核对拍板优先级（用户拍板 > Web 形态）。
        assertEquals(listOf("SEVEN_DAYS", "THIRTY_DAYS", "ALL"), StatsRangeOption.entries.map { it.name })
    }

    @Test
    fun `UI 文案三档与默认档`() {
        assertEquals(listOf("7天", "30天", "全部"), StatsRangeOption.entries.map { it.label })
        assertEquals(StatsRangeOption.SEVEN_DAYS, DEFAULT_STATS_RANGE)
    }

    @Test
    fun `详情页标题后缀与主页胶囊文案不同字`() {
        // GUIDE_UI §统计详情页：标题后缀「· 近7天/近30天/全部」，主页胶囊为「7天/30天/全部」
        assertEquals(listOf("近7天", "近30天", "全部"), StatsRangeOption.entries.map { it.detailTitleSuffix })
    }
}
