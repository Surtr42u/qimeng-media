package media.qimeng.app.feature.detail.video

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 方向写入条件化纯函数锁定（任务U11 批次C，U10-1 三步实验续）：
 * 同值跳过/跨值才写是消灭 nubia ROM「启动写点反应」的裁决核心，
 * 镜像常量与框架常量的对齐是执行层正确性的前提（映射错=全屏方向写错值）。
 * ActivityInfo 常量在 JVM 单测可用（静态 final int 编译期内联真值）。
 */
class OrientationWritePolicyTest {

    // ---- 镜像常量对齐框架（防手抄漂移） ----

    @Test
    fun `镜像常量与 ActivityInfo 框架常量逐一对齐`() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, SCREEN_ORIENTATION_UNSPECIFIED)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, SCREEN_ORIENTATION_LANDSCAPE)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, SCREEN_ORIENTATION_PORTRAIT)
    }

    // ---- 枚举映射 ----

    @Test
    fun `方向指令映射到框架常量档`() {
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, VideoFullscreenOrientation.PORTRAIT.toScreenOrientation())
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, VideoFullscreenOrientation.LANDSCAPE.toScreenOrientation())
    }

    // ---- 写入裁决：同值跳过 / 跨值才写 ----

    @Test
    fun `目标与当前相同跳过写`() {
        // U10-1 场景：冷启动恢复期 onDispose 兜底，当前已是自然基线 UNSPECIFIED
        assertNull(resolveOrientationWrite(SCREEN_ORIENTATION_UNSPECIFIED, SCREEN_ORIENTATION_UNSPECIFIED))
        // 防御回退场景：分屏破锁转回竖屏后回退写竖屏，当前已是竖屏
        assertNull(resolveOrientationWrite(SCREEN_ORIENTATION_PORTRAIT, SCREEN_ORIENTATION_PORTRAIT))
        // 横屏锁内重复确认
        assertNull(resolveOrientationWrite(SCREEN_ORIENTATION_LANDSCAPE, SCREEN_ORIENTATION_LANDSCAPE))
    }

    @Test
    fun `目标与当前不同才落写`() {
        // 进全屏：UNSPECIFIED → LANDSCAPE
        assertEquals(
            SCREEN_ORIENTATION_LANDSCAPE,
            resolveOrientationWrite(SCREEN_ORIENTATION_UNSPECIFIED, SCREEN_ORIENTATION_LANDSCAPE),
        )
        // 退全屏回排版：LANDSCAPE → UNSPECIFIED（D2 离场恢复）
        assertEquals(
            SCREEN_ORIENTATION_UNSPECIFIED,
            resolveOrientationWrite(SCREEN_ORIENTATION_LANDSCAPE, SCREEN_ORIENTATION_UNSPECIFIED),
        )
        // 全屏残留锁（PORTRAIT 粘性）→ UNSPECIFIED 恢复
        assertEquals(
            SCREEN_ORIENTATION_UNSPECIFIED,
            resolveOrientationWrite(SCREEN_ORIENTATION_PORTRAIT, SCREEN_ORIENTATION_UNSPECIFIED),
        )
    }
}
