/**
 * 资产浏览/详情/推荐/打点 hooks（首页/相册/详情页消费；铁律 7：组件不直接调 API）。
 * 数据源全部来自生成 SDK（协议唯一口径）：列表带签名缩略图 URL（thumbUrl），
 * 详情带签名原件直链（origUrl，"查看永远发原件"）。
 */

import { keepPreviousData, useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  getApiV1Assets,
  getApiV1AssetsByAssetId,
  getApiV1AssetsFacets,
  getApiV1Recommendations,
  getApiV1Sources,
  putApiV1AssetsByAssetIdFavorite,
  putApiV1AssetsByAssetIdLike,
  putApiV1AssetsByAssetIdTags,
  type AssetDetail,
  type AssetSummary,
  type CountRange,
  type SizeRange,
} from '@/api/generated'
// facets 相关类型本文件也要用，import（而非仅 re-export）才能进当前模块作用域
import type { AssetFacets, FacetBucket, Partition } from '@/api/generated'
export type { AssetFacets, FacetBucket, Partition }
import { getEventLedger } from '@/lib/ledger-instance'
import type { MediaCardProps } from '@/components/media/MediaCard'
import { unwrapSdkResult } from '@/lib/api-client'
import { DEFAULT_PAGE_SIZE } from '@/lib/constants'
import { deviceTzOffsetMinutes, formatCardUp, formatDuration, formatShortDate } from '@/lib/format'
import { lengthCursorNext } from '@/lib/pagination'
import {
  ASSETS_LIST_QUERY_KEY,
  ASSETS_QUERY_KEY,
  RECOMMENDATIONS_QUERY_KEY,
  SOURCES_QUERY_KEY,
  TAGS_QUERY_KEY,
} from '@/lib/query-keys'

export type MediaType = 'image' | 'animated_image' | 'video'
export type AssetSort =
  | 'default' | 'fileDate' | 'addedDate' | 'viewCount' | 'playCount' | 'sizeBytes' | 'name' | 'favoriteAt'

/** 资产列表查询参数（协议 GET /assets 的用户面子集；分页游标由无限滚动管理）。
 *  区间值域与协议枚举一致（CountRange/SizeRange），不自行再造字符串。 */
export interface AssetListParams {
  libraryId?: string
  mediaType?: MediaType
  source?: string
  character?: string
  /** COS 作品名（migration 0008：COS 分区下「角色」维的筛选值） */
  work?: string
  /** 只要 COS 作者关联文件（分区胶囊「COS」语义；与 includeCos 同真时优先） */
  cosOnly?: boolean
  sort?: AssetSort
  order?: 'asc' | 'desc'
  limit?: number
  q?: string
  authorId?: string
  tagIds?: string[]
  tagMode?: 'fuzzy' | 'exact'
  includeCos?: boolean
  favorite?: boolean
  liked?: boolean
  viewRange?: CountRange
  playRange?: CountRange
  sizeRange?: SizeRange
  dateFrom?: string
  dateTo?: string
  yearFrom?: number
  yearTo?: number
  /** 请求服务端按本地日聚合的精确计数（协议 GET /assets dateCounts，
   *  2026-10-10 加）：日期分组页（相册）组头「今天 N 项」的数据源。 */
  dateCounts?: boolean
  /** 本地日界的固定偏移（协议 tzOffsetMinutes）。缺省由 SDK 边界按设备现算；
   *  日期分组页显式传入，保证「请求分桶键」与「客户端分组日键」同值。 */
  tzOffsetMinutes?: number
}

/** 推荐流无限分页（M3 十维算法，协议 GET /recommendations；首页推荐/cos
 *  tab 触底增量加载的数据源）。
 *  签名 (limit=DEFAULT_PAGE_SIZE, seed=0, cosOnly=false, offset=0)：offset
 *  是初始翻页偏移（useInfiniteQuery 的 initialPageParam，默认 0 向后兼容
 *  ——调用点 HomePage useRecommendations(DEFAULT_PAGE_SIZE, …) 即从第一页起）。
 *  queryKey 只含数据身份 [limit, seed, cosOnly]：offset 是分页游标，按
 *  TanStack 惯例存于 pageParams 而非缓存键——seed/根键变化（换一批、
 *  AppShell 刷新信号、SSE 失效）时整条流重置回第一页，正是旧版 refreshSeed++
 *  全量重排语义。
 *  续页判据 hasNextPage 口径单源在 lib/pagination.ts（lengthCursorNext）。 */
