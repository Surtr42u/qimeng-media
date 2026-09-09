package media.qimeng.app.core.model

import java.time.LocalDate

/**
 * 四维筛选的维度行键（芯片栏顺序：分区/作品/角色/类型）。
 * 标签 = 旧版「全部」页芯片栏逐字口径（旧版实录 all_partition.txt「分区 (2)/作品 (61)/
 * 角色 (294)/类型 (3)」，M4-2A-B2 拍板：完全复刻优先于 Web 相册页的「作者/角色·作品」字样）。
 * 「角色」行 = 常规角色 ∪ COS 作品合并一行（kind 分派）。
 */
enum class AlbumDim(val label: String) {
    PARTITION("分区"),
    AUTHOR("作品"),
    CHARACTER("角色"),
    TYPE("类型"),
}

/**
 * 相册/收藏/历史共用的四维筛选状态机（纯 Kotlin，无 IO；单测锁定行为）。
 *
 * 四维 = 分区(Zone) / 作者行(出处∪COS 作者，kind 分派 source|authorId) /
 * 角色行(角色∪COS 作品，kind 分派 character|work) / 类型(MediaKind)。
 * 联动规则（拍板口径）：
 * - 切分区清空作者/角色选择（两行候选的 kind 命名空间随分区变化）；
 * - 行内点已选 = 取消；行前置「全部」胶囊 = 清本行（等价于取消选中）；
 * - **作者/角色行多选**（N4 消费批，GUIDE_UI §浏览历史「支持多选作品筛选」同款
 *   旧版语义；协议批 #29 起同维参数数组化）：同维内 OR、与其余维 AND；多选
 *   不退出（点药丸不收起面板）、收起即时响应（选择即重拉列表）；
 * - 进页药丸容器默认收起（用户 2026-09-07 覆盖 B8「切换到新模式时默认展开」）；
 *   切维度行仍强制展开——旧仓库实录四 Fragment（AllFiles/Favorite/BrowseHistory 等
 *   setViewMode）齐证切维即展示药丸，进页默认态按新拍板与旧版不同（非缺陷）。
 */
data class AlbumFilterState(
    val partition: Zone = Zone.ALL,
    /**
     * 作者行多选集（出处∪COS 作者混选，「其他」桶可混选）。
     * 集内按 key+kind 判同（FacetOption.fileCount 随 facets 刷新变化，不参与判等——
     * toggle 时先剔同名旧条目再入集，集合内同 key+kind 恒唯一）。
     */
    val authors: Set<FacetOption> = emptySet(),
    /** 角色行多选集（角色∪COS 作品混选；判同口径同 [authors]） */
    val characters: Set<FacetOption> = emptySet(),
    val mediaType: MediaKind? = null,
    /** 药丸容器展开态（进页默认收起，用户 2026-09-07 覆盖 B8；切维度行时由调用方重置为 true） */
    val expanded: Boolean = false,
    // ---- 万能筛选面板字段（M4-2A-B3；编辑态/映射语义见 AlbumPanelFilter.kt，默认值=协议缺省不传） ----

    /** 排序键（面板「排序方式」七选；协议 sort） */
    val sort: AssetSort = AssetSort.DEFAULT,
    /** 顺位（面板「顺位」二选；协议 order） */
    val order: SortOrder = SortOrder.DESC,
    /** 观看次数档（协议 viewRange；ALL=不传） */
    val viewRange: PanelCountRange = PanelCountRange.ALL,
    /** 点击次数档（协议 playRange；ALL=不传） */
    val playRange: PanelCountRange = PanelCountRange.ALL,
    /** 文件大小档（协议 sizeRange；ALL=不传） */
    val sizeRange: PanelSizeRange = PanelSizeRange.ALL,
    /** 时间范围档（协议 dateFrom/dateTo 或 yearFrom/yearTo；ALL=不传） */
    val dateRange: PanelDateRange = PanelDateRange.ALL,
    /** 标签匹配模式（协议 tagMode；仅 tagIds 非空时传） */
    val tagMode: PanelTagMode = PanelTagMode.FUZZY,
    /** 选中标签 id 集（协议 tagIds；空=不传） */
    val tagIds: List<String> = emptyList(),
    /** 按年份筛选起始/结束年（仅 dateRange=YEAR_RANGE 且两者齐备时传） */
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
)

object AlbumFilter {

