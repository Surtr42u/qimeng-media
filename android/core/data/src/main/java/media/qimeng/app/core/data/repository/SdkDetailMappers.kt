package media.qimeng.app.core.data.repository

import media.qimeng.app.core.model.AssetDetail
import media.qimeng.app.core.model.DetailAuthor
import media.qimeng.app.core.model.DetailTag
import media.qimeng.app.core.model.LikeToggleResult
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.model.TagChip
import media.qimeng.app.core.model.TimelineTag
import media.qimeng.app.core.model.ViewEventKind
import media.qimeng.sdk.models.ApiV1AssetsAssetIdTimelineTagsPostRequest
import media.qimeng.sdk.models.AssetDetail as SdkAssetDetail
import media.qimeng.sdk.models.Author
import media.qimeng.sdk.models.LikeState
import media.qimeng.sdk.models.MediaType
import media.qimeng.sdk.models.ProgressUpdate
import media.qimeng.sdk.models.Tag
import media.qimeng.sdk.models.TimelineTag as SdkTimelineTag
import media.qimeng.sdk.models.ViewEventReport

/** SDK DTO → 详情领域模型映射（:core:data 独占 internal；UI/ViewModel 不得接触 SDK 类型，ADR-0014 分层）。 */
internal object SdkDetailMappers {

    /**
     * 详情映射（字段取子集，口径逐条对照 [SdkMappers.toMediaAsset] 同名属性）。
     * @param baseUrl 服务端根地址：thumbUrl/thumbUrlMd/origUrl 是签名相对路径（/media/...），
     *   必须一次性拼成绝对直链——签名 URL 禁止客户端改写任何参数（ADR-0002）。
     */
    fun toAssetDetail(detail: SdkAssetDetail, baseUrl: String): AssetDetail = AssetDetail(
        id = detail.id?.toString().orEmpty(),
        fileName = detail.fileName.orEmpty(),
        // 标题口径与列表卡一致：cosWork 优先、回退 fileName（协议 AssetSummary.cosWork 注释）
        title = detail.cosWork ?: detail.fileName.orEmpty(),
        mediaType = detail.mediaType.toDomainMediaKind(),
        sizeBytes = detail.sizeBytes,
        modifiedAtMs = detail.modifiedAt?.toInstant()?.toEpochMilli(),
        source = detail.source,
        isFavorite = detail.isFavorite ?: false,
        likeCount = detail.likeCount ?: 0,
        likedToday = detail.likedToday ?: false,
        thumbUrl = detail.thumbUrl?.let { SdkMappers.absolutize(it, baseUrl) },
        // md 档（512，预生成）与 lg 同款绝对化：详情海报 md 先行消费源（协议批 2026-09-18）
        thumbUrlMd = detail.thumbUrlMd?.let { SdkMappers.absolutize(it, baseUrl) },
        origUrl = detail.origUrl?.let { SdkMappers.absolutize(it, baseUrl) },
        durationMs = detail.durationMs,
        cosWork = detail.cosWork,
        // 断点续播位置为 Double 秒（协议批 2026-09-09：format:double 后 SDK
        // 原生 Double，不再经 BigDecimal 转换），客户端统一 Double 消费（3b 播放器起点）
        lastPositionSeconds = detail.lastPositionSeconds,
        viewCount = detail.viewCount,
        playCount = detail.playCount,
        width = detail.width,
        height = detail.height,
        tags = detail.tags.orEmpty().map(::toDetailTag),
        authors = detail.authors.orEmpty().map(::toDetailAuthor),
        // 库内相对路径原样透传（文件整理弹窗预填；null = 库根，任务G G1b）
        directory = detail.directory,
        // 库内相对路径（任务X X3 详细信息 Sheet「路径」行）；SDK 可空兜底空串 = 服务端未返回
        relPath = detail.relPath.orEmpty(),
    )

    fun toDetailTag(tag: Tag): DetailTag = DetailTag(
        id = tag.id.orEmpty(),
        name = tag.name.orEmpty(),
    )

    fun toDetailAuthor(author: Author): DetailAuthor = DetailAuthor(
        id = author.id.orEmpty(),
        displayName = author.displayName.orEmpty(),
        isCos = author.type == Author.Type.cos,
        followed = author.followed ?: false,
    )

    fun toTagChip(tag: Tag): TagChip = TagChip(
        id = tag.id.orEmpty(),
        name = tag.name.orEmpty(),
    )

    fun toLikeToggleResult(state: LikeState): LikeToggleResult = LikeToggleResult(
        likedToday = state.likedToday ?: false,
        likeCount = state.likeCount ?: 0,
    )

    // ---------------- M4-3 3d：进度 / 打点 / 时间轴标签 ----------------

    /** 时间轴标签映射（SDK 可空字段兜底：timeMillis 协议 required，null 仅防御性归 0；
     *  color 空串归 null=无服务端颜色，客户端走前缀推断保底） */
    fun toTimelineTag(tag: SdkTimelineTag): TimelineTag = TimelineTag(
        id = tag.id.orEmpty(),
        timeMillis = tag.timeMillis ?: 0L,
        name = tag.name.orEmpty(),
        color = tag.color?.takeIf { it.isNotEmpty() },
    )

    /** 时间轴标签列表映射 */
    fun toTimelineTags(tags: List<SdkTimelineTag>): List<TimelineTag> = tags.map(::toTimelineTag)

    /** 进度上报请求体（Double 秒直传；协议批 2026-09-09 #26 根修后 positionSeconds 为原生 Double，moshi 序列化为 JSON 数字） */
    fun toProgressUpdate(positionSeconds: Double): ProgressUpdate =
        ProgressUpdate(positionSeconds = positionSeconds)

    /** 新建时间轴标签请求体 */
    fun toAddTimelineTagRequest(timeMillis: Long, name: String): ApiV1AssetsAssetIdTimelineTagsPostRequest =
        ApiV1AssetsAssetIdTimelineTagsPostRequest(timeMillis = timeMillis, name = name)

    /**
     * 行为打点报告体（POST /events/view）。startedAt 统一转 UTC OffsetDateTime
     * （客户端本地时区只用于展示，打点存绝对时刻）。
     * clientEventId（任务L L5）：协议幂等键，调用方在事件产生时生成并随本地暂存
     * 持久，重试/导出携带同一 id（服务端唯一索引幂等，重发不双计）。
     */
    fun toViewEventReport(
        assetId: String,
        kind: ViewEventKind,
        startedAtMs: Long,
        sessionId: String,
        clientEventId: String,
        dwellSeconds: Long?,
    ): ViewEventReport = ViewEventReport(
        assetId = java.util.UUID.fromString(assetId),
        kind = when (kind) {
            ViewEventKind.OPEN -> ViewEventReport.Kind.`open`
            ViewEventKind.PLAY -> ViewEventReport.Kind.play
            ViewEventKind.DWELL -> ViewEventReport.Kind.dwell
        },
        startedAt = java.time.OffsetDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(startedAtMs),
            java.time.ZoneOffset.UTC,
        ),
        sessionId = sessionId,
        clientEventId = java.util.UUID.fromString(clientEventId),
        seconds = dwellSeconds?.toDouble(),
    )

    private fun MediaType?.toDomainMediaKind(): MediaKind = when (this) {
        MediaType.animated_image -> MediaKind.ANIMATED_IMAGE
        MediaType.video -> MediaKind.VIDEO
        else -> MediaKind.IMAGE
    }

    // U11 小债清偿批：原 5 行复制（M4-2A 并行边界期临时）已收拢回 SdkMappers.absolutize 单源
}
