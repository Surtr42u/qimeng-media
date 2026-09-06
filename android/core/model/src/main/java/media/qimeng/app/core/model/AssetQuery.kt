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
 * viewRange 以下字段为 M4-2A-B3 万能筛选面板新增（协议侧改动须同步此处与 :core:data SDK 映射）。
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
    /** 观看次数档（协议 viewRange=CountRange；null=不传） */
    val viewRange: PanelCountRange? = null,
    /** 点击次数档（协议 playRange=CountRange；null=不传） */
    val playRange: PanelCountRange? = null,
    /** 文件大小档（协议 sizeRange=SizeRange；null=不传） */
    val sizeRange: PanelSizeRange? = null,
    /** 文件日期区间起/止（协议 date 类型；时间范围档展开，null=不传） */
    val dateFrom: java.time.LocalDate? = null,
    val dateTo: java.time.LocalDate? = null,
    /** 按年份筛选起/止（integer；仅「按年份」档传，null=不传） */
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    /** 选中标签 id 集（协议 tagIds；null/空=不传） */
    val tagIds: List<String>? = null,
    /** 标签匹配模式（协议 tagMode；仅随 tagIds 一起传） */
    val tagMode: PanelTagMode? = null,
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
