package media.qimeng.app.core.data.repository

import media.qimeng.app.core.model.AssetDetail
import media.qimeng.app.core.model.DetailAuthor
import media.qimeng.app.core.model.DetailTag
import media.qimeng.app.core.model.LikeToggleResult
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.model.TagChip
import media.qimeng.sdk.models.AssetDetail as SdkAssetDetail
import media.qimeng.sdk.models.Author
import media.qimeng.sdk.models.LikeState
import media.qimeng.sdk.models.MediaType
import media.qimeng.sdk.models.Tag

/** SDK DTO → 详情领域模型映射（:core:data 独占 internal；UI/ViewModel 不得接触 SDK 类型，ADR-0014 分层）。 */
internal object SdkDetailMappers {

    /**
     * 详情映射（字段取子集，口径逐条对照 [SdkMappers.toMediaAsset] 同名属性）。
     * @param baseUrl 服务端根地址：thumbUrl/origUrl 是签名相对路径（/media/...），
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
        thumbUrl = detail.thumbUrl?.let { absolutize(it, baseUrl) },
        origUrl = detail.origUrl?.let { absolutize(it, baseUrl) },
        durationMs = detail.durationMs,
        cosWork = detail.cosWork,
        // 协议断点续播位置为 BigDecimal（秒），客户端统一 Double 消费（3b 播放器起点）
        lastPositionSeconds = detail.lastPositionSeconds?.toDouble(),
        viewCount = detail.viewCount,
        playCount = detail.playCount,
        width = detail.width,
        height = detail.height,
        tags = detail.tags.orEmpty().map(::toDetailTag),
        authors = detail.authors.orEmpty().map(::toDetailAuthor),
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

    private fun MediaType?.toDomainMediaKind(): MediaKind = when (this) {
        MediaType.animated_image -> MediaKind.ANIMATED_IMAGE
        MediaType.video -> MediaKind.VIDEO
        else -> MediaKind.IMAGE
    }

    /**
     * 与 SdkMappers.absolutize 同语义；并行边界禁改对方文件（M4-2A 已声明改动集），
     * 故在此复制 5 行小函数——合并后可收拢回单源（两个文件头注释互指）。
     */
    private fun absolutize(pathOrUrl: String, baseUrl: String): String =
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
            pathOrUrl
        } else {
            baseUrl.trimEnd('/') + pathOrUrl
        }
}
