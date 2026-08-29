/**
 * 库管理区（管理页）：库列表 + 注册入口 + 扫描触发与扫描进度条。
 *
 * 扫描进度：本区订阅 SSE scan.progress（useSSEEvents 支持多实例回调——
 * SseBridge 已全局订阅 library.changed/upload.done，scan.progress 按约定由管理页自订阅，
 * 模块级单连接引用计数保证不会重复建连），仅按 libraryId 记录各自进度。
 */

import { useState } from 'react'
import { toast } from 'sonner'
import { Plus, RefreshCw } from 'lucide-react'
import type { Library, ScanProgressEvent } from '@/api/generated'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { useLibraries, useLibrariesScan } from '@/hooks/use-libraries'
import { useSSEEvents } from '@/hooks/use-sse-events'
import { apiErrorText } from '@/pages/_shared/error-text'
import {
  SCAN_PHASE_LABELS,
  SCAN_STATE_BADGE_CLASSES,
  SCAN_STATE_LABELS,
} from '@/pages/_shared/labels'
import { RegisterLibraryDialog } from '@/components/admin/RegisterLibraryDialog'

const TEXT_SECTION_TITLE = '库管理'
const TEXT_REGISTER_BUTTON = '注册库'
const TEXT_SCAN_BUTTON = '扫描'
const TEXT_SCANNING_LABEL = '扫描中'
const TEXT_EMPTY = '尚未注册媒体库——注册后扫描，文件即可入库'
const TEXT_SCANNER_UNAVAILABLE_HINT = '扫描器未装配（M3 前），注册库后可先行上传'

/** 每库的滚动扫描进度（SSE 事件落地缓存，服务端只推增量） */
type ScanProgressByLibrary = Record<string, ScanProgressEvent>

export function LibraryManager() {
  const libraries = useLibraries()
  const scan = useLibrariesScan()
  const [registerOpen, setRegisterOpen] = useState(false)
  const [progressByLibrary, setProgressByLibrary] = useState<ScanProgressByLibrary>({})

  useSSEEvents({
    onScanProgress: (event) => {
      setProgressByLibrary((prev) => ({ ...prev, [event.libraryId]: event }))
    },
  })

  const handleScan = (library: Library) => {
    if (!library.id) return
    scan.mutate(library.id, {
      onError: (error) => toast.error(apiErrorText(error)),
    })
  }

  return (
    <section className="flex flex-col gap-3">
      <div className="flex items-center justify-between">
        <h2 className="text-base font-bold">{TEXT_SECTION_TITLE}</h2>
        <Button size="sm" onClick={() => setRegisterOpen(true)}>
          <Plus aria-hidden />
          {TEXT_REGISTER_BUTTON}
        </Button>
      </div>
      {libraries.isLoading && (
        <div className="flex flex-col gap-2">
          <Skeleton className="h-24 w-full" />
          <Skeleton className="h-24 w-full" />
        </div>
      )}
      {libraries.isError && <p className="text-sm text-[var(--qm-destructive)]">{apiErrorText(libraries.error)}</p>}
      {libraries.isSuccess && (libraries.data ?? []).length === 0 && (
        <div className="rounded-xl border border-[var(--qm-border)] bg-[var(--qm-surface-soft)] p-6 text-center">
          <p className="text-sm">{TEXT_EMPTY}</p>
          <p className="mt-1 text-xs text-[var(--qm-text-muted)]">{TEXT_SCANNER_UNAVAILABLE_HINT}</p>
        </div>
      )}
      {(libraries.data ?? []).map((library) => (
        <LibraryCard
          key={library.id}
          library={library}
          progress={library.id ? progressByLibrary[library.id] : undefined}
          scanning={scan.isPending}
          onScan={() => handleScan(library)}
        />
      ))}
      <RegisterLibraryDialog open={registerOpen} onOpenChange={setRegisterOpen} />
    </section>
  )
}

interface LibraryCardProps {
  library: Library
  /** 本轮扫描的 SSE 进度（无则为 undefined） */
  progress?: ScanProgressEvent
  /** 本卡扫描按钮是否正在提交（触发中的禁用，多库同时扫描由服务端校验） */
  scanning: boolean
  onScan: () => void
}

/** 单库卡片：名称/路径/计数/扫描状态徽标 + 扫描按钮与进度条 */
function LibraryCard({ library, progress, scanning, onScan }: LibraryCardProps) {
  const scanState = library.scanState ?? 'idle'
  const isScanning = scanState === 'scanning'

  return (
    <div className="flex flex-col gap-2 rounded-xl border border-[var(--qm-border)] bg-[var(--qm-surface)] p-4">
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <h3 className="truncate text-sm font-semibold">{library.name}</h3>
            <Badge className={SCAN_STATE_BADGE_CLASSES[scanState]}>{SCAN_STATE_LABELS[scanState]}</Badge>
          </div>
          <p className="mt-0.5 truncate font-mono text-xs text-[var(--qm-text-muted)]" title={library.rootPath}>
            {library.rootPath}
          </p>
        </div>
        <Button
          size="sm"
          variant="outline"
          disabled={isScanning || scanning}
          onClick={onScan}
          aria-label={`扫描 ${library.name ?? ''}`}
        >
          {isScanning ? (
            <RefreshCw className="animate-spin" aria-hidden />
          ) : (
            <RefreshCw aria-hidden />
          )}
          {isScanning ? TEXT_SCANNING_LABEL : TEXT_SCAN_BUTTON}
        </Button>
      </div>
      <dl className="grid grid-cols-3 gap-2 text-center text-xs">
        <CountCell label="文件" value={library.fileCount} />
        <CountCell label="图片" value={library.imageCount} />
        <CountCell label="视频" value={library.videoCount} />
      </dl>
      {isScanning && progress && <ScanProgressBar progress={progress} />}
    </div>
  )
}

/** 计数单元格（文件名/图片/视频三列统一版式） */
function CountCell({ label, value }: { label: string; value?: number }) {
  return (
    <div className="rounded-lg bg-[var(--qm-chip-bg)] py-1.5">
      <dt className="text-[var(--qm-text-muted)]">{label}</dt>
      <dd className="font-mono text-sm font-semibold">{value ?? '--'}</dd>
    </div>
  )
}

/** 扫描进度条：scanned/total + 阶段与增改计数（total=0 时仅显示已扫描数，不产生 NaN 百分比） */
function ScanProgressBar({ progress }: { progress: ScanProgressEvent }) {
  const percent =
    progress.total > 0 ? Math.min(100, Math.round((progress.scanned / progress.total) * 100)) : null

  return (
    <div className="flex flex-col gap-1">
      <div className="h-1.5 overflow-hidden rounded-full bg-[var(--qm-chip-bg)]">
        <div
          className="h-full rounded-full bg-[var(--qm-primary)] transition-[width] duration-[var(--qm-duration-base)]"
          style={{ width: percent === null ? undefined : `${percent}%` }}
        />
      </div>
      <p className="text-xs text-[var(--qm-text-muted)]">
        {SCAN_PHASE_LABELS[progress.phase]}：已扫描 {progress.scanned}
        {progress.total > 0 ? ` / ${progress.total}` : ''}（新增 {progress.added} · 更新 {progress.updated}）
      </p>
    </div>
  )
}
