import { useEffect, useMemo, useState } from 'react'
import { toast } from 'sonner'
import { LOCALE_ZH } from '@/lib/constants'
import { formatBytes } from '@/lib/format'
import { batchFailureReason } from '@/lib/batch'
import { BatchOpsPanel } from '@/components/ui/batch-ops-panel'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { MultiSelectBar } from '@/components/ui/multi-select-bar'
import { LoadingHint } from '@/components/ui/loading-hint'
import { ManagePageHeader, ManageQueryError } from '@/components/manage/ManagePageHeader'
import {
  useDeleteTrashItem, useEmptyTrash, useRestoreTrash, useTrash,
} from '@/hooks/use-trash'
import { useBatchRunner } from '@/hooks/use-batch-runner'
import { useMultiSelect } from '@/hooks/use-multi-select'
import { runBatchWithToast } from '@/lib/batch-run'
import type { TrashItem } from '@/api/generated'

/**
 * 回收站页（维护页「回收站」入口卡 → /app/maintenance/trash）。
 * 删除资产 = 移入回收站（铁律 4）；本页提供恢复 / 彻底删除 / 清空，
 * 后两者是显式的物理删除管理操作——全部 confirm 二次确认。
 *
 * F6 批量多选：「多选」切换钮进入多选态（行首 checkbox；Esc/再点退出），
 * MultiSelectBar 提供全选 + 批量恢复/批量彻底删除；批量执行走共享
 * useBatchRunner 逐条循环既有单条 mutation（协议零改动，失败不中断整批，
 * 进度/失败汇总见 BatchOpsPanel），失效链路 = 各 mutation 自身 onSuccess + SSE。
 */

/** 复选框列宽（px）：只容一个选择框（F6 批选列） */
const SELECT_COL_PX = 36
/** 原路径列最大宽（px）：超长单行截断省略，完整路径由 title 悬停可见 */
const PATH_COL_MAX_PX = 300

