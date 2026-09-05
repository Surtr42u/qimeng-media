package media.qimeng.app.core.model

/** GET /assets 响应（cursor 分页） */
data class AssetPageResult(
    val items: List<MediaAsset>,
    val nextCursor: String?,
    val totalMatched: Int?,
)

/** GET /history 响应（cursor 分页） */
data class HistoryPageResult(
    val items: List<HistoryEntry>,
    val nextCursor: String?,
)

/** GET /assets/facets 响应（四维候选；「其他」桶置底由 UI 侧 withOtherBucketLast 兜底） */
data class FacetsResult(
    /** 分区三桶（key=all/regular/cos；「全部」计数供作者/角色行「全部」胶囊复用） */
    val partitions: List<FacetOption>,
    /** 作者行候选（kind=source 出处分组 ∪ kind=author COS 作者） */
    val authors: List<FacetOption>,
    /** 角色行候选（常规=kind=character ∪ COS=kind=work） */
    val characters: List<FacetOption>,
    /** 类型四桶（key=MediaType 枚举值/all，中文 name） */
    val types: List<FacetOption>,
)
