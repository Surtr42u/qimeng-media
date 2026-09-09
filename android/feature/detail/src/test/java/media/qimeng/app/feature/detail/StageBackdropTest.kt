package media.qimeng.app.feature.detail

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 舞台底色裁决单源单测（任务K K1 黑底污染清偿）：锁定「chrome 显=主题底 / 沉浸或播放中
 * =纯黑」口径（对齐旧版 MediaDetailFragment 颜色行为——日间主题下浏览态不再黑底污染）。
 * （androidx.compose.ui.graphics.Color 为纯 Kotlin 值类，JVM 单测可直接断言相等，先例
 * TimelineTagColorsTest。）
 */
class StageBackdropTest {

    /** 旧版 qmColorBg 日间档（#FAFAFA）——只作代表值，裁决函数对主题色应原样透传 */
    private val lightBackground = Color(0xFFFAFAFA)

    /** 旧版 qmColorBg 夜间档（#1A1A1A，非纯黑） */
    private val darkBackground = Color(0xFF1A1A1A)

    @Test
    fun `chrome显示且播放器未活动 - 透传主题底`() {
        assertEquals(lightBackground, stageBackdropColor(true, false, lightBackground))
        assertEquals(darkBackground, stageBackdropColor(true, false, darkBackground))
    }

    @Test
    fun `chrome显示且播放器活动 - 纯黑（视频播放中画面区黑）`() {
        assertEquals(Color.Black, stageBackdropColor(true, true, lightBackground))
        assertEquals(Color.Black, stageBackdropColor(true, true, darkBackground))
    }

    @Test
    fun `chrome隐藏沉浸态 - 纯黑`() {
        assertEquals(Color.Black, stageBackdropColor(false, false, lightBackground))
        assertEquals(Color.Black, stageBackdropColor(false, false, darkBackground))
    }

    @Test
    fun `chrome隐藏且播放器活动 - 纯黑`() {
        assertEquals(Color.Black, stageBackdropColor(false, true, lightBackground))
        assertEquals(Color.Black, stageBackdropColor(false, true, darkBackground))
    }

    @Test
    fun `黑底污染回归锁 - 浏览态绝不返回纯黑`() {
        // 用户原话「黑色背景的详情页污染视觉」：日间主题 chrome 显示时旧实现恒黑，
        // 本断言锁定主题底透传语义，防口径回退
        val result = stageBackdropColor(true, false, lightBackground)
        assertEquals(lightBackground, result)
        assert(result != Color.Black)
    }
}
