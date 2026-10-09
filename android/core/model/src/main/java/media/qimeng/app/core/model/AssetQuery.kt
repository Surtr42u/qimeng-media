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
    /** 出处分区多选（协议批 #29 数组化：同维 OR；「其他」桶可混选；null=不传） */
    val source: List<String>? = null,
    val authorId: String? = null,
    /** 角色多选（数组内 OR；每元素可含 'a+b' 组合；null=不传） */
    val character: List<String>? = null,
    /** COS 作品多选（数组内 OR；null=不传） */
    val work: List<String>? = null,
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
    /**
     * 请求服务端按本地日历日聚合的文件计数（协议 GET /assets dateCounts，
     * 2026-10-10 加）。日期分组页（相册/收藏/搜索/作者合集）传 true——
     * 分页只加载前若干条时，组头「今天 N 项」据此显示真实总数，不再拿
     * 已加载条数冒充（服务端仅首屏计算，翻页传了也忽略）。
     * 日界由设备时区决定，偏移量在 :core:data 的 SDK 边界统一补（见
     * SdkMediaRepository.assets 注释）——领域层不重复持有时区状态。
     */
    val dateCounts: Boolean? = null,
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

/** GET /history 请求参数包（cursor 分页；服务端缺省 includeCos=true=全部）。
 * source/character/work 数组参数（协议批 #29/#30：同维内 OR、与其余维 AND）；
 * source 位为 N4 消费批新开（历史页「作品」维行——出处分组多选）。 */
data class HistoryQuery(
    val cursor: String? = null,
    val limit: Int? = null,
    val includeCos: Boolean? = null,
    val cosOnly: Boolean? = null,
    val mediaType: MediaKind? = null,
    /** 出处分区多选（「其他」=无出处常规文件桶；null=不传） */
    val source: List<String>? = null,
    val authorId: String? = null,
    /** 角色多选（每元素 'a+b' 组合出镜组内 AND，数组内 OR；null=不传） */
    val character: List<String>? = null,
    /** COS 作品多选（null=不传） */
    val work: List<String>? = null,
)

/** 排行榜周期四档（协议 period 六档， quarter/all 不展示——规格书 §首页 日/周/月/年） */
enum class RankingPeriod(val apiValue: String, val label: String) {
    DAY("day", "日榜"),
    WEEK("week", "周榜"),
    MONTH("month", "月榜"),
    YEAR("year", "年榜"),
}
