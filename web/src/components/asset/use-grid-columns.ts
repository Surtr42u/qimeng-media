/**
 * 网格列数共享状态（首页/全部页共用）。
 *
 * 为什么放 components 层：列数是纯展示偏好，与数据无关；hooks 目录是数据层（fetch/TanStack Query），
 * 展示偏好不归它管，放这里避免污染 hooks 边界（web/README.md 分层纪律）。
 *
 * 持久化 key：'qm_grid_columns'（交互规格：2-5 循环切换，多页共用=全局列数）。
 * 跨标签页同步：监听 window 'storage' 事件（同源 localStorage 变化会广播，移动端多标签页少见但桌面常见）。
 */

import { useEffect, useState } from 'react'

/** localStorage 键名（交互规格定值） */
const COLUMNS_STORAGE_KEY = 'qm_grid_columns'

/** 列数循环档位：2~5 档（交互规格定值：2-5 循环切换） */
const COLUMN_STEPS = [2, 3, 4, 5] as const

/** 列数循环的步进（每次点按 +1 档） */
const COLUMN_STEP = 1

/** 读取持久化列数；非法/缺失回退默认值（2 列=移动端一屏一档） */
function readStoredColumns(): number {
  const raw = localStorage.getItem(COLUMNS_STORAGE_KEY)
  const parsed = Number(raw)
  return COLUMN_STEPS.includes(parsed as (typeof COLUMN_STEPS)[number]) ? parsed : COLUMN_STEPS[0]
}

/** 列数状态：{ columns, cycle, setColumns }（cycle = 2→3→4→5→2 循环） */
export function useGridColumns() {
  const [columns, setColumns] = useState<number>(readStoredColumns)

  // 其他标签页改了列数 → 同步到本页（'storage' 事件仅在其它 doc 写入时触发）
  useEffect(() => {
    const onStorage = (event: StorageEvent) => {
      if (event.key === COLUMNS_STORAGE_KEY) setColumns(readStoredColumns())
    }
    window.addEventListener('storage', onStorage)
    return () => window.removeEventListener('storage', onStorage)
  }, [])

  const cycle = () => {
    setColumns((prev) => {
      const index = COLUMN_STEPS.indexOf(prev as (typeof COLUMN_STEPS)[number])
      const next = COLUMN_STEPS[(index + COLUMN_STEP) % COLUMN_STEPS.length]
      localStorage.setItem(COLUMNS_STORAGE_KEY, String(next))
      return next
    })
  }

  return { columns, cycle, setColumns }
}

/** 网格列数 → tailwind grid-cols-* 类名映射（完整类名写死：Tailwind JIT 不识别运行时拼接类名） */
export const GRID_COLUMNS_CLASSES = [
  'grid-cols-2',
  'grid-cols-3',
  'grid-cols-4',
  'grid-cols-5',
] as const

/** 列数 → 类名（越界回退 2 列） */
export function gridColumnsClass(columns: number): string {
  return GRID_COLUMNS_CLASSES[columns - 2] ?? GRID_COLUMNS_CLASSES[0]
}
