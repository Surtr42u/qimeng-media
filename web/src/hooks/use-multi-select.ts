/**
 * 列表多选选集管理 hook（F6 文件管理页 DirFileList / 回收站页 TrashPage 共用；
 * 代码卫生 6：第 2 次出现即抽共享 hook）：进入/退出多选态、toggle/全选/清空、
 * Esc 退出。执行期锁定：locked=true（批量请求在途/确认弹窗展开）时一切改选集
 * 入口 no-op——批量执行中行会随成功逐条消失，途中的选集变更既不可见也不可撤销。
 * 纯 UI 状态零 API 依赖（铁律 7）；选集 id 语义 = 调用方列表行的稳定 id。
 */

import { useCallback, useEffect, useState } from 'react'

export interface MultiSelectOptions {
  /** 批量执行中/确认弹窗展开时传 true：toggle/全选/清空/Esc 全部冻结 */
  locked?: boolean
  /** false 时 Esc 不退出多选态（弹窗打开期间让位给弹窗自身的 Esc 关闭，避免一按两清） */
  escEnabled?: boolean
}

export function useMultiSelect(options: MultiSelectOptions = {}) {
  const { locked = false, escEnabled = true } = options
  const [active, setActive] = useState(false)
  const [selected, setSelected] = useState<ReadonlySet<string>>(() => new Set())

  /** 进入多选态（不清既有选集——上次退出的选择在重进时保留，属自然语义） */
  const enter = useCallback(() => setActive(true), [])

  /** 退出多选态并清空选集（执行期锁定下 no-op） */
  const exit = useCallback(() => {
    if (locked) return
    setActive(false)
    setSelected(new Set())
  }, [locked])

  const toggle = useCallback(
    (id: string) => {
      if (locked) return
      setSelected((prev) => {
        const next = new Set(prev)
        if (next.has(id)) next.delete(id)
        else next.add(id)
        return next
      })
    },
    [locked],
  )

  /** 全选给定 id 集（调用方传当前列表全量 id；锁定下 no-op） */
  const selectAll = useCallback(
    (ids: readonly string[]) => {
      if (locked) return
      setSelected(new Set(ids))
    },
    [locked],
  )

  const clear = useCallback(() => {
    if (locked) return
    setSelected(new Set())
  }, [locked])

  /** 选集重置为给定 id 集：批量收尾只保留失败项（可原地重试），全成功即清空。
   *  审查清偿（F6·P2-1）：本方法唯一调用方是 useBatchRunner 的 onFinished 终局
   *  回调——回调经弹窗确认渲染的闭包进入，若带 locked 守卫则恒捕获 true 成为
   *  永不生效的死机制（终局收敛此前实际由列表刷新后的 prune 兜底达成）。
   *  终局回调是程序事件非用户输入入口，不在执行期锁的禁改面内，去守卫后
   *  「终局只留失败项」名副其实；用户入口（toggle/selectAll/clear/exit/Esc）
   *  的锁定语义不变。 */
  const selectOnly = useCallback((ids: readonly string[]) => {
    setSelected(new Set(ids))
  }, [])

  /** 选集对给定合法 id 集做修剪（剔除已不存在的行）：列表刷新后选集始终指向
   *  可见行，phantom 计数不会出现。执行期锁定下 no-op（改选集是用户操作的
   *  禁区，修剪是数据一致性，留给终局 selectOnly/解锁后的下次刷新处理）。 */
  const prune = useCallback(
    (validIds: readonly string[]) => {
      if (locked) return
      const valid = new Set(validIds)
      setSelected((prev) => {
        if (prev.size === 0) return prev
        const next = new Set([...prev].filter((id) => valid.has(id)))
        return next
      })
    },
    [locked],
  )

  // Esc 退出多选态（需求口径）；弹窗展开期间 escEnabled=false 不抢弹窗的 Esc
  useEffect(() => {
    if (!active || locked || !escEnabled) return
    const onKey = (e: KeyboardEvent): void => {
      if (e.key !== 'Escape') return
      setActive(false)
      setSelected(new Set())
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [active, locked, escEnabled])

  const isSelected = useCallback((id: string) => selected.has(id), [selected])

  return {
    active,
    selectedCount: selected.size,
    isSelected,
    enter,
    exit,
    toggle,
    selectAll,
    clear,
    selectOnly,
    prune,
  }
}
