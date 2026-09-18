package media.qimeng.app.core.model

/**
 * 资产详情领域模型（M4-3，映射 SDK AssetDetail 的详情页所需子集）。
 * 排版基准 = Web 现版 AssetDetailPage.tsx（B站式，2026-09-05 拍板）的移动端移植。
 *
 * @param title 详情标题：cosWork 优先、回退 fileName（由 mapper 算好，与列表卡同口径）
 * @param thumbUrl 已拼 base 的签名缩略图绝对直链（签名 URL 禁改写，ADR-0002）
 * @param thumbUrlMd 已拼 base 的签名缩略图 md 档（512）绝对直链（可空口径与 thumbUrl 一致）。
 *   协议批 2026-09-18：md 档与列表网格同源、服务端开机预生成，详情海报先用本档立即出图、
 *   thumbUrl（lg）就绪后换上——lg 不在预生成范围，首开详情直等 lg 会触发现场 ffmpeg 生成
 *   （秒级起步）。null = 旧服务端/异常，UI 退化为直接 lg（现状行为）
 * @param origUrl 已拼 base 的签名原件绝对直链（"查看永远发原件"；3a 占位未消费，3b 播放器/原图用）
 * @param lastPositionSeconds 断点续播位置秒（协议 BigDecimal → Double；已看完判定在 3b/3c 消费）
 * @param tags 详情标签（服务端按关联时间倒序返回——「最近添加置顶」，LEGACY §A）
 * @param authors 详情作者完整对象（关注态随行）
 * @param directory 当前所在目录（库内相对路径，'' = 库根；文件整理弹窗预填当前目录，任务G G1b）
 * @param relPath 库内相对路径（任务X X3 详细信息 Sheet「路径」行；空 = 服务端未返回不渲染）
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
    /** md 档缩略图（512，预生成）；海报 md 先行策略见类 KDoc，null 退化直接 lg */
    val thumbUrlMd: String? = null,
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
    /** 当前所在目录（库内相对路径，null/空串 = 库根；协议 AssetDetail.directory） */
    val directory: String? = null,
    /**
     * 库内相对路径（协议 AssetDetail.relPath；任务X X3 详细信息 Sheet「路径」行数据源）。
     * SDK 可空兜底空串；空 = 服务端未返回，UI 沿用「null/0 不渲染」口径隐藏行
     */
    val relPath: String = "",
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
 * @param color 协议镜像字段：服务端存储的十六进制颜色（如 "#d6336c"；N3 协议批 P2 #32）。
 *   App 显示已不消费（2026-09-12 用户拍板「时间轴标签颜色对齐旧版」S1a：芯片恒按
 *   TimelineTagColors 前缀档，消费链已在 VideoStage 映射层断开）；字段保留供协议完整性，
 *   映射层仍透传（SdkDetailMappers 断言不动）。
 */
data class TimelineTag(
    val id: String,
    val timeMillis: Long,
    val name: String,
    val color: String? = null,
)

/**
 * 行为打点事件类别（POST /events/view 的 kind 枚举；DOMAIN_RULES §5 底层 ViewEvent 事件流）。
 * open=进入详情页（同会话一次）、play=起播（同会话一次）、dwell=停留时长（带 seconds）。
 * 服务端按 assetId+kind+sessionId+当日 会话级去重。
 */
enum class ViewEventKind { OPEN, PLAY, DWELL }
