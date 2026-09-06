package media.qimeng.app.feature.detail.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 海报态/播放态状态机单测（3c）：幂等起播、ENDED 再播回 0（任务书冻结口径）。
 */
class VideoStageStateMachineTest {

    @Test
    fun posterStartTransitionsToPlayingWithoutRestartFlag() {
        val machine = VideoStageStateMachine()
        assertEquals(VideoStageMode.POSTER, machine.mode)

        val command = machine.start()

        assertEquals(VideoStageMode.PLAYING, machine.mode)
        assertFalse(command!!.restartFromZero)
    }

    @Test
    fun startIsIdempotentWhilePlaying() {
        val machine = VideoStageStateMachine()
        machine.start()

        // 幂等起播：已播放直接返回，执行层不重置进度/不重装源
        assertNull(machine.start())
        assertEquals(VideoStageMode.PLAYING, machine.mode)
    }

    @Test
    fun startAfterEndedRestartsFromZero() {
        val machine = VideoStageStateMachine()
        machine.start()
        machine.onPlaybackEnded()
        assertEquals(VideoStageMode.ENDED, machine.mode)

        // G9：ENDED 再点播放 → seekTo(0) 重播
        val command = machine.start()
        assertTrue(command!!.restartFromZero)
        assertEquals(VideoStageMode.PLAYING, machine.mode)
    }

    @Test
    fun playbackEndedIsIdempotent() {
        val machine = VideoStageStateMachine()
        machine.start()
        machine.onPlaybackEnded()
        machine.onPlaybackEnded()
        assertEquals(VideoStageMode.ENDED, machine.mode)
    }

    @Test
    fun inViewReplayAfterEndedReturnsMachineToPlaying() {
        // 视图内再播路径（BiliPlayerView togglePlayPause 内 seekTo(0) 后 isPlaying=true）：
        // 状态机经 onPlaybackStarted 补账，不经过 start()
        val machine = VideoStageStateMachine()
        machine.start()
        machine.onPlaybackEnded()

        machine.onPlaybackStarted()

        assertEquals(VideoStageMode.PLAYING, machine.mode)
    }

    @Test
    fun playbackStartedIgnoredFromPoster() {
        // 海报态收到「恢复播放」回调不迁移（脏回调防御）
        val machine = VideoStageStateMachine()
        machine.onPlaybackStarted()
        assertEquals(VideoStageMode.POSTER, machine.mode)
    }
}