export default function TrashPage() {
  const query = useTrash()
  const { data: items = [], isLoading } = query
  const restore = useRestoreTrash()
  const removeOne = useDeleteTrashItem()
  const emptyAll = useEmptyTrash()
  const runner = useBatchRunner()
  // 单条操作互斥：恢复/彻底删除/清空三者任一在途时，其余入口（含下方批量按钮与清空）一并禁用，
  // 防同一 id 被并发的单条与批量操作重复提交（单条失败重试语义见 doRemoveOne/doEmpty 回调）。
  const singleOpPending = restore.isPending || removeOne.isPending || emptyAll.isPending || query.isError

  const doRestore = (it: TrashItem): void => {
    if (singleOpPending) return
    restore.mutate(it.id ?? '', {
      onSuccess: () => toast.success(`「${it.fileName}」已恢复（冲突时自动重命名）`),
      onError: (err) => toast.error(`恢复失败：${batchFailureReason(err)}`),
    })
  }

  const doRemoveOne = (it: TrashItem): void => {
    if (singleOpPending) return
    removeOne.mutate(it.id ?? '', {
      onSuccess: () => {
        toast.success('已彻底删除')
        setRemoveTarget(null)
      },
      // 保留确认弹窗与目标文件，用户可以修正问题后直接重试。
      onError: (err) => toast.error(`删除失败：${batchFailureReason(err)}`),
    })
  }

  const doEmpty = (): void => {
    if (singleOpPending) return
    emptyAll.mutate(undefined, {
      onSuccess: () => {
        toast.success('回收站已清空')
        setEmptyConfirmOpen(false)
      },
      onError: (err) => toast.error(`清空失败：${batchFailureReason(err)}`),
    })
  }

  // W-2：window.confirm 换 ConfirmDialog（原型风格二次确认），状态记待确认目标
  const [removeTarget, setRemoveTarget] = useState<TrashItem | null>(null)
  const [emptyConfirmOpen, setEmptyConfirmOpen] = useState(false)
  // F6：批量彻底删除的 danger 二次确认弹窗
  const [batchPurgeOpen, setBatchPurgeOpen] = useState(false)

  // 批量执行期/批量弹窗展开期冻结选集（执行期禁改选集）
  const selectionLocked = runner.state.running || batchPurgeOpen
  // 弹窗展开期间 Esc 让位给弹窗自身的关闭，不叠加退出多选态
  const escEnabled = removeTarget === null && !emptyConfirmOpen && !batchPurgeOpen
  const select = useMultiSelect({ locked: selectionLocked, escEnabled })
  // effect/memo 用的方法解构取稳定引用（useCallback 产物），依赖数组不依赖 select 对象本身
  const { isSelected, prune } = select

  const ids = useMemo(
    () => items.map((it) => it.id ?? '').filter((id) => id !== ''),
    [items],
  )
  const selectedItems = useMemo(
    () => items.filter((it) => it.id !== undefined && isSelected(it.id)),
    [items, isSelected],
  )

  // 列表刷新后选集修剪：始终指向仍在回收站的行（执行期锁定下为 no-op，
  // 终局 selectOnly 只留失败项，随后的失效重取不会把它清掉）
  useEffect(() => {
    prune(ids)
  }, [ids, prune])

  // 批量执行胶水收敛（lib/batch-run.ts）：id 守卫/toast/selectOnly 收口共享，
  // 调用点只留差异部分（action 文案 + 单条 mutateAsync）；targets = 选集快照
  const runBatchRestore = (): void => {
    if (singleOpPending || runner.state.running) return
    const targets = selectedItems
    runBatchWithToast(runner, {
      action: '批量恢复',
      targets,
      runOneById: (id) => restore.mutateAsync(id),
      selectOnly: select.selectOnly,
    })
  }

  const runBatchPurge = (): void => {
    if (singleOpPending || runner.state.running) return
    const targets = selectedItems
    setBatchPurgeOpen(false)
    runBatchWithToast(runner, {
      action: '彻底删除',
      targets,
      runOneById: (id) => removeOne.mutateAsync(id),
      selectOnly: select.selectOnly,
    })
  }

  const totalBytes = items.reduce((acc, it) => acc + (it.sizeBytes ?? 0), 0)
  const allSelected = ids.length > 0 && selectedItems.length === ids.length

  return (
    <div className="page manage-workspace" id="page-maintenance-trash">
      <ManagePageHeader title="回收站" description="先核对并恢复需要的文件；彻底删除与清空仍需独立确认" />
      <div className="manage-summary"><span>{query.data ? `${items.length} 项待处理` : '条目数待读取'}</span><span>{query.data ? formatBytes(totalBytes) : '容量待读取'}</span><span>恢复冲突时自动重命名</span></div>
      {query.isError && <ManageQueryError retry={() => { void query.refetch() }}>回收站读取失败{query.data ? '，保留上次列表，恢复/清理暂不可用' : '，不能据此判断回收站为空'}</ManageQueryError>}

      <div className="settings-actions">
        <button
          className="pill manage-danger-action"
          type="button"
          onClick={() => setEmptyConfirmOpen(true)}
          disabled={singleOpPending || runner.state.running || items.length === 0}
        >
          清空回收站
        </button>
        <span className="save-tip" hidden={emptyAll.isPending}>物理删除不可恢复</span>
        <button
          className={`pill${select.active ? ' active' : ''}`}
          type="button"
          disabled={selectionLocked || items.length === 0}
          title="进入/退出多选（多选态可批量恢复/彻底删除，Esc 退出）"
          onClick={() => (select.active ? select.exit() : select.enter())}
        >
          {select.active ? '退出多选' : '多选'}
        </button>
      </div>

      {select.active && items.length > 0 && (
        <MultiSelectBar
          selectedCount={selectedItems.length}
          totalCount={ids.length}
          allSelected={allSelected}
          locked={selectionLocked}
          selectAllLabel="全选"
          onToggleAll={() => (allSelected ? select.clear() : select.selectAll(ids))}
          onExit={select.exit}
        >
          <button
            className="pill"
            type="button"
            disabled={selectedItems.length === 0 || runner.state.running || singleOpPending}
            onClick={runBatchRestore}
          >
            批量恢复
          </button>
          <button
            className="pill"
            type="button"
            disabled={selectedItems.length === 0 || runner.state.running || singleOpPending}
            onClick={() => setBatchPurgeOpen(true)}
          >
            批量彻底删除
          </button>
        </MultiSelectBar>
      )}

      {/* 批量进度/失败汇总（上传队列同款交互语言；失败明细页内可滚动核对） */}
      <BatchOpsPanel state={runner.state} onDismiss={runner.reset} />

      <div className="rank-card manage-table-scroll">
        {isLoading ? (
          <LoadingHint />
        ) : query.isError && query.data === undefined ? <p className="grid-empty">请重新读取回收站。</p> : items.length === 0 ? (
          <p className="grid-empty">回收站是空的——删除资产后会出现在这里。</p>
        ) : (
          <table className="log-table">
            <thead>
              <tr>
                {select.active && <th style={{ width: SELECT_COL_PX }} aria-label="选择" />}
                <th>文件名</th><th>原路径</th><th>大小</th><th>删除时间</th><th>操作</th>
              </tr>
            </thead>
            <tbody>
              {items.map((it) => {
                const id = it.id ?? ''
                const checked = id !== '' && select.isSelected(id)
                return (
                  <tr key={it.id} className={checked ? 'ms-row-selected' : undefined}>
                    {select.active && (
                      <td>
                        <input
                          type="checkbox"
                          className="qm-check"
                          checked={checked}
                          disabled={selectionLocked}
                          onChange={() => select.toggle(id)}
                          aria-label={`选择 ${it.fileName ?? ''}`}
                        />
                      </td>
                    )}
                    <td>{it.fileName}</td>
                    <td style={{ maxWidth: PATH_COL_MAX_PX, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={it.originalPath}>{it.originalPath}</td>
                    <td>{formatBytes(it.sizeBytes ?? 0)}</td>
                    <td>{it.deletedAt ? new Date(it.deletedAt).toLocaleString(LOCALE_ZH) : '-'}</td>
                    <td>
                      <button
                        className="pill"
                        type="button"
                        disabled={singleOpPending || runner.state.running}
                        onClick={() => doRestore(it)}
                      >
                        {restore.isPending && restore.variables === it.id ? '恢复中…' : '恢复'}
                      </button>
                      <button
                        className="pill"
                        type="button"
                        disabled={singleOpPending || runner.state.running}
                        onClick={() => setRemoveTarget(it)}
                      >
                        {removeOne.isPending && removeTarget?.id === it.id ? '删除中…' : '彻底删除'}
                      </button>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
      </div>

      {/* 彻底删除（物理删除类 → danger 深色强调） */}
      <ConfirmDialog
        open={removeTarget !== null}
        title={`彻底删除「${removeTarget?.fileName ?? ''}」？`}
        description="物理删除不可恢复。"
        confirmText={removeOne.isPending ? '正在彻底删除…' : '彻底删除'}
        cancelText="取消"
        danger
        busy={removeOne.isPending}
        keepOpenOnConfirm
        onConfirm={() => {
          const it = removeTarget
          if (it) doRemoveOne(it)
        }}
        onOpenChange={(next) => {
          if (!next && !removeOne.isPending) setRemoveTarget(null)
        }}
      />

      {/* F6 批量彻底删除（danger 二次确认；文案明示数量与「彻底删除不可恢复」） */}
      <ConfirmDialog
        open={batchPurgeOpen}
        title={`彻底删除选中的 ${selectedItems.length} 个文件？`}
        description={`这 ${selectedItems.length} 个文件将被永久删除，物理删除不可恢复，无法再从回收站找回。`}
        confirmText={`彻底删除（${selectedItems.length}）`}
        cancelText="取消"
        danger
        onConfirm={runBatchPurge}
        onOpenChange={setBatchPurgeOpen}
      />

      {/* 清空回收站（清空类 → danger 深色强调）；空态由按钮 disabled 拦截，不进弹窗 */}
      <ConfirmDialog
        open={emptyConfirmOpen}
        title="清空回收站？"
        description={`${items.length} 个文件将被物理删除，不可恢复。`}
        confirmText={emptyAll.isPending ? '正在清空…' : '清空'}
        cancelText="取消"
        danger
        busy={emptyAll.isPending}
        keepOpenOnConfirm
        onConfirm={doEmpty}
        onOpenChange={(next) => {
          if (!next && !emptyAll.isPending) setEmptyConfirmOpen(false)
        }}
      />
    </div>
  )
}
