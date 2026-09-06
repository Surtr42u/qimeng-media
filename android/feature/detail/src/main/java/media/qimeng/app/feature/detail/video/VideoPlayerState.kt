package media.qimeng.app.feature.detail.video

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/** logcat TAG（3c 验收文本证据：STATE_IDLE/BUFFERING/READY/ENDED 每次迁移各一行） */
private const val VIDEO_LOG_TAG = "QimengVideo"

/**
 * ExoPlayer 持有器（Compose 侧封装，3c；View 世界与 Compose 世界的边界在这里收口）。
 *
 * 职责：
 * - 创建即挂音频焦点（`handleAudioFocus = true`，任务书口径）；
 * - G8 初始默认静音（volume=0，用户拍板；搬运件 BiliPlayerView.isMuted=true 同口径，
 *   setPlayer 时会再压一次，此处先行保证「挂控制器之前也不出声」）；
 * - 播放状态迁移打 logcat（TAG=[VIDEO_LOG_TAG]）；
 * - 生命周期行为由 [rememberVideoPlayerState] 接线（onPause 暂停/onResume 恢复/销毁释放）。
 *
 * 视频源装载走 [prepare]（startPositionMs 为 3d 断点续播留缝，本批默认 0）。
 */
@UnstableApi
internal class VideoPlayerState(context: Context) {

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .build()

    /** onPause 时是否在播（onResume 恢复依据；暂停中切后台，回来不自动起播） */
    private var wasPlayingOnPause = false

    private var released = false

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                // 每次迁移一行（3c 验收靠 logcat 文本证据）
                Log.i(VIDEO_LOG_TAG, "playbackState -> ${playbackStateName(playbackState)}")
            }
        })
        // G8：初始默认静音（用户拍板）
        player.volume = 0f
    }

    /** 装载视频源并 prepare（ExoPlayer 原生支持起播位置，等价 setMediaItem + seekTo） */
    fun prepare(uri: String, startPositionMs: Long = 0L) {
        player.setMediaItem(MediaItem.fromUri(uri), startPositionMs)
        player.prepare()
    }

    fun play() {
        player.play()
    }

    fun pause() {
        player.pause()
    }

    /** 生命周期 ON_PAUSE：记住播放态再暂停（[resumeFromLifecycle] 依据） */
    fun pauseForLifecycle() {
        wasPlayingOnPause = player.isPlaying
        player.pause()
    }

    /** 生命周期 ON_RESUME：仅当暂停时正在播才恢复 */
    fun resumeFromLifecycle() {
        if (wasPlayingOnPause) player.play()
    }

    /**
     * 释放（幂等：重复调用无害兜底）。唯一执行点 = [rememberVideoPlayerState] 的
     * DisposableEffect onDispose（组合离场必达）；生命周期 observer 不挂 ON_DESTROY。
     */
    fun release() {
        if (!released) {
            released = true
            player.release()
        }
    }
}

/** Player.STATE_* → 日志名（仅日志用途，不参与业务分支） */
private fun playbackStateName(state: Int): String = when (state) {
    Player.STATE_IDLE -> "IDLE"
    Player.STATE_BUFFERING -> "BUFFERING"
    Player.STATE_READY -> "READY"
    Player.STATE_ENDED -> "ENDED"
    else -> "UNKNOWN($state)"
}

/**
 * remember 持有 [VideoPlayerState] 并接线生命周期：observer 只挂 ON_PAUSE（暂停并记住
 * 播放态）与 ON_RESUME（按记忆恢复），**不挂 ON_DESTROY**——释放唯一执行点是
 * DisposableEffect onDispose（组合离场必达，Navigation pop 与父级离场都覆盖）；
 * [VideoPlayerState.release] 幂等，仅作重复调用兜底。
 */
@androidx.annotation.OptIn(UnstableApi::class) // 内部创建 ExoPlayer（Media3 @UnstableApi 面）
@Composable
internal fun rememberVideoPlayerState(): VideoPlayerState {
    val context = LocalContext.current
    val state = remember(context) { VideoPlayerState(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, state) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> state.pauseForLifecycle()
                Lifecycle.Event.ON_RESUME -> state.resumeFromLifecycle()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            state.release()
        }
    }
    return state
}
