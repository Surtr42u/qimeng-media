package media.qimeng.app.feature.detail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统栏图标暗色判定单源单测（任务S S7 抽取锁定）：S7 拍板后系统栏透明恒显（显隐链
 * 退役），图标明暗是 SystemBarsImmersiveEffect 唯一残留的状态行为——X7 判定链
 * （chrome 显+日间=暗图标，其余=浅图标）拆纯函数锁定，防后续批次无感漂移。
 */
class SystemBarIconAppearanceTest {

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
