package media.qimeng.app.core.data.repository

import media.qimeng.app.core.model.AssetSort
import media.qimeng.app.core.model.AuthorSummary
import media.qimeng.app.core.model.AuthorType
import media.qimeng.app.core.model.FacetOption
import media.qimeng.app.core.model.FacetParamKind
import media.qimeng.app.core.model.HistoryEntry
import media.qimeng.app.core.model.MediaAsset
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.model.NameSuggestion
import media.qimeng.app.core.model.RankingPeriod
import media.qimeng.app.core.model.SortOrder
import media.qimeng.app.core.model.SuggestionKind
import media.qimeng.app.core.model.Zone
import media.qimeng.sdk.apis.DefaultApi
import media.qimeng.sdk.models.AssetSummary
import media.qimeng.sdk.models.Author
import media.qimeng.sdk.models.FacetBucket
import media.qimeng.sdk.models.HistoryItem
import media.qimeng.sdk.models.MediaType
import media.qimeng.sdk.models.Partition
import media.qimeng.sdk.models.SearchSuggestion
import media.qimeng.sdk.models.SearchSuggestionType

/** SDK DTO → 领域模型映射（:core:data 独占；UI/ViewModel 不得接触 SDK 类型，ADR-0014 分层）。 */
internal object SdkMappers {

    /**
     * 列表资产映射。
     * @param baseUrl 服务端根地址：thumbUrl 是签名相对路径（/media/thumb/...），
     *   必须 here 一次性拼成绝对直链——签名 URL 禁止客户端改写任何参数（ADR-0002）。
     */
    fun toMediaAsset(summary: AssetSummary, baseUrl: String): MediaAsset = MediaAsset(
        id = summary.id?.toString().orEmpty(),
        fileName = summary.fileName.orEmpty(),
        title = summary.cosWork ?: summary.fileName.orEmpty(),
        mediaType = summary.mediaType.toDomainMediaKind(),
        thumbUrl = summary.thumbUrl?.let { absolutize(it, baseUrl) },
        source = summary.source,
        characters = summary.characters.orEmpty(),
        isFavorite = summary.isFavorite ?: false,
        authorNames = summary.authorNames.orEmpty(),
        modifiedAtMs = summary.modifiedAt?.toInstant()?.toEpochMilli(),
        durationMs = summary.durationMs,
        viewCount = summary.viewCount,
        playCount = summary.playCount,
        lastViewedAtMs = null,
    )

    fun toHistoryEntry(item: HistoryItem, baseUrl: String): HistoryEntry = HistoryEntry(
        asset = MediaAsset(
            id = item.id?.toString().orEmpty(),
            fileName = item.fileName.orEmpty(),
            title = item.cosWork ?: item.fileName.orEmpty(),
            mediaType = item.mediaType.toDomainMediaKind(),
            thumbUrl = item.thumbUrl?.let { absolutize(it, baseUrl) },
            source = item.source,
            characters = item.characters.orEmpty(),
            isFavorite = item.isFavorite ?: false,
            authorNames = item.authorNames.orEmpty(),
            modifiedAtMs = item.modifiedAt?.toInstant()?.toEpochMilli(),
            durationMs = item.durationMs,
            viewCount = item.viewCount,
            playCount = item.playCount,
            lastViewedAtMs = item.lastViewedAt,
        ),
    )

    fun toFacetOption(bucket: FacetBucket): FacetOption = FacetOption(
        key = bucket.key,
        name = bucket.name,
        fileCount = bucket.fileCount,
        kind = when (bucket.kind) {
            FacetBucket.Kind.author -> FacetParamKind.AUTHOR
            FacetBucket.Kind.character -> FacetParamKind.CHARACTER
            FacetBucket.Kind.work -> FacetParamKind.WORK
            FacetBucket.Kind.source -> FacetParamKind.SOURCE
            // 服务端老版本可能缺 kind：按出处分组兜底（作者行主要候选形态）
            null -> FacetParamKind.SOURCE
        },
    )