    /** 切分区：清作者/角色两行下级选择（类型保留——类型与分区正交） */
    fun selectPartition(state: AlbumFilterState, zone: Zone): AlbumFilterState =
        if (zone == state.partition) state
        else state.copy(partition = zone, authors = emptySet(), characters = emptySet())

    /** 点作者行候选：已选（key+kind 同）则取消，否则入集（多选 OR，不退出）；null=清本行（「全部」胶囊） */
    fun selectAuthor(state: AlbumFilterState, option: FacetOption?): AlbumFilterState =
        state.copy(authors = if (option == null) emptySet() else toggleMembership(state.authors, option))

    /** 点角色行候选：语义同 [selectAuthor] */
    fun selectCharacter(state: AlbumFilterState, option: FacetOption?): AlbumFilterState =
        state.copy(characters = if (option == null) emptySet() else toggleMembership(state.characters, option))

    fun selectMediaType(state: AlbumFilterState, kind: MediaKind?): AlbumFilterState =
        state.copy(mediaType = if (state.mediaType == kind) null else kind)

    /** 激活判定：作者/角色行必须 key+kind 双匹配（出处与 COS 作者可能同名） */
    fun isAuthorActive(state: AlbumFilterState, option: FacetOption): Boolean =
        state.authors.any { it.key == option.key && it.kind == option.kind }

    fun isCharacterActive(state: AlbumFilterState, option: FacetOption): Boolean =
        state.characters.any { it.key == option.key && it.kind == option.kind }

    /**
     * 状态 → GET /assets 参数（相册页口径）：
     * 分区 全部=显式 includeCos=true / 常规=不传 / COS=cosOnly=true；
     * 作者行按 kind 分派 source|authorId，角色行分派 character|work。
     * source/character/work 多选数组（协议批 #29：同维 OR）——按 kind 从多选集
     * 投影为 key 列表；authorId 协议仍单值（无数组位），>1 个 COS 作者同选时
     * 降级不传（列表放宽，非错误）——单选语义完全向后兼容。
     * 排序/顺位/观看/点击/大小/时间/标签 = 万能筛选面板字段展开（M4-2A-B3）：
     * 默认档一律映射为不传（viewRange/playRange/sizeRange=ALL→null、tagIds 空→null、
     * dateRange=ALL→不传日期；YEAR_RANGE→yearFrom/yearTo 且起止交叉归一 start=min/end=max，
     * 旧版 footer 应用时交叉校验口径）；[today] 注入时间档区间计算（纯函数可单测）。
     */
    fun toAssetQuery(
        state: AlbumFilterState,
        limit: Int? = null,
        cursor: String? = null,
        today: LocalDate = LocalDate.now(),
    ): AssetQuery {
        val dateBounds = panelDateRangeBounds(state.dateRange, today)
        // 按年份：起止交叉归一（旧版 buildFooter 口径 start=min/end=max）；年份不全=不传
        val years: Pair<Int, Int>? =
            if (state.dateRange == PanelDateRange.YEAR_RANGE && state.yearFrom != null && state.yearTo != null) {
                minOf(state.yearFrom, state.yearTo) to maxOf(state.yearFrom, state.yearTo)
            } else {
                null
            }
        return AssetQuery(
            cursor = cursor,
            limit = limit,
            includeCos = if (state.partition == Zone.ALL) true else null,
            cosOnly = if (state.partition == Zone.COS) true else null,
            mediaType = state.mediaType,
            source = state.authors.keysOfKind(FacetParamKind.SOURCE),
            authorId = state.authors.singleKeyOfKind(FacetParamKind.AUTHOR),
            character = state.characters.keysOfKind(FacetParamKind.CHARACTER),
            work = state.characters.keysOfKind(FacetParamKind.WORK),
            sort = state.sort,
            order = state.order,
            viewRange = state.viewRange.toQuery(),
            playRange = state.playRange.toQuery(),
            sizeRange = state.sizeRange.toQuery(),
            dateFrom = dateBounds?.first,
            dateTo = dateBounds?.second,
            yearFrom = years?.first,
            yearTo = years?.second,
            tagIds = state.tagIds.ifEmpty { null },
            tagMode = if (state.tagIds.isEmpty()) null else state.tagMode.toQuery(),
        )
    }

