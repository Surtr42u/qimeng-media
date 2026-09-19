package media.qimeng.app.feature.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片态沉浸图标反色纯函数单测（任务S 批S10 件1，2026-09-19 用户拍板「白色上面状态栏
 * 反色……手机相册那种」）：
 * - [topRegionAverageLuminance]：顶部 1/3 区域语义 + BT.601 加权正确性；
 * - [statusBarIconsDarkForLuminance]：阈值口径（白→深/黑→浅/中灰灰区取深/暗灰→浅）。
 */
class StatusBarLuminanceTest {

    /** 造 rows×cols 纯色图像素（行优先展平） */
    private fun solidPixels(argb: Int, cols: Int, rows: Int): IntArray =
        IntArray(cols * rows) { argb }

    /** 造 rows×cols 图：给定行区间用行色表（行号→ARGB），未命中行用 fallback */
    private fun stripedPixels(
        cols: Int,
        rows: Int,
        fallback: Int,
        rowColors: Map<Int, Int>,
    ): IntArray = IntArray(cols * rows) { i ->
        rowColors[i / cols] ?: fallback
    }

    // ---- topRegionAverageLuminance：区域与加权语义 ----

    /** 全白图 → 亮度 1.0 */
    @Test
    fun allWhiteYieldsFullLuminance() {
        assertEquals(
            1.0,
            topRegionAverageLuminance(solidPixels(0xFFFFFFFF.toInt(), cols = 4, rows = 9), 4, 9),
            1e-9,
        )
    }

    /** 全黑图 → 亮度 0.0 */
    @Test
    fun allBlackYieldsZeroLuminance() {
        assertEquals(
            0.0,
            topRegionAverageLuminance(solidPixels(0xFF000000.toInt(), cols = 4, rows = 9), 4, 9),
            1e-9,
        )
    }

    /** 顶部 1/3 白、其余黑 → 只统计顶部 1/3 → 亮度≈1.0（中部/底部不参与） */
    @Test
    fun topThirdWhiteRestBlackCountsTopRegionOnly() {
        // 9 行图 → 顶部 3 行白；若整图参与均值应为 1/3≈0.33，顶部 1/3 口径应为 1.0
        val pixels = stripedPixels(
            cols = 4, rows = 9, fallback = 0xFF000000.toInt(),
            rowColors = mapOf(0 to 0xFFFFFFFF.toInt(), 1 to 0xFFFFFFFF.toInt(), 2 to 0xFFFFFFFF.toInt()),
        )
        assertEquals(1.0, topRegionAverageLuminance(pixels, 4, 9), 1e-9)
    }

    /** 顶部 1/3 黑、其余白 → 亮度≈0.0（下方大片白不翻转判定面） */
    @Test
    fun topThirdBlackRestWhiteCountsTopRegionOnly() {
        val pixels = stripedPixels(
            cols = 4, rows = 9, fallback = 0xFFFFFFFF.toInt(),
            rowColors = mapOf(0 to 0xFF000000.toInt(), 1 to 0xFF000000.toInt(), 2 to 0xFF000000.toInt()),
        )
        assertEquals(0.0, topRegionAverageLuminance(pixels, 4, 9), 1e-9)
    }

    /** BT.601 加权正确性：纯绿 luma=0.587、纯蓝 luma=0.114（等权均值会误判两者同值） */
    @Test
    fun bt601WeightsApplied() {
        val green = topRegionAverageLuminance(
            solidPixels(0xFF00FF00.toInt(), cols = 2, rows = 3), 2, 3,
        )
        assertEquals(0.587, green, 1e-9)
        val blue = topRegionAverageLuminance(
            solidPixels(0xFF0000FF.toInt(), cols = 2, rows = 3), 2, 3,
        )
        assertEquals(0.114, blue, 1e-9)
    }

    /** 极扁图（1 行）防御：采样行数向下取整归零时至少保 1 行，不除零不崩 */
    @Test
    fun oneRowImageStillSamplesOneRow() {
        assertEquals(
            1.0,
            topRegionAverageLuminance(solidPixels(0xFFFFFFFF.toInt(), cols = 4, rows = 1), 4, 1),
            1e-9,
        )
    }

    /** 非法入参防御（空像素/零尺寸）：返回 0.0（消费点按暗底兜底浅图标），不抛异常 */
    @Test
    fun invalidInputYieldsZeroLuminance() {
        assertEquals(0.0, topRegionAverageLuminance(IntArray(0), 0, 0), 1e-9)
        assertEquals(0.0, topRegionAverageLuminance(IntArray(3), 4, 9), 1e-9)
    }

    // ---- statusBarIconsDarkForLuminance：阈值口径 ----

    /** 白图（1.0）→ 深色图标（拍板：白色上面反色） */
    @Test
    fun whiteYieldsDarkIcons() {
        assertTrue(statusBarIconsDarkForLuminance(1.0))
    }

    /** 黑图（0.0）→ 浅色图标（黑底语义） */
    @Test
    fun blackYieldsLightIcons() {
        assertFalse(statusBarIconsDarkForLuminance(0.0))
    }

    /** 中灰（sRGB 128 ≈ 0.502）→ 深色图标：拍板「0.35~0.5 灰区取深」口径锁死 */
    @Test
    fun midGrayYieldsDarkIcons() {
        assertTrue(statusBarIconsDarkForLuminance(128 / 255.0))
    }

    /** 暗灰（sRGB 80 ≈ 0.314）→ 浅色图标（阈值以下归浅） */
    @Test
    fun darkGrayYieldsLightIcons() {
        assertFalse(statusBarIconsDarkForLuminance(80 / 255.0))
    }

    /** 阈值边界：恰等于 0.35 → 浅色图标（严格大于才判深，防阈值抖动两侧翻转口径含混） */
    @Test
    fun thresholdBoundaryYieldsLightIcons() {
        assertFalse(statusBarIconsDarkForLuminance(STATUS_BAR_LUMINANCE_DARK_ICONS))
    }
}
