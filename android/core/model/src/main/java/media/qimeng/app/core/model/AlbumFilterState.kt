package media.qimeng.app.core.model

/**
 * 四维筛选的维度行键（芯片栏顺序：分区/作者/角色·作品/类型——Web 相册页同款）。
 * 「角色·作品」= 常规角色 ∪ COS 作品合并一行（kind 分派）。
 */
enum class AlbumDim(val label: String) {
    PARTITION("分区"),
    AUTHOR("作者"),
    CHARACTER("角色·作品"),
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
 * - 切换维度行时药丸容器默认展开（B8：规格书 §药丸容器「切换到新模式时默认展开」，
 *   Web 现版自相矛盾处以规格书为准）。
 */
data class AlbumFilterState(
    val partition: Zone = Zone.ALL,
    val author: FacetOption? = null,
    val character: FacetOption? = null,
    val mediaType: MediaKind? = null,
    /** 药丸容器展开态（切维度行时由调用方重置为 true） */
    val expanded: Boolean = true,
)

object AlbumFilter {

    /** 切分区：清作者/角色两行下级选择（类型保留——类型与分区正交） */
    fun selectPartition(state: AlbumFilterState, zone: Zone): AlbumFilterState =
        if (zone == state.partition) state
        else state.copy(partition = zone, author = null, character = null)

    /** 点候选：已选（key+kind 同）则取消，否则选中 */
    fun selectAuthor(state: AlbumFilterState, option: FacetOption?): AlbumFilterState =
        state.copy(author = toggleSelection(state.author, option))

    fun selectCharacter(state: AlbumFilterState, option: FacetOption?): AlbumFilterState =
        state.copy(character = toggleSelection(state.character, option))

    fun selectMediaType(state: AlbumFilterState, kind: MediaKind?): AlbumFilterState =
        state.copy(mediaType = if (state.mediaType == kind) null else kind)

    /** 激活判定：作者/角色行必须 key+kind 双匹配（出处与 COS 作者可能同名） */
    fun isAuthorActive(state: AlbumFilterState, option: FacetOption): Boolean =
        state.author?.key == option.key && state.author?.kind == option.kind

    fun isCharacterActive(state: AlbumFilterState, option: FacetOption): Boolean =
        state.character?.key == option.key && state.character?.kind == option.kind

    /**
     * 状态 → GET /assets 参数（相册页口径）：
     * 分区 全部=显式 includeCos=true / 常规=不传 / COS=cosOnly=true；
     * 作者行按 kind 分派 source|authorId，角色行分派 character|work。
     * 排序 = 协议缺省 default 降序（2026-09-06 用户拍板：相册页=旧版「全部」页只改名，
     * 不引入 Web 相册页排序组等规格书没有的元素）。
     */
    fun toAssetQuery(state: AlbumFilterState, limit: Int? = null, cursor: String? = null): AssetQuery = AssetQuery(
        cursor = cursor,
        limit = limit,
        includeCos = if (state.partition == Zone.ALL) true else null,
        cosOnly = if (state.partition == Zone.COS) true else null,
        mediaType = state.mediaType,
        source = state.author?.takeIf { it.kind == FacetParamKind.SOURCE }?.key,
        authorId = state.author?.takeIf { it.kind == FacetParamKind.AUTHOR }?.key,
        character = state.character?.takeIf { it.kind == FacetParamKind.CHARACTER }?.key,
        work = state.character?.takeIf { it.kind == FacetParamKind.WORK }?.key,
        sort = AssetSort.DEFAULT,
        order = SortOrder.DESC,
    )

    /**
     * 四维候选请求（排自身计数）：每个请求缺自身维度的选择参数，
     * partition 恒显式传（不依赖服务端缺省=all 的隐式行为——拍板口径）。
     * 收藏页加 favorite 子集约束、历史页加 history（由调用方经 [subsetFavorite]/[subsetHistory] 变体）。
     */
    fun partitionFacetsQuery(state: AlbumFilterState, favorite: Boolean? = null, history: Boolean? = null) = FacetsQuery(
        partition = state.partition,
        mediaType = state.mediaType,
        source = state.author?.takeIf { it.kind == FacetParamKind.SOURCE }?.key,
        authorId = state.author?.takeIf { it.kind == FacetParamKind.AUTHOR }?.key,
        character = state.character?.takeIf { it.kind == FacetParamKind.CHARACTER }?.key,
        work = state.character?.takeIf { it.kind == FacetParamKind.WORK }?.key,
        favorite = favorite,
        history = history,
    )

    fun authorFacetsQuery(state: AlbumFilterState, favorite: Boolean? = null, history: Boolean? = null) = FacetsQuery(
        partition = state.partition,
        mediaType = state.mediaType,
        character = state.character?.takeIf { it.kind == FacetParamKind.CHARACTER }?.key,
        work = state.character?.takeIf { it.kind == FacetParamKind.WORK }?.key,
        favorite = favorite,
        history = history,
    )

    fun characterFacetsQuery(state: AlbumFilterState, favorite: Boolean? = null, history: Boolean? = null) = FacetsQuery(
        partition = state.partition,
        mediaType = state.mediaType,
        source = state.author?.takeIf { it.kind == FacetParamKind.SOURCE }?.key,
        authorId = state.author?.takeIf { it.kind == FacetParamKind.AUTHOR }?.key,
        favorite = favorite,
        history = history,
    )

    fun typeFacetsQuery(state: AlbumFilterState, favorite: Boolean? = null, history: Boolean? = null) = FacetsQuery(
        partition = state.partition,
        source = state.author?.takeIf { it.kind == FacetParamKind.SOURCE }?.key,
        authorId = state.author?.takeIf { it.kind == FacetParamKind.AUTHOR }?.key,
        character = state.character?.takeIf { it.kind == FacetParamKind.CHARACTER }?.key,
        work = state.character?.takeIf { it.kind == FacetParamKind.WORK }?.key,
        favorite = favorite,
        history = history,
    )

    private fun toggleSelection(current: FacetOption?, clicked: FacetOption?): FacetOption? =
        if (current != null && clicked != null && current.key == clicked.key && current.kind == clicked.kind) {
            null
        } else {
            clicked
        }
}

/**
 * 分区三态 → GET /history 参数（历史页口径；与 /assets 方向不同）：
 * 全部=不传（服务端缺省 includeCos=true 已是全部）/ 常规=显式 includeCos=false / COS=cosOnly=true。
 */
fun zoneToHistoryParams(zone: Zone): Pair<Boolean?, Boolean?> = when (zone) {
    Zone.ALL -> null to null
    Zone.REGULAR -> false to null
    Zone.COS -> null to true
}
