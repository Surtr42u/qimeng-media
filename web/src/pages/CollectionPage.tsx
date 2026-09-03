import { useMemo } from 'react'
import { useNavigate, useParams } from 'react-router'
import { MediaCard } from '@/components/media/MediaCard'
import { useAssetsInfinite, assetToCard, type AssetListParams } from '@/hooks/use-assets'
import { useAuthors } from '@/hooks/use-authors'
import { useTags } from '@/hooks/use-tags'
import { DEFAULT_PAGE_SIZE } from '@/lib/constants'

/**
 * 集合子页（/app/collection/tag/:name 与 /app/collection/author/:name）：
 * 数据页/完整榜单页点标签行或作者行进入——旧项目语义「点标签看该标签所有文件、
 * 点作者看该作者所有文件」，展示复用相册页同款媒体卡网格（用户拍板）。
 * 阶段 B 已接真实数据：tag 按 name 查 /tags 拿 id（tagMode=exact），author 按
 * displayName 查 /authors 拿 id（authorId 精确过滤）；找不到实体显示空态。
 * 注意 useParams 自带 URL 解码（中文名无需处理）；多标签同名不存在时不猜 id。
 */
export default function CollectionPage() {
  const navigate = useNavigate()
  const { kind = 'tag', name = '' } = useParams()
  const isAuthor = kind === 'author'
  const kindLabel = isAuthor ? '作者' : '标签'

  const { data: tags, isLoading: tagsLoading } = useTags()
  const { data: authors, isLoading: authorsLoading } = useAuthors()

  // 按名字找实体：tag.name === 参数名 / author.displayName === 参数名（找不到 → 空态）
  const tag = isAuthor ? undefined : tags?.find((t) => t.name === name)
  const author = isAuthor ? authors?.find((a) => a.displayName === name) : undefined
  const found = isAuthor ? author : tag

  const listParams = useMemo<AssetListParams>(() => {
    // includeCos：集合页语义=该标签/作者名下全部文件，不受默认 COS 排除影响
    //（COS 作者（type=cos，如蠢沫沫）的文件如不显式包含，列表恒空）
    if (isAuthor) {
      return author?.id
        ? { authorId: author.id, includeCos: true, limit: DEFAULT_PAGE_SIZE }
        : {}
    }
    // exact：该标签的全部文件（与搜索页的模糊命中区分——集合页是精确归属）
    return tag?.id
      ? { tagIds: [tag.id], tagMode: 'exact', includeCos: true, limit: DEFAULT_PAGE_SIZE }
      : {}
  }, [isAuthor, author, tag])

  const { data: pages, isFetching, fetchNextPage, hasNextPage } = useAssetsInfinite(
    listParams,
    !!found,
  )
  const items = useMemo(() => pages?.pages.flatMap((pg) => pg.items ?? []) ?? [], [pages])

  // 标题计数：实体 fileCount（标签/作者均有）优先，缺省回退列表 totalMatched
  const count = (isAuthor ? author?.fileCount : tag?.fileCount) ?? pages?.pages[0]?.totalMatched ?? 0

  // 加载中文案（实体列表仍在拉取）
  const loading = (isAuthor ? authorsLoading : tagsLoading)

  return (
    <div className="page" id="page-collection">
      <div className="page-head">
        <h2>{name}</h2>
        <p>
          {kindLabel} · {count} 个文件
        </p>
      </div>
      {loading ? (
        <p className="grid-empty">加载中…</p>
      ) : items.length ? (
        <div className="media-grid">
          {items.map((a) => (
            <MediaCard
              key={a.id}
              {...assetToCard(a)}
              onClick={() => a.id && navigate(`/app/asset/${a.id}`)}
            />
          ))}
        </div>
      ) : (
        <p className="grid-empty">该{kindLabel}下暂无内容。</p>
      )}
      {found && hasNextPage ? (
        <p className="grid-empty">
          <button
            className="pill"
            type="button"
            disabled={isFetching}
            onClick={() => fetchNextPage()}
          >
            {isFetching ? '加载中…' : '加载更多'}
          </button>
          <span className="pill-count">共 {items.length} 项</span>
        </p>
      ) : null}
    </div>
  )
}
