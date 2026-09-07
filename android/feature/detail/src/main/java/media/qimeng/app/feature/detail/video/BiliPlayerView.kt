package media.qimeng.app.feature.detail.video

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.PopupWindow
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import media.qimeng.app.feature.detail.R
import media.qimeng.app.feature.detail.TimelineTagColors
import kotlin.math.abs
import kotlin.math.max
import androidx.media3.common.util.UnstableApi
import kotlin.math.min

/**
 * B 站式视频播放器控件（ADR-0014 例外③「复杂自绘控件 AndroidView 互操作桥接」的搬运件，
 * 已入 3c 交付报告桥接清单）。
 *
 * 来源：旧项目 QimengMedia `app/src/main/java/com/qimeng/media/ui/detail/BiliPlayerView.kt`
 * （837 行）**整文件搬运**；手势参数为冻结口径（G1~G9，参数=旧版实测），禁止随手改参。
 * 适配点（其余逐行原样，旧注释保留）：
 *   ① 包名/R 资源改指本模块（`media.qimeng.app.feature.detail`）；
 *   ② [TimelineTagEntity] 由旧 Room 实体剥离为同包纯数据类（字段不变）；
 *   ③ G4 拖进度自适应上限抽为 [gestureSeekCapMs] 纯函数（同包，行为不变）；
 *   ④ G7 倍速档位/按钮文案抽为 [PLAYER_SPEED_TIERS]/[speedButtonText] 纯函数（行为不变）；
 *   ⑤ G6 全屏按钮加「仅横屏视频可点」门禁（竖屏点按仅提示，任务书冻结口径）。
 *
 * 手势冻结口径（G1~G9）：G1 竖屏单击播停/横屏单击显隐控制器；G2 横屏双击播停；
 * G3 长按 2x 松开还原（竖屏下方锁速区拖入锁定/拖出退出，长按期间禁起拖）；
 * G4 水平拖进度（24dp 起拖阈值且 |dx|>|dy|，上限见 [gestureSeekCapMs]，无时长忽略）；
 * G5 亮度/音量手势=不做（旧版无此功能）；G6 全屏=仅横屏视频可点（方向经 Activity 请求，
 * Compose 侧不持状态）；G7 倍速菜单 0.5/1/1.5/2x（1x 按钮显示「倍速」）；
 * G8 初始默认静音（volume=0，用户拍板）；G9 控制器 5s 自动隐藏（ENDED 强制显示，
 * 再点播放 seekTo(0)）。
 */
