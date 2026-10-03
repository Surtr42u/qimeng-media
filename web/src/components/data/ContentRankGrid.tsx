/**
 * 内容榜网格（数据页 Top5 / 完整榜单页 / 首页排行榜 tab 共用）。纯渲染组件：
 * 输入 AssetSummary[]，rank-item 结构照原型（封面 + 标题），点击由调用方注入跳转。
 * 数值角标（rank-views）2026-09-05 用户拍板去除——热度排序已在卡片顺序里，
 * 不再叠加次数文本（作者榜/作者总览行的计数字段是榜单度量本体，不受影响）。
 */

import type { AssetSummary } from '@/api/generated'
import { EmptyNote } from './EmptyNote'
import { useThumbSize } from '@/hooks/use-thumb-size'
import { applyThumbSize } from '@/lib/thumb-size'

export function ContentRankGrid({
  items,
  onOpen,
}: {
  items: AssetSummary[]
  onOpen: (a: AssetSummary) => void
}) {
  // 档位偏好接入选点（与 MediaCard 同口径；auto=服务端下发原样）
  const [thumbSize] = useThumbSize()
  if (!items.length) return <EmptyNote />
  return (
    <div className="rank-cards">
      {items.map((a) => (
        <div className="rank-item" key={a.id} onClick={() => onOpen(a)} role="button">
          <div className="rank-cover">
            <img src={applyThumbSize(a.thumbUrl ?? '', thumbSize)} alt="" loading="lazy" />
          </div>
          <p className="rank-title">{a.fileName ?? ''}</p>
        </div>
      ))}
    </div>
  )
}
