package media.qimeng.app.feature.detail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统栏呈现判定单源单测（任务S S9 抽取锁定；沿革记档：S7 的 statusBarIconsDark 4 用例
 * 保留——S9 后图标判定链原样在用，仅新增 [systemBarsShouldShow] 显隐三分支锁定）：
 * - [systemBarsShouldShow]：显隐三分支裁决（2026-09-19 用户拍板 B 站竖屏两态语义，纠正
 *   批S2/S7——播放态显示=控制条+状态栏透明、沉浸=全隐纯视频，图片常态恒透明显示，
 *   图片放大沉浸隐藏）；
 * - [statusBarIconsDark]：图标明暗判定（X7 判定链，S7 抽取，S9 未动）。
 */
class SystemBarIconAppearanceTest {

    // ---- systemBarsShouldShow：显隐三分支（S9） ----

    /** 播放态显示：控制条显（播放镜像 true）→ 状态栏透明显示（B 站竖屏显示态，含「上方的手机状态栏」） */
    @Test
    fun playerActiveWithControllerYieldsVisibleBars() {
        assertTrue(
            systemBarsShouldShow(
                zoomImmersive = false,
                playerActive = true,
                playbackChromeVisible = true,
            ),
        )
    }

    /** 播放态沉浸：控制条隐（单击切换/G9 自动隐/拖拽隐/长按隐同源镜像）→ 状态栏隐藏（纯视频） */
    @Test
    fun playerActiveWithoutControllerYieldsHiddenBars() {
        assertFalse(
            systemBarsShouldShow(
                zoomImmersive = false,
                playerActive = true,
                playbackChromeVisible = false,
            ),
        )
    }

    /** 图片常态：排版/浏览两态、chrome 显隐与播放镜像取值均不改变裁决 → 恒显示透明（S7 口径保留） */
    @Test
    fun imageNormalStateYieldsAlwaysVisibleBars() {
        assertTrue(
            systemBarsShouldShow(
                zoomImmersive = false,
                playerActive = false,
                playbackChromeVisible = false,
            ),
        )
        assertTrue(
            systemBarsShouldShow(
                zoomImmersive = false,
                playerActive = false,
                playbackChromeVisible = true,
            ),
        )
    }

    /** 图片放大沉浸（批C 链路）→ 状态栏隐藏（S9 起放大态恢复隐藏，纠正 S7 恒显口径） */
    @Test
    fun zoomImmersiveYieldsHiddenBars() {
        assertFalse(
            systemBarsShouldShow(
                zoomImmersive = true,
                playerActive = false,
                playbackChromeVisible = false,
            ),
        )
    }

    // ---- statusBarIconsDark：图标明暗（S7 抽取，判定链 S9 未动） ----

    /** chrome 显态舞台底=浅色主题背景（日 #FAFAFA）→ 暗色图标 */
    @Test
    fun chromeVisibleAndDayThemeYieldsDarkIcons() {
        assertTrue(statusBarIconsDark(chromeVisible = true, isSystemDarkTheme = false))
    }

    /** chrome 显态舞台底=夜间主题背景（#1A1A1A）→ 浅色图标（随系统明暗，X7 auto 语义） */
    @Test
    fun chromeVisibleAndNightThemeYieldsLightIcons() {
        assertFalse(statusBarIconsDark(chromeVisible = true, isSystemDarkTheme = true))
    }

    /** 沉浸/播放/放大黑底（chromeEffective=false）→ 浅色图标（日间也浅色——黑底暗图标不可辨，X7 根因） */
    @Test
    fun chromeHiddenOnBlackBackdropYieldsLightIconsEvenInDay() {
        assertFalse(statusBarIconsDark(chromeVisible = false, isSystemDarkTheme = false))
    }

    /** 沉浸态+系统夜间 → 浅色图标 */
    @Test
    fun chromeHiddenAndNightThemeYieldsLightIcons() {
        assertFalse(statusBarIconsDark(chromeVisible = false, isSystemDarkTheme = true))
    }
}
