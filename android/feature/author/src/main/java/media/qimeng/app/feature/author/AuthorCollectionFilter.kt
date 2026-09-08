package media.qimeng.app.feature.author

import media.qimeng.app.core.model.AlbumDim
import media.qimeng.app.core.model.AlbumFilterState
import media.qimeng.app.core.model.AssetQuery
import media.qimeng.app.core.model.FacetParamKind
import media.qimeng.app.core.model.FacetsQuery
import media.qimeng.app.core.model.Zone

/**
 * 作者集合页筛选纯函数层（任务I I6，GUIDE_UI §芯片栏配置对比 L68-69 + §相册详情页 L362-369）：
 * 集合页 = 锁定作者维度的四维芯片子集——常规作者「作品/角色/类型（无分区）」、
 * COS 作者「角色/类型」（出处=作者已锁定，分区/作品维不适用）。维度子集与
 * 「固定 authorId + 维选择按 kind 分派」的参数映射收敛在此（纯函数，单测锁定）；
 * core:model 的 AlbumFilterState/AlbumDim/FourDimPills 只读复用零改动
 （维度子集机制本身在 core:model 已由 dimChips(dims) / pillsFor(dim) 的重载参数位承接）。
 */

/** COS 作者 id 前缀（openapi Author.id「常规=generateAuthorId 规则；COS=cos_ 前缀」——协议内判定，零额外请求） */
internal const val COS_AUTHOR_ID_PREFIX = "cos_"

/** COS 作者判定（路由 authorId 前缀，openapi Author.id 口径；纯函数单测锁定） */
internal fun isCosAuthorId(authorId: String): Boolean = authorId.startsWith(COS_AUTHOR_ID_PREFIX)

/**
 * 集合页维度子集（GUIDE_UI §芯片栏配置对比 L68-69 逐行）：常规作者 = 作品/角色/类型
 * （无「分区」——已锁定作者维度）；COS 作者 = 角色/类型（无「分区」「作品」——
 * COS 作者出处=作者已锁定；角色按作品名分派 work 参数，分组语义在服务端候选口径内）。
 */
internal fun authorCollectionDims(isCos: Boolean): List<AlbumDim> =
    if (isCos) {
        listOf(AlbumDim.CHARACTER, AlbumDim.TYPE)
    } else {
        listOf(AlbumDim.AUTHOR, AlbumDim.CHARACTER, AlbumDim.TYPE)
    }

/**
 * 集合页 GET /assets 查询映射：authorId 恒定（集合作者，覆盖路由参数）+ includeCos 恒 true
 * （COS 作者文件不显式包含则列表恒空——原 VM 口径保持）+ 维选择按 kind 分派：
 * 作品维（仅常规作者）恒 SOURCE→source；角色维 kind 分派 character|work；类型维→mediaType。
 * 不经 AlbumFilter.toAssetQuery（其 authorId 位留给分区态的作者维选择，与本页固定
 * authorId 语义冲突——显式映射避免同参覆盖）。
 */
internal fun collectionAssetQuery(
    authorId: String,
    filter: AlbumFilterState,
    limit: Int?,
    cursor: String?,
): AssetQuery = AssetQuery(
    cursor = cursor,
    limit = limit,
    authorId = authorId,
    includeCos = true,
    mediaType = filter.mediaType,
    source = filter.author?.takeIf { it.kind == FacetParamKind.SOURCE }?.key,
    character = filter.character?.takeIf { it.kind == FacetParamKind.CHARACTER }?.key,
    work = filter.character?.takeIf { it.kind == FacetParamKind.WORK }?.key,
)

/**
 * 维候选 GET /facets 查询映射（与收藏页同构的「排自身维」口径）：固定 authorId 收窄到
 * 本作者文件（openapi facets authorId 同语义）+ 排自身维选择（作者/角色/类型各查各的）
 * + 其余维选择联动（作品/角色递归筛选——选了作品后角色只显示该作品下的候选，服务端口径）。
 * partition 恒 ALL（本页无分区芯片，候选只由固定 authorId 收窄）。
 *
 * 已知协议限制（服务端 facets.go「作者行排自身=source 与 authorId 一起忽略」）：
 * 作品维（authors 桶）候选**不随 authorId 收窄**——服务端返回全库的 source∪COS 作者桶，
 * 本页在消费侧裁剪为 SOURCE 子集（见 AuthorCollectionViewModel.loadFacets）；角色/类型
 * 两桶的 authorId 收窄正常（已实测）。协议扩展归台账，本卷零协议。
 */
internal fun collectionFacetsQuery(
    authorId: String,
    dim: AlbumDim,
    filter: AlbumFilterState,
): FacetsQuery = FacetsQuery(
    partition = Zone.ALL,
    mediaType = filter.mediaType.takeIf { dim != AlbumDim.TYPE },
    source = filter.author
        ?.takeIf { it.kind == FacetParamKind.SOURCE }
        ?.key
        ?.takeIf { dim != AlbumDim.AUTHOR },
    // 固定集合作者恒传（收窄键）；作品维选择只可能 kind=SOURCE（常规作者文件无 COS 作者桶），不占本位
    authorId = authorId,
    character = filter.character
        ?.takeIf { it.kind == FacetParamKind.CHARACTER }
        ?.key
        ?.takeIf { dim != AlbumDim.CHARACTER },
    work = filter.character
        ?.takeIf { it.kind == FacetParamKind.WORK }
        ?.key
        ?.takeIf { dim != AlbumDim.CHARACTER },
)
