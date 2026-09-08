import { useEffect, useMemo, useState } from 'react'
import { toast } from 'sonner'
import { FolderInput, Trash2 } from 'lucide-react'
import type { AssetSummary, MediaType } from '@/api/generated'
import { MoveDialog } from '@/components/manage/MoveDialog'
import {
  FileOpsDialogs,
  type FileOpsMode,
  type FileOpsTarget,
} from '@/components/manage/FileOpsDialogs'
import { BatchOpsPanel } from '@/components/ui/batch-ops-panel'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { MultiSelectBar } from '@/components/ui/multi-select-bar'
import { useAssetsInDirectory } from '@/hooks/use-assets'
import { useBatchRunner } from '@/hooks/use-batch-runner'
import { useDeleteAsset, useMoveAsset } from '@/hooks/use-file-ops'
import { useMultiSelect } from '@/hooks/use-multi-select'
import { failureListText, formatBatchSummary } from '@/lib/batch'
import { dirLabel, formatBytes } from '@/lib/format'

/**
 * 目录树选中目录的直接子文件行（B-5 目录树文件行接线）：数据源
 * useAssetsInDirectory（GET /assets 的 B-4 directory 过滤，一次拉全 limit=200，
 * query key 含 directory——切目录换键重取；挂资产根键，移动/删除的根键失效
 * 直接命中本清单，行随操作结果即时刷新）。行 = 文件名 + 类型/大小 metric +
 * 行内 hover 两操作（移动/重命名共用一个端点弹窗、删除）；弹窗编排复用共享 FileOpsDialogs
 * （MoveDialog / ConfirmDialog 删除语义，文案含「移入回收站」），本组件零
 * 直调 API（铁律 7）。行容器复用 .dir-row（与目录行同一 hover 操作语言）。
 *
 * F6 批量多选：工具行「多选」切换钮进入多选态（行首 checkbox；Esc/再点退出），
 * MultiSelectBar 提供全选本目录 + 批量删除/批量移动；批量执行走共享
 * useBatchRunner 逐条循环既有单条 mutation（协议零改动，失败不中断整批，
 * 进度/失败汇总见 BatchOpsPanel），失效链路 = 各 mutation 自身 onSuccess + SSE。
 */

/** MediaType 协议枚举 → 行内类型文案（单点定义；协议加枚举须同步此处） */
const MEDIA_TYPE_LABEL: Record<MediaType, string> = {
  image: '图片',
  animated_image: '动图',
  video: '视频',
}

/** FileOpsDialogs 常驻挂载（confirm 关闭动画依赖常驻），无操作时的空目标 */
const NO_TARGET: FileOpsTarget = { assetId: '', libraryId: '', directory: '', fileName: '' }

/** 批量确认弹窗态：none=全关；delete=批量删除确认；move=批量移动（MoveDialog batch 模式） */
type BatchDialog = 'none' | 'delete' | 'move'

