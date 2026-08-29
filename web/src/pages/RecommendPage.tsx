/**
 * 首页（推荐流）：推荐 / 浏览热度 / 我的收藏 三 Tab。
 *
 * 交互规格要点：
 * - 推荐 Tab 数据 = useRecommendations（M2 视图热度占位：viewCount DESC，seed 无效）；
 *   "换一批" 由客户端对当前数组轮转打散（服务端 seed 未接线前占位），M3 算法接入后改传 seed。
 * - 浏览热度 = useAssets({ sort:'viewCount' })，收藏 = useAssets({ favorite:true })——两者
 *   都是 cursor 无限滚动（useAssets 已封装），isFetchingNextPage 显示骨架。
 * - 打开详情携带批次 state（{ items, index, from }）——详情页左右切换范围 = 本批次数组。
 * - Tab 选择存 URL searchParams（页面状态存 URL 的约定，刷新/Tab 切回不丢）。
 */

import { useMemo, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import { RefreshCw, Shuffle } from 'lucide-react'
import { Skeleton } from '@/components/ui/skeleton'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Button } from '@/components/ui/button'
import { AssetGrid } from '@/components/asset/AssetGrid'
import { useGridColumns } from '@/components/asset/use-grid-columns'
import { InfiniteScrollSentinel } from '@/components/misc/InfiniteScrollSentinel'
import { useAssets } from '@/hooks/use-assets'
import { useRecommendations } from '@/hooks/use-recommendations'
import { DEFAULT_PAGE_SIZE } from '@/lib/constants'
import type { AssetSummary } from '@/api/generated'

/* ------------------------------ 文案/常量（交互规格来源） ------------------------------ */

const TAB_VALUE_RECOMMEND = 'recommend'
const TAB_VALUE_HOT = 'hot'
const TAB_VALUE_FAVORITE = 'favorite'
const TAB_NAME_RECOMMEND = '推荐'
const TAB_NAME_HOT = '浏览热度'
const TAB_NAME_FAVORITE = '我的收藏'
const TEXT_EMPTY = '这里还没有内容，换个时间再来看看吧'
const TEXT_SHUFFLE = '换一批'
const TEXT_REFRESH = '刷新'

/** 轮转打散步进（"换一批"每次向后挪的位数）。M3 说明：服务端 seed 生效后改传 seed 参数 */
const ROTATE_STEP = 5

/** URL searchParams 键：当前 Tab */
const PARAM_TAB = 'tab'

/** 批次携带的跳转上下文（DetailPage location.state 形状） */
export interface DetailBatchState {
  items: AssetSummary[]
  index: number
  /** 来源路径（返回按钮回到列表页用） */
  from: string
}

/** 推荐数据轮转：把数组向后挪 offset 位（shift 语义：取 after 排列） */
function rotateItems(items: AssetSummary[], offset: number): AssetSummary[] {
  if (items.length === 0) return items
  const shift = offset % items.length
  return [...items.slice(shift), ...items.slice(0, shift)]
}

