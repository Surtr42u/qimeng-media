/**
 * 维护页（正式 UI）：性能监控 + 维护工具合并为一页（上下滚动，不做内部 Tabs）。
 *
 * V4 结构调整：原独立性能页（PerfMonitors）作为上半「性能监控」区块并入，
 * 下半「维护工具」= 文件管理 / 回收站入口卡 + 用户端错误日志表（全部 mock）。
 */
import { Card } from '@/components/tremor/card'
import { Badge } from '@/components/tremor/badge'
import { Button } from '@/components/tremor/button'
import { ProgressBar } from '@/components/tremor/progress-bar'
import {
  TableRoot,
  Table,
  TableHead,
  TableHeaderCell,
  TableBody,
  TableRow,
  TableCell,
} from '@/components/tremor/table'
import { RiFolderChartLine, RiDeleteBin6Line } from '@remixicon/react'
import { Reveal } from './widgets'
import PerfMonitors from './PerfMonitors'
import { MOCK_LOGS, MOCK_TRASH, LOG_LEVEL_BADGE } from './mock'

/** 区块标题（性能监控 / 维护工具） */
const SECTION_TITLES = {
  perf: '性能监控',
  tools: '维护工具',
} as const

/** 入口卡文案 */
const ENTRY_TEXT = {
  files: {
    title: '文件管理',
    desc: '资产文件浏览、整理与去重（正式版整理功能接入点）',
    badge: '接入点预留',
    action: '打开文件管理',
  },
  trash: {
    title: '回收站',
    desc: '已删除资产先入回收站，可恢复或彻底清除',
  },
} as const

/** 日志表标题与空态 */
const LOG_TABLE = {
  title: '用户端崩溃 / 错误日志',
  desc: '最近上报的客户端异常（mock 数据）',
  columns: ['时间', '级别', '来源', '消息摘要'],
} as const

export default function MaintenancePage() {
  const trashPercent = Math.round((MOCK_TRASH.usedGB / MOCK_TRASH.capacityGB) * 100)

  return (
    <div className="space-y-6">
      {/* 上半：性能监控区块（原独立性能页内容：圆环 + 指标卡 + 网络负载图） */}
      <section aria-label={SECTION_TITLES.perf}>
        <h2 className="text-sm font-semibold">{SECTION_TITLES.perf}</h2>
        <p className="mt-0.5 text-xs text-muted-foreground">服务器硬件实时状态（mock 数据）</p>
        <div className="mt-4">
          <PerfMonitors />
        </div>
      </section>

      {/* 下半：维护工具区块（入口卡 + 日志表） */}
      <section aria-label={SECTION_TITLES.tools} className="space-y-6">
        <div>
          <h2 className="text-sm font-semibold">{SECTION_TITLES.tools}</h2>
          <p className="mt-0.5 text-xs text-muted-foreground">文件管理与客户端异常排查</p>
        </div>

        {/* 入口卡 */}
        <Reveal index={0}>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Card className="flex flex-col gap-3">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2.5">
                  <RiFolderChartLine className="size-5 text-blue-500 dark:text-blue-500" aria-hidden={true} />
                  <h3 className="text-sm font-semibold">{ENTRY_TEXT.files.title}</h3>
                </div>
                <Badge variant="default">{ENTRY_TEXT.files.badge}</Badge>
              </div>
              <p className="text-sm text-muted-foreground">{ENTRY_TEXT.files.desc}</p>
              {/* 原型无行为：正式植入时跳转整理功能 */}
              <Button variant="secondary" className="mt-auto w-fit" type="button">
                {ENTRY_TEXT.files.action}
              </Button>
            </Card>

            <Card className="flex flex-col gap-3">
              <div className="flex items-center gap-2.5">
                <RiDeleteBin6Line className="size-5 text-blue-500 dark:text-blue-500" aria-hidden={true} />
                <h3 className="text-sm font-semibold">{ENTRY_TEXT.trash.title}</h3>
              </div>
              <div className="flex items-baseline gap-2">
                <span className="text-2xl font-semibold tabular-nums">{MOCK_TRASH.count}</span>
                <span className="text-sm text-muted-foreground">条待处理</span>
              </div>
              <div className="mt-auto space-y-1.5">
                <div className="flex justify-between text-xs text-muted-foreground">
                  <span>占用 {MOCK_TRASH.usedGB} GB</span>
                  <span>容量 {MOCK_TRASH.capacityGB} GB</span>
                </div>
                <ProgressBar value={trashPercent} aria-label="回收站占用比例" />
              </div>
            </Card>
          </div>
        </Reveal>

        {/* 日志表 */}
        <Reveal index={1}>
          <Card>
            <div className="mb-3">
              <h3 className="text-sm font-semibold">{LOG_TABLE.title}</h3>
              <p className="mt-0.5 text-xs text-muted-foreground">{LOG_TABLE.desc}</p>
            </div>
            <TableRoot>
              <Table>
                <TableHead>
                  <TableRow>
                    {LOG_TABLE.columns.map((col) => (
                      <TableHeaderCell key={col}>{col}</TableHeaderCell>
                    ))}
                  </TableRow>
                </TableHead>
                <TableBody>
                  {MOCK_LOGS.map((log) => {
                    const badge = LOG_LEVEL_BADGE[log.level]
                    return (
                      <TableRow key={log.time + log.source}>
                        <TableCell className="tabular-nums">{log.time}</TableCell>
                        <TableCell>
                          <Badge variant={badge.variant}>{badge.label}</Badge>
                        </TableCell>
                        <TableCell className="text-muted-foreground">{log.source}</TableCell>
                        <TableCell className="max-w-md whitespace-normal">{log.message}</TableCell>
                      </TableRow>
                    )
                  })}
                </TableBody>
              </Table>
            </TableRoot>
          </Card>
        </Reveal>
      </section>
    </div>
  )
}