export function DirFileList({ libraryId, directory }: { libraryId: string; directory: string }) {
  // isLoading（非 isFetching，B-8 P3 清偿）：失效重取/切目录换键期间保留旧列表
  // （hook 配 placeholderData: keepPreviousData），不再整段替换成「加载中…」闪烁；
  // 仅首次无数据时显示加载态
  const { data: files = [], isLoading } = useAssetsInDirectory(libraryId, directory, true)
  // 行内操作的打开弹窗态：mode + 操作目标（打开时刻快照，与上传队列表同口径）
  const [ops, setOps] = useState<{ mode: FileOpsMode; target: FileOpsTarget } | null>(null)
  // 批量弹窗态（删除确认 / 移动目标目录）
  const [batchDialog, setBatchDialog] = useState<BatchDialog>('none')

  const deleteAsset = useDeleteAsset()
  const moveAsset = useMoveAsset()
  const runner = useBatchRunner()

  // 批量执行期/批量弹窗展开期冻结选集：checkbox/全选禁改，计数与提交目标一致
  const selectionLocked = runner.state.running || batchDialog !== 'none'
  // 弹窗展开期间 Esc 让位给弹窗自身的关闭，不叠加退出多选态
  const escEnabled = ops === null && batchDialog === 'none'
  const select = useMultiSelect({ locked: selectionLocked, escEnabled })
  // effect/memo 用的方法解构取稳定引用（useCallback 产物），依赖数组不依赖 select 对象本身
  const { isSelected, prune } = select

  const ids = useMemo(
    () => files.map((f) => f.id ?? '').filter((id) => id !== ''),
    [files],
  )
  const selectedFiles = useMemo(
    () => files.filter((f) => f.id !== undefined && isSelected(f.id)),
    [files, isSelected],
  )

  // 列表刷新/切目录换键后选集修剪：始终指向当前目录可见行（执行期锁定下为
  // no-op，终局 selectOnly 只留失败项，随后的失效重取不会把它清掉）
  useEffect(() => {
    prune(ids)
  }, [ids, prune])

  const openOps = (mode: FileOpsMode, f: AssetSummary): void => {
    setOps({
      mode,
      target: { assetId: f.id ?? '', libraryId, directory, fileName: f.fileName ?? '' },
    })
  }

  const runBatchDelete = (): void => {
    const targets = selectedFiles
    setBatchDialog('none')
    void runner.run(targets, {
      action: '移入回收站',
      runOne: (f) => deleteAsset.mutateAsync(f.id!),
      id: (f) => f.id ?? '',
      label: (f) => f.fileName ?? '',
      onFinished: (outcome) => {
        if (outcome.failures.length > 0) {
          toast.error(formatBatchSummary('移入回收站', outcome), {
            description: failureListText(outcome.failures),
          })
        } else {
          toast.success(formatBatchSummary('移入回收站', outcome))
        }
        select.selectOnly(outcome.failures.map((f) => f.id))
      },
    })
  }

  const runBatchMove = (targetDir: string): void => {
    const targets = selectedFiles
    void runner.run(targets, {
      action: '批量移动',
      runOne: (f) => moveAsset.mutateAsync({ assetId: f.id!, targetDir }),
      id: (f) => f.id ?? '',
      label: (f) => f.fileName ?? '',
      onFinished: (outcome) => {
        if (outcome.failures.length > 0) {
          toast.error(formatBatchSummary('批量移动', outcome), {
            description: failureListText(outcome.failures),
          })
        } else {
          toast.success(formatBatchSummary('批量移动', outcome))
        }
        select.selectOnly(outcome.failures.map((f) => f.id))
      },
    })
  }

  const allSelected = ids.length > 0 && selectedFiles.length === ids.length

  return (
    <div className="dir-files">
      <p className="dir-files-title">
        「{dirLabel(directory)}」的直接子文件
        <b>{files.length}</b>
        <button
          className={`pill${select.active ? ' active' : ''}`}
          type="button"
          disabled={selectionLocked}
          title="进入/退出多选（多选态可批量删除/移动，Esc 退出）"
          onClick={() => (select.active ? select.exit() : select.enter())}
        >
          {select.active ? '退出多选' : '多选'}
        </button>
      </p>
      {select.active && files.length > 0 && (
        <MultiSelectBar
          selectedCount={selectedFiles.length}
          totalCount={ids.length}
          allSelected={allSelected}
          locked={selectionLocked}
          selectAllLabel="全选本目录"
          onToggleAll={() => (allSelected ? select.clear() : select.selectAll(ids))}
          onExit={select.exit}
        >
          <button
            className="pill"
            type="button"
            disabled={selectedFiles.length === 0 || runner.state.running}
            onClick={() => setBatchDialog('move')}
          >
            批量移动
          </button>
          <button
            className="pill"
            type="button"
            disabled={selectedFiles.length === 0 || runner.state.running}
            onClick={() => setBatchDialog('delete')}
          >
            批量删除
          </button>
        </MultiSelectBar>
      )}
      {isLoading ? (
        <p className="grid-empty">加载中…</p>
      ) : files.length === 0 ? (
        <p className="grid-empty">该目录下暂无文件。</p>
      ) : (
        <ul className="dir-file-list">
          {files.map((f) => {
            const id = f.id ?? ''
            const checked = id !== '' && select.isSelected(id)
            return (
              <li
                key={f.id}
                className={`dir-row dir-file-row${checked ? ' ms-row-selected' : ''}`}
              >
                {select.active && (
                  <input
                    type="checkbox"
                    className="qm-check"
                    checked={checked}
                    disabled={selectionLocked}
                    onChange={() => select.toggle(id)}
                    aria-label={`选择 ${f.fileName ?? ''}`}
                  />
                )}
                <span className="dir-file-name" title={f.fileName ?? ''}>{f.fileName}</span>
                <span className="dir-file-meta">
                  {f.mediaType ? MEDIA_TYPE_LABEL[f.mediaType] : '未知'} · {formatBytes(f.sizeBytes ?? 0)}
                </span>
                {!select.active && (
                  <span className="dir-actions">
                    <button
                      className="dir-action-btn"
                      type="button"
                      title="移动或重命名"
                      onClick={() => openOps('move', f)}
                    >
                      <FolderInput width={14} height={14} />
                      移动/重命名
                    </button>
                    <button
                      className="dir-action-btn"
                      type="button"
                      title="移入回收站（可在维护页恢复）"
                      onClick={() => openOps('delete', f)}
                    >
                      <Trash2 width={14} height={14} />
                      删除
                    </button>
                  </span>
                )}
              </li>
            )
          })}
        </ul>
      )}

      {/* 批量删除确认（danger；文案明示数量与去向=移入回收站，铁律 4 语义，逐条 DELETE） */}
      <ConfirmDialog
        open={batchDialog === 'delete'}
        title={`把选中的 ${selectedFiles.length} 个文件移入回收站？`}
        description="将逐个移入回收站并从媒体库移除（浏览/点赞等记录保留）。\n可在维护页「回收站」恢复。"
        confirmText={`移入回收站（${selectedFiles.length}）`}
        cancelText="取消"
        danger
        onConfirm={runBatchDelete}
        onOpenChange={(next) => {
          if (!next) setBatchDialog('none')
        }}
      />

      {/* 批量移动：复用 MoveDialog batch 模式选目标目录，逐条 POST move 不中断整批 */}
      {batchDialog === 'move' && (
        <MoveDialog
          open
          libraryId={libraryId}
          currentDir={directory}
          currentName=""
          batch={{ count: selectedFiles.length, onConfirm: runBatchMove }}
          onClose={() => setBatchDialog('none')}
        />
      )}

      {/* 批量进度/失败汇总（上传队列同款交互语言；失败明细页内可滚动核对） */}
      <BatchOpsPanel state={runner.state} onDismiss={runner.reset} />

      <FileOpsDialogs
        mode={ops?.mode ?? 'none'}
        target={ops?.target ?? NO_TARGET}
        onClose={() => setOps(null)}
      />
    </div>
  )
}
