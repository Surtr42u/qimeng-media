package media.qimeng.app.core.ui.component

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 格式化纯函数单测（口径冻结对照 web/src/lib/format.ts，与 Web 逐值对齐）：
 * formatCount（千分位/万压缩/截尾/取整）、formatShortDate（月日均不补零）、
 * formatBytesForDetail（详情 meta 行专用：GB/MB 一位小数、整数 KB、空值 0 B）、
 * formatDurationBadge（网格卡时长角标，任务L L1 补旧版口径断言：m:ss / h:mm:ss 纯文字）。
 * 全部纯 JVM，无 Android 依赖（core:ui 铁律：格式化不碰 IO/Compose）。
 */
class QimengFormatTest {

    // ---------- formatCount（Web formatCount，format.ts:55-63） ----------

    @Test
    fun `formatCount - 空值回退 0`() {
        assertEquals("0", formatCount(null))
    }

    @Test
    fun `formatCount - 万以下千分位分组`() {
        assertEquals("0", formatCount(0))
        assertEquals("999", formatCount(999))
        assertEquals("1,234", formatCount(1234))
        assertEquals("9,999", formatCount(9999))
    }

    @Test
    fun `formatCount - 万档一位小数且截尾 0`() {
        assertEquals("1万", formatCount(10_000)) // 1.0 → 截尾 ".0"
        assertEquals("1.1万", formatCount(11_000))
        assertEquals("1.5万", formatCount(15_000))
        assertEquals("10万", formatCount(99_999)) // 9.9999 → "10.0" → 截尾
    }

    @Test
    fun `formatCount - 百万位以上整数万`() {
        assertEquals("100万", formatCount(1_000_000))
        assertEquals("123万", formatCount(1_234_567))
    }

    // ---------- formatShortDate（Web formatShortDate，format.ts:18-23） ----------

    @Test
    fun `formatShortDate - 空值与非正值返回空串`() {
        assertEquals("", formatShortDate(null))
        assertEquals("", formatShortDate(0))
        assertEquals("", formatShortDate(-1))
    }

    @Test
    fun `formatShortDate - 月日均不补零`() {
        fun epochMs(y: Int, m: Int, d: Int): Long =
            LocalDate.of(y, m, d).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals("1-5", formatShortDate(epochMs(2026, 1, 5)))
        assertEquals("9-7", formatShortDate(epochMs(2026, 9, 7)))
        assertEquals("12-31", formatShortDate(epochMs(2026, 12, 31)))
    }

    // ---------- formatBytesForDetail（Web formatBytes，format.ts:35-40） ----------

    @Test
    fun `formatBytesForDetail - 空值与非正值按 Web sizeBytes ?? 0 语义`() {
        assertEquals("0 B", formatBytesForDetail(null))
        assertEquals("0 B", formatBytesForDetail(0))
        assertEquals("0 B", formatBytesForDetail(-5))
    }

    @Test
    fun `formatBytesForDetail - 字节档`() {
        assertEquals("512 B", formatBytesForDetail(512))
        assertEquals("1023 B", formatBytesForDetail(1023))
    }

    @Test
    fun `formatBytesForDetail - KB 档取整`() {
        assertEquals("1 KB", formatBytesForDetail(1024))
        assertEquals("2 KB", formatBytesForDetail(2048))
        // 1023.9990… → 取整 1024（Web toFixed(0) 同口径，不截断）
        assertEquals("1024 KB", formatBytesForDetail(1_048_575))
    }

    @Test
    fun `formatBytesForDetail - MB 档一位小数`() {
        assertEquals("1.0 MB", formatBytesForDetail(1_048_576))
        assertEquals("1.5 MB", formatBytesForDetail(1_572_864))
    }

    @Test
    fun `formatBytesForDetail - GB 档一位小数`() {
        assertEquals("1.0 GB", formatBytesForDetail(1_073_741_824))
        assertEquals("2.5 GB", formatBytesForDetail(2_684_354_560))
        assertEquals("10.0 GB", formatBytesForDetail(10_737_418_240))
    }

    // ---------- formatDurationBadge（旧版 §缩略图口径，任务L L1 补断言：分秒/时分秒纯文字） ----------
    // 注意：反引号测试名不能带冒号（JVM 方法名非法字符，app-test 实测编译失败）

    @Test
    fun `formatDurationBadge - 空值与非正值返回 null（不渲染角标）`() {
        assertEquals(null, formatDurationBadge(null))
        assertEquals(null, formatDurationBadge(0))
        assertEquals(null, formatDurationBadge(-1_000))
    }

    @Test
    fun `formatDurationBadge - 分秒档 秒补零分不补零`() {
        assertEquals("0:01", formatDurationBadge(1_000))
        assertEquals("0:59", formatDurationBadge(59_000))
        assertEquals("1:00", formatDurationBadge(60_000))
        assertEquals("1:01", formatDurationBadge(61_000))
        assertEquals("12:34", formatDurationBadge(754_000))
    }

    @Test
    fun `formatDurationBadge - 时分秒档`() {
        assertEquals("1:00:00", formatDurationBadge(3_600_000))
        assertEquals("1:01:01", formatDurationBadge(3_661_000))
        assertEquals("2:03:05", formatDurationBadge(7_385_000))
        assertEquals("10:00:00", formatDurationBadge(36_000_000))
    }

    @Test
    fun `formatDurationBadge - 不足一秒截尾为 0_00`() {
        assertEquals("0:00", formatDurationBadge(500))
        assertEquals("1:00", formatDurationBadge(60_999)) // 毫秒截尾不进位
    }
}
