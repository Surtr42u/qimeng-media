/**
 * 首页（正式 UI）：B 站 PC 客户端风推荐卡片流（全 mock）。
 *
 * - 卡片 = 16:9 渐变占位封面（hover 轻缩放）+ 时长角标 + 两行截断标题
 *   + UP 主 / 浏览量 / 相对时间元信息行；网格宽 4 列 / 中 3 列 / 窄 2 列自适应。
 * - 单击卡片 → 右侧详情滑入 sheet（fixed 右侧 ~380px，遮罩点击关闭）；
 *   双击 → 播放占位 Dialog。单击延迟 220ms 判定，双击时取消未触发的单击。
 * - 顶部仅页头（标题 + 推荐语），不做搜索框（全局搜索另有规划）。
 */
import { useEffect, useMemo, useRef, useState } from 'react'
import { AnimatePresence, motion } from 'motion/react'
import { toast } from 'sonner'
import { RiHeartLine, RiDeleteBin6Line, RiPlayFill, RiCloseLine } from '@remixicon/react'
import { Card } from '@/components/tremor/card'
import { Badge } from '@/components/tremor/badge'
import { Button } from '@/components/tremor/button'
import { Dialog, DialogContent, DialogTitle } from '@/components/tremor/dialog'
import { Reveal } from './widgets'
import { CARD_HOVER } from './BrowseFilterPanel'
import { fmtViewCount, generateHomeFeed, type HomeFeedItem } from './mock'
import { cx } from '@/lib/tremor'

/** 单击→双击判定延迟（ms）：单击开 sheet 前等待，双击取消之 */
const DOUBLE_CLICK_DELAY_MS = 220

/** 页头与操作按钮文案 */
const HOME_TEXT = {
  title: '首页',
  tagline: '为你推荐 · 来自媒体库的最新与最热内容（mock 数据）',
  sectionLabel: '推荐流',
  play: '播放',
  save: '收藏',
  remove: '删除',
  playToast: '播放（原型演示，正式版接入视频播放器）',
  saveToast: '已加入收藏（mock）',
  removeToast: '已移入回收站（mock，正式版走回收站语义）',
  playerNote: '正式版接入视频播放器',
} as const

/** 封面渐变占位（按条目 gradient 索引轮换；Tailwind 类须完整字面量供扫描） */
const COVER_GRADIENTS = [
  'bg-gradient-to-br from-blue-500/25 via-violet-500/20 to-amber-500/15',
  'bg-gradient-to-br from-emerald-500/25 via-cyan-500/20 to-blue-500/15',
  'bg-gradient-to-br from-rose-500/25 via-pink-500/20 to-violet-500/15',
  'bg-gradient-to-br from-amber-500/25 via-orange-500/20 to-rose-500/15',
  'bg-gradient-to-br from-indigo-500/25 via-sky-500/20 to-emerald-500/15',
] as const

/** 详情 sheet 标签行文案 */
const SHEET_TEXT = { metaTitle: '文件信息', tagsTitle: '标签' } as const

/** 推荐流卡片：16:9 封面 + 时长角标 + 两行标题 + 元信息行（单击/双击分派见页面级） */
function FeedCard({
  item,
  onClick,
  onDoubleClick,
}: {
  item: HomeFeedItem
  onClick: () => void
  onDoubleClick: () => void
}) {
  return (
    <Card className={cx('group overflow-hidden p-0', CARD_HOVER)}>
      <button
        type="button"
        onClick={onClick}
        onDoubleClick={onDoubleClick}
        className="block w-full text-left focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
      >
        {/* 封面：渐变占位 + hover 轻缩放 + 视频时长角标（正式版接入签名直链封面） */}
        <div className="relative aspect-video w-full overflow-hidden">
          <div
            className={cx(
              'flex h-full w-full items-center justify-center transition-transform duration-300 group-hover:scale-105',
              COVER_GRADIENTS[item.gradient],
            )}
          >
            <span className="text-xs text-muted-foreground">封面占位</span>
          </div>
          {item.durationText ? (
            <span className="absolute right-1.5 bottom-1.5 rounded bg-black/70 px-1.5 py-0.5 text-[11px] font-medium text-white tabular-nums">
              {item.durationText}
            </span>
          ) : null}
        </div>
        {/* 标题最多两行截断；min-h 保底让同排卡片元信息行对齐 */}
        <div className="p-3">
          <p className="line-clamp-2 min-h-[2.5rem] text-sm leading-5 font-medium">{item.title}</p>
          <p className="mt-1.5 truncate text-xs text-muted-foreground">
            {item.author} · {fmtViewCount(item.views)} 浏览 · {item.timeText}
          </p>
        </div>
      </button>
    </Card>
  )
}

