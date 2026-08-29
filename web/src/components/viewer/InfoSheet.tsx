/**
 * 信息面板（详情页"信息"入口 → Sheet，交互规格"信息面板"）。
 *
 * 内容：文件名、库内相对路径、类型、尺寸/时长、大小、修改/入库时间、出处（null 显示"其他"）、
 * 角色（容忍缺失）、作者（displayName；authors 端点后端 M3 才接线——本面板**不显示关注按钮**）、
 * 统计（浏览/播放/停留/最近浏览）、操作（移动到 [/admin/organize]、删除[见详情页缺口清单]）。
 *
 * 全部字段来自 AssetDetail（详情接口单数据源），组件不单独请求任何接口。
 */

import { Link } from 'react-router'
import { FileText, FolderOpen } from 'lucide-react'
import { Sheet, SheetContent, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { Button } from '@/components/ui/button'
import { formatBytes, formatDateTime, formatDuration, relativeTime } from '@/lib/format'
import type { AssetDetail, MediaType } from '@/api/generated'

/* ------------------------------ 文案常量（交互规格来源） ------------------------------ */

const TEXT_TYPE_IMAGE = '图片'
const TEXT_TYPE_ANIMATED = '动图'
const TEXT_TYPE_VIDEO = '视频'
const TEXT_TYPE_UNKNOWN = '未知类型'
const TEXT_SOURCE_UNKNOWN = '其他'
const TEXT_ACTION_MOVE = '移动到'
const TEXT_ACTION_DELETE = '删除'
const TEXT_ROW_FILE = '文件名'
const TEXT_ROW_PATH = '库内路径'
const TEXT_ROW_TYPE = '类型'
const TEXT_ROW_DIMENSION = '尺寸'
const TEXT_ROW_SIZE = '大小'
const TEXT_ROW_MODIFIED = '修改时间'
const TEXT_ROW_ADDED = '入库时间'
const TEXT_ROW_SOURCE = '出处'
const TEXT_ROW_CHARACTERS = '角色'
const TEXT_ROW_AUTHORS = '作者'
const TEXT_ROW_VIEWS = '浏览'
const TEXT_ROW_PLAYS = '播放'
const TEXT_ROW_DWELL = '累计浏览'
const TEXT_ROW_LAST_VIEWED = '最近浏览'
const TEXT_NA = '—'

/** 媒体类型文案（协议 MediaType 枚举全量） */
const MEDIA_TYPE_LABEL: Record<MediaType, string> = {
  image: TEXT_TYPE_IMAGE,
  animated_image: TEXT_TYPE_ANIMATED,
  video: TEXT_TYPE_VIDEO,
}

interface InfoSheetProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  detail: AssetDetail | undefined
}

export function InfoSheet({ open, onOpenChange, detail }: InfoSheetProps) {
  const mediaType = detail?.mediaType
  const typeLabel = mediaType ? (MEDIA_TYPE_LABEL[mediaType] ?? TEXT_TYPE_UNKNOWN) : TEXT_NA
  const dimension =
    detail?.width && detail?.height
      ? `${detail.width} × ${detail.height}`
      : detail?.durationMs
        ? formatDuration(detail.durationMs)
        : TEXT_NA
  const characters = detail?.characters ?? []
  const authors = detail?.authors ?? []

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="bottom" className="max-h-[85dvh] gap-4 overflow-y-auto">
        <SheetHeader>
          <SheetTitle>信息</SheetTitle>
        </SheetHeader>

        <dl className="flex flex-col gap-3 text-sm">
          <InfoRow label={TEXT_ROW_FILE}>{detail?.fileName ?? TEXT_NA}</InfoRow>
          <InfoRow label={TEXT_ROW_PATH}>
            {detail?.directory || detail?.relPath ? `${detail.directory ?? ''}/${detail.relPath ?? ''}`.replace(/\/+/g, '/') : TEXT_NA}
          </InfoRow>
          <InfoRow label={TEXT_ROW_TYPE}>{typeLabel}</InfoRow>
          <InfoRow label={TEXT_ROW_DIMENSION}>
            {mediaType === 'video' || !detail?.width ? dimension : `${dimension}`}
          </InfoRow>
          <InfoRow label={TEXT_ROW_SIZE}>{formatBytes(detail?.sizeBytes)}</InfoRow>
          <InfoRow label={TEXT_ROW_MODIFIED}>{formatDateTime(detail?.modifiedAt)}</InfoRow>
          <InfoRow label={TEXT_ROW_ADDED}>{formatDateTime(detail?.addedAt)}</InfoRow>
          <InfoRow label={TEXT_ROW_SOURCE}>{detail?.source ?? TEXT_SOURCE_UNKNOWN}</InfoRow>
          <InfoRow label={TEXT_ROW_CHARACTERS}>
            {characters.length > 0 ? characters.join('、') : TEXT_NA}
          </InfoRow>
          <InfoRow label={TEXT_ROW_AUTHORS}>
            {authors.length > 0 ? authors.map((a) => a.displayName ?? '—').join('、') : TEXT_NA}
          </InfoRow>
          <InfoRow label={TEXT_ROW_VIEWS}>{detail?.viewCount ?? 0}</InfoRow>
          <InfoRow label={TEXT_ROW_PLAYS}>{detail?.playCount ?? 0}</InfoRow>
          <InfoRow label={TEXT_ROW_DWELL}>
            {formatDuration((detail?.totalBrowseSeconds ?? 0) * 1000)}
          </InfoRow>
          <InfoRow label={TEXT_ROW_LAST_VIEWED}>
            {detail?.lastViewedAt ? relativeTime(detail.lastViewedAt) : TEXT_NA}
          </InfoRow>
        </dl>

        {/* 操作区：移动到（整理页入口）；删除入口见详情页（hooks 缺口，未接线） */}
        <div className="flex flex-col gap-2 border-t border-[var(--qm-divider)] pt-4">
          <Button variant="outline" asChild>
            <Link to="/admin/organize">
              <FolderOpen className="size-4" aria-hidden />
              {TEXT_ACTION_MOVE}
            </Link>
          </Button>
          <Button variant="ghost" disabled className="text-[var(--qm-text-muted)]">
            <FileText className="size-4" aria-hidden />
            {TEXT_ACTION_DELETE}
            <span className="sr-only">（等待 hooks 支持）</span>
          </Button>
        </div>
      </SheetContent>
    </Sheet>
  )
}

/** 信息行：键值对（键左值右，值截长） */
function InfoRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-baseline justify-between gap-[var(--qm-space-2)]">
      <dt className="shrink-0 text-xs text-[var(--qm-text-muted)]">{label}</dt>
      <dd className="truncate text-right">{children}</dd>
    </div>
  )
}
