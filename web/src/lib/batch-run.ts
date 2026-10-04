/**
 * 批量执行胶水（F6 第 2 批收敛，代码卫生 6 第 2 次出现即抽共享）：DirFileList
 * （批量删除/批量移动）与 TrashPage（批量恢复/批量彻底删除）四处 runner.run
 * 调用 25 行同构——id 常量守卫、id/label 取值、终局 toast（失败/成功两口径）
 * 与 selectOnly(failures) 选集收口完全一致，差异只有 action 文案与单条执行体。
 * 收敛后调用点只剩差异部分；toast 文案、selectOnly 行为与逐条 mutateAsync
 * 调用链路保持原样（UI 冻结批，逐字节口径）。
 * 分层：toast 是 IO，不进 lib/batch.ts（该文件是纯函数且行为由 batch.test.ts
 * 锁定）；runner 以结构化最小面传入而不 import hooks——保持 lib←hooks 单向
 * （ADR-0008 分层纪律的 web 侧对应）。
 */

import { toast } from 'sonner'
import { failureListText, formatBatchSummary, type BatchOutcome } from '@/lib/batch'

/** 可批量条目最小面：选集元素须可取稳定 id（失败记录主键）与展示名（失败
 *  清单行）——AssetSummary（文件行）与 TrashItem（回收站行）均满足 */
export interface BatchSelectableItem {
  id?: string
  fileName?: string
}

/** useBatchRunner.run 的结构化最小面（避免 lib→hooks 反向依赖；结构兼容即可） */
interface BatchRunnerLike {
  run: <T>(items: readonly T[], opts: {
    action: string
    runOne: (item: T) => Promise<unknown>
    id: (item: T) => string
    label: (item: T) => string
    onFinished?: (outcome: BatchOutcome) => void
  }) => Promise<unknown>
}

/**
 * 批量执行 + 终局收口（toast + 只留失败项的选集终局）。
 * 调用方保留的职责：快照选集（targets）与关闭确认弹窗——两句调用点代码，
 * 执行期选集锁定由 useMultiSelect 的 locked 承担，不在这里重复。
 */
export function runBatchWithToast<T extends BatchSelectableItem>(
  runner: BatchRunnerLike,
  opts: {
    /** 动作名（面板标题 + toast 主文案前缀） */
    action: string
    /** 已过滤 id 非空的选集快照 */
    targets: readonly T[]
    /** 单条执行体（既有单条 mutation 的 mutateAsync；reject = 该条失败，不中断整批） */
    runOneById: (id: string) => Promise<unknown>
    /** 终局选集收口（传 useMultiSelect 的 selectOnly，useCallback 稳定引用） */
    selectOnly: (ids: readonly string[]) => void
  },
): void {
  const { action, targets, runOneById, selectOnly } = opts
  void runner.run(targets, {
    action,
    // 回调内取常量守卫（TS 收窄不进回调参数）：防御兜底 reject = runner 记
    // 该条失败，不中断整批
    runOne: (item) => {
      const id = item.id
      if (id === undefined) return Promise.reject(new Error('条目缺少 id'))
      return runOneById(id)
    },
    id: (item) => item.id ?? '',
    label: (item) => item.fileName ?? '',
    onFinished: (outcome) => {
      if (outcome.failures.length > 0) {
        toast.error(formatBatchSummary(action, outcome), {
          description: failureListText(outcome.failures),
        })
      } else {
        toast.success(formatBatchSummary(action, outcome))
      }
      selectOnly(outcome.failures.map((f) => f.id))
    },
  })
}
