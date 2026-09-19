package media.qimeng.app.feature.detail

import androidx.core.graphics.Insets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统栏瞬时隐藏纯逻辑单测（任务S 批S11，2026-09-19 用户拍板「状态栏也瞬时」）：
 * - [supportsInstantInsetsAnimation]：API 档门——androidx.core compat 的
 *   controlWindowInsetsAnimation 在 API < 30 是 no-op（本地 Gradle 缓存 1.17.0 sources.jar
 *   核实：Impl/Impl20 空实现、listener 永不回调），<30 必须走普通 hide()；30+ 委托平台
 *   控制器零时长生效（用户真机 Android 16=API 36 走此主路径）；
 * - [INSTANT_HIDE_DURATION_MS]：零时长档（平台 hide 动画 ~300ms 拖尾的短路开关）；
 * - [instantHideTargetInsets]：onReady 置零计算单源——systemBars 隐态 inset 恒为零，
 *   与传入的实时值无关。
 * 动画控制本体（onReady 置零+finish(false)、onCancelled 兜底 hide）是 Android 运行时
 * 行为，编译+既有套件覆盖，真机观感用户验收（批S11 任务书口径）。
 */
class InstantSystemBarsTest {

    // ---- supportsInstantInsetsAnimation：API 档门 ----

    /** API 26~29（项目 minSdk=26 覆盖段）：compat backport 是 no-op → 走普通 hide() */
    @Test
    fun belowApi30FallsBackToPlainHide() {
        assertFalse(supportsInstantInsetsAnimation(26))
        assertFalse(supportsInstantInsetsAnimation(28))
        assertFalse(supportsInstantInsetsAnimation(29))
    }

    /** API 30 起平台控制面生效（零时长主路径起点） */
    @Test
    fun api30EnablesInstantPath() {
        assertTrue(supportsInstantInsetsAnimation(30))
    }

    /** API 35/36（targetSdk 35 强制 edge-to-edge 档 + 用户真机 Android 16）：主路径 */
    @Test
    fun api35And36EnableInstantPath() {
        assertTrue(supportsInstantInsetsAnimation(35))
        assertTrue(supportsInstantInsetsAnimation(36))
    }

    // ---- INSTANT_HIDE_DURATION_MS：零时长档 ----

    /** 零时长=平台 hide 动画（约 300ms 平移渐隐）被整体短路的实现关键，改动即破坏拍板语义 */
    @Test
    fun instantHideDurationIsZero() {
        assertEquals(0L, INSTANT_HIDE_DURATION_MS)
    }

    // ---- instantHideTargetInsets：置零计算单源 ----

    /** 实时状态栏/导航栏 inset（如 128/63px）传入 → 四向全零（隐态） */
    @Test
    fun nonZeroCurrentInsetsYieldZeroTarget() {
        val target = instantHideTargetInsets(Insets.of(0, 128, 0, 63))
        assertEquals(Insets.of(0, 0, 0, 0), target)
    }

    /** 已全零（重复隐藏/已隐态）传入 → 恒等零（幂等） */
    @Test
    fun zeroCurrentInsetsYieldZeroTarget() {
        val target = instantHideTargetInsets(Insets.of(0, 0, 0, 0))
        assertEquals(Insets.of(0, 0, 0, 0), target)
    }
}
