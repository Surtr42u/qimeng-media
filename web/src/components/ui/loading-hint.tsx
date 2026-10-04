/**
 * 加载占位行（卫生约束 6）：`<p className="grid-empty">加载中…</p>` 的单一来源，
 * 此前在多文件手抄，文案或类名改动需逐处同步。className 覆盖页面级提示行类
 * （a-empty/m-note/rank-note 等，缺省 grid-empty 不变）；children 覆盖文案
 * （如「目录加载中…」，缺省「加载中…」）。各换用点的最终 class 与文案保持
 * 原手抄形态逐字节不变（UI 冻结口径）。纯展示、零状态、不调 API（铁律 7）。
 */
import type { ReactNode } from 'react'

export function LoadingHint({
  className = 'grid-empty',
  children = '加载中…',
}: {
  className?: string
  children?: ReactNode
}) {
  return <p className={className}>{children}</p>
}