export function useRecommendations(limit = DEFAULT_PAGE_SIZE, seed = 0, cosOnly = false, offset = 0) {
  return useInfiniteQuery({
    queryKey: [...RECOMMENDATIONS_QUERY_KEY, limit, seed, cosOnly],
    queryFn: ({ pageParam }) =>
      unwrapSdkResult(getApiV1Recommendations({ query: { limit, seed, cosOnly, offset: pageParam } })),
    initialPageParam: offset,
    getNextPageParam: lengthCursorNext(limit),
    // E1（次生根因）：换键重取期间保留旧流占位（TanStack 官方 keepPreviousData
    // 模式）——换一批/壳层刷新信号与返回首页时不得整流闪「加载中…」
    placeholderData: keepPreviousData,
  })
}

/** 资产列表无限滚动（游标分页；相册/搜索/收藏/集合页共用口径）。
 *  enabled=false 用于"无搜索词/未找到合集实体"时避免无效请求（q 为空仍要渲染空态）。
 *  换键（筛选/分区/搜索词变化）期间保留旧列表占位（对齐 useRecommendations/
 *  useRankingsInfinite 的 E1 口径，2026-09-20 全库审查补齐——此前四页换键
 *  整列表闪「加载中…」；消费方哨兵的 isPlaceholderData 守卫自此真实生效，
 *  防换键期间 fetchNextPage 用旧游标打新键）。 */
export function useAssetsInfinite(params: AssetListParams = {}, enabled = true) {
  return useInfiniteQuery({
    queryKey: [...ASSETS_LIST_QUERY_KEY, params],
    queryFn: ({ pageParam }) =>
      unwrapSdkResult(getApiV1Assets({ query: { ...toSdkAssetListQuery(params), cursor: pageParam } })),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    enabled,
    placeholderData: keepPreviousData,
  })
}

/**
 * 用户面单值 → 协议多值的 SDK 边界归一（协议批 2026-09-09，#29）：
 * GET /assets 的 source/character/work 已多值化（数组、同维内 OR、跨维 AND），
 * web 现发单值——用户面 AssetListParams 保持单值 string 零改动，仅在传给
 * 生成 SDK 时包单元素数组（单值=单元素数组向后兼容）；多选 UI 属后续批次。
 */
function toSdkAssetListQuery(params: AssetListParams) {
  const { source, character, work, ...rest } = params
  return {
    ...rest,
    source: source ? [source] : undefined,
    character: character ? [character] : undefined,
    work: work ? [work] : undefined,
    // 时区偏移恒补（协议 GET /assets tzOffsetMinutes，2026-10-10 加）：它不是
    // 页面参数而是设备属性——两个消费点同源，① dateCounts 的日界分桶必须与
    // 客户端日键一致（否则组头计数与分组劈叉，夏令时区域尤甚）；② dateFrom/
    // dateTo 的「本地日历日」解释（缺省 0=UTC 与本地日界差一个时区）。
    // 页面显式传入时以页面为准（相册页把同一个值同时喂给分组，保证同源）。
    tzOffsetMinutes: params.tzOffsetMinutes ?? deviceTzOffsetMinutes(),
  }
}

/** 目录树文件行单页上限：协议 GET /assets 的 limit 上限=200（openapi limit
 *  maximum）。目录内直接子文件量级小，一次拉全不分页（协议侧改动须同步此处）。 */
const DIR_FILES_LIMIT = 200

/**
 * 目录树选中目录的直接子文件清单（B-5 目录树文件行数据源；GET /assets 的
 * directory 过滤是 B-4 协议扩展：库内相对目录精确匹配、只含直接子文件、
 * 空串=库根、缺省不过滤——本 hook 恒传 directory，选中目录语义不含"不过滤"态）。
 * queryKey 含 libraryId+directory：切目录即换键重取；挂在 ASSETS_QUERY_KEY
 * 根键下，useMoveAsset/useDeleteAsset 的根键失效直接命中本清单（文件行
 * 随操作结果即时刷新，目录树计数由 DIRS 失效同步）。enabled=false（未选库）
 * 挂起不发请求（同 useDirTree 口径）。totalMatched>limit 的目录不翻页——
 * 目录内文件量级远小于协议上限，超出属异常规模，暂按前 200 条展示。
 */
export function useAssetsInDirectory(libraryId: string, directory: string, enabled: boolean) {
  return useQuery({
    queryKey: [...ASSETS_QUERY_KEY, 'dir-files', libraryId, directory],
    queryFn: () =>
      unwrapSdkResult(
        getApiV1Assets({
          query: { libraryId, directory, limit: DIR_FILES_LIMIT, tzOffsetMinutes: deviceTzOffsetMinutes() },
        }),
      ),
    enabled,
    // B-8（reviewer P3 清偿）：query key 含 directory——切目录换键、失效重取期间
    // 保留旧列表占位（TanStack 官方模式），消费方配 isLoading 只在真正无数据时显示加载态
    placeholderData: keepPreviousData,
    select: (page) => page.items ?? [],
  })
}

