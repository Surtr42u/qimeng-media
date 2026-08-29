/**
 * 统计页（/stats，底部第 4 Tab）：统计总览。
 *
 * 设计背景：GET /stats/overview、/stats/trends 当前是 501 stub（M3 推荐算法上线才实现，
 * 见 server/internal/httpapi/stubs.go）——接口已生成、数据未上线，故本页按"可用数据照常渲染、
 * 未上线数据渲染说明性空态（不弹错误）"设计：
 * - 概览卡：useQuery 调 generated getApiV1StatsOverview，就绪按 StatsOverview 字段渲染，
 *   出错（含 501）显示"M3 上线后可用"说明；
 * - 库概览子卡：useLibraries（数据可用，不依赖 501），渲染各库名称/文件计数/扫描状态徽标。
 */

import { useQuery } from '@tanstack/react-query'
import { getApiV1StatsOverview, type StatsOverview } from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'
import { Badge } from '@/components/ui/badge'
import { Skeleton } from '@/components/ui/skeleton'
import { useLibraries } from '@/hooks/use-libraries'
import { SCAN_STATE_BADGE_CLASSES, SCAN_STATE_LABELS } from '@/pages/_shared/labels'
import { formatBytes } from '@/lib/format'
import { cn } from '@/lib/utils'

/* ------------------------------ 文案常量 ------------------------------ */

const TEXT_TITLE = '统计'
const TEXT_SECTION_OVERVIEW = '总览'
const TEXT_SECTION_LIBRARIES = '媒体库概览'
const TEXT_STATS_PENDING_TITLE = '统计功能尚未上线'
const TEXT_STATS_PENDING_HINT = '浏览 / 播放统计将在 M3 推荐算法上线后开放，届时可查看趋势与排行'
const TEXT_NO_LIBRARIES = '还没有媒体库'
const TEXT_NO_LIBRARIES_HINT = '到管理页注册媒体库后，这里会显示各库文件计数与扫描状态'
const TEXT_LIBRARY_FILES_TEMPLATE = '共 {n} 个文件'

/** 概览指标顺序与中文标签（StatsOverview 字段子集=核心指标） */
const OVERVIEW_STATS: Array<{ key: keyof StatsOverview; label: string; kind: 'count' | 'bytes' }> = [
  { key: 'totalFiles', label: '文件总数', kind: 'count' },
  { key: 'imageCount', label: '图片', kind: 'count' },
  { key: 'videoCount', label: '视频', kind: 'count' },
  { key: 'totalSizeBytes', label: '总大小', kind: 'bytes' },
  { key: 'todayViews', label: '今日浏览', kind: 'count' },
  { key: 'totalViews', label: '累计浏览', kind: 'count' },
]

/** 计数展示：千分位可读；字段缺失显示 --（与 formatBytes 非法值口径一致） */
function formatCount(value: number | undefined): string {
  return value == null || !Number.isFinite(value) ? '--' : value.toLocaleString('zh-CN')
}

/** 概览单项取值：计数走 formatCount，字节数走 formatBytes */
function formatStatValue(item: (typeof OVERVIEW_STATS)[number], overview: StatsOverview): string {
  const value = overview[item.key]
  return item.kind === 'bytes' ? formatBytes(value) : formatCount(value)
}

export default function StatsPage() {
  // 后端当前 501 stub：query 必然进 error 态（错误在此被吞掉，只切换空态展示，不弹 toast）。
  // retry: false——501 是预期态而非瞬时故障，避免每次进页 1+3 次无效重试
  const overviewQuery = useQuery({
    queryKey: ['stats-overview'],
    queryFn: () => unwrapSdkResult(getApiV1StatsOverview()),
    retry: false,
  })
  const librariesQuery = useLibraries()
  const libraries = librariesQuery.data ?? []
  const overview = overviewQuery.data

  return (
    <div className="flex flex-col gap-4 px-4 py-4">
      <h1 className="text-lg font-bold">{TEXT_TITLE}</h1>

      {/* 总览：数据就绪渲染指标卡；出错（含 501）渲染说明性空态，不弹错误 */}
      <section className="flex flex-col gap-3">
        <h2 className="text-sm font-semibold text-[var(--qm-text-muted)]">{TEXT_SECTION_OVERVIEW}</h2>
        {overviewQuery.isLoading ? (
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
            {OVERVIEW_STATS.map((item) => (
              <Skeleton key={item.key} className="h-20 rounded-xl" />
            ))}
          </div>
        ) : overviewQuery.isError || !overview ? (
          <div className="flex flex-col items-center gap-2 rounded-xl border border-[var(--qm-border)] bg-[var(--qm-surface)] px-4 py-10 text-center">
            <p className="text-sm font-semibold">{TEXT_STATS_PENDING_TITLE}</p>
            <p className="text-xs text-[var(--qm-text-muted)]">{TEXT_STATS_PENDING_HINT}</p>
          </div>
        ) : (
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
            {OVERVIEW_STATS.map((item) => (
              <div
                key={item.key}
                className="rounded-xl border border-[var(--qm-border)] bg-[var(--qm-surface)] p-4"
              >
                <p className="text-xs text-[var(--qm-text-muted)]">{item.label}</p>
                <p className="mt-1 text-xl font-bold tabular-nums">{formatStatValue(item, overview)}</p>
              </div>
            ))}
          </div>
        )}
      </section>

      {/* 库概览：数据可用，不依赖 501 的统计端点 */}
      <section className="flex flex-col gap-3">
        <h2 className="text-sm font-semibold text-[var(--qm-text-muted)]">{TEXT_SECTION_LIBRARIES}</h2>
        {librariesQuery.isLoading ? (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {Array.from({ length: 2 }).map((_, i) => (
              <Skeleton key={i} className="h-20 rounded-xl" />
            ))}
          </div>
        ) : libraries.length === 0 ? (
          <div className="flex flex-col items-center gap-2 rounded-xl border border-[var(--qm-border)] bg-[var(--qm-surface)] px-4 py-8 text-center">
            <p className="text-sm font-semibold">{TEXT_NO_LIBRARIES}</p>
            <p className="text-xs text-[var(--qm-text-muted)]">{TEXT_NO_LIBRARIES_HINT}</p>
          </div>
        ) : (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {libraries.map((lib) => {
              // scanState 协议缺省按 idle 展示（labels Record 键为非空联合）
              const scanState = lib.scanState ?? 'idle'
              return (
                <div
                  key={lib.id ?? lib.name}
                  className="rounded-xl border border-[var(--qm-border)] bg-[var(--qm-surface)] p-4"
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className="min-w-0 truncate text-sm font-semibold" title={lib.name}>
                      {lib.name ?? '--'}
                    </span>
                    <Badge className={cn('shrink-0 font-normal', SCAN_STATE_BADGE_CLASSES[scanState])}>
                      {SCAN_STATE_LABELS[scanState]}
                    </Badge>
                  </div>
                  <p className="mt-1 text-xs text-[var(--qm-text-muted)]">
                    {TEXT_LIBRARY_FILES_TEMPLATE.replace('{n}', String(lib.fileCount ?? 0))}
                  </p>
                </div>
              )
            })}
          </div>
        )}
      </section>
    </div>
  )
}
