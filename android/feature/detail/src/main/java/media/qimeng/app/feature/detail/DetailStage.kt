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
 * 媒体舞台编排器（任务I I7 沉浸复刻改版；任务K K1 黑底污染清偿改底色口径）：舞台 =
 * 第一屏 edge-to-edge 全出血媒体层（GUIDE_UI §详情页 L158-160 沉浸 4 层组织的媒体层；
 * G1a 时代的 68vh 排版钳制 DETAIL_STAGE_MAX_HEIGHT_FRACTION 随基准切回旧版退役），
 * 负责媒体类型分发——图片/动图 → [ImageStage]，视频 → [VideoStage]。
 *
 * 舞台底色单源 [stageBackdropColor]（K1 对齐旧版 MediaDetailFragment 颜色口径，覆盖
 * I7「舞台盒恒黑底」旧口径——旧版舞台底透出主题色，恒黑导致日间主题整页黑底污染）：
 * - chrome 有效显示（默认浏览）：主题背景色（≈旧 qmColorBg，日 #FAFAFA / 夜 #1A1A1A，
 *   非纯黑）——媒体 contain 居中的 letterbox 边由主题底打底；
 * - 沉浸态（chrome 隐藏）或播放器活动期：纯黑（旧版 setChromeVisible(false) 显式 BLACK
 *   同款；视频播放中舞台盒同步黑，与 BiliPlayerView 自身黑 letterbox 无缝衔接）；
 * - 全屏覆盖层 Dialog 维持黑（FullscreenOverlayShell，拍板口径不动）。
 *
 * 图片：ZoomImageView fit-center 画法（configureBaseMatrix min(宽比,高比)+居中），
 * 填满整屏盒后竖图上下/横图左右留主题底边，缩放手势链（0.5~5x/双击 1.8/智能分层 4096）
 * 不变；视频：海报态缩略图 Fit 居中 + 播放态 BiliPlayerView 自适应 letterbox（行为同
 * 旧版，固定宽高比舞台退役——旧版媒体层恒全屏）。
 *
 * 舞台动作（沉浸开关/兄弟切换/播放态上报）与视频接线参数（续播起点/已看完/进度上报/
 * 打点/时间轴标签）维持具名参数逐层下发（3d 解冻拓扑不变，DetailScreen 单源）。
 */

/**
 * 舞台底色裁决单源（任务K K1，用户原话「黑色背景的详情页污染视觉」清偿）：chrome 有效
 * 显示 → 主题背景透传；沉浸（chrome 隐藏）或播放器活动（视频 PLAYING/ENDED，chrome 让位
 * 播放器控制器即 chromeEffective=false）→ 纯黑。纯函数无 Compose 依赖（Color 为纯 Kotlin
 * 值类），JVM 单测锁定见 StageBackdropTest；裁决点唯一在 DetailScreen，经 [DetailMediaStage]
 * 的 backdrop 参数逐层下发，子层禁止再自带底色（防口径分叉复发）。
 */
internal fun stageBackdropColor(
    chromeVisible: Boolean,
    playerActive: Boolean,
    themeBackground: Color,
): Color = if (chromeVisible && !playerActive) themeBackground else Color.Black

/**
 * 舞台全出血有效补偿裁决单源（任务K K3c D1 清偿，2026-09-10）：补偿值 = 详情页顶部
 * 「背板色填充条」的高度（画在滚动裁剪区外 [-comp,0]，沉浸态纯黑延伸到 y=0——机制见
 * DetailScreen D1 重做注）。沉浸期系统栏隐藏，实时 inset 在部分设备上归零，若补偿跟随
 * 实时值则垫条塌缩、壳层主题底色带露出（走查 k2-10b 实证 128px）。按态裁决：
 * - 系统栏可见：实时值——分屏/字号等真实 inset 变化即时响应，chrome 显示态行为不变；
 * - 沉浸态（栏已隐藏）：冻结在记忆的可见态值——垫条高度两态恒定；
 * - 可见态但实时值瞬时归零：回退记忆值——show()/hide() 的 inset 派发存在 1~2 帧滞后，
 *   裸跟实时会先塌缩再回位闪跳。
 * 纯函数无 Compose 依赖，JVM 单测锁定见 StageEdgeToEdgeCompensationTest；记忆值的采集
 * （实时值 >0 才刷新）在 DetailScreen 调用点。
 */
