package media.qimeng.app.feature.detail.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 两级全屏状态机单测（D2）：分级迁移、逐级回退、方向指令、瞬态横屏忽略（settle 窗口）。
 */
class VideoFullscreenStateMachineTest {

    @Test
    fun toggleFromNoneEntersPortraitLevel() {
        // 冻结口径：第一级=竖屏全屏（写竖屏方向），全类型视频可进全屏
        val machine = VideoFullscreenStateMachine()

        val command = machine.onFullscreenToggle()

        assertEquals(VideoFullscreenLevel.PORTRAIT, machine.level)
        assertEquals(
            VideoFullscreenCommand(VideoFullscreenLevel.PORTRAIT, VideoFullscreenOrientation.PORTRAIT),
            command,
        )
    }

    @Test
    fun toggleFromPortraitEscalatesToLandscape() {
        // 冻结口径：覆盖层内再点全屏钮 → 第二级=横屏全屏（固定横屏，防双横屏乱闪）
        val machine = VideoFullscreenStateMachine()
        machine.onFullscreenToggle()

        val command = machine.onFullscreenToggle()

        assertEquals(VideoFullscreenLevel.LANDSCAPE, machine.level)
        assertEquals(
            VideoFullscreenCommand(VideoFullscreenLevel.LANDSCAPE, VideoFullscreenOrientation.LANDSCAPE),
            command,
        )
    }

    @Test
    fun toggleFromLandscapeStepsDownToPortrait() {
        // 冻结口径：退出逐级回退（横屏级 → 竖屏级），不跳级
        val machine = VideoFullscreenStateMachine()
        machine.onFullscreenToggle()
        machine.onFullscreenToggle()

        val command = machine.onFullscreenToggle()

        assertEquals(VideoFullscreenLevel.PORTRAIT, machine.level)
        assertEquals(
            VideoFullscreenCommand(VideoFullscreenLevel.PORTRAIT, VideoFullscreenOrientation.PORTRAIT),
            command,
        )
    }

    @Test
    fun exitFromPortraitClosesOverlayAndRestoresPortrait() {
        // 竖屏级退出 → NONE，仍写竖屏（与 onDispose 兜底同向，防卡横屏）
        val machine = VideoFullscreenStateMachine()
        machine.onFullscreenToggle()

        val command = machine.onExitRequested()

        assertEquals(VideoFullscreenLevel.NONE, machine.level)
        assertEquals(
            VideoFullscreenCommand(VideoFullscreenLevel.NONE, VideoFullscreenOrientation.PORTRAIT),
            command,
        )
    }

    @Test
    fun exitFromLandscapeStepsDownToPortraitNotNone() {
        val machine = VideoFullscreenStateMachine()
        machine.onFullscreenToggle()
        machine.onFullscreenToggle()

        val command = machine.onExitRequested()

        assertEquals(VideoFullscreenLevel.PORTRAIT, machine.level)
        assertEquals(
            VideoFullscreenCommand(VideoFullscreenLevel.PORTRAIT, VideoFullscreenOrientation.PORTRAIT),
            command,
        )
    }

    @Test
    fun exitFromNoneIsNoOp() {
        val machine = VideoFullscreenStateMachine()

        assertNull(machine.onExitRequested())
        assertEquals(VideoFullscreenLevel.NONE, machine.level)
    }

    @Test
    fun rotationIgnoredAtNoneLevel() {
        // 排版态自由旋转语义不变：NONE 层旋转不锁方向不迁移
        val machine = VideoFullscreenStateMachine()

        assertNull(machine.onRotationChanged(isLandscape = true))
        assertNull(machine.onRotationChanged(isLandscape = false))
        assertEquals(VideoFullscreenLevel.NONE, machine.level)
    }

    @Test
    fun transientLandscapeAfterPortraitWriteIsIgnoredUntilPortraitSettles() {
        // 横屏手持点全屏：写竖屏后系统转屏有延迟，窗口期内横屏配置是瞬态，须忽略（防乱闪）
        val machine = VideoFullscreenStateMachine()
        machine.onFullscreenToggle() // 写竖屏，settle 窗口开启

        assertNull(machine.onRotationChanged(isLandscape = true)) // 瞬态横屏
        assertEquals(VideoFullscreenLevel.PORTRAIT, machine.level)

        assertNull(machine.onRotationChanged(isLandscape = false)) // 竖屏落地，窗口关闭
        assertNull(machine.onRotationChanged(isLandscape = false))

        // 此后的横屏配置变化（常规全屏被一级方向锁堵死，仅分屏/自由窗口等忽略方向锁
        // 环境可达，见状态机 onRotationChanged KDoc #23）→ 升第二级并锁横屏
        val command = machine.onRotationChanged(isLandscape = true)
        assertEquals(VideoFullscreenLevel.LANDSCAPE, machine.level)
        assertEquals(
            VideoFullscreenCommand(VideoFullscreenLevel.LANDSCAPE, VideoFullscreenOrientation.LANDSCAPE),
            command,
        )
    }

    @Test
    fun rotationCausedPortraitDescentHasNoSettleWindow() {
        // 横屏级被外力转回竖屏（多窗等）→ 防御回退竖屏级；配置已落地，无 settle 窗口：
        // 立即再转横屏须直接升级（对照上一条：写竖屏指令的迁移才开窗口）
        val machine = VideoFullscreenStateMachine()
        machine.onFullscreenToggle()
        machine.onFullscreenToggle()
        assertEquals(VideoFullscreenLevel.LANDSCAPE, machine.level)

        val descend = machine.onRotationChanged(isLandscape = false)
        assertEquals(VideoFullscreenLevel.PORTRAIT, machine.level)
        assertEquals(
            VideoFullscreenCommand(VideoFullscreenLevel.PORTRAIT, VideoFullscreenOrientation.PORTRAIT),
            descend,
        )

        val escalate = machine.onRotationChanged(isLandscape = true)
        assertEquals(VideoFullscreenLevel.LANDSCAPE, machine.level)
        assertEquals(
            VideoFullscreenCommand(VideoFullscreenLevel.LANDSCAPE, VideoFullscreenOrientation.LANDSCAPE),
            escalate,
        )
    }

    @Test
    fun stepDownToPortraitReopensSettleWindow() {
        // 横屏级回退竖屏级（写竖屏）：同样有 settle 窗口——配置还报横屏时不能被顶回横屏级
        val machine = VideoFullscreenStateMachine()
        machine.onFullscreenToggle()
        machine.onFullscreenToggle()

        machine.onFullscreenToggle() // 回退竖屏级，settle 窗口开启
        assertNull(machine.onRotationChanged(isLandscape = true)) // 瞬态横屏，忽略
        assertEquals(VideoFullscreenLevel.PORTRAIT, machine.level)
    }

    @Test
    fun landscapeLevelStaysUnderLandscapeConfiguration() {
        // 横屏级已锁横屏：横屏配置不产生迁移指令
        val machine = VideoFullscreenStateMachine()
        machine.onFullscreenToggle()
        machine.onFullscreenToggle()

        assertNull(machine.onRotationChanged(isLandscape = true))
        assertEquals(VideoFullscreenLevel.LANDSCAPE, machine.level)
    }
}
