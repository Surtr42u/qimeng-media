/**
 * 数据区块空态一行提示。存在原因：数据页三组件（TrendChart/RankRowList/
 * ContentRankGrid）的「暂无数据」`<p className="rank-note">` 曾逐字重复三处
 * ——代码卫生 6「同一字面量第 2 次出现即抽共享」，收敛为单一来源；样式固定
 * rank-note（数据卡内小字注记位）。纯展示组件，零业务规则（ADR-0008）。
 */

import type { ReactNode } from 'react'

/** 空态文案，默认「暂无数据」，可通过 children 覆盖（暂无其他消费方，预留扩展） */
export function EmptyNote({ children = '暂无数据' }: { children?: ReactNode }) {
  return <p className="rank-note">{children}</p>
}
