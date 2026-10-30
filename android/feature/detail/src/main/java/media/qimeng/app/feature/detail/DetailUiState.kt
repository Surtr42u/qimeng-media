package media.qimeng.app.feature.detail

import media.qimeng.app.core.model.AssetDetail
import media.qimeng.app.core.model.TagChip
import media.qimeng.app.feature.detail.playback.WatchState
import media.qimeng.app.core.model.TimelineTag

/**
 * 详情页 UI 状态（3a 骨架）。错误反馈照 home 的 errorMessage 横幅模式；
 * asset 为 null 且 isLoading=false 即「无内容可恢复」态（加载失败/路由缺参）。
 */
data class DetailUiState(
    val isLoading: Boolean = true,
    val asset: AssetDetail? = null,
    val errorMessage: String? = null,
    /** 当前资产在批次清单中的序号（0 基；-1 = 无批次上下文，序号区不显示） */
    val batchIndex: Int = -1,
    /** 批次清单大小（「i / N」的 N） */
    val batchSize: Int = 0,
    /** 标签管理弹窗：全量标签池（服务端名字序）与勾选集合 */
    val tagPool: List<TagChip> = emptyList(),
    val selectedTagIds: List<String> = emptyList(),
    val tagSheetOpen: Boolean = false,
    val savingTags: Boolean = false,
    /** 解绑在途的标签 id 集（N4 I7b：chip 关闭图标=立即 DELETE；同 id 在途防重） */
    val unbindingTagIds: List<String> = emptyList(),
    /** 互动行请求进行中（按钮 disabled 态） */
    val likePending: Boolean = false,
    val favoritePending: Boolean = false,
    /** 关注 toggle 进行中的作者 id（该行按钮 disabled；null = 无在途关注请求） */
    val followPendingAuthorId: String? = null,
    /**
     * 预加载目标（3b 拍板③窗口：前 1 后 2，距当前最近序）。url 取窗口内邻位详情——
     * 图片=origUrl 原图、视频=thumbUrl 海报帧；url 缺失的邻位不出现在列表里。
     * Coil 入队/取消在 UI 层（DetailScreen DisposableEffect），VM 只发目标清单。
     */
    val preloadTargets: List<DetailPreloadTarget> = emptyList(),
    /**
     * 视频续播起点毫秒（3d，[WatchState] 冻结口径）：未看完 = 断点秒×1000；
     * 已看完 = 0（重播语义）。图片资产恒 0（不被消费）。
     */
    val videoStartPositionMs: Long = 0L,
    /** 已看完徽标（3d，[WatchState] 冻结口径：lastPositionSeconds 与 durationMs 齐备且 ≥ 才真） */
    val videoWatched: Boolean = false,
    /** 时间轴标签（3d，仅视频资产加载；GET 按 timeMillis 升序，服务端排序） */
    val timelineTags: List<TimelineTag> = emptyList(),
    // ---------- 文件操作（任务G G1b：整理/删除） ----------
    /** 整理弹窗开关（条件挂载——打开即从当前 directory/fileName 惰性复位预填值） */
    val moveSheetOpen: Boolean = false,
    /** 删除确认弹窗开关 */
    val deleteConfirmOpen: Boolean = false,
    /** 文件操作请求进行中（两弹窗确认钮与互动行两钮 disabled 同源） */
    val fileOpsPending: Boolean = false,
    /** 整理弹窗内失败文案（null=无失败；弹窗内独立展示——错误横幅在弹窗遮罩后不可见） */
    val moveError: String? = null,
)

/**
 * 单个预加载目标（3b）。isVideo 决定 UI 侧请求形态：图片按原图尺寸（口径②不降采样），
 * 视频海报帧是小缩略图按默认档即可。
 */
data class DetailPreloadTarget(
    val assetId: String,
    val url: String,
    val isVideo: Boolean,
    val sizeBytes: Long? = null,
    val dims: ImageDims? = null,
)
