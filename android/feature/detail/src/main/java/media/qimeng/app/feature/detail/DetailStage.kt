package media.qimeng.app.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.media3.common.util.UnstableApi
import media.qimeng.app.core.model.AssetDetail
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.model.TimelineTag

/**
 * 媒体舞台编排器（任务I I7 沉浸复刻改版）：舞台 = 第一屏 edge-to-edge 全出血媒体层
 * （GUIDE_UI §详情页 L158-160 沉浸 4 层组织的媒体层；G1a 时代的 68vh 排版钳制
 * DETAIL_STAGE_MAX_HEIGHT_FRACTION 随基准切回旧版退役），负责媒体类型分发——
 * 图片/动图 → [ImageStage]，视频 → [VideoStage]。
 *
 * 舞台盒恒黑底（L161 chrome 隐藏态始终黑底沉浸；媒体 contain 居中、黑边由舞台盒打底）：
 * - 图片：ZoomImageView fit-center 画法（configureBaseMatrix min(宽比,高比)+居中），
 *   填满整屏盒后竖图上下/横图左右留黑边，缩放手势链（0.5~5x/双击 1.8/智能分层 4096）不变；
 * - 视频：海报态缩略图 Fit 居中 + 播放态 BiliPlayerView 自适应 letterbox（行为同旧版，
 *   固定宽高比舞台退役——旧版媒体层恒全屏）。
 *
 * 舞台动作（沉浸开关/兄弟切换/播放态上报）与视频接线参数（续播起点/已看完/进度上报/
 * 打点/时间轴标签）维持具名参数逐层下发（3d 解冻拓扑不变，DetailScreen 单源）。
 */
@androidx.annotation.OptIn(UnstableApi::class) // VideoStage 桥接 Media3 @UnstableApi 面（BiliPlayerView），调用方显式 opt-in
@Composable
internal fun DetailMediaStage(
    asset: AssetDetail,
    /** 已看完徽标（3d WatchState 口径；仅 VideoStage 消费） */
    watched: Boolean,
    /** 视频续播起点毫秒（3d；仅 VideoStage 消费） */
    startPositionMs: Long,
    /** 时间轴标签（3d，仅视频资产有值；VideoStage 映射桥接实体） */
    timelineTags: List<TimelineTag>,
    modifier: Modifier,
    onSiblingNavigate: (delta: Int) -> Unit,
    /** 沉浸模式 chrome 开关回调（I7：图片态单击舞台切换；视频态由播放态镜像驱动） */
    onToggleChrome: () -> Unit,
    /** 视频播放器活动态上报（I7：海报态=false，播放/暂停/ENDED=true——chrome 让位播放器控制器） */
    onPlayerActiveChanged: (Boolean) -> Unit,
    /** 播放中按返回先退 chrome 浏览模式（L279）后 chrome 恢复显示的回调 */
    onExitToChromeBrowse: () -> Unit,
    /** 图片态解码失败覆盖层「返回」（RES #27；离开详情页 popBackStack 语义，仅图片分支消费） */
    onExitDetail: () -> Unit = {},
    /** 起播回调（3d 打点 play 用；图片舞台无此语义不传） */
    onPlaybackStarted: () -> Unit,
    /** 播放进度 tick 秒（3d 节流上报用） */
    onPositionChanged: (positionSeconds: Double) -> Unit,
    /** 时间轴标签添加（timeMillis=当前播放位置毫秒） */
    onAddTimelineTag: (timeMillis: Long, name: String) -> Unit,
    /** 时间轴标签删除（长按菜单入口） */
    onDeleteTimelineTag: (tagId: String) -> Unit,
) {
    if (asset.mediaType == MediaKind.VIDEO) {
        // 视频舞台（I7 全出血）：播放器自适应 letterbox（G1a 冻结口径不变）
        VideoStage(
            asset = asset,
            watched = watched,
            startPositionMs = startPositionMs,
            timelineTags = timelineTags,
            modifier = modifier.background(Color.Black),
            onSiblingNavigate = onSiblingNavigate,
            onToggleChrome = onToggleChrome,
            onPlayerActiveChanged = onPlayerActiveChanged,
            onExitToChromeBrowse = onExitToChromeBrowse,
            onPlaybackStarted = onPlaybackStarted,
            onPositionChanged = onPositionChanged,
            onAddTimelineTag = onAddTimelineTag,
            onDeleteTimelineTag = onDeleteTimelineTag,
        )
    } else {
        // 图片舞台（I7 全出血）：整屏黑底盒 + ZoomImageView fit-center（缩放手势链不变），
        // 单击切 chrome（沉浸主形态，D1 全屏覆盖层退役——裁决记档见 ImageStage KDoc）
        Box(
            modifier = modifier.background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            ImageStage(
                asset = asset,
                modifier = Modifier.fillMaxSize(),
                onSiblingNavigate = onSiblingNavigate,
                onToggleChrome = onToggleChrome,
                onExitDetail = onExitDetail,
            )
        }
    }
}