@UnstableApi
class BiliPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val playerView: PlayerView
    private val topBar: LinearLayout
    private val bottomBar: LinearLayout
    private val playPauseBtn: ImageView
    private val speedBtn: TextView
    private val muteBtn: ImageView
    private val currentTimeText: TextView
    private val totalTimeText: TextView
    private val progressBar: SeekBar
    private val backBtn: ImageView

    private val gestureIndicator: LinearLayout
    private val gestureText: TextView

    private val topIndicator: LinearLayout
    private val topIndicatorText: TextView

    private val lockSpeedHint: LinearLayout
    private val lockSpeedHintText: TextView

    private val tagsScroller: HorizontalScrollView
    private val tagsContainer: LinearLayout

    private val fullscreenBtn: ImageView
    private var isFullscreen = false

    var onBack: (() -> Unit)? = null
    var onFullscreen: (() -> Unit)? = null
    var onBookmark: (() -> Unit)? = null
    var onJump: (() -> Unit)? = null
    var onTagLongPress: ((TimelineTagEntity) -> Unit)? = null

    private var controllerVisible = false
    private var currentSpeed = 1f
    // G8（用户拍板）：初始默认静音——setPlayer 时按本位压 volume=0
    private var isMuted = true
    private var isGestureDragging = false
    private var gestureType = GESTURE_NONE
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var seekStartPos = 0L
    private var seekDelta = 0L
    private var isSeekbarDragging = false
    private var wasPlayingBeforeSeek = false
    private var isLongPressing = false
    private var speedBeforeLongPress = 1f
    private var isSpeedLocked = false

    val currentPositionMs: Long
        get() = playerView.player?.currentPosition ?: 0L

    val currentPositionFormatted: String
        get() = formatMs(currentPositionMs)

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayPauseButton()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                val d = duration
                if (d > 0) totalTimeText.text = formatMs(d)
            } else if (playbackState == Player.STATE_ENDED) {
                // G9：播完强制显示控制器（再点播放由 togglePlayPause/startPlayback seekTo(0)）
                showController(true)
            }
        }
    }

    private val hideControllerRunnable = Runnable { showController(false) }
    private val hideTopIndicatorRunnable = Runnable {
        topIndicator.isVisible = false
    }

    private companion object {
        const val GESTURE_NONE = 0
        const val GESTURE_PROGRESS = 1
        const val HIDE_DELAY = 5000L
        const val TOP_INDICATOR_DELAY = 1500L
        const val LONG_PRESS_SPEED = 2f
        /** 播放器倍速选中色（固定蓝色，深色/浅色主题下一致） */
        const val SPEED_SELECTED_COLOR = 0xFF4FC3F7.toInt()
        /** 播放器倍速弹窗背景色（固定深色，深色/浅色主题下一致） */
        const val SPEED_POPUP_BG_COLOR = 0xDD222222.toInt()
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            performClick()
            if (isFullscreen) {
                // 横屏：单击显隐控制器
                showController(!controllerVisible)
            } else {
                // 竖屏：单击切换播放/暂停
                togglePlayPause()
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (isFullscreen) {
                // 横屏：双击切换播放/暂停
                togglePlayPause()
            }
            // 竖屏：双击无功能
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (isLongPressing) return
            if (isSpeedLocked) {
                // 已锁定倍速时，长按显示退出提示，拖到下方退出锁定
                isLongPressing = true
                isUnlockingSpeed = true
                speedBeforeLongPress = currentSpeed
                // 期间保持2x不变
                showTopIndicator("长按拖动退出倍速")
                if (isFullscreen) {
                    // 横屏长按时隐藏UI
                    showController(false)
                } else {
                    lockSpeedHintText.text = "⬇ 拖动到此处退出倍速"
                    lockSpeedHint.isVisible = true
                }
                removeCallbacks(hideControllerRunnable)
                return
            }
            if (currentSpeed == LONG_PRESS_SPEED) return
            isLongPressing = true
            speedBeforeLongPress = currentSpeed
            playerView.player?.setPlaybackSpeed(LONG_PRESS_SPEED)
            currentSpeed = LONG_PRESS_SPEED
            updateSpeedButtonText()
            showTopIndicator("长按倍速 ${LONG_PRESS_SPEED}x")
            if (isFullscreen) {
                // 横屏长按时隐藏UI
                showController(false)
            } else {
                lockSpeedHintText.text = "⬇ 拖动到此处锁定倍速"
                lockSpeedHint.isVisible = true
            }
            removeCallbacks(hideControllerRunnable)
            if (!controllerVisible && !isFullscreen) {
                showController(true)
            }
        }
    })

    init {
        playerView = PlayerView(context).apply {
            useController = false
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        addView(playerView)

        gestureIndicator = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24.dp(context), 20.dp(context), 24.dp(context), 20.dp(context))
            setBackgroundColor(0xB3000000.toInt())
            visibility = View.GONE
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
        }
        gestureText = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
        }
        gestureIndicator.addView(gestureText)
        addView(gestureIndicator)

        topIndicator = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(16.dp(context), 8.dp(context), 16.dp(context), 8.dp(context))
            setBackgroundColor(0x66000000)
            visibility = View.GONE
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = 60.dp(context)
            }
        }
        topIndicatorText = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
        }
        topIndicator.addView(topIndicatorText)
        addView(topIndicator)

        lockSpeedHint = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(16.dp(context), 10.dp(context), 16.dp(context), 10.dp(context))
            setBackgroundColor(0x66000000)
            visibility = View.GONE
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = 100.dp(context)
            }
        }
        lockSpeedHintText = TextView(context).apply {
            text = "⬇ 拖动到此处锁定倍速"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
        }
        lockSpeedHint.addView(lockSpeedHintText)
        addView(lockSpeedHint)

        topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp(context), 10.dp(context), 12.dp(context), 6.dp(context))
            setBackgroundColor(0x88000000.toInt())
            visibility = View.GONE
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP
            }
        }
        backBtn = ImageView(context).apply {
            setImageResource(R.drawable.ic_detail_back)
            setPadding(9.dp(context), 9.dp(context), 9.dp(context), 9.dp(context))
            layoutParams = LinearLayout.LayoutParams(40.dp(context), 40.dp(context))
            setColorFilter(Color.WHITE)
            setOnClickListener { onBack?.invoke() }
        }
        topBar.addView(backBtn)
        addView(topBar)

        bottomBar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(context), 8.dp(context), 16.dp(context), 12.dp(context))
            setBackgroundColor(0x88000000.toInt())
            visibility = View.GONE
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
            }
        }

        // 时间轴标签横向滚动区域
        tagsScroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 4.dp(context)
            }
        }
        tagsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        tagsScroller.addView(tagsContainer)
        bottomBar.addView(tagsScroller)

        val progressRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        currentTimeText = TextView(context).apply {
            text = "00:00"
            setTextColor(Color.WHITE)
            textSize = 12f
        }
        progressBar = SeekBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 8.dp(context)
                marginEnd = 8.dp(context)
            }
            max = 1000
            progressDrawable?.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            thumb?.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val d = duration
                        if (d > 0) {
                            currentTimeText.text = formatMs(progress.toLong() * d / 1000)
                        }
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    isSeekbarDragging = true
                    wasPlayingBeforeSeek = playerView.player?.isPlaying == true
                    removeCallbacks(hideControllerRunnable)
                }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    isSeekbarDragging = false
                    val d = duration
                    if (d > 0) {
                        val pos = (seekBar?.progress?.toLong() ?: 0) * d / 1000
                        playerView.player?.seekTo(pos)
                    }
                    if (wasPlayingBeforeSeek) {
                        playerView.player?.play()
                    }
                    postDelayed(hideControllerRunnable, HIDE_DELAY)
                }
            })
        }
        totalTimeText = TextView(context).apply {
            text = "00:00"
            setTextColor(Color.WHITE)
            textSize = 12f
        }
        progressRow.addView(currentTimeText)
        progressRow.addView(progressBar)
        progressRow.addView(totalTimeText)
        bottomBar.addView(progressRow)

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4.dp(context), 0, 0)
        }
        playPauseBtn = ImageView(context).apply {
            setImageResource(R.drawable.ic_player_play)
            setPadding(4.dp(context), 4.dp(context), 4.dp(context), 4.dp(context))
            layoutParams = LinearLayout.LayoutParams(36.dp(context), 36.dp(context))
            setColorFilter(Color.WHITE)
            setOnClickListener { togglePlayPause() }
        }
        muteBtn = ImageView(context).apply {
            setImageResource(R.drawable.ic_player_mute)
            setPadding(4.dp(context), 4.dp(context), 4.dp(context), 4.dp(context))
            layoutParams = LinearLayout.LayoutParams(36.dp(context), 36.dp(context))
            setColorFilter(Color.WHITE)
            setOnClickListener { toggleMute() }
        }
        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        val timelineTagBtn = ImageView(context).apply {
            setImageResource(R.drawable.ic_timeline_tag)
            setPadding(4.dp(context), 4.dp(context), 4.dp(context), 4.dp(context))
            layoutParams = LinearLayout.LayoutParams(36.dp(context), 36.dp(context)).apply {
                marginStart = 8.dp(context)
            }
            setColorFilter(Color.WHITE)
            setOnClickListener { onBookmark?.invoke() }
        }
        speedBtn = TextView(context).apply {
            text = "倍速"
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(8.dp(context), 4.dp(context), 8.dp(context), 4.dp(context))
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = 8.dp(context)
            }
            setOnClickListener { showSpeedPopup() }
        }
        val quickJumpBtn = ImageView(context).apply {
            setImageResource(R.drawable.ic_detail_jump)
            setPadding(4.dp(context), 4.dp(context), 4.dp(context), 4.dp(context))
            layoutParams = LinearLayout.LayoutParams(36.dp(context), 36.dp(context)).apply {
                marginStart = 8.dp(context)
            }
            setColorFilter(Color.WHITE)
            setOnClickListener { onJump?.invoke() }
        }
        fullscreenBtn = ImageView(context).apply {
            setImageResource(R.drawable.ic_player_fullscreen)
            setPadding(4.dp(context), 4.dp(context), 4.dp(context), 4.dp(context))
            layoutParams = LinearLayout.LayoutParams(36.dp(context), 36.dp(context))
            setColorFilter(Color.WHITE)
            setOnClickListener {
                // G6 冻结口径：全屏按钮仅横屏视频可点；竖屏视频点按只提示不改方向
                if (isLandscapeVideo()) {
                    onFullscreen?.invoke()
                } else {
                    showTopIndicator(context.getString(R.string.detail_video_fullscreen_portrait_unsupported))
                }
            }
        }
        buttonRow.addView(playPauseBtn)
        buttonRow.addView(muteBtn)
        buttonRow.addView(spacer)
        buttonRow.addView(timelineTagBtn)
        buttonRow.addView(speedBtn)
        buttonRow.addView(quickJumpBtn)
        buttonRow.addView(fullscreenBtn)
        bottomBar.addView(buttonRow)

        addView(bottomBar)
    }

    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            val player = playerView.player
            // v1.16 修复：player 未挂载时不再排下一轮，避免永久空轮询；
            // setPlayer() 与 onAttachedToWindow() 均会重新 post 恢复轮询
            if (player == null) return
            // v1.15：手势拖拽期间也跳过轮询更新（原只跳过 seekbar 拖动，横滑手势拖进度时
            // isSeekbarDragging 为 false，200ms 轮询用 player.currentPosition 覆盖手势预览，
            // 导致进度条抖动、seek 落点被回写）
            if (!isSeekbarDragging && !isGestureDragging) {
                val pos = player.currentPosition
                val d = duration
                if (d > 0) {
                    progressBar.progress = ((pos * 1000) / d).toInt()
                    currentTimeText.text = formatMs(pos)
                }
            }
            postDelayed(this, 200)
        }
    }

    fun setPlayer(player: ExoPlayer) {
        playerView.player?.removeListener(playerListener)
        playerView.player = player
        player.addListener(playerListener)
        // G8：挂载即按静音位压 volume（搬运件 isMuted 初始 true → 首挂即静音）
        player.volume = if (isMuted) 0f else 1f
        // v1.15：恢复当前倍速——原实现只恢复音量，锁定过 2x 后新播放器实际 1x 但 UI 显示 2x，状态脱节
        player.setPlaybackSpeed(currentSpeed)
        removeCallbacks(updateProgressRunnable)
        post(updateProgressRunnable)
    }

    fun setVideoUri(uri: String) {
        val player = playerView.player as? ExoPlayer ?: return
        player.setMediaItem(androidx.media3.common.MediaItem.fromUri(uri))
        player.prepare()
    }

    fun startPlayback() {
        val player = playerView.player as? ExoPlayer ?: return
        // v1.15：播完后再点播放需先 seek 回开头（与 togglePlayPause 行为对齐），否则保持 ENDED 态不播
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0)
        }
        player.playWhenReady = true
        showController(true)
    }

    fun pausePlayback() {
        val player = playerView.player as? ExoPlayer ?: return
        player.pause()
    }

    fun isPlaying(): Boolean = playerView.player?.isPlaying == true

    private val duration: Long
        get() {
            val d = playerView.player?.duration ?: 0
            return if (d == C.TIME_UNSET) 0L else d
        }

    private fun togglePlayPause() {
        val player = playerView.player ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) {
                player.seekTo(0)
            }
            player.play()
        }
        showController(true)
    }

    private fun updatePlayPauseButton() {
        val player = playerView.player ?: return
        playPauseBtn.setImageResource(
            if (player.isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play
        )
    }

    private fun toggleMute() {
        isMuted = !isMuted
        playerView.player?.volume = if (isMuted) 0f else 1f
        muteBtn.setImageResource(if (isMuted) R.drawable.ic_player_mute else R.drawable.ic_player_unmute)
        showTopIndicator(if (isMuted) "🔇 已静音" else "🔊 已开启声音")
        showController(true)
    }

    // G7 倍速档位表/按钮文案已抽为同包纯函数（PLAYER_SPEED_TIERS/speedButtonText，行为不变）
    private var speedPopup: PopupWindow? = null

    private fun showSpeedPopup() {
        speedPopup?.dismiss()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SPEED_POPUP_BG_COLOR)
            setPadding(4.dp(context), 4.dp(context), 4.dp(context), 4.dp(context))
        }
        for (tier in PLAYER_SPEED_TIERS) {
            val isSelected = currentSpeed == tier.speed
            val item = TextView(context).apply {
                text = tier.menuLabel
                setTextColor(if (isSelected) SPEED_SELECTED_COLOR else Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(20.dp(context), 10.dp(context), 20.dp(context), 10.dp(context))
                setOnClickListener {
                    currentSpeed = tier.speed
                    isSpeedLocked = currentSpeed != 1f
                    playerView.player?.setPlaybackSpeed(currentSpeed)
                    updateSpeedButtonText()
                    speedPopup?.dismiss()
                    showController(true)
                }
            }
            container.addView(item)
        }
        // 先测量容器高度
        container.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val popupHeight = container.measuredHeight
        val popup = PopupWindow(container, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            isFocusable = true
            isOutsideTouchable = true
            setBackgroundDrawable(null)
            setOnDismissListener { speedPopup = null }
        }
        speedPopup = popup
        // 从倍速按钮位置往上弹出
        popup.showAsDropDown(speedBtn, 0, -(speedBtn.height + popupHeight))
    }

    private fun updateSpeedButtonText() {
        speedBtn.text = speedButtonText(currentSpeed)
    }

    private var isLockHintHovered = false
    private var isUnlockingSpeed = false // 正在通过长按退出倍速锁定

    private fun updateLockHintHoverState(hovered: Boolean) {
        if (isLockHintHovered == hovered) return
        isLockHintHovered = hovered
        if (hovered) {
            lockSpeedHint.setBackgroundColor(0xAAFFFFFF.toInt())
            lockSpeedHintText.setTextColor(Color.BLACK)
        } else {
            lockSpeedHint.setBackgroundColor(0x66000000)
            lockSpeedHintText.setTextColor(Color.WHITE)
        }
    }

    private fun showController(visible: Boolean) {
        controllerVisible = visible
        topBar.isVisible = visible
        bottomBar.isVisible = visible
        if (visible) {
            removeCallbacks(hideControllerRunnable)
            postDelayed(hideControllerRunnable, HIDE_DELAY)
        } else {
            removeCallbacks(hideControllerRunnable)
            speedPopup?.dismiss()
        }
    }

    private fun showTopIndicator(text: String) {
        topIndicatorText.text = text
        topIndicator.isVisible = true
        removeCallbacks(hideTopIndicatorRunnable)
        postDelayed(hideTopIndicatorRunnable, TOP_INDICATOR_DELAY)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (playerView.player == null) return false
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureStartX = event.x
                gestureStartY = event.y
                isGestureDragging = false
                gestureType = GESTURE_NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (isLongPressing && !isFullscreen) {
                    val rect = Rect()
                    val isOverHint = lockSpeedHint.getGlobalVisibleRect(rect) &&
                        rect.contains(event.rawX.toInt(), event.rawY.toInt())
                    updateLockHintHoverState(isOverHint)
                }
                if (isLongPressing) {
                    // 长按期间不启动手势拖拽（防止进度条拖动）
                } else {
                    val dx = event.x - gestureStartX
                    val dy = event.y - gestureStartY
                    if (!isGestureDragging && (abs(dx) > 24.dp(context) || abs(dy) > 24.dp(context))) {
                        isGestureDragging = true
                        if (abs(dx) > abs(dy)) {
                            gestureType = GESTURE_PROGRESS
                            seekStartPos = playerView.player?.currentPosition ?: 0
                            seekDelta = 0
                        } else {
                            isGestureDragging = false
                        }
                        showController(false)
                    }
                    if (isGestureDragging) {
                        when (gestureType) {
                            GESTURE_PROGRESS -> {
                                val d = duration
                                // v1.15：duration 未就绪（流未 READY）时忽略手势 seek，避免 seekTo(0)
                                if (d <= 0) {
                                    // 无时长信息，直接结束本次拖拽
                                } else {
                                    // G4：自适应上限抽为纯函数 gestureSeekCapMs（三档口径单测锁定）
                                    val maxSeekMs = gestureSeekCapMs(d)
                                    seekDelta = ((dx / width) * maxSeekMs).toLong()
                                    val newPos = max(0L, min(seekStartPos + seekDelta, d))
                                    val sign = if (seekDelta >= 0) "+" else ""
                                    gestureText.text = "$sign${formatMs(seekDelta)}  ${formatMs(newPos)} / ${formatMs(d)}"
                                    gestureIndicator.isVisible = true
                                    progressBar.progress = ((newPos * 1000) / d).toInt()
                                    currentTimeText.text = formatMs(newPos)
                                    bottomBar.isVisible = true
                                    playerView.player?.seekTo(newPos)
                                }
                            }
                            GESTURE_NONE -> { isGestureDragging = false }
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isLongPressing) {
                    if (isUnlockingSpeed) {
                        // 退出倍速锁定的长按
                        if (isLockHintHovered && !isFullscreen) {
                            // 拖到下方，退出锁定回到1x
                            isSpeedLocked = false
                            currentSpeed = 1f
                            playerView.player?.setPlaybackSpeed(1f)
                            updateSpeedButtonText()
                            showTopIndicator("已退出倍速锁定")
                        }
                        // 否则松手什么都不做，保持当前倍速
                    } else if (isLockHintHovered && !isFullscreen) {
                        // 锁定倍速在2x
                        isSpeedLocked = true
                        currentSpeed = LONG_PRESS_SPEED
                        updateSpeedButtonText()
                        showTopIndicator("已锁定 ${LONG_PRESS_SPEED}x 倍速")
                    } else {
                        currentSpeed = speedBeforeLongPress
                        playerView.player?.setPlaybackSpeed(currentSpeed)
                        updateSpeedButtonText()
                    }
                    lockSpeedHint.isVisible = false
                    isLockHintHovered = false
                    isUnlockingSpeed = false
                    isLongPressing = false
                    if (!isFullscreen) {
                        showController(true)
                    }
                }
                if (isGestureDragging) {
                    when (gestureType) {
                        GESTURE_PROGRESS -> {
                            val newPos = max(0L, min(seekStartPos + seekDelta, duration))
                            playerView.player?.seekTo(newPos)
                        }
                    }
                    gestureIndicator.isVisible = false
                    if (controllerVisible) {
                        showController(true)
                    } else {
                        bottomBar.isVisible = false
                    }
                }
                isGestureDragging = false
                gestureType = GESTURE_NONE
            }
        }
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (playerView.player != null) {
            // v1.15：detach 时移除的 listener 需在重新 attach 后补挂，
            // 否则视图复用场景播放/暂停图标不再更新、STATE_ENDED 不再弹控制器
            playerView.player?.addListener(playerListener)
            post(updateProgressRunnable)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(updateProgressRunnable)
        removeCallbacks(hideControllerRunnable)
        removeCallbacks(hideTopIndicatorRunnable)
        speedPopup?.dismiss()
        playerView.player?.removeListener(playerListener)
    }

    /** 更新时间轴标签显示（数据源随 3d 接线；类型=剥离 Room 后的同包纯数据类） */
    fun updateTimelineTags(tags: List<TimelineTagEntity>) {
        tagsContainer.removeAllViews()
        if (tags.isEmpty()) {
            tagsScroller.visibility = View.GONE
            return
        }
        tagsScroller.visibility = View.VISIBLE
        for (tag in tags) {
            val chip = createTagChip(tag)
            tagsContainer.addView(chip)
        }
    }

    /** 创建单个标签芯片，预设标签使用对应色调背景 */
    private fun createTagChip(tag: TimelineTagEntity): TextView {
        // 判定口径与 TimelineTagColors.colorFor 同源（裸 ❤/⭐ 前缀 startsWith）：
        // 同时命中带/不带 U+FE0F 变体选择符两种写法——此前这里用裸字面量 "❤️" 判定，
        // 手输无变体符的 ❤ 标签取色命中红而芯片底色不命中（2026-09-07 审查 P2），已收敛。
        val isLike = tag.name.startsWith(TimelineTagColors.HEART_PREFIX)
        val isFav = tag.name.startsWith(TimelineTagColors.STAR_PREFIX)
        val chipBg = when {
            isLike -> R.drawable.bg_timeline_tag_like
            isFav -> R.drawable.bg_timeline_tag_fav
            else -> R.drawable.bg_timeline_tag_chip
        }
        return TextView(context).apply {
            text = "${formatMs(tag.timeMillis)} ${tag.name}"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(10.dp(context), 5.dp(context), 10.dp(context), 5.dp(context))
            setBackgroundResource(chipBg)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = 6.dp(context)
            }
            setOnClickListener {
                val player = playerView.player ?: return@setOnClickListener
                player.seekTo(tag.timeMillis)
            }
            // 长按弹出操作菜单，而非直接删除
            setOnLongClickListener {
                onTagLongPress?.invoke(tag)
                true
            }
        }
    }

    /** 跳转到指定位置 */
    fun seekToPosition(pos: Long) {
        playerView.player?.seekTo(pos)
    }

    /** 视频是否为横屏（宽>高） */
    fun isLandscapeVideo(): Boolean {
        val player = playerView.player ?: return false
        val w = player.videoSize.width
        val h = player.videoSize.height
        return w > 0 && h > 0 && w > h
    }

    /** 设置全屏状态，更新按钮图标，横屏时默认隐藏控制器，处理导航栏内边距 */
    fun setFullscreen(fullscreen: Boolean) {
        isFullscreen = fullscreen
        fullscreenBtn.setImageResource(
            if (fullscreen) R.drawable.ic_player_fullscreen_exit else R.drawable.ic_player_fullscreen
        )
        if (fullscreen) {
            showController(false)
        }
        // 横屏全屏时为底部栏添加导航栏内边距，防止被系统导航栏遮挡
        applyBottomBarInsets()
    }

    /** 根据全屏状态应用底部栏的系统导航栏内边距 */
    private fun applyBottomBarInsets() {
        if (isFullscreen) {
            ViewCompat.setOnApplyWindowInsetsListener(bottomBar) { v, insets ->
                val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, 12.dp(context) + navBar.bottom)
                insets
            }
            // 立即触发一次 insets 应用
            ViewCompat.requestApplyInsets(bottomBar)
        } else {
            ViewCompat.setOnApplyWindowInsetsListener(bottomBar) { v, insets ->
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, 12.dp(context))
                insets
            }
            bottomBar.setPadding(bottomBar.paddingLeft, bottomBar.paddingTop, bottomBar.paddingRight, 12.dp(context))
        }
    }

    /** 当前是否处于全屏状态 */
    fun getFullscreen(): Boolean = isFullscreen

    internal fun formatMs(ms: Long): String {
        val absMs = abs(ms)
        val totalSec = absMs / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        val prefix = if (ms < 0) "-" else ""
        // 固定使用 Locale.US，确保时长始终输出拉丁数字（避免非拉丁 locale 下显示异常字符）
        val locale = java.util.Locale.US
        return if (h > 0) "$prefix$h:${String.format(locale, "%02d", m)}:${String.format(locale, "%02d", s)}"
        else "$prefix${m}:${String.format(locale, "%02d", s)}"
    }

}

/** dp 转 px（旧项目 ui/widget/DimenExt.kt 同款实现，仅本文件 View 世界使用，随搬运件文件私有化） */
private fun Int.dp(context: Context): Int =
    (this * context.resources.displayMetrics.density).toInt()
