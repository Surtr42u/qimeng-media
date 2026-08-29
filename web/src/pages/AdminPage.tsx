/**
 * 管理页（/admin）：三个入口区块——库管理（默认展开）/ 监控仪表盘 / 维护入口。
 * 当前 Tab 状态存 URL searchParams（?tab=...，页面状态约定：刷新不丢）。
 *
 * 监控说明：本页仪表盘只做 system/status 实时面板（OBSERVABILITY 约定 3s 轮询）；
 * /metrics 是 Prometheus 文本（Bearer 鉴权），不做图表解析——那是 Grafana 等
 * 观测体系的领域，长期趋势由其接管（Dashboard.tsx 内注释同旨）。
 */

import { Link, useSearchParams } from 'react-router'
import { FolderTree, Trash2 } from 'lucide-react'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { LibraryManager } from '@/components/admin/LibraryManager'
import { Dashboard } from '@/components/admin/Dashboard'

/** 三个入口区块（value = URL search 参数值与 Tabs 值） */
const ADMIN_TABS = [
  { value: 'libraries', label: '库管理' },
  { value: 'dashboard', label: '监控仪表盘' },
  { value: 'maintenance', label: '维护' },
] as const

type AdminTabValue = (typeof ADMIN_TABS)[number]['value']

/** Tab 状态的 URL 参数键（页面状态存 searchParams 约定；多个参数共存由 searchParams 保证） */
const TAB_PARAM_KEY = 'tab'

/** 维护入口两张卡片（整理 + 回收站） */
const MAINTENANCE_ENTRIES = [
  {
    to: '/admin/organize',
    title: '文件整理',
    description: '目录树浏览、上传文件、新建目录',
    icon: FolderTree,
  },
  {
    to: '/admin/trash',
    title: '回收站',
    description: '恢复或永久删除已删除文件',
    icon: Trash2,
  },
] as const

const TEXT_MAINTENANCE_TITLE = '维护'
const TEXT_MAINTENANCE_HINT = '文件整理与回收站为独立入口（不占底部 Tab）'

export default function AdminPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const rawTab = searchParams.get(TAB_PARAM_KEY)
  const activeTab: AdminTabValue = ADMIN_TABS.some((tab) => tab.value === rawTab)
    ? (rawTab as AdminTabValue)
    : 'libraries'

  return (
    <div className="flex flex-col gap-4 px-4 py-4">
      <h1 className="text-lg font-bold">管理</h1>
      <Tabs value={activeTab} onValueChange={(value) => setSearchParams({ [TAB_PARAM_KEY]: value })}>
        <TabsList>
          {ADMIN_TABS.map((tab) => (
            <TabsTrigger key={tab.value} value={tab.value}>
              {tab.label}
            </TabsTrigger>
          ))}
        </TabsList>
        <TabsContent value="libraries">
          <LibraryManager />
        </TabsContent>
        <TabsContent value="dashboard">
          <Dashboard />
        </TabsContent>
        <TabsContent value="maintenance">
          <section className="flex flex-col gap-3">
            <h2 className="text-base font-bold">{TEXT_MAINTENANCE_TITLE}</h2>
            <p className="text-xs text-[var(--qm-text-muted)]">{TEXT_MAINTENANCE_HINT}</p>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              {MAINTENANCE_ENTRIES.map((entry) => (
                <Link
                  key={entry.to}
                  to={entry.to}
                  className="flex items-start gap-3 rounded-xl border border-[var(--qm-border)] bg-[var(--qm-surface)] p-4 transition-colors hover:border-[var(--qm-primary)] hover:bg-[var(--qm-primary-soft)]"
                >
                  <entry.icon className="size-5 shrink-0 text-[var(--qm-primary)]" aria-hidden />
                  <span className="min-w-0">
                    <span className="block text-sm font-semibold">{entry.title}</span>
                    <span className="block text-xs text-[var(--qm-text-muted)]">{entry.description}</span>
                  </span>
                </Link>
              ))}
            </div>
          </section>
        </TabsContent>
      </Tabs>
    </div>
  )
}
