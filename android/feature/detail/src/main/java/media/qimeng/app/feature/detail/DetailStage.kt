package media.qimeng.app.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import media.qimeng.app.core.model.AssetDetail
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.model.TimelineTag
import media.qimeng.app.core.ui.component.THUMBNAIL_ASPECT_RATIO

/**
 * 图片舞台最大高度占屏比（任务G G1a）：来源 = Web prototype.css .asset-stage
 * max-height:68vh（媒体舞台黑底 contain，高度受视口约束）。竖图按真实宽高比撑宽高时
 * 会超一屏（旧版固定 aspectRatio 的根因），钳到本比例后上下留黑边（letterbox）。
 */
internal const val DETAIL_STAGE_MAX_HEIGHT_FRACTION = 0.68f

/**
 * 媒体舞台编排器（自 DetailSections.kt 拆出，3a 纯移动零行为变化）：
 * 负责宽高比自适应（width/height 齐备且 w>0、h>0 用真实比，否则回退 16:9 缩略比）与
 * 媒体类型分发——图片/动图 → [ImageStage]，视频 → [VideoStage]。
 * 真身替换节奏：3b 图片左右滑/沉浸、3c 视频播放器、**3d 解冻**：舞台动作（沉浸开关/
 * 兄弟切换）与视频接线参数（续播起点/已看完/进度上报/打点/时间轴标签）改为具名参数
 * 逐层下发（DetailScreen 单源 → 本编排器 → 各 Stage），3b 冻结期的
 * LocalDetailStageActions 穿墙通道随之删除（技术债清偿，见 DetailChrome.kt）。
 *
 * G1a 舞台高度钳制（图片独有，视频不动）：图片舞台从「固定 aspectRatio」改为
 * 「fillMaxWidth + heightIn(max=屏高×[DETAIL_STAGE_MAX_HEIGHT_FRACTION]) 黑底盒，
 * 内层内容盒守图片宽高比」——内层在盒约束下取 contain 尺寸（等价 Web object-fit:contain /
 * ContentScale.Fit），超出钳制比的竖图上下不再撑超屏、图片居中、黑边由舞台盒打底；
 * 横图高度=宽/比 < 钳制上限时舞台随图高收缩（Web 舞台 wrap img 同语义）。
 * 视频舞台保留固定 aspectRatio：播放器自适应已有 letterbox（G1a 方案基线，不动）。
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
    onSiblingNavigate: (delta: Int) -> Unit,
    onToggleChrome: () -> Unit,
    /** 图片全屏查看覆盖层开关（D1：排版态单击图片舞台打开；视频舞台不消费仍走 onToggleChrome） */
    onOpenFullScreen: () -> Unit,
    /** 起播回调（3d 打点 play 用；图片舞台无此语义不传） */
    onPlaybackStarted: () -> Unit,
    /** 播放进度 tick 秒（3d 节流上报用） */
    onPositionChanged: (positionSeconds: Double) -> Unit,
    /** 时间轴标签添加（timeMillis=当前播放位置毫秒） */
    onAddTimelineTag: (timeMillis: Long, name: String) -> Unit,
    /** 时间轴标签删除（长按菜单入口） */
    onDeleteTimelineTag: (tagId: String) -> Unit,
) {
    // 局部拷贝避免跨模块属性 smart cast 限制（width/height 在 :core:model）
    val w = asset.width
    val h = asset.height
    val ratio = if (w != null && h != null && w > 0 && h > 0) {
        w.toFloat() / h.toFloat()
    } else {
        THUMBNAIL_ASPECT_RATIO
    }
    if (asset.mediaType == MediaKind.VIDEO) {
        // 视频舞台（不动）：固定宽高比黑底舞台，播放器内部自适应 letterbox
        VideoStage(
            asset = asset,
            watched = watched,
            startPositionMs = startPositionMs,
            timelineTags = timelineTags,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .background(Color.Black),
            onSiblingNavigate = onSiblingNavigate,
            onToggleChrome = onToggleChrome,
            onPlaybackStarted = onPlaybackStarted,
            onPositionChanged = onPositionChanged,
            onAddTimelineTag = onAddTimelineTag,
            onDeleteTimelineTag = onDeleteTimelineTag,
        )
    } else {
        // 图片舞台（G1a 钳制）：外层黑底舞台盒钳高度上限，内层内容盒守图片宽高比——
        // aspectRatio 在盒的松约束（0..宽, 0..高）下自动取 contain 尺寸（竖图=高顶格宽收缩、
        // 横图=宽顶格），居中摆放，黑边由舞台盒露出（ZoomImageView configureBaseMatrix
        // 本就是 min(宽比,高比)+居中 的 fit-center 画法，contain 语义一致）
        val maxStageHeight = LocalConfiguration.current.screenHeightDp * DETAIL_STAGE_MAX_HEIGHT_FRACTION
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxStageHeight.dp)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            ImageStage(
                asset = asset,
                modifier = Modifier.aspectRatio(ratio),
                onSiblingNavigate = onSiblingNavigate,
                onOpenFullScreen = onOpenFullScreen,
            )
        }
    }
}
