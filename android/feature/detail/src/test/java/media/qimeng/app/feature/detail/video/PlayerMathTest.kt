package media.qimeng.app.feature.detail.video

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 播放器纯逻辑单测（3c）：G4 拖进度自适应上限三档边界 + G7 倍速档位表。
 * 冻结口径 = 旧版 BiliPlayerView 实测参数（任务书 G1~G9）。
 */
class PlayerMathTest {

    // ---------- G4：gestureSeekCapMs 三档边界 ----------

    @Test
    fun durationNonReadyReturnsZeroSoGestureSeekIsIgnored() {
        // 旧版 v1.15 口径：duration 非 READY（<=0，含 TIME_UNSET 折算）忽略手势 seek
        assertEquals(0L, gestureSeekCapMs(0L))
        assertEquals(0L, gestureSeekCapMs(-1L))
    }

    @Test
    fun shortVideoCapEqualsDuration() {
        // 时长 ≤30s → 上限=时长（含 30s 端点）
        assertEquals(1L, gestureSeekCapMs(1L))
        assertEquals(29_999L, gestureSeekCapMs(29_999L))
        assertEquals(30_000L, gestureSeekCapMs(30_000L))
    }

    @Test
    fun mediumVideoCapIsSixtySeconds() {
        // 30s~2min → 60s（两端点各归本档）
        assertEquals(60_000L, gestureSeekCapMs(30_001L))
        assertEquals(60_000L, gestureSeekCapMs(60_000L))
        assertEquals(60_000L, gestureSeekCapMs(120_000L))
    }

    @Test
    fun longVideoCapIsHundredTwentySeconds() {
        // >2min → 120s
        assertEquals(120_000L, gestureSeekCapMs(120_001L))
        assertEquals(120_000L, gestureSeekCapMs(3_600_000L))
    }

    // ---------- G7：倍速档位表 ----------

    @Test
    fun speedTiersAreExactlyTwoOneAndHalfOneHalf() {
        assertEquals(listOf(2f, 1.5f, 1f, 0.5f), PLAYER_SPEED_TIERS.map { it.speed })
        // 菜单行标签与旧控件逐字一致（1x 行显示「1x」；「倍速」是按钮文案，见下）
        assertEquals(listOf("2x", "1.5x", "1x", "0.5x"), PLAYER_SPEED_TIERS.map { it.menuLabel })
    }

    @Test
    fun speedButtonTextShowsBeiSuForOneX() {
        // G7：1x 显示「倍速」；其余档「N倍」
        assertEquals("倍速", speedButtonText(1f))
        assertEquals("0.5倍", speedButtonText(0.5f))
        assertEquals("1.5倍", speedButtonText(1.5f))
        assertEquals("2倍", speedButtonText(2f))
    }

    @Test
    fun speedButtonTextFallsBackToNxForNonStandardTier() {
        assertEquals("1.25x", speedButtonText(1.25f))
    }

    // ---------- W5 #50：总时长赋值判定 + 时长格式化（冻结件例外三件套） ----------

    @Test
    fun totalDurationTextNullWhenDurationUnavailable() {
        // 时长不可用（0=TIME_UNSET 折算/非 READY；负数防御）→ null=不赋值、控件保持现状
        assertEquals(null, totalDurationText(0L))
        assertEquals(null, totalDurationText(-1L))
    }

    @Test
    fun totalDurationTextFormatsPositiveDuration() {
        // 正值时长即产出格式化文本（#50 修复口径：挂载时 READY 已过也能补出总时长）
        assertEquals("0:02", totalDurationText(2_000L))
        assertEquals("1:01", totalDurationText(61_000L))
        assertEquals("1:00:00", totalDurationText(3_600_000L))
    }

    @Test
    fun formatDurationMsMatchesLegacyFormatMsBehavior() {
        // 原 BiliPlayerView.formatMs 行为锁定：小时档 h:mm:ss / 分钟不补零 m:ss / 负时长带前缀
        assertEquals("12:34", formatDurationMs((12 * 60 + 34) * 1000L))
        assertEquals("0:05", formatDurationMs(5_000L))
        assertEquals("2:03:04", formatDurationMs((2 * 3600 + 3 * 60 + 4) * 1000L))
        assertEquals("-0:05", formatDurationMs(-5_000L))
        assertEquals("-1:00:00", formatDurationMs(-3_600_000L))
    }
}
