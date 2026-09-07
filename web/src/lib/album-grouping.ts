import type { AssetSummary } from '@/api/generated'
import { dateLabel } from '@/lib/format'

/**
 * 相册页网格时间分区（原型 #5 renderAlbumGrid，2026-09-07 自 AlbumsPage 抽离——
 * ADR-0008 业务规则不内嵌页面组件）。
 * 为什么按 dateLabel 分组：分组键与「最新/最旧」的 fileDate 排序、卡片角标日期
 * 同源（同一时间语义），用户切排序档时分组随 items 重算自动跟随；胶囊切换只改变
 * items，分组是其上的纯函数。空 label（无日期）不参与分组语义——组固定最后且
 * 页面不渲染组头。纯函数：只依赖入参，不碰 IO。
 */
export interface AlbumGroup {
  /** 组头文案（今天/昨天/周X/yyyy-MM-dd）；空串 = 无日期桶 */
  label: string
  /** 组内资产：保持列表原序（服务端排序即组内序，不再二次排序） */
  assets: AssetSummary[]
}

/** 按 modifiedAt 的 dateLabel 归并同组；组间按组首 modifiedAt 降序（新→旧），无日期组殿后 */
export function groupAlbumsByDate(items: AssetSummary[]): AlbumGroup[] {
  const byLabel = new Map<string, AssetSummary[]>()
  for (const a of items) {
    const label = dateLabel(a.modifiedAt)
    const bucket = byLabel.get(label)
    if (bucket) bucket.push(a)
    else byLabel.set(label, [a])
  }
  return [...byLabel.entries()]
    .map(([label, assets]) => ({ label, assets }))
    .sort((x, y) => {
      if (!x.label) return 1
      if (!y.label) return -1
      const tx = Date.parse(x.assets[0]?.modifiedAt ?? '') || 0
      const ty = Date.parse(y.assets[0]?.modifiedAt ?? '') || 0
      return ty - tx
    })
}