/** 类型总数徽标（limit=1 只取 totalMatched；搜索页类型 tab 计数用，不带其他筛选）。
 *  partition 三态与列表口径一致：缺省=常规（默认排除 COS），'cos'=只要 COS，'all'=常规∪COS。 */
export function useAssetsTotal(mediaType?: MediaType, partition?: 'regular' | 'cos' | 'all') {
  return useQuery({
    queryKey: [...ASSETS_QUERY_KEY, 'total', mediaType ?? 'all', partition ?? 'regular'],
    queryFn: () =>
      unwrapSdkResult(
        getApiV1Assets({
          query: {
            limit: 1,
            mediaType,
            // 同 SDK 边界恒补口径（其余两处 getApiV1Assets 调用点均带，禁漏）
            tzOffsetMinutes: deviceTzOffsetMinutes(),
            ...(partition === 'all'
              ? { includeCos: true }
              : partition === 'cos'
                ? { cosOnly: true }
                : {}),
          },
        }),
      ),
    select: (page) => page.totalMatched ?? 0,
  })
}

/** AssetSummary → MediaCardProps（卡片展示字段映射唯一入口，搜索/集合页共用，
 *  首页/相册页也复用）。映射口径（原型 #4）：封面=thumbUrl、标题=cosWork 优先
 *  （COS 作品子目录名，用户口径「COS 卡显示文件夹名」；null 回退 fileName）、
 *  时长=视频 durationMs（图片/动图省略，角标不渲染）、作者行=authorNames[0]
 *  （多作者「名 等N」，无作者回退 source）、date=modifiedAt 短日期。 */
export function assetToCard(a: AssetSummary): MediaCardProps {
  return {
    id: a.id,
    cover: a.thumbUrl ?? '',
    title: a.cosWork ?? a.fileName ?? '',
    duration: a.mediaType === 'video' && a.durationMs ? formatDuration(a.durationMs) : undefined,
    up: formatCardUp(a.authorNames, a.source),
    date: formatShortDate(a.modifiedAt),
  }
}

/** 资产详情（签名原件直链/编码信息/标签作者——详情页与播放兼容性判断数据源）。
 *  E1：详情→详情（右栏换资产）换键重取期间保留上一资产占位，到货即整体切换
 *  ——不闪整页「加载中…」；仅直达冷启动（真无数据）由消费方显示加载态。 */
export function useAssetDetail(assetId?: string) {
  return useQuery({
    queryKey: [...ASSETS_QUERY_KEY, 'detail', assetId],
    queryFn: () => unwrapSdkResult(getApiV1AssetsByAssetId({ path: { assetId: assetId! } })),
    enabled: !!assetId,
    placeholderData: keepPreviousData,
  })
}

/** 出处分组计数（搜索页/集合页来源维度；null 名 = 前端兜底"其他"） */
export function useSources() {
  return useQuery({
    queryKey: SOURCES_QUERY_KEY,
    queryFn: () => unwrapSdkResult(getApiV1Sources()),
  })
}

/** 筛选胶囊候选（分区/作者/角色/类型；GET /assets/facets，排自身口径）。
 *  params 里只传"其他维度"的当前选择——被渲染维自身的参数由调用方省略，
 *  排自身口径由调用方实现：每维独立请求、省略自身参数——source 与 authorId
 *  同属「作者」行（查作者行时两者都不传）、character 与 work 同属「角色」行（同理）；
 *  服务端对传入的收窄参数照常生效（2026-09-09 协议批，openapi facets description）。
 *  enabled=false 防无效请求（集合子页作者实体未定位时用）。B-3 合并：
 *  CollectionPage 原专用 use-collection-facets.ts（并行冲突规避产物）与本
 *  hook 逐语义重复，收敛后该文件消亡——其缓存键第三段 'collection' 一并
 *  去掉（同参数同响应，与相册页共享缓存无碍；两页参数形态不同键为主：
 *  即使同键也同参数同响应共享缓存零行为差）。 */
export function useAssetFacets(
  params: {
    partition?: Partition
    mediaType?: MediaType
    character?: string
    authorId?: string
    work?: string
    source?: string
  } = {},
  enabled = true,
) {
  return useQuery({
    queryKey: [...ASSETS_QUERY_KEY, 'facets', params],
    queryFn: () => unwrapSdkResult(getApiV1AssetsFacets({ query: params })),
    select: (res): AssetFacets => ({
      partitions: res.partitions ?? [],
      authors: res.authors ?? [],
      characters: res.characters ?? [],
      types: res.types ?? [],
    }),
    enabled,
  })
}

/** 作者/角色行的胶囊混合候选类型：kind 决定回传哪个筛选参数
 *  （source=GET /assets 的 source；author=authorId；character=character；
 *  work=work——同一胶囊栏混合两种候选时前端按 kind 分派）。 */
