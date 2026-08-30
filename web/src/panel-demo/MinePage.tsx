/**
 * 我的页（正式 UI）：参照 B 站「我的」页形态——头部资料卡 + Tabs 内容区。
 *
 * - 头部卡：圆形渐变头像占位 + 用户名 + 库数量 / 文件总数 / 总大小（mock 静态值）
 * - Tab「数据」：原数据总览页全部内容（DataContent），排行「查看全部」仍进 rank 路由
 * - Tab「浏览历史」：mock 观看记录（缩略占位 + 文件名 + 观看时间 + 进度条），
 *   按「今天 / 昨天 / 更早」分组
 */
import { Card } from '@/components/tremor/card'
import { ProgressBar } from '@/components/tremor/progress-bar'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/tremor/tabs'
import { Reveal } from './widgets'
import { DataContent } from './DataPage'
import { MOCK_MINE_PROFILE, MOCK_WATCH_HISTORY } from './mock'

/** 页面文案（头部小字段 / Tabs 标签 / 历史说明） */
const MINE_TEXT = {
  role: '本地管理员 · mock 数据',
  stats: [
    { label: '媒体库', value: String(MOCK_MINE_PROFILE.libraryCount) },
    { label: '文件总数', value: MOCK_MINE_PROFILE.fileCount.toLocaleString('zh-CN') },
    { label: '总大小', value: MOCK_MINE_PROFILE.totalSize },
  ],
  tabData: '数据',
  tabHistory: '浏览历史',
  historyNote: '按观看时间分组（mock 数据）',
} as const

/** 头部资料卡：头像占位（圆形渐变 + 首字）+ 用户名 + 三个统计小字段 */
function ProfileCard() {
  return (
    <Card className="p-6">
      <div className="flex flex-wrap items-center gap-5">
        {/* 头像占位：正式版接入用户头像（签名直链） */}
        <div className="flex size-16 shrink-0 items-center justify-center rounded-full bg-gradient-to-br from-blue-500 to-violet-500 text-2xl font-semibold text-white">
          {MOCK_MINE_PROFILE.name.slice(0, 1)}
        </div>
        <div className="min-w-0">
          <h2 className="text-lg font-semibold">{MOCK_MINE_PROFILE.name}</h2>
          <p className="mt-1 text-xs text-muted-foreground">{MINE_TEXT.role}</p>
        </div>
        {/* 三个统计小字段：右对齐、窄屏换行 */}
        <dl className="ml-auto flex gap-8 text-center">
          {MINE_TEXT.stats.map((stat) => (
            <div key={stat.label}>
              <dd className="text-xl font-semibold tabular-nums">{stat.value}</dd>
              <dt className="mt-0.5 text-xs text-muted-foreground">{stat.label}</dt>
            </div>
          ))}
        </dl>
      </div>
    </Card>
  )
}

/** 浏览历史 Tab 内容：按今天 / 昨天 / 更早分组的观看记录（横条卡） */
function WatchHistory() {
  return (
    <div className="space-y-6">
      <p className="text-xs text-muted-foreground">{MINE_TEXT.historyNote}</p>
      {MOCK_WATCH_HISTORY.map((group) => (
        <section key={group.label} aria-label={`观看记录：${group.label}`}>
          <h3 className="text-sm font-semibold">
            {group.label}
            <span className="ml-2 text-xs font-normal text-muted-foreground">
              {group.items.length} 条
            </span>
          </h3>
          <ul className="mt-2 space-y-2">
            {group.items.map((item) => (
              <li key={`${item.watchedAt}-${item.fileName}`}>
                {/* 横条卡：缩略占位 + 文件名/时间/进度 */}
                <Card className="flex items-center gap-3 p-2.5">
                  <div className="relative h-14 w-24 shrink-0 overflow-hidden rounded-md bg-gradient-to-br from-blue-500/20 via-violet-500/20 to-amber-500/15">
                    <span className="absolute inset-0 flex items-center justify-center text-[10px] text-muted-foreground">
                      缩略占位
                    </span>
                  </div>
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-sm font-medium">{item.fileName}</p>
                    <p className="mt-1 text-xs text-muted-foreground">
                      {item.watchedAt} · 已看 {item.progress}% · {item.durationText}
                    </p>
                    <ProgressBar value={item.progress} className="mt-1.5" aria-label={`观看进度 ${item.progress}%`} />
                  </div>
                </Card>
              </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  )
}

export default function MinePage() {
  return (
    <div className="space-y-6">
      <Reveal index={0}>
        <ProfileCard />
      </Reveal>
      <Reveal index={1}>
        {/* Tabs 内容区：数据 / 浏览历史（DataContent 状态自包含，切 Tab 不丢状态） */}
        <Tabs defaultValue="data">
          <TabsList>
            <TabsTrigger value="data">{MINE_TEXT.tabData}</TabsTrigger>
            <TabsTrigger value="history">{MINE_TEXT.tabHistory}</TabsTrigger>
          </TabsList>
          <TabsContent value="data" className="mt-6">
            <DataContent />
          </TabsContent>
          <TabsContent value="history" className="mt-6">
            <WatchHistory />
          </TabsContent>
        </Tabs>
      </Reveal>
    </div>
  )
}
