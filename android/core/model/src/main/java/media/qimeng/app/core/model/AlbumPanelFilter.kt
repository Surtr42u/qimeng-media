package media.qimeng.app.core.model

import java.time.LocalDate

/**
 * 万能筛选面板（M4-2A-B3）的领域枚举与草稿模型。
 * 分区/文案逐字照旧版 uiautomator 实录（filter_sheet.txt / filter_sheet_rest.txt），
 * 计算口径照旧仓库 MediaFilterSheet.kt / MediaBrowserLogic.kt（只提取口径，不搬实现）。
 * 各枚举均为协议 GET /assets 对应 query 参数的领域侧镜像（协议侧改动须同步）。
 */

/**
 * 观看/点击次数共用五档（协议 CountRange：all/none/low/mid/high）。
 * ALL 在展开为 AssetQuery 时映射为 null（=不传该参数，即「全部」缺省语义）。
 */
enum class PanelCountRange {
    ALL,
    NONE,
    LOW,
    MID,
    HIGH,
}

/** 文件大小五档（协议 SizeRange：all/lt1m/m1to10/m10to50/gt50m；ALL 同上映射 null） */
enum class PanelSizeRange {
    ALL,
    LT_1M,
    M_1_TO_10,
    M_10_TO_50,
    GT_50M,
}

/** 标签匹配模式（协议 tagMode：fuzzy=含任一 / exact=含全部；仅 tagIds 非空时才传） */
enum class PanelTagMode {
    FUZZY,
    EXACT,
}

/**
 * 时间范围七档（旧版 MediaDateRange 全档照搬）。
 * TODAY/WEEK/MONTH/QUARTER/YEAR → dateFrom+dateTo（[AlbumDateRanges.bounds]）；
 * YEAR_RANGE → yearFrom+yearTo（integer 参数）；ALL → 不传。
 */
enum class PanelDateRange {
    ALL,
    TODAY,
    WEEK,
    MONTH,
    QUARTER,
    YEAR,
    YEAR_RANGE,
}

/**
 * 时间档 → 文件日期区间（纯函数；today 由调用方注入便于单测锁定）。
 *
 * 口径照旧仓库 MediaBrowserLogic.dateRangeBounds（相对「现在」的滚动窗口，非自然周/月）：
 * - TODAY = 当日 00:00 起（date 粒度即今天..今天）
 * - WEEK  = 过去 7 天（旧版 now-604_800_000ms，**不是**周一起始自然周）
 * - MONTH = 过去 30 天（旧版 now-2_592_000_000ms）
 * - QUARTER（近三月）= 过去 90 天（旧版 now-7_776_000_000ms，非自然月）
 * - YEAR（本年）= 过去 365 天（旧版 now-31_536_000_000ms，非自然年）
 * dateFrom/dateTo 为协议 date 类型（LocalDate）；ALL / YEAR_RANGE 不产生日期区间
 * （YEAR_RANGE 走 yearFrom/yearTo 参数，见 [AlbumFilter.toAssetQuery]）。
 */
fun panelDateRangeBounds(range: PanelDateRange, today: LocalDate): Pair<LocalDate, LocalDate>? =
    when (range) {
        PanelDateRange.ALL -> null
        PanelDateRange.TODAY -> today to today
        PanelDateRange.WEEK -> today.minusDays(OLD_WEEK_DAYS) to today
        PanelDateRange.MONTH -> today.minusDays(OLD_MONTH_DAYS) to today
        PanelDateRange.QUARTER -> today.minusDays(OLD_QUARTER_DAYS) to today
        PanelDateRange.YEAR -> today.minusDays(OLD_YEAR_DAYS) to today
        PanelDateRange.YEAR_RANGE -> null
    }

// 旧版滚动窗口天数（MediaBrowserLogic.kt 的毫秒常量换算，口径锁定依据，见上函数注释）
private const val OLD_WEEK_DAYS = 7L
private const val OLD_MONTH_DAYS = 30L
private const val OLD_QUARTER_DAYS = 90L
private const val OLD_YEAR_DAYS = 365L

/** 年份筛选下界（旧仓库 MediaFilterSheet.YEAR_MIN=1990：原写死 2010 导致更早媒体无法按年份筛选，v1.12 放宽） */
const val PANEL_MIN_YEAR = 1990

/**
 * 选「按年份」且无历史值时的起年缺省（P2-3 修正：镜像旧仓库 MediaFilterState 缺省
 * yearStart=2020、yearEnd=当前年——NumberPicker 初值即 2020..当前年，非双当前年）。
 */
const val PANEL_DEFAULT_YEAR_FROM = 2020

/**
 * 面板草稿（编辑态语义：打开面板 = 拷贝已应用值为草稿；面板内只改草稿；
 * 「应用筛选」= 草稿写入 [AlbumFilterState] 触发刷新；下滑/点外部关闭 = 丢弃草稿）。
 * 只含面板字段，不含四维芯片（分区/作者/角色/类型）与药丸展开态。
 */
data class AlbumPanelDraft(
    val sort: AssetSort = AssetSort.DEFAULT,
    val order: SortOrder = SortOrder.DESC,
    val viewRange: PanelCountRange = PanelCountRange.ALL,
    val playRange: PanelCountRange = PanelCountRange.ALL,
    val sizeRange: PanelSizeRange = PanelSizeRange.ALL,
    val dateRange: PanelDateRange = PanelDateRange.ALL,
    val tagMode: PanelTagMode = PanelTagMode.FUZZY,
    val tagIds: List<String> = emptyList(),
    /** 按年份筛选的起始/结束年（仅 dateRange=YEAR_RANGE 时展开为 yearFrom/yearTo 参数；两者须齐备才传） */
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
)

/** 已应用态 → 草稿（打开面板时拷贝） */
fun AlbumFilterState.panelDraft(): AlbumPanelDraft = AlbumPanelDraft(
    sort = sort,
    order = order,
    viewRange = viewRange,
    playRange = playRange,
    sizeRange = sizeRange,
    dateRange = dateRange,
    tagMode = tagMode,
    tagIds = tagIds,
    yearFrom = yearFrom,
    yearTo = yearTo,
)

/** 草稿 → 已应用态（「应用筛选」时只覆盖面板字段，四维芯片与展开态原样保留） */
fun AlbumFilterState.withPanelDraft(draft: AlbumPanelDraft): AlbumFilterState = copy(
    sort = draft.sort,
    order = draft.order,
    viewRange = draft.viewRange,
    playRange = draft.playRange,
    sizeRange = draft.sizeRange,
    dateRange = draft.dateRange,
    tagMode = draft.tagMode,
    tagIds = draft.tagIds,
    yearFrom = draft.yearFrom,
    yearTo = draft.yearTo,
)