/** 右侧详情滑入 sheet：大封面 + 标题 + 元数据 + 标签 + 操作按钮（全部 mock 行为） */
function DetailSheet({ item, onClose }: { item: HomeFeedItem; onClose: () => void }) {
  const metaRows: Array<[string, string]> = [
    ['大小', item.size],
    ['时长', item.durationText ?? '—'],
    ['收录时间', item.time],
    ['UP 主', item.author],
    ['浏览量', fmtViewCount(item.views)],
  ]
  return (
    <>
      {/* 遮罩：点击关闭 */}
      <motion.div
        key="sheet-mask"
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        exit={{ opacity: 0 }}
        transition={{ duration: 0.2 }}
        onClick={onClose}
        className="fixed inset-0 z-40 bg-black/40"
        aria-hidden={true}
      />
      {/* 面板：右侧滑入，内容超高内部滚动 */}
      <motion.aside
        key="sheet-panel"
        initial={{ x: '100%' }}
        animate={{ x: 0 }}
        exit={{ x: '100%' }}
        transition={{ type: 'tween', duration: 0.25, ease: 'easeOut' }}
        className="fixed top-0 right-0 z-50 flex h-full w-[380px] max-w-[92vw] flex-col border-l border-border bg-background"
        role="dialog"
        aria-label={`详情：${item.title}`}
      >
        <div className="flex items-center justify-between border-b border-border px-4 py-3">
          <p className="text-sm font-semibold">详情</p>
          <button
            type="button"
            onClick={onClose}
            aria-label="关闭详情"
            className="rounded-md p-1 text-muted-foreground transition-colors hover:bg-accent/50 hover:text-foreground"
          >
            <RiCloseLine className="size-4.5" aria-hidden={true} />
          </button>
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto p-4">
          {/* 大封面占位 */}
          <div
            className={cx(
              'flex aspect-video w-full items-center justify-center rounded-md',
              COVER_GRADIENTS[item.gradient],
            )}
          >
            <span className="text-sm text-muted-foreground">封面占位</span>
          </div>
          <h2 className="mt-3 text-base font-semibold">{item.title}</h2>
          {/* 元数据行（label/value 两列小字段） */}
          <p className="mt-4 text-xs text-muted-foreground">{SHEET_TEXT.metaTitle}</p>
          <dl className="mt-1.5 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 text-sm">
            {metaRows.map(([label, value]) => (
              <div key={label} className="col-span-2 flex items-baseline justify-between gap-4">
                <dt className="shrink-0 text-xs text-muted-foreground">{label}</dt>
                <dd className="truncate text-right tabular-nums">{value}</dd>
              </div>
            ))}
          </dl>
          {/* 标签 Badge 行 */}
          <p className="mt-4 text-xs text-muted-foreground">{SHEET_TEXT.tagsTitle}</p>
          <div className="mt-1.5 flex flex-wrap gap-1.5">
            {item.tags.map((tag) => (
              <Badge key={tag} variant="neutral">
                {tag}
              </Badge>
            ))}
          </div>
          <p className="mt-3 text-xs text-muted-foreground">原型占位：正式版接入真实文件元数据与签名直链。</p>
        </div>
        {/* 操作按钮行：播放 / 收藏 / 删除（toast mock；删除语义=移入回收站） */}
        <div className="flex gap-2 border-t border-border p-4">
          <Button className="flex-1" onClick={() => toast.info(HOME_TEXT.playToast)}>
            <RiPlayFill className="size-4" aria-hidden={true} />
            {HOME_TEXT.play}
          </Button>
          <Button variant="secondary" onClick={() => toast.success(HOME_TEXT.saveToast)}>
            <RiHeartLine className="size-4" aria-hidden={true} />
            {HOME_TEXT.save}
          </Button>
          <Button variant="secondary" onClick={() => toast.error(HOME_TEXT.removeToast)}>
            <RiDeleteBin6Line className="size-4" aria-hidden={true} />
            {HOME_TEXT.remove}
          </Button>
        </div>
      </motion.aside>
    </>
  )
}

