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

    // ---------- G7：倍速档位表（全面对齐 Web 端 PLAYBACK_RATES 六档） ----------

    @Test
    fun speedTiersMatchWebPlaybackRates() {
        assertEquals(listOf(2.0f, 1.5f, 1.25f, 1.0f, 0.75f, 0.5f), PLAYER_SPEED_TIERS.map { it.speed })
        assertEquals(listOf("2.0x", "1.5x", "1.25x", "1.0x 正常", "0.75x", "0.5x"), PLAYER_SPEED_TIERS.map { it.menuLabel })
    }

    @Test
    fun speedButtonTextShowsBeiSuForOneX() {
        assertEquals("倍速", speedButtonText(1.0f))
        assertEquals("0.5x", speedButtonText(0.5f))
        assertEquals("0.75x", speedButtonText(0.75f))
        assertEquals("1.25x", speedButtonText(1.25f))
        assertEquals("1.5x", speedButtonText(1.5f))
        assertEquals("2.0x", speedButtonText(2.0f))
    }

    @Test
    fun qualityLabelTextMapsVideoHeightCorrectly() {
        assertEquals("4K 超清", qualityLabelText(2160))
        assertEquals("2K 超清", qualityLabelText(1440))
        assertEquals("1080P 高清", qualityLabelText(1080))
        assertEquals("720P 高清", qualityLabelText(720))
        assertEquals("480P 标清", qualityLabelText(480))
        assertEquals("360P", qualityLabelText(360))
        assertEquals("原画", qualityLabelText(0))
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

    // ---------- S2：点按动作映射（2026-09-19 拍板冻结口径） ----------

    @Test
    fun singleTapTogglesControllerInBothOrientations() {
        // 拍板「单击变成显示 ui」：单击=切控制层显隐，排版态/全屏态同语义
        // （推翻 GUIDE_UI L185-186 旧口径「竖屏单击播停」——播停让位双击）
        assertEquals(
            PlayerTapAction.TOGGLE_CONTROLLER,
            resolvePlayerTapAction(isFullscreen = false, isDoubleTap = false),
        )
        assertEquals(
            PlayerTapAction.TOGGLE_CONTROLLER,
            resolvePlayerTapAction(isFullscreen = true, isDoubleTap = false),
        )
    }

    @Test
    fun doubleTapTogglesPlayPauseInBothOrientations() {
        // 拍板「双击才是暂停」：双击=播停，排版态/全屏态同语义
        // （旧版「竖屏双击无功能」废止；横屏双击播停语义保持）
        assertEquals(
            PlayerTapAction.TOGGLE_PLAY_PAUSE,
            resolvePlayerTapAction(isFullscreen = false, isDoubleTap = true),
        )
        assertEquals(
            PlayerTapAction.TOGGLE_PLAY_PAUSE,
            resolvePlayerTapAction(isFullscreen = true, isDoubleTap = true),
        )
    }
}
