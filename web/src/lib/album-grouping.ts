import type { AssetSummary } from '@/api/generated'
import { dateLabel, localDayKey } from '@/lib/format'

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
  /**
   * 组头「N 项」的数字：服务端按本地日聚合的精确总数（协议 GET /assets
   * dateCounts=true，2026-10-10 加）命中时用真实总数；未命中（未请求/翻页
   * 未带/无日期桶）回退组内已加载条数。
   * 为什么必须区分：分页一次只加载 60 条，同一天文件多时 `assets.length`
   * 只是「已加载条数」——组头数字会随滚动一路跳增，第一眼看到的就是错的
   * （2026-10-09 真机实测：今天 120→125、周三 66→282）。
   */
  total: number
}

/** 按 modifiedAt 的 dateLabel 归并同组；组间按组首 modifiedAt 降序（新→旧），无日期组殿后。
 *  @param dateCounts 服务端按本地日精确计数（key = yyyy-MM-dd 本地日，见
 *    format.localDayKey；口径与 App 侧 core:model localDayKey 同源）。 */
export function groupAlbumsByDate(
  items: AssetSummary[],
  dateCounts: Map<string, number> = new Map(),
): AlbumGroup[] {
  const byLabel = new Map<string, AssetSummary[]>()
  for (const a of items) {
    const label = dateLabel(a.modifiedAt)
    const bucket = byLabel.get(label)
    if (bucket) bucket.push(a)
    else byLabel.set(label, [a])
  }
  return [...byLabel.entries()]
    .map(([label, assets]) => ({
      label,
      assets,
      // 同标签必同日（标签由日界唯一确定）：取组首项的本地日键查精确总数
      total: dateCounts.get(localDayKey(assets[0]?.modifiedAt)) ?? assets.length,
    }))
    .sort((x, y) => {
      if (!x.label) return 1
      if (!y.label) return -1
      const tx = Date.parse(x.assets[0]?.modifiedAt ?? '') || 0
      const ty = Date.parse(y.assets[0]?.modifiedAt ?? '') || 0
      return ty - tx
    })
}