export type FacetOptionKind = NonNullable<FacetBucket['kind']>

/** FacetBucket → 值行胶囊结构（label 用服务端显示名，count 带 fileCount，
 *  kind 供作者/角色混合行分派筛选参数）。 */
export function facetToOptions(
  buckets: FacetBucket[],
): { label: string; value: string; count: number; kind?: FacetOptionKind }[] {
  return buckets.map((b) => ({ label: b.name, value: b.key, count: b.fileCount, kind: b.kind }))
}

/** 行为上报（DOMAIN_RULES §5：open/play/dwell；会话去重依赖每标签页 sessionId）。
 * 任务L L5 本地优先：事件先落 IndexedDB 账本（入账即生成 clientEventId 幂等键），
 * 上报成功才删、失败保留同 id 重试（lib/event-ledger 单测锁定；断网补发由
 * hooks/use-event-ledger 的触发通道承担）。组件侧 API 形态不变。 */
export function useReportView() {
  return useMutation({
    mutationFn: async (body: {
      assetId: string
      kind: 'open' | 'play' | 'dwell'
      startedAt: string
      sessionId: string
      seconds?: number
    }) => {
      const ledger = await getEventLedger()
      await ledger.record(body)
    },
  })
}

/* ===== 详情页互动（2026-09-05 B站式排版大改：点赞/收藏/标签管理/接下来播放） ===== */

/** 回填详情缓存的口径：互动响应即时回填详情（按钮零延迟反馈），再失效资产
 *  根键让列表/推荐等旁路计数走服务端权威值——回填只 patch 已有缓存，不造数据。 */
function patchDetail(
  qc: ReturnType<typeof useQueryClient>,
  assetId: string,
  patch: Partial<AssetDetail>,
) {
  qc.setQueryData<AssetDetail>([...ASSETS_QUERY_KEY, 'detail', assetId], (old) =>
    old ? { ...old, ...patch } : old,
  )
  qc.invalidateQueries({ queryKey: ASSETS_QUERY_KEY })
}

/** 点赞 toggle（PUT like 无 body）：当日已赞则本请求为取消（服务端判定当日，
 *  前端不做当日限制）；响应 LikeState{likedToday, likeCount} 直接回填。 */
export function useToggleLike() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (assetId: string) =>
      unwrapSdkResult(putApiV1AssetsByAssetIdLike({ path: { assetId } })),
    onSuccess: (state, assetId) =>
      patchDetail(qc, assetId, { likedToday: state.likedToday, likeCount: state.likeCount }),
  })
}

/** 收藏设置（PUT favorite 显式值，非 toggle——由 isFavorite 推导提交值） */
export function useSetFavorite() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (args: { assetId: string; favorite: boolean }) =>
      unwrapSdkResult(
        putApiV1AssetsByAssetIdFavorite({
          path: { assetId: args.assetId },
          body: { favorite: args.favorite },
        }),
      ),
    onSuccess: (_res, args) => patchDetail(qc, args.assetId, { isFavorite: args.favorite }),
  })
}

/** 资产标签整体替换（PUT tags 是唯一标签端点——保留项也要一并提交，
 *  DOMAIN_RULES §7；标签池 fileCount 随关联变化同步失效） */
export function useReplaceAssetTags() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (args: { assetId: string; tagIds: string[] }) =>
      unwrapSdkResult(
        putApiV1AssetsByAssetIdTags({
          path: { assetId: args.assetId },
          body: { tagIds: args.tagIds },
        }),
      ),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ASSETS_QUERY_KEY })
      qc.invalidateQueries({ queryKey: TAGS_QUERY_KEY })
    },
  })
}

/** 「接下来播放」单页（GET /recommendations，详情页右栏）：同类型收窄 +
 *  seed 打散；seed 入缓存键——换一批即换 seed 重取（同 seed 可复现，
 *  DOMAIN_RULES §1.1 禁止纯随机）。与首页 useRecommendations 同根键不同
 *  子族，SSE upload.done 的根键失效同步覆盖本栏。 */
export function useUpNextList(params: {
  mediaType?: MediaType
  cosOnly?: boolean
  seed: number
  limit?: number
}) {
  const { mediaType, cosOnly = false, seed, limit = 12 } = params
  return useQuery({
    queryKey: [...RECOMMENDATIONS_QUERY_KEY, 'upnext', mediaType ?? 'all', cosOnly, seed, limit],
    queryFn: () =>
      unwrapSdkResult(
        getApiV1Recommendations({
          query: { limit, seed, cosOnly, ...(mediaType ? { mediaType } : {}), offset: 0 },
        }),
      ),
  })
}
