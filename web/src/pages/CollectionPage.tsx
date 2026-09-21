import { useCallback, useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router'
import { MediaCard } from '@/components/media/MediaCard'
import { InfiniteTail } from '@/components/media/InfiniteTail'
import { Pill } from '@/components/ui/pill'
import { LoadingHint } from '@/components/ui/loading-hint'
import { useAutoMore } from '@/hooks/use-auto-more'
import {
  useAssetsInfinite,
  useAssetFacets,
  assetToCard,
  type AssetListParams,
  type MediaType,
} from '@/hooks/use-assets'
import { useAuthors } from '@/hooks/use-authors'
import { useTags } from '@/hooks/use-tags'
import { DEFAULT_PAGE_SIZE, LOCALE_ZH } from '@/lib/constants'
import { assetDetail } from '@/lib/route-keys'

/**
 * 集合子页（/app/collection/tag/:name 与 /app/collection/author/:name）：
 * 数据页/完整榜单页点标签行或作者行进入——旧项目语义「点标签看该标签所有文件、
 * 点作者看该作者所有文件」，展示复用相册页同款媒体卡网格（用户拍板）。
 * 阶段 B 已接真实数据：tag 按 name 查 /tags 拿 id（tagMode=exact），author 按
 * displayName 查 /authors 拿 id（authorId 精确过滤）；找不到实体显示空态。
 * 注意 useParams 自带 URL 解码（中文名无需处理）；多标签同名不存在时不猜 id。
 *
 * 作者页筛选胶囊栏（2026-09-05 反馈④，旧版作者文件页芯片栏对齐，GUIDE_UI）：
 * 常规作者=「角色/类型」两组、COS 作者=「作品/类型」两组（旧版 COS 的角色即
 * 按作品名分组，统一用「作品」语义呈现；旧版常规作者的「作品」=出处概念，
 * web 协议无对应数据，不做）。胶囊计数经 GET /assets/facets（authorId 恒传，
 * 每维独立请求排自身口径——被渲染维自身参数省略，见 hooks/use-assets.ts
 * useAssetFacets，B-3 已合并原专用 use-collection-facets.ts）；
 * 选中状态拼进 useAssetsInfinite 参数（work/character/mediaType，协议单值参数，
 * 故每维单选：点已选胶囊取消、点同行另一胶囊切换）。交互对齐旧版：点值胶囊
 * 只更新选中不收起（多选不退出）、点「收起 ▲」折叠且区域完全隐藏、点维度胶囊
 * 再展开。初始默认收起（2026-09-17 用户拍板，对齐相册/搜索页口径）。
 */

/** 「 ·COS」后缀 = format.ts authorDisplayName 给 COS 作者追加的展示标识。
 *  剥后缀工具就近放本模块而非 format.ts：format.ts 是多页共享纯函数模块
 *  （本任务禁改），且该后缀仅服务集合页的 displayName 反查，内聚此处。 */
const COS_DISPLAY_SUFFIX = ' ·COS'
function stripCosSuffix(name: string): string {
  return name.endsWith(COS_DISPLAY_SUFFIX) ? name.slice(0, -COS_DISPLAY_SUFFIX.length) : name
}

export default function CollectionPage() {
  const navigate = useNavigate()
  const { kind = 'tag', name = '' } = useParams()
  const isAuthor = kind === 'author'
  const kindLabel = isAuthor ? '作者' : '标签'

  // 稳定打开回调（MediaCard memo 生效前提，2026-09-20 全库审查）
  const openCard = useCallback(
    (id?: string) => {
      if (id) navigate(assetDetail(id))
    },
    [navigate],
  )

  const { data: tags, isLoading: tagsLoading } = useTags()
  const { data: authors, isLoading: authorsLoading } = useAuthors()

  // 按名字找实体：tag.name === 参数名 / author.displayName === 参数名（找不到 → 空态）。
  // 作者反查两段式：先 displayName 精确匹配（作者管理页入口传原始名），失败再剥
  // 「 ·COS」后缀匹配——数据页/榜单行经 authorDisplayName 渲染，COS 作者名带
  // 「 ·COS」后缀，原精确匹配恒失败 → 空态（2026-09-05 反馈③的根因）。
  const tag = isAuthor ? undefined : tags?.find((t) => t.name === name)
  const author = isAuthor
    ? (authors?.find((a) => a.displayName === name) ??
      authors?.find((a) => a.displayName === stripCosSuffix(name)))
    : undefined
  const found = isAuthor ? author : tag

  // ---------- 作者页筛选胶囊状态 ----------
  // 协议 work/character/mediaType 为单值参数（openapi），每维单选；
  // 常规作者只涉 character、COS 作者只涉 work，另一维状态闲置。
  const isCosAuthor = author?.type === 'cos'
  const attrLabel = isCosAuthor ? '作品' : '角色'
  const [attrSel, setAttrSel] = useState<string | null>(null)
  const [mediaType, setMediaType] = useState<MediaType | null>(null)
  const [dim, setDim] = useState<'attr' | 'type'>('attr')
  // 默认收起（2026-09-17 用户拍板，对齐相册/搜索页「默认收起+按钮点亮」口径）；
  // 点维度胶囊/切维度仍按旧版交互展开
  const [expanded, setExpanded] = useState(false)

  // 路由复用组件实例（/app/collection/:kind/:name 只换参数）：作者切换时重置
  // 筛选，防止上一作者页的选中胶囊泄漏到新作者页。用 React 官方「渲染期调整
  // 状态」模式而非 useEffect——重置发生在同一帧渲染内，不会拿上一作者的
  // 筛选先发一针过渡请求（react-compiler set-state-in-effect 告警即此因）。
  const authorId = author?.id
  const [prevAuthorId, setPrevAuthorId] = useState(authorId)
  if (prevAuthorId !== authorId) {
    setPrevAuthorId(authorId)
    setAttrSel(null)
    setMediaType(null)
    setDim('attr')
    setExpanded(false) // 重置=回默认态，默认收起（同上）
  }

  // 胶囊计数（排自身口径）：作品/角色维请求缺自身参数（带类型选择），类型维
  // 请求缺 mediaType（带作品/角色选择）；authorId 恒传锁定该作者范围
  const attrFacets = useAssetFacets(
    { authorId, ...(mediaType ? { mediaType } : {}) },
    !!authorId,
  )
  const typeFacets = useAssetFacets(
    { authorId, ...(attrSel ? (isCosAuthor ? { work: attrSel } : { character: attrSel }) : {}) },
    !!authorId,
  )

  // 作品/角色维候选：facets 角色行=常规角色（kind=character）∪ COS 作品
  // （kind=work）按分区合并，按作者 type 取对应 kind（authorId 已锁定单一作者）
  const attrOptions = useMemo(
    () =>
      (attrFacets.data?.characters ?? [])
        .filter((b) => b.kind === (isCosAuthor ? 'work' : 'character'))
        .map((b) => ({ label: b.name, value: b.key, count: b.fileCount })),
    [attrFacets.data, isCosAuthor],
  )
  // 类型维候选：facets 固定 all/image/animated_image/video 四项，剔除「全部」
  // 桶——「全部」=清除类型选择的独立胶囊，不计数（旧版「数量不含全部/收起」）
  const typeOptions = useMemo(
    () => (typeFacets.data?.types ?? []).filter((b) => b.key !== 'all'),
    [typeFacets.data],
  )

  const listParams = useMemo<AssetListParams>(() => {
    // includeCos：集合页语义=该标签/作者名下全部文件，不受默认 COS 排除影响
    //（COS 作者（type=cos，如蠢沫沫）的文件如不显式包含，列表恒空）
    if (isAuthor) {
      if (!author?.id) return {}
      return {
        authorId: author.id,
        includeCos: true,
        limit: DEFAULT_PAGE_SIZE,
        // 筛选胶囊选中状态拼进列表参数（work/character 按作者 type 二选一）
        ...(attrSel ? (isCosAuthor ? { work: attrSel } : { character: attrSel }) : {}),
        ...(mediaType ? { mediaType } : {}),
      }
    }
    // exact：该标签的全部文件（与搜索页的模糊命中区分——集合页是精确归属）
    return tag?.id
      ? { tagIds: [tag.id], tagMode: 'exact', includeCos: true, limit: DEFAULT_PAGE_SIZE }
      : {}
  }, [isAuthor, author, tag, attrSel, mediaType, isCosAuthor])

  const {
    data: pages, isFetchingNextPage, isPlaceholderData, fetchNextPage, hasNextPage,
  } = useAssetsInfinite(listParams, !!found)
  const items = useMemo(() => pages?.pages.flatMap((pg) => pg.items ?? []) ?? [], [pages])

  // E3 无感加载哨兵：enabled 与原 pill 的 when 同口径（实体未定位不挂不拉）。
  // onHit 双守卫：isFetchingNextPage 防重复拉页；isPlaceholderData 前瞻防混拼
  // （useAssetsInfinite 未配 placeholderData 恒 false，守卫零成本）。
  const sentinelRef = useAutoMore(!!found && hasNextPage, () => {
    if (!isFetchingNextPage && !isPlaceholderData) void fetchNextPage()
  })

  // 标题计数：实体 fileCount（标签/作者均有）优先，缺省回退列表 totalMatched
  const count = (isAuthor ? author?.fileCount : tag?.fileCount) ?? pages?.pages[0]?.totalMatched ?? 0

  // 加载中文案（实体列表仍在拉取）
  const loading = (isAuthor ? authorsLoading : tagsLoading)

  // 维度胶囊点击：点非当前维度=切换并展开（旧版「切模式默认展开」）；
  // 点当前维度=切换展开/折叠（旧版「点已选芯片切换展开/折叠状态」）
  const dimClick = (d: 'attr' | 'type') => {
    if (dim === d) setExpanded((v) => !v)
    else {
      setDim(d)
      setExpanded(true)
    }
  }

  return (
    <div className="page" id="page-collection">
      <div className="page-head">
        <h2>{name}</h2>
        <p>
          {kindLabel} · {count} 个文件
        </p>
      </div>
      {isAuthor && author ? (
        <section className="filter-card" aria-label="文件筛选">
          {/* 维度行：徽标=各维候选数（不含「全部/收起」——旧版芯片文字口径） */}
          <div className="pill-row" role="group">
            <span className="pill-row-item">
              <Pill active={dim === 'attr'} onClick={() => dimClick('attr')}>
                {attrLabel} <span className="pill-count">{attrOptions.length}</span>
              </Pill>
            </span>
            <span className="pill-row-item">
              <span className="pill-divider" aria-hidden="true" />
              <Pill active={dim === 'type'} onClick={() => dimClick('type')}>
                类型 <span className="pill-count">{typeOptions.length}</span>
              </Pill>
            </span>
          </div>
          {/* 值行：只展示当前维度胶囊；「收起 ▲」折叠后区域完全隐藏（旧版口径），
              再展开靠点维度胶囊；点值胶囊只更新选中不收起（多选不退出） */}
          {expanded ? (
            <>
              <div className="pill-row value-row expanded">
                {dim === 'attr'
                  ? attrOptions.map((o) => (
                      <Pill
                        key={o.value}
                        active={attrSel === o.value}
                        onClick={() => setAttrSel((v) => (v === o.value ? null : o.value))}
                      >
                        {o.label}
                        <span className="pill-count">{o.count.toLocaleString(LOCALE_ZH)}</span>
                      </Pill>
                    ))
                  : (
                    <>
                      <Pill active={mediaType === null} onClick={() => setMediaType(null)}>
                        全部
                      </Pill>
                      {typeOptions.map((o) => (
                        <Pill
                          key={o.key}
                          active={mediaType === o.key}
                          onClick={() => setMediaType((v) => (v === o.key ? null : (o.key as MediaType)))}
                        >
                          {o.name}
                          <span className="pill-count">{o.fileCount.toLocaleString(LOCALE_ZH)}</span>
                        </Pill>
                      ))}
                    </>
                  )}
              </div>
              <button className="expand-btn" type="button" onClick={() => setExpanded(false)}>
                收起 ▲
              </button>
            </>
          ) : null}
        </section>
      ) : null}
      {loading ? (
        <LoadingHint />
      ) : items.length ? (
        <div className="media-grid">
          {items.map((a) => (
            <MediaCard key={a.id} {...assetToCard(a)} onOpen={openCard} />
          ))}
        </div>
      ) : (
        <p className="grid-empty">
          {isAuthor && (attrSel || mediaType)
            ? '该筛选组合下暂无内容，换个胶囊试试。'
            : `该${kindLabel}下暂无内容。`}
        </p>
      )}
      {/* E3 无感加载尾部（三件套见 components/media/InfiniteTail）；哨兵
          仅在集合存在（found）时挂载 */}
      <InfiniteTail
        isFetchingNextPage={isFetchingNextPage}
        hasNextPage={hasNextPage}
        itemCount={items.length}
        sentinelRef={sentinelRef}
        sentinelActive={!!found}
      />
    </div>
  )
}