/** 播放占位 Dialog：黑底大播放器占位 + 文件名标题 */
function PlayerDialog({ item, onClose }: { item: HomeFeedItem; onClose: () => void }) {
  return (
    <Dialog open={true} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-3xl">
        <DialogTitle className="truncate text-base">{item.title}</DialogTitle>
        <div className="mt-3 flex aspect-video w-full flex-col items-center justify-center gap-3 rounded-md bg-black">
          <RiPlayFill className="size-14 text-white" aria-hidden={true} />
          <p className="text-sm text-white/80">{HOME_TEXT.playerNote}</p>
        </div>
      </DialogContent>
    </Dialog>
  )
}

export default function HomePage() {
  // seeded mock：挂载生成一次即可
  const feed = useMemo(() => generateHomeFeed(), [])
  const [sheetItem, setSheetItem] = useState<HomeFeedItem | null>(null)
  const [playerItem, setPlayerItem] = useState<HomeFeedItem | null>(null)

  // 单击延迟判定：双击时取消未触发的单击，避免 sheet 与播放器同时弹出
  const clickTimer = useRef<number | null>(null)
  useEffect(() => {
    return () => {
      if (clickTimer.current !== null) window.clearTimeout(clickTimer.current)
    }
  }, [])
  const openSheetWithClick = (item: HomeFeedItem) => {
    if (clickTimer.current !== null) window.clearTimeout(clickTimer.current)
    clickTimer.current = window.setTimeout(() => {
      clickTimer.current = null
      setSheetItem(item)
    }, DOUBLE_CLICK_DELAY_MS)
  }
  const openPlayer = (item: HomeFeedItem) => {
    if (clickTimer.current !== null) {
      window.clearTimeout(clickTimer.current)
      clickTimer.current = null
    }
    setPlayerItem(item)
  }

  return (
    <div className="space-y-6">
      {/* 页头：标题 + 推荐语（搜索框另有全局规划，此处不做） */}
      <Reveal index={0}>
        <div>
          <h2 className="text-lg font-semibold">{HOME_TEXT.title}</h2>
          <p className="mt-0.5 text-xs text-muted-foreground">{HOME_TEXT.tagline}</p>
        </div>
      </Reveal>

      {/* 推荐流网格：窄 2 列 / 中 3 列 / 宽 4 列 */}
      <Reveal index={1}>
        <section
          aria-label={HOME_TEXT.sectionLabel}
          className="grid grid-cols-2 gap-4 sm:grid-cols-3 xl:grid-cols-4"
        >
          {feed.map((item) => (
            <FeedCard
              key={item.id}
              item={item}
              onClick={() => openSheetWithClick(item)}
              onDoubleClick={() => openPlayer(item)}
            />
          ))}
        </section>
      </Reveal>

      {/* 详情 sheet：单击时挂载，遮罩/关闭按钮退出（AnimatePresence 保退出动画） */}
      <AnimatePresence>
        {sheetItem ? <DetailSheet item={sheetItem} onClose={() => setSheetItem(null)} /> : null}
      </AnimatePresence>

      {/* 播放占位 Dialog：双击时挂载 */}
      {playerItem ? <PlayerDialog item={playerItem} onClose={() => setPlayerItem(null)} /> : null}
    </div>
  )
}
