package media.qimeng.app.core.model

/**
 * 资产详情领域模型（M4-3，映射 SDK AssetDetail 的详情页所需子集）。
 * 排版基准 = Web 现版 AssetDetailPage.tsx（B站式，2026-09-05 拍板）的移动端移植。
 *
 * @param title 详情标题：cosWork 优先、回退 fileName（由 mapper 算好，与列表卡同口径）
 * @param thumbUrl 已拼 base 的签名缩略图绝对直链（签名 URL 禁改写，ADR-0002）
 * @param origUrl 已拼 base 的签名原件绝对直链（"查看永远发原件"；3a 占位未消费，3b 播放器/原图用）
 * @param lastPositionSeconds 断点续播位置秒（协议 BigDecimal → Double；已看完判定在 3b/3c 消费）
 * @param tags 详情标签（服务端按关联时间倒序返回——「最近添加置顶」，LEGACY §A）
 * @param authors 详情作者完整对象（关注态随行）
 */
data class AssetDetail(
    val id: String,
    val fileName: String,
    val title: String,
    val mediaType: MediaKind,
    val sizeBytes: Long?,
    val modifiedAtMs: Long?,
    val source: String?,
    val isFavorite: Boolean,
    val likeCount: Int,
    /** 当日是否已点赞（本地日历日，每资产每日一次；点赞按钮初始态，协议注释明文） */
    val likedToday: Boolean,
    val thumbUrl: String?,
    val origUrl: String?,
    val durationMs: Long?,
    val cosWork: String?,
    val lastPositionSeconds: Double?,
    val viewCount: Int?,
    val playCount: Int?,
    val width: Int?,
    val height: Int?,
    val tags: List<DetailTag>,
    val authors: List<DetailAuthor>,
)

/** 详情标签（详情/弹窗共用；id 是关联操作主键） */
data class DetailTag(val id: String, val name: String)

/** 详情作者（isCos 对应 SDK Author.Type.cos，展示追加「 ·COS」——Web authorDisplayName 同口径） */
data class DetailAuthor(
    val id: String,
    val displayName: String,
    val isCos: Boolean,
    val followed: Boolean,
)

/** 全量标签池条目（GET /tags；详情弹窗「其他标签」勾选候选） */
data class TagChip(val id: String, val name: String)

/** 点赞 toggle 结果（PUT /assets/{id}/like → LikeState；服务端权威值回填防本地猜测） */
data class LikeToggleResult(val likedToday: Boolean, val likeCount: Int)

/**
 * 时间轴标签（M4-3 3d，GET/POST /assets/{id}/timeline-tags）。
 * id 为服务端生成主键（DELETE /assets/{id}/timeline-tags/{tagId} 用）；POST 返回体带回。
 * @param timeMillis 标签指向播放位置（毫秒，从 0 起算）
 */
data class TimelineTag(val id: String, val timeMillis: Long, val name: String)

/**
 * 行为打点事件类别（POST /events/view 的 kind 枚举；DOMAIN_RULES §5 底层 ViewEvent 事件流）。
 * open=进入详情页（同会话一次）、play=起播（同会话一次）、dwell=停留时长（带 seconds）。
 * 服务端按 assetId+kind+sessionId+当日 会话级去重。
 */
enum class ViewEventKind { OPEN, PLAY, DWELL }
