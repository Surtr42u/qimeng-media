package media.qimeng.app.feature.detail.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单级横屏全屏状态机单测（任务K K2，对齐旧版 MediaDetailFragment:1163-1183 口径）：
 * 横屏视频可进 / 竖屏视频 no-op / 层内退出写竖屏 / 外力破锁防御回退 / 尺寸判定门槛。
 * D2 两级制用例（升级/settle 窗口/逐级回退）随两级制一并删除。
 */
class VideoFullscreenStateMachineTest {

    @Test
    fun toggleFromNoneWithLandscapeVideoEntersLandscapeFullscreen() {
        // K2 冻结口径：横屏视频（宽>高）排版态点全屏 → 单级横屏全屏（写横屏固定锁，非 SENSOR）
        val machine = VideoFullscreenStateMachine()

        val command = machine.onFullscreenToggle(isLandscapeVideo = true)

        assertEquals(VideoFullscreenLevel.LANDSCAPE, machine.level)
        assertEquals(
            VideoFullscreenCommand(VideoFullscreenLevel.LANDSCAPE, VideoFullscreenOrientation.LANDSCAPE),
            command,
        )
    }

    @Test
    fun toggleFromNoneWithPortraitVideoIsNoOp() {
        // K2 冻结口径（旧版同款）：竖屏视频全屏钮点击无反应——不迁移、不产生方向写入
        val machine = VideoFullscreenStateMachine()

        assertNull(machine.onFullscreenToggle(isLandscapeVideo = false))
        assertEquals(VideoFullscreenLevel.NONE, machine.level)
    }

    @Test
    fun toggleFromLandscapeExitsToLayoutAndRestoresPortrait() {
        // 覆盖层内全屏钮=退出：LANDSCAPE → NONE 且写竖屏（旧版「退出全屏回竖屏」口径）
        val machine = VideoFullscreenStateMachine()
        machine.onFullscreenToggle(isLandscapeVideo = true)

        val command = machine.onFullscreenToggle(isLandscapeVideo = false)

        assertEquals(VideoFullscreenLevel.NONE, machine.level)
        assertEquals(
            VideoFullscreenCommand(VideoFullscreenLevel.NONE, VideoFullscreenOrientation.PORTRAIT),
            command,
        )
    }

    @Test
    fun exitFromLandscapeRestoresPortrait() {
        // 系统返回/覆盖层顶栏返回退出：同向写竖屏（退出详情 onDispose 兜底之外的主路径）
        val machine = VideoFullscreenStateMachine()
        machine.onFullscreenToggle(isLandscapeVideo = true)

        val command = machine.onExitRequested()

        assertEquals(VideoFullscreenLevel.NONE, machine.level)
        assertEquals(
            VideoFullscreenCommand(VideoFullscreenLevel.NONE, VideoFullscreenOrientation.PORTRAIT),
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
    fun landscapeLevelStaysUnderLandscapeConfiguration() {
        // 横屏全屏已锁横屏：横屏配置与锁一致，无迁移指令
        val machine = VideoFullscreenStateMachine()
        machine.onFullscreenToggle(isLandscapeVideo = true)

        assertNull(machine.onRotationChanged(isLandscape = true))
        assertEquals(VideoFullscreenLevel.LANDSCAPE, machine.level)
    }

    @Test
    fun landscapeBreakLockByExternalRotationFallsBackToLayoutWritingPortrait() {
        // 防御回退（分屏/自由窗口等忽略 requestedOrientation 的外力把配置转回竖屏）：
        // 退出全屏写竖屏，恢复排版态自由旋转
        val machine = VideoFullscreenStateMachine()
        machine.onFullscreenToggle(isLandscapeVideo = true)

        val command = machine.onRotationChanged(isLandscape = false)

        assertEquals(VideoFullscreenLevel.NONE, machine.level)
        assertEquals(
            VideoFullscreenCommand(VideoFullscreenLevel.NONE, VideoFullscreenOrientation.PORTRAIT),
            command,
        )
    }

    @Test
    fun afterBreakLockFallbackFullscreenCanBeReEntered() {
        // 破锁回退后的再进入：状态机无残留态，横屏视频仍可正常进全屏
        val machine = VideoFullscreenStateMachine()
        machine.onFullscreenToggle(isLandscapeVideo = true)
        machine.onRotationChanged(isLandscape = false)

        val command = machine.onFullscreenToggle(isLandscapeVideo = true)

        assertEquals(VideoFullscreenLevel.LANDSCAPE, machine.level)
        assertEquals(
            VideoFullscreenCommand(VideoFullscreenLevel.LANDSCAPE, VideoFullscreenOrientation.LANDSCAPE),
            command,
        )
    }

    // ---------- isLandscapeVideoSize 判定门槛（喂入状态机的数据源口径） ----------

    @Test
    fun landscapeVideoSizeIsTrueOnlyForWidthGreaterThanHeight() {
        assertTrue(isLandscapeVideoSize(1920, 1080))
        assertFalse(isLandscapeVideoSize(1080, 1920)) // 竖屏视频：全屏钮 no-op 的数据面
    }

    @Test
    fun unknownOrSquareVideoSizeIsNotLandscape() {
        // 尺寸未知（服务端未探测/旧数据）与正方形一律不放行：宁紧勿松
        assertFalse(isLandscapeVideoSize(null, null))
        assertFalse(isLandscapeVideoSize(1920, null))
        assertFalse(isLandscapeVideoSize(null, 1080))
        assertFalse(isLandscapeVideoSize(1080, 1080))
    }
}
