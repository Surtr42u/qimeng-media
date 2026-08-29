/**
 * 相册页（/albums）：按"出处分区"分组浏览（旧项目验证语义的 Web 对齐）。
 *
 * 分组语义：相册 = 按出处（source）分区，非手工建相册——M2 简化为一层
 * 出处分区列表；点进分区 = 跳转全部页并带 source 筛选（/all?source=<name>，
 * AllAssetsPage 由并行任务实现 source 参数支持，跳转按约定写好）。
 */

import { Link, useNavigate, useSearchParams } from 'react-router'
import { Image as ImageIcon } from 'lucide-react'
import type { SourceCount } from '@/api/generated'
import { Badge } from '@/components/ui/badge'
import { Skeleton } from '@/components/ui/skeleton'
import { Switch } from '@/components/ui/switch'
import { useSources } from '@/hooks/use-sources'
import { apiErrorText } from '@/pages/_shared/error-text'
import { UNKNOWN_SOURCE_LABEL } from '@/pages/_shared/labels'

const TEXT_TITLE = '相册'
const TEXT_COS_SWITCH = 'COS 分区'
const TEXT_COS_HINT = '包含 COS 作者关联文件（默认排除）'
const TEXT_EMPTY = '暂无文件，先上传或扫描'
const TEXT_EMPTY_HINT = '前往管理页注册媒体库并扫描'
const TEXT_EMPTY_ACTION = '去注册库'
const TEXT_FILE_UNIT = '张'
/** 分组卡首字图标取名称首字符（"其他"等空名兜底用第一个字符） */

/** COS 开关的 URL 参数键（页面状态存 searchParams 约定） */
const COS_PARAM_KEY = 'cos'

export default function AlbumsPage() {
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const includeCos = searchParams.get(COS_PARAM_KEY) === '1'
  const sources = useSources({ includeCos })

  return (
    <div className="flex flex-col gap-4 px-4 py-4">
      <div className="flex items-center justify-between gap-3">
        <h1 className="text-lg font-bold">{TEXT_TITLE}</h1>
        <label className="flex items-center gap-2">
          <span className="text-sm">{TEXT_COS_SWITCH}</span>
          <Switch
            checked={includeCos}
            onCheckedChange={(checked) => setSearchParams(checked ? { [COS_PARAM_KEY]: '1' } : {})}
          />
        </label>
      </div>
      {includeCos && <p className="text-xs text-[var(--qm-text-muted)]">{TEXT_COS_HINT}</p>}

      {sources.isLoading && (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
          <Skeleton className="h-24 w-full" />
          <Skeleton className="h-24 w-full" />
          <Skeleton className="h-24 w-full" />
        </div>
      )}
      {sources.isError && <p className="text-sm text-[var(--qm-destructive)]">{apiErrorText(sources.error)}</p>}
      {sources.isSuccess && (sources.data ?? []).length === 0 && (
        <div className="flex flex-col items-center gap-2 rounded-xl border border-[var(--qm-border)] bg-[var(--qm-surface-soft)] p-10 text-center">
          <ImageIcon className="size-8 text-[var(--qm-text-muted)]" aria-hidden />
          <p className="text-sm">{TEXT_EMPTY}</p>
          <p className="text-xs text-[var(--qm-text-muted)]">{TEXT_EMPTY_HINT}</p>
          <Link to="/admin" className="text-sm text-[var(--qm-primary)] underline-offset-4 hover:underline">
            {TEXT_EMPTY_ACTION}
          </Link>
        </div>
      )}
      {sources.isSuccess && (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
          {(sources.data ?? []).map((source) => (
            <SourceCard key={source.name ?? UNKNOWN_SOURCE_LABEL} source={source} onOpen={() => openSource(navigate, source)} />
          ))}
        </div>
      )}
    </div>
  )
}

/** 跳转全部页并带 source 筛选（协议 assets query source 与出处分同名；name=null → "其他"） */
function openSource(navigate: ReturnType<typeof useNavigate>, source: SourceCount): void {
  const name = source.name ?? UNKNOWN_SOURCE_LABEL
  void navigate(`/all?${new URLSearchParams({ source: name }).toString()}`)
}

interface SourceCardProps {
  source: SourceCount
  onOpen: () => void
}

/** 出处分区卡：名称（首字图标）+ 文件数 + 空格显示；点击进入该分区的全部文件 */
function SourceCard({ source, onOpen }: SourceCardProps) {
  const name = source.name ?? UNKNOWN_SOURCE_LABEL
  const initial = name.charAt(0) || UNKNOWN_SOURCE_LABEL
  return (
    <button
      type="button"
      onClick={onOpen}
      className="flex flex-col gap-2 rounded-xl border border-[var(--qm-border)] bg-[var(--qm-surface)] p-4 text-left transition-[border-color,box-shadow] duration-[var(--qm-duration-base)] hover:border-[var(--qm-primary)] hover:shadow-sm"
    >
      <span className="flex size-10 items-center justify-center rounded-full bg-[var(--qm-primary-soft)] font-bold text-[var(--qm-primary)]" aria-hidden>
        {initial}
      </span>
      <span className="min-w-0">
        <span className="block truncate text-sm font-semibold" title={name}>
          {name}
        </span>
        <span className="mt-0.5 flex items-center gap-1.5">
          <span className="font-mono text-xs text-[var(--qm-text-muted)]">{source.fileCount ?? 0}</span>
          <span className="text-xs text-[var(--qm-text-muted)]">{TEXT_FILE_UNIT}</span>
          <Badge variant="outline" className="text-[10px]">
            分区
          </Badge>
        </span>
      </span>
    </button>
  )
}