internal fun stageEdgeToEdgeCompensationPx(
    liveInsetPx: Int,
    rememberedVisibleInsetPx: Int,
    barsVisible: Boolean,
): Int = when {
    !barsVisible -> rememberedVisibleInsetPx
    liveInsetPx <= 0 -> rememberedVisibleInsetPx
    else -> liveInsetPx
}

/**
 * 舞台盒沉浸几何冻结单源（任务W W2，2026-09-11）：返回钉死在「系统栏可见态」的舞台
 * 盒高——chrome 显隐全程（含栏动画中间态）恒定，杜绝盒高随壳层 Scaffold innerPadding
 * 抖动引发的图片居中基准漂移（根因链与实测证据见 DetailScreen W2 注）。
 *
 * 口径：screenPx = 内容高 + status inset + nav inset（恒等式：内容区就是屏幕减两栏，
 * 栏动画期三项此消彼长逐帧不变）；稳定 inset = max(实时, 见过最大)——动画中间值
 * （小于稳定值）被压回稳定值，真实 inset 增长（字号/分屏）即时采纳。
 *
 * 各态验证（1080x2400、status=128、nav=63 例）：
 * - 可见稳定：2400 - max(128,128) - max(63,63) = 2209（=壳层内容区高，G1a 口径不变）；
 * - 隐藏稳定（两 inset 均归零设备）：2400 - 128 - 63 = 2209（恒定）；
 * - 隐藏稳定（status 回读滞留设备，如 API35 模拟器实测 128 不归零）：内容高 2272，
 *   screenPx 仍 2400 → 2209（恒定）；
 * - 显/隐动画中间态（inset 部分回传）：max 压回稳定值 → 2209（恒定）。
 * 纯函数无 Compose 依赖，JVM 单测锁定见 StageViewportHeightTest。
 */
internal fun stageImmersiveViewportHeightPx(
    liveContentHeightPx: Int,
    liveStatusBarTopPx: Int,
    liveNavBarBottomPx: Int,
    maxSeenStatusBarTopPx: Int,
    maxSeenNavBarBottomPx: Int,
): Int {
    val screenPx = liveContentHeightPx + liveStatusBarTopPx + liveNavBarBottomPx
    return screenPx - maxOf(liveStatusBarTopPx, maxSeenStatusBarTopPx) -
        maxOf(liveNavBarBottomPx, maxSeenNavBarBottomPx)
}

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
    /** 舞台底色（K1 单源：DetailScreen 经 stageBackdropColor 裁决后下发，本层不再自带颜色） */
    backdrop: Color,
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
        // 视频舞台（I7 全出血）：播放器自适应 letterbox（G1a 冻结口径不变）；底色随
        // backdrop 单源切换（K1：海报态透主题底，播放中转黑衔接播放器 letterbox）
        VideoStage(
            asset = asset,
            watched = watched,
            startPositionMs = startPositionMs,
            timelineTags = timelineTags,
            backdrop = backdrop,
            modifier = modifier.background(backdrop),
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
        // 图片舞台（I7 全出血）：整屏舞台盒打底 backdrop（K1 单源：chrome 显=主题底/
        // 沉浸=纯黑）+ ZoomImageView fit-center（缩放手势链不变），单击切 chrome
        //（沉浸主形态，D1 全屏覆盖层退役——裁决记档见 ImageStage KDoc）
        Box(
            modifier = modifier.background(backdrop),
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
