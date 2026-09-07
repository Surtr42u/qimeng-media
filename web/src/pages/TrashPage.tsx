import { useEffect, useMemo, useState } from 'react'
import { toast } from 'sonner'
import { LOCALE_ZH } from '@/lib/constants'
import { formatBytes } from '@/lib/format'
import { BatchOpsPanel } from '@/components/ui/batch-ops-panel'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { MultiSelectBar } from '@/components/ui/multi-select-bar'
import {
  useDeleteTrashItem, useEmptyTrash, useRestoreTrash, useTrash,
} from '@/hooks/use-trash'
import { useBatchRunner } from '@/hooks/use-batch-runner'
import { useMultiSelect } from '@/hooks/use-multi-select'
import { failureListText, formatBatchSummary } from '@/lib/batch'
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

export default function TrashPage() {
  const { data: items = [], isLoading } = useTrash()
  const restore = useRestoreTrash()
  const removeOne = useDeleteTrashItem()
  const emptyAll = useEmptyTrash()
  const runner = useBatchRunner()

  const doRestore = (it: TrashItem): void => {
    restore.mutate(it.id ?? '', {
      onSuccess: () => toast.success(`「${it.fileName}」已恢复（冲突时自动重命名）`),
      onError: (err) => toast.error(`恢复失败：${err instanceof Error ? err.message : String(err)}`),
    })
  }

  const doRemoveOne = (it: TrashItem): void => {
    removeOne.mutate(it.id ?? '', {
      onSuccess: () => toast.success('已彻底删除'),
      onError: (err) => toast.error(`删除失败：${err instanceof Error ? err.message : String(err)}`),
    })
  }

  const doEmpty = (): void => {
    emptyAll.mutate(undefined, {
      onSuccess: () => toast.success('回收站已清空'),
      onError: (err) => toast.error(`清空失败：${err instanceof Error ? err.message : String(err)}`),
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

  const runBatchRestore = (): void => {
    const targets = selectedItems
    void runner.run(targets, {
      action: '批量恢复',
      runOne: (it) => restore.mutateAsync(it.id!),
      id: (it) => it.id ?? '',
      label: (it) => it.fileName ?? '',
      onFinished: (outcome) => {
        if (outcome.failures.length > 0) {
          toast.error(formatBatchSummary('批量恢复', outcome), {
            description: failureListText(outcome.failures),
          })
        } else {
          toast.success(formatBatchSummary('批量恢复', outcome))
        }
        select.selectOnly(outcome.failures.map((f) => f.id))
      },
    })
  }

  const runBatchPurge = (): void => {
    const targets = selectedItems
    setBatchPurgeOpen(false)
    void runner.run(targets, {
      action: '彻底删除',
      runOne: (it) => removeOne.mutateAsync(it.id!),
      id: (it) => it.id ?? '',
      label: (it) => it.fileName ?? '',
      onFinished: (outcome) => {
        if (outcome.failures.length > 0) {
          toast.error(formatBatchSummary('彻底删除', outcome), {
            description: failureListText(outcome.failures),
          })
        } else {
          toast.success(formatBatchSummary('彻底删除', outcome))
        }
        select.selectOnly(outcome.failures.map((f) => f.id))
      },
    })
  }

  const totalBytes = items.reduce((acc, it) => acc + (it.sizeBytes ?? 0), 0)
  const allSelected = ids.length > 0 && selectedItems.length === ids.length

  return (
    <div className="page" id="page-maintenance-trash">
      <div className="page-head">
        <h2>回收站</h2>
        <p>删除的文件先进回收站；恢复或彻底清除都在这里 · 共 {items.length} 项 / {formatBytes(totalBytes)}</p>
      </div>

      <div className="settings-actions">
        <button className="save-btn" type="button" onClick={() => setEmptyConfirmOpen(true)} disabled={emptyAll.isPending || items.length === 0}>
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
            disabled={selectedItems.length === 0 || runner.state.running}
            onClick={runBatchRestore}
          >
            批量恢复
          </button>
          <button
            className="pill"
            type="button"
            disabled={selectedItems.length === 0 || runner.state.running}
            onClick={() => setBatchPurgeOpen(true)}
          >
            批量彻底删除
          </button>
        </MultiSelectBar>
      )}

      {/* 批量进度/失败汇总（上传队列同款交互语言；失败明细页内可滚动核对） */}
      <BatchOpsPanel state={runner.state} onDismiss={runner.reset} />

      <div className="rank-card">
        {isLoading ? (
          <p className="grid-empty">加载中…</p>
        ) : items.length === 0 ? (
          <p className="grid-empty">回收站是空的——删除资产后会出现在这里。</p>
        ) : (
          <table className="log-table">
            <thead>
              <tr>
                {select.active && <th style={{ width: 36 }} aria-label="选择" />}
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
                    <td style={{ maxWidth: 300, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={it.originalPath}>{it.originalPath}</td>
                    <td>{formatBytes(it.sizeBytes ?? 0)}</td>
                    <td>{it.deletedAt ? new Date(it.deletedAt).toLocaleString(LOCALE_ZH) : '-'}</td>
                    <td>
                      <button className="pill" type="button" onClick={() => doRestore(it)}>恢复</button>
                      <button className="pill" type="button" onClick={() => setRemoveTarget(it)}>彻底删除</button>
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
        confirmText="彻底删除"
        cancelText="取消"
        danger
        onConfirm={() => {
          const it = removeTarget
          setRemoveTarget(null)
          if (it) doRemoveOne(it)
        }}
        onOpenChange={(next) => { if (!next) setRemoveTarget(null) }}
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
        confirmText="清空"
        cancelText="取消"
        danger
        onConfirm={() => {
          setEmptyConfirmOpen(false)
          doEmpty()
        }}
        onOpenChange={setEmptyConfirmOpen}
      />
    </div>
  )
}
