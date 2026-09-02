import { useParams } from 'react-router'
import { MediaCard } from '@/components/media/MediaCard'
import { filesByAuthor, filesByTag } from '@/pages/mock'

/**
 * 集合子页（/app/collection/tag/:name 与 /app/collection/author/:name）：
 * 数据页/完整榜单页点标签行或作者行进入——旧项目语义「点标签看该标签所有文件、
 * 点作者看该作者所有文件」，展示复用相册页同款媒体卡网格（用户拍板）。
 * 阶段 A 数据 = mock 内容池按标签/作者过滤；阶段 B 换资产查询接口。
 */
export default function CollectionPage() {
  const { kind = 'tag', name = '' } = useParams()
  const isAuthor = kind === 'author'
  const files = isAuthor ? filesByAuthor(name) : filesByTag(name)
  const kindLabel = isAuthor ? '作者' : '标签'

  return (
    <div className="page" id="page-collection">
      <div className="page-head">
        <h2>{name}</h2>
        <p>
          {kindLabel} · {files.length} 个文件 · mock 数据
        </p>
      </div>
      {files.length ? (
        <div className="media-grid">
          {files.map((f) => (
            <MediaCard
              key={f.name}
              id={f.name}
              cover={f.cover}
              title={f.name}
              duration={f.duration}
              up={f.up}
              date={f.date}
            />
          ))}
        </div>
      ) : (
        <p className="grid-empty">该{kindLabel}下暂无内容。</p>
      )}
    </div>
  )
}