    fun toAuthorSummary(author: Author): AuthorSummary = AuthorSummary(
        id = author.id.orEmpty(),
        displayName = author.displayName.orEmpty(),
        type = when (author.type) {
            Author.Type.cos -> AuthorType.COS
            else -> AuthorType.REGULAR
        },
        fileCount = author.fileCount,
        followed = author.followed ?: false,
        viewCount = author.viewCount,
    )

    fun toNameSuggestion(suggestion: SearchSuggestion): NameSuggestion = NameSuggestion(
        name = suggestion.name,
        kind = when (suggestion.type) {
            SearchSuggestionType.character -> SuggestionKind.CHARACTER
            SearchSuggestionType.cosAuthor -> SuggestionKind.COS_AUTHOR
            SearchSuggestionType.cosWork -> SuggestionKind.COS_WORK
            SearchSuggestionType.author -> SuggestionKind.AUTHOR
            SearchSuggestionType.source -> SuggestionKind.SOURCE
        },
    )

    private fun MediaType?.toDomainMediaKind(): MediaKind = when (this) {
        MediaType.animated_image -> MediaKind.ANIMATED_IMAGE
        MediaType.video -> MediaKind.VIDEO
        else -> MediaKind.IMAGE
    }

    private fun absolutize(pathOrUrl: String, baseUrl: String): String =
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
            pathOrUrl
        } else {
            baseUrl.trimEnd('/') + pathOrUrl
        }
}

// ---- 领域枚举 → SDK 枚举（顶层扩展，同包免 import；协议枚举改动时此处编译期报漏） ----

internal fun MediaKind.toSdk(): MediaType = when (this) {
    MediaKind.IMAGE -> MediaType.image
    MediaKind.ANIMATED_IMAGE -> MediaType.animated_image
    MediaKind.VIDEO -> MediaType.video
}

internal fun Zone.toSdk(): Partition = when (this) {
    Zone.ALL -> Partition.all
    Zone.REGULAR -> Partition.regular
    Zone.COS -> Partition.cos
}

internal fun AssetSort.toSdk(): DefaultApi.SortApiV1AssetsGet = when (this) {
    AssetSort.DEFAULT -> DefaultApi.SortApiV1AssetsGet.default
    AssetSort.FILE_DATE -> DefaultApi.SortApiV1AssetsGet.fileDate
    AssetSort.ADDED_DATE -> DefaultApi.SortApiV1AssetsGet.addedDate
    AssetSort.VIEW_COUNT -> DefaultApi.SortApiV1AssetsGet.viewCount
    AssetSort.PLAY_COUNT -> DefaultApi.SortApiV1AssetsGet.playCount
    AssetSort.SIZE_BYTES -> DefaultApi.SortApiV1AssetsGet.sizeBytes
    AssetSort.NAME -> DefaultApi.SortApiV1AssetsGet.nameValue // 生成器因 Enum.name 冲突改名 nameValue
    AssetSort.FAVORITE_AT -> DefaultApi.SortApiV1AssetsGet.favoriteAt
}

internal fun SortOrder.toSdk(): DefaultApi.OrderApiV1AssetsGet = when (this) {
    SortOrder.ASC -> DefaultApi.OrderApiV1AssetsGet.asc
    SortOrder.DESC -> DefaultApi.OrderApiV1AssetsGet.desc
}

internal fun RankingPeriod.toSdk(): DefaultApi.PeriodApiV1RankingsGet = when (this) {
    RankingPeriod.DAY -> DefaultApi.PeriodApiV1RankingsGet.day
    RankingPeriod.WEEK -> DefaultApi.PeriodApiV1RankingsGet.week
    RankingPeriod.MONTH -> DefaultApi.PeriodApiV1RankingsGet.month
    RankingPeriod.YEAR -> DefaultApi.PeriodApiV1RankingsGet.year
}
