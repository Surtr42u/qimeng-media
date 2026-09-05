package media.qimeng.app.core.model

/** 列表排序键（协议 GET /assets sort 枚举的领域侧镜像；协议侧改动须同步此处） */
enum class AssetSort {
    DEFAULT,
    FILE_DATE,
    ADDED_DATE,
    VIEW_COUNT,
    PLAY_COUNT,
    SIZE_BYTES,
    NAME,
    FAVORITE_AT,
}

/** 排序方向 */
enum class SortOrder { ASC, DESC }

/**
 * GET /assets 请求参数包（领域侧；各列表页筛选状态机的输出）。
 * 分区三态不在这里出现——由调用方按页面口径展开为 includeCos/cosOnly
 * （/assets 与 /history 的缺省方向不同，见 [Zone] 注释）。
 */
data class AssetQuery(
    val cursor: String? = null,
    val limit: Int? = null,
    val includeCos: Boolean? = null,
    val cosOnly: Boolean? = null,
    val mediaType: MediaKind? = null,
    val source: String? = null,
    val authorId: String? = null,
    val character: String? = null,
    val work: String? = null,
    val favorite: Boolean? = null,
    val q: String? = null,
    val sort: AssetSort = AssetSort.DEFAULT,
    val order: SortOrder = SortOrder.DESC,
)

/** GET /assets/facets 请求参数包（partition 恒显式传——不依赖服务端缺省的隐式行为） */
data class FacetsQuery(
    val partition: Zone,
    val mediaType: MediaKind? = null,
    val source: String? = null,
    val authorId: String? = null,
    val character: String? = null,
    val work: String? = null,
    val q: String? = null,
    /** 收藏页子集约束（favorite=1） */
    val favorite: Boolean? = null,
    /** 历史页子集约束（history=1） */
    val history: Boolean? = null,
)

/** GET /history 请求参数包（cursor 分页；服务端缺省 includeCos=true=全部） */
data class HistoryQuery(
    val cursor: String? = null,
    val limit: Int? = null,
    val includeCos: Boolean? = null,
    val cosOnly: Boolean? = null,
    val mediaType: MediaKind? = null,
    val source: String? = null,
    val authorId: String? = null,
    val character: String? = null,
    val work: String? = null,
)

/** 排行榜周期四档（协议 period 六档， quarter/all 不展示——规格书 §首页 日/周/月/年） */
enum class RankingPeriod(val apiValue: String, val label: String) {
    DAY("day", "日榜"),
    WEEK("week", "周榜"),
    MONTH("month", "月榜"),
    YEAR("year", "年榜"),
}