export default function RecommendPage() {
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const { columns } = useGridColumns()
  const tab = searchParams.get(PARAM_TAB) ?? TAB_VALUE_RECOMMEND
  const setTab = (next: string) => {
    const params = new URLSearchParams(searchParams)
    if (next === TAB_VALUE_RECOMMEND) params.delete(PARAM_TAB)
    else params.set(PARAM_TAB, next)
    setSearchParams(params, { replace: true })
  }

  /* ---- 推荐 Tab：返回数组（无游标，一次取满）---- */
  const recommendQuery = useRecommendations({ limit: DEFAULT_PAGE_SIZE })
  /** 本地轮转偏移（仅当前展示用；换一批累加，刷新归零） */
  const [rotateOffset, setRotateOffset] = useState(0)
  const recommendItems = useMemo(
    () => rotateItems(recommendQuery.data ?? [], rotateOffset),
    [recommendQuery.data, rotateOffset],
  )

  /* ---- 热度/收藏 Tab：cursor 无限滚动 ---- */
  const hotQuery = useAssets({ sort: 'viewCount' })
  const favoriteQuery = useAssets({ favorite: true })
  // data 在加载/出错态为 undefined——统一兜底空数组，渲染分支无需再判空
  const hotItems = hotQuery.data?.items ?? []
  const favoriteItems = favoriteQuery.data?.items ?? []

  /* ---- 打开详情：携带批次 state（详情页左右切换/序号范围用）---- */
  const openDetail = (items: AssetSummary[], index: number) => {
    const target = items[index]
    if (!target?.id) return
    navigate(`/detail/${target.id}`, {
      state: { items, index, from: '/' } satisfies DetailBatchState,
    })
  }

  const currentTab = tab === TAB_VALUE_HOT || tab === TAB_VALUE_FAVORITE ? tab : TAB_VALUE_RECOMMEND

  return (
    <div className="flex flex-col gap-[var(--qm-space-2)] p-[var(--qm-space-3)]">
      <Tabs value={currentTab} onValueChange={setTab} className="gap-[var(--qm-space-2)]">
        <TabsList className="w-full">
          <TabsTrigger value={TAB_VALUE_RECOMMEND} className="flex-1">
            {TAB_NAME_RECOMMEND}
          </TabsTrigger>
          <TabsTrigger value={TAB_VALUE_HOT} className="flex-1">
            {TAB_NAME_HOT}
          </TabsTrigger>
          <TabsTrigger value={TAB_VALUE_FAVORITE} className="flex-1">
            {TAB_NAME_FAVORITE}
          </TabsTrigger>
        </TabsList>

        {/* 推荐 Tab：换一批（本地轮转）+ 刷新（refetch） */}
        <TabsContent value={TAB_VALUE_RECOMMEND} className="flex flex-col gap-[var(--qm-space-2)]">
          <div className="flex justify-end gap-2">
            <Button size="sm" variant="secondary" onClick={() => setRotateOffset((v) => v + ROTATE_STEP)}>
              <Shuffle className="size-3.5" aria-hidden />
              {TEXT_SHUFFLE}
            </Button>
            <Button size="sm" variant="secondary" onClick={() => { setRotateOffset(0); void recommendQuery.refetch() }}>
              <RefreshCw className="size-3.5" aria-hidden />
              {TEXT_REFRESH}
            </Button>
          </div>
          {recommendQuery.isLoading ? (
            <Skeleton className="aspect-square w-full" />
          ) : recommendItems.length === 0 ? (
            <p className="py-16 text-center text-sm text-[var(--qm-text-muted)]">{TEXT_EMPTY}</p>
          ) : (
            <AssetGrid
              items={recommendItems}
              columns={columns}
              onOpen={(_, index) => openDetail(recommendItems, index)}
            />
          )}
        </TabsContent>

        {/* 浏览热度 Tab：无限滚动 */}
        <TabsContent value={TAB_VALUE_HOT} className="flex flex-col gap-[var(--qm-space-2)]">
          {hotQuery.isLoading ? (
            <Skeleton className="aspect-square w-full" />
          ) : hotItems.length === 0 ? (
            <p className="py-16 text-center text-sm text-[var(--qm-text-muted)]">{TEXT_EMPTY}</p>
          ) : (
            <>
              <AssetGrid
                items={hotItems}
                columns={columns}
                onOpen={(_, index) => openDetail(hotItems, index)}
              />
              <InfiniteScrollSentinel
                hasNextPage={hotQuery.hasNextPage}
                isLoading={hotQuery.isFetchingNextPage}
                onLoadMore={() => void hotQuery.fetchNextPage()}
              />
            </>
          )}
        </TabsContent>

        {/* 我的收藏 Tab：无限滚动 */}
        <TabsContent value={TAB_VALUE_FAVORITE} className="flex flex-col gap-[var(--qm-space-2)]">
          {favoriteQuery.isLoading ? (
            <Skeleton className="aspect-square w-full" />
          ) : favoriteItems.length === 0 ? (
            <p className="py-16 text-center text-sm text-[var(--qm-text-muted)]">{TEXT_EMPTY}</p>
          ) : (
            <>
              <AssetGrid
                items={favoriteItems}
                columns={columns}
                onOpen={(_, index) => openDetail(favoriteItems, index)}
              />
              <InfiniteScrollSentinel
                hasNextPage={favoriteQuery.hasNextPage}
                isLoading={favoriteQuery.isFetchingNextPage}
                onLoadMore={() => void favoriteQuery.fetchNextPage()}
              />
            </>
          )}
        </TabsContent>
      </Tabs>
    </div>
  )
}
