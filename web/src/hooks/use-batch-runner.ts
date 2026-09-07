/**
 * 批量执行器 hook（F6 两页共用；代码卫生 6：第 2 次出现即抽共享函数）。
 * 职责：把「逐条循环调用既有单条操作」包成统一状态机——严格串行、单条失败
 * 收集后继续（不中断整批）、进度 done/total、终局回调。
 * 查询失效不在本 hook：runOne 由调用方传既有 mutation 的 mutateAsync
 * （useDeleteAsset/useMoveAsset/useRestoreTrash/useDeleteTrashItem），每条
 * 成功各自触发其 onSuccess 失效 + SSE 双保险——与单条操作同一条失效链路，
 * 本 hook 不另立失效口径。
 * 交互口径复用上传队列语言（进度条 + 完成计数 + 失败明细行），进度展示在
 * 页内 BatchOpsPanel；ref 防重入保证双击确认不会起第二条并行队列。
 */

import { useCallback, useRef, useState } from 'react'
import { batchFailureReason, type BatchFailure, type BatchOutcome } from '@/lib/batch'

/** 批量执行快照（渲染口径：BatchOpsPanel 的进度条/完成计数/失败清单） */
export interface BatchState {
  /** 是否有批量在途（两页据此锁定选集与动作按钮） */
  running: boolean
  done: number
  total: number
  /** 最近一次批量的失败清单（空 = 全部成功或尚未跑过批量） */
  failures: BatchFailure[]
  /** 动作名（「批量移入回收站」等，面板标题用） */
  action: string
}

const IDLE_BATCH: BatchState = { running: false, done: 0, total: 0, failures: [], action: '' }

export interface BatchRunOptions<T> {
  /** 动作名（面板标题 + toast 主文案前缀） */
  action: string
  /** 每条的执行体（既有单条 mutation 的 mutateAsync；reject = 该条失败，不中断整批） */
  runOne: (item: T) => Promise<unknown>
  /** 条目稳定 id（失败记录主键） */
  id: (item: T) => string
  /** 条目展示名（失败清单行/toast 明细） */
  label: (item: T) => string
  /** 终局回调（toast/选集收尾在这里做；本 hook 不碰 toast） */
  onFinished?: (outcome: BatchOutcome) => void
}

export function useBatchRunner() {
  const [state, setState] = useState<BatchState>(IDLE_BATCH)
  // 批量在途标志经 ref 防重入（setState 是异步镜像，闭包读 state 判重入会漏）
  const runningRef = useRef(false)

  const run = useCallback(
    async <T,>(items: readonly T[], opts: BatchRunOptions<T>): Promise<void> => {
      if (runningRef.current || items.length === 0) return
      runningRef.current = true
      setState({ running: true, done: 0, total: items.length, failures: [], action: opts.action })
      const failures: BatchFailure[] = []
      let succeeded = 0
      for (const item of items) {
        try {
          await opts.runOne(item)
          succeeded += 1
        } catch (err) {
          failures.push({
            id: opts.id(item),
            label: opts.label(item),
            reason: batchFailureReason(err),
          })
        }
        // 每条终态即推进计数并同步失败清单（面板逐条可读，对齐上传队列节奏）
        setState((prev) => ({ ...prev, done: prev.done + 1, failures: [...failures] }))
      }
      runningRef.current = false
      setState({ running: false, done: items.length, total: items.length, failures, action: opts.action })
      opts.onFinished?.({ succeeded, failures })
    },
    [],
  )

  /** 收起批量面板（回空闲态；仅终态后由调用方触发，执行中不可收） */
  const reset = useCallback(() => setState(IDLE_BATCH), [])

  return { state, run, reset }
}