    /**
     * 四维候选请求（排自身计数）：每个请求缺自身维度的选择参数，
     * partition 恒显式传（不依赖服务端缺省=all 的隐式行为——拍板口径）。
     * 收藏页加 favorite 子集约束、历史页加 history（由调用方经 [subsetFavorite]/[subsetHistory] 变体）。
     * facets 协议参数为单值（source/character/authorId/work 均单字符串）——多选集
     * 恰好单选时照常传（保持既有跨维收窄），多选（>1）时该维收窄参数省略
     * （候选放宽为未约束计数，协议无多值位，记交付报告）。
     */
    fun partitionFacetsQuery(state: AlbumFilterState, favorite: Boolean? = null, history: Boolean? = null) = FacetsQuery(
        partition = state.partition,
        mediaType = state.mediaType,
        source = state.authors.singleKeyOfKind(FacetParamKind.SOURCE),
        authorId = state.authors.singleKeyOfKind(FacetParamKind.AUTHOR),
        character = state.characters.singleKeyOfKind(FacetParamKind.CHARACTER),
        work = state.characters.singleKeyOfKind(FacetParamKind.WORK),
        favorite = favorite,
        history = history,
    )

    fun authorFacetsQuery(state: AlbumFilterState, favorite: Boolean? = null, history: Boolean? = null) = FacetsQuery(
        partition = state.partition,
        mediaType = state.mediaType,
        character = state.characters.singleKeyOfKind(FacetParamKind.CHARACTER),
        work = state.characters.singleKeyOfKind(FacetParamKind.WORK),
        favorite = favorite,
        history = history,
    )

    fun characterFacetsQuery(state: AlbumFilterState, favorite: Boolean? = null, history: Boolean? = null) = FacetsQuery(
        partition = state.partition,
        mediaType = state.mediaType,
        source = state.authors.singleKeyOfKind(FacetParamKind.SOURCE),
        authorId = state.authors.singleKeyOfKind(FacetParamKind.AUTHOR),
        favorite = favorite,
        history = history,
    )

    fun typeFacetsQuery(state: AlbumFilterState, favorite: Boolean? = null, history: Boolean? = null) = FacetsQuery(
        partition = state.partition,
        source = state.authors.singleKeyOfKind(FacetParamKind.SOURCE),
        authorId = state.authors.singleKeyOfKind(FacetParamKind.AUTHOR),
        character = state.characters.singleKeyOfKind(FacetParamKind.CHARACTER),
        work = state.characters.singleKeyOfKind(FacetParamKind.WORK),
        favorite = favorite,
        history = history,
    )

    /**
     * 行内 toggle（多选语义）：已选（key+kind 同）则移除；未选则入集——
     * 入集前剔除同 key+kind 旧条目（facets 刷新后 fileCount 变化不影响判等）。
     */
    private fun toggleMembership(current: Set<FacetOption>, clicked: FacetOption): Set<FacetOption> {
        val rest = current.filterNot { it.key == clicked.key && it.kind == clicked.kind }.toSet()
        return if (rest.size == current.size) current + clicked else rest
    }
}

/** 多选集 → 协议数组参数：该 kind 的全部选中 key（空集=null=不传） */
private fun Set<FacetOption>.keysOfKind(kind: FacetParamKind): List<String>? =
    filter { it.kind == kind }.map { it.key }.ifEmpty { null }

/**
 * 多选集 → 协议单值参数（facets source/character/work 与 authorId 均单值位）：
 * 恰好单选时返回该 key；空集或多数选中=null（多选无法在单值位表达，降级不收窄）。
 */
private fun Set<FacetOption>.singleKeyOfKind(kind: FacetParamKind): String? =
    filter { it.kind == kind }.map { it.key }.singleOrNull()

/** 「全部」档 → null（不传参数=协议缺省全量语义）；其余档原样进查询包（SDK 枚举映射在 :core:data） */
private fun PanelCountRange.toQuery(): PanelCountRange? = if (this == PanelCountRange.ALL) null else this

private fun PanelSizeRange.toQuery(): PanelSizeRange? = if (this == PanelSizeRange.ALL) null else this

private fun PanelTagMode.toQuery(): PanelTagMode = this

/**
 * 分区三态 → GET /history 参数（历史页口径；与 /assets 方向不同）：
 * 全部=不传（服务端缺省 includeCos=true 已是全部）/ 常规=显式 includeCos=false / COS=cosOnly=true。
 */
fun zoneToHistoryParams(zone: Zone): Pair<Boolean?, Boolean?> = when (zone) {
    Zone.ALL -> null to null
    Zone.REGULAR -> false to null
    Zone.COS -> null to true
}
