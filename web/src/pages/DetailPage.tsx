/**
 * 详情覆盖页（沉浸全屏，无 AppShell——router 顶层路由）。
 *
 * 结构（旧项目详情页 4 层的 Web 对齐）：媒体区（image/animated_image → ImageViewer；
 * video → 预览封面/VideoPlayer 两态）+ chrome 轻操作层（顶部返回/信息、底部互动行）
 * + 弹层（标签/信息/移动/删除确认）。
 *
 * 批次导航：列表页跳转携带 location.state（DetailBatchState）——左右切换限定本批次，
 * navigate 用 replace 防历史堆栈随切换增长；直接访问 URL（无批次）时无切换入口。
 *
 * 打点：进入报 open、离开报 dwell（秒，达到下限才报；尽力而为静默失败，useReportView 同旨）；
 * play 由 VideoPlayer 首次播放时内部上报（ref 幂等）。
 */

import { useEffect, useRef, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useLocation, useNavigate, useParams } from 'react-router'
import {
  ChevronLeft,
  FolderOpen,
  Heart,
  Info,
  Play,
  Star,
  Tag,
  Trash2,
  type LucideIcon,
} from 'lucide-react'
import { toast } from 'sonner'
import { deleteApiV1AssetsByAssetId } from '@/api/generated'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { ImageViewer } from '@/components/viewer/ImageViewer'
import { VideoPlayer } from '@/components/viewer/VideoPlayer'
import { InfoSheet } from '@/components/viewer/InfoSheet'
import { TagManager } from '@/components/viewer/TagManager'
import { MoveDialog } from '@/components/organize/MoveDialog'
import { useAssetDetail, useReportView } from '@/hooks/use-asset-detail'
import { useFavorite, useLike } from '@/hooks/use-engagement'
import { useLibraries } from '@/hooks/use-libraries'
import { unwrapSdkResult } from '@/lib/api-client'
import { apiErrorText } from '@/pages/_shared/error-text'
import type { DetailBatchState } from '@/pages/RecommendPage'
import { cn } from '@/lib/utils'

/* ------------------------------ 文案/常量（交互规格来源） ------------------------------ */

const TEXT_BACK = '返回'
const TEXT_INFO = '信息'
const TEXT_PLAY = '播放'
const TEXT_LOAD_FAILED = '媒体不存在或已删除'
const TEXT_BACK_TO_LIST = '返回列表'
const TEXT_LIKE = '点赞'
const TEXT_FAVORITE = '收藏'
const TEXT_TAGS = '标签'
const TEXT_MOVE = '移动'
const TEXT_DELETE = '删除'
const TEXT_CANCEL = '取消'
const TEXT_DELETE_TITLE = '删除这条媒体？'
const TEXT_DELETE_CONFIRM = '删除后将移入回收站，可在「管理 → 回收站」中恢复。'
const TEXT_DELETE_SUCCESS = '已移入回收站'

/** dwell 上报最小秒数：<1s 的误触不计停留 */
const DWELL_MIN_SECONDS = 1

export default function DetailPage() {
  const { assetId = '' } = useParams()
  if (!assetId) return null
  // key=assetId：批次导航（replace 换 id）时整棵子树重挂载——界面态/弹层/打开时刻
  // 自动回到初始态，免写复位 effect（React reset-state-with-key 惯用法）
  return <DetailAssetView key={assetId} assetId={assetId} />
}

function DetailAssetView({ assetId }: { assetId: string }) {
  const navigate = useNavigate()
  const location = useLocation()
  const queryClient = useQueryClient()

  /* ---- 批次上下文（列表页 state 携带；直接访问 URL 时无） ---- */
  const batchState = location.state as DetailBatchState | null
  const from = batchState?.from ?? '/'
  const batch =
    batchState && Array.isArray(batchState.items) && typeof batchState.index === 'number'
      ? { items: batchState.items, index: batchState.index }
      : undefined

  const detailQuery = useAssetDetail(assetId)
  const detail = detailQuery.data
  const { mutate: reportViewMutate } = useReportView()
  const like = useLike(assetId)
  const favorite = useFavorite(assetId)
  // MoveDialog 目标树锚定库（/dirs 必填 libraryId）；AssetDetail 不携带 libraryId
  // （协议缺口），M2 以单库为主取第一个库，多库场景遗留待协议补字段
  const libraries = useLibraries()
  const defaultLibraryId = (libraries.data ?? [])[0]?.id

  /* ---- 界面态（assetId 变化随 key 重挂载自动复位） ---- */
  // useLike 无本地基线时 likeCount 恒 0——未点击前以详情 likeCount 显示（点击后用 hook 基线）
  const [likeTouched, setLikeTouched] = useState(false)
  // chrome 显隐（图片模式由 ImageViewer 单击切换；视频播放态强制顶部常显/底部隐藏）
  const [chromeVisible, setChromeVisible] = useState(true)
  // 视频两态：false=预览封面（thumbUrl + 播放钮），true=挂载 VideoPlayer 自动播放
  const [videoPlaying, setVideoPlaying] = useState(false)
  const [tagOpen, setTagOpen] = useState(false)
  const [infoOpen, setInfoOpen] = useState(false)
  const [moveOpen, setMoveOpen] = useState(false)
  const [deleteConfirmOpen, setDeleteConfirmOpen] = useState(false)

  const isVideo = detail?.mediaType === 'video'

  // 进入报 open / 离开报 dwell（key 重挂载语义：每次进入即一帧会话；打点尽力而为——
  // 服务端当前插入即 202 不去重，会话级去重由 M3 统计侧裁决，勿在此处假设已去重）
  const openedAtRef = useRef<number | null>(null)
  useEffect(() => {
    // 渲染期禁止 Date.now()（React Compiler purity），打开时刻在挂载 effect 首跑时记录
    openedAtRef.current ??= Date.now()
    reportViewMutate({ assetId, kind: 'open' })
    return () => {
      const openedAt = openedAtRef.current
      if (openedAt == null) return
      const seconds = Math.round((Date.now() - openedAt) / 1000)
      if (seconds >= DWELL_MIN_SECONDS) {
        reportViewMutate({ assetId, kind: 'dwell', seconds })
      }
    }
  }, [assetId, reportViewMutate])

  /* ---- 操作 ---- */
  const handleBack = () => navigate(from, { replace: true })

  const handleNavigate = (nextIndex: number) => {
    if (!batch) return
    const next = batch.items[nextIndex]
    if (!next?.id) return
    navigate(`/detail/${next.id}`, {
      replace: true,
      state: { items: batch.items, index: nextIndex, from } satisfies DetailBatchState,
    })
  }

  const handleLike = () => {
    setLikeTouched(true)
    like.toggle()
  }

  const handleFavorite = () => {
    if (!detail) return
    favorite.mutate(!detail.isFavorite, {
      onError: (error) => toast.error(apiErrorText(error)),
    })
  }

  // 删除 = 移入回收站（协议 DELETE 语义）；成功后回列表并失效列表缓存
  const deleteAsset = useMutation({
    mutationFn: () => unwrapSdkResult(deleteApiV1AssetsByAssetId({ path: { assetId } })),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['assets'] })
      toast.success(TEXT_DELETE_SUCCESS)
      navigate(from, { replace: true })
    },
    onError: (error) => toast.error(apiErrorText(error)),
  })

  /* ---- 渲染 ---- */
  const likeCount = likeTouched ? like.likeCount : (detail?.likeCount ?? 0)
  const showTopChrome = isVideo && videoPlaying ? true : chromeVisible
  const showBottomChrome = isVideo && videoPlaying ? false : chromeVisible

  const mediaArea = detailQuery.isError ? (
    <div className="flex h-full flex-col items-center justify-center gap-3">
      <p className="text-sm text-[var(--qm-text-muted)]">{TEXT_LOAD_FAILED}</p>
      <Button variant="outline" size="sm" onClick={handleBack}>
        {TEXT_BACK_TO_LIST}
      </Button>
    </div>
  ) : isVideo ? (
    videoPlaying && detail ? (
      <VideoPlayer detail={detail} initAutoPlay />
    ) : (
      <button
        type="button"
        className="relative flex h-full w-full cursor-pointer items-center justify-center"
        onClick={() => setVideoPlaying(true)}
        aria-label={TEXT_PLAY}
        disabled={!detail}
      >
        {detail?.thumbUrl && <img src={detail.thumbUrl} alt="" className="h-full w-full object-contain" />}
        <span className="absolute flex size-16 items-center justify-center rounded-full bg-black/50 text-white ring-1 ring-white/30">
          <Play className="size-8" aria-hidden />
        </span>
      </button>
    )
  ) : (
    <ImageViewer
      detail={detail}
      batch={batch}
      onNavigate={handleNavigate}
      onTap={() => setChromeVisible((v) => !v)}
    />
  )

  return (
    <div className="relative flex h-dvh flex-col overflow-hidden bg-black">
      {/* 媒体层：绝对铺满，chrome 浮在其上（中部无遮挡，手势直达媒体区） */}
      <div className="absolute inset-0">{mediaArea}</div>

      {showTopChrome && (
        <header className="relative z-10 flex items-center justify-between bg-gradient-to-b from-black/60 to-transparent p-[var(--qm-space-2)]">
          <Button variant="ghost" size="icon" onClick={handleBack} aria-label={TEXT_BACK}>
            <ChevronLeft className="size-5 text-white" aria-hidden />
          </Button>
          <Button
            variant="ghost"
            size="icon"
            onClick={() => setInfoOpen(true)}
            aria-label={TEXT_INFO}
            disabled={!detail}
          >
            <Info className="size-5 text-white" aria-hidden />
          </Button>
        </header>
      )}

      <div className="pointer-events-none flex-1" />

      {showBottomChrome && (
        <footer className="relative z-10 grid grid-cols-5 bg-gradient-to-t from-black/60 to-transparent p-[var(--qm-space-2)]">
          <ChromeAction
            icon={Heart}
            label={`${TEXT_LIKE} ${likeCount}`}
            active={like.likedToday}
            activeClass="text-[var(--qm-destructive)]"
            disabled={like.isPending}
            onClick={handleLike}
          />
          <ChromeAction
            icon={Star}
            label={TEXT_FAVORITE}
            active={detail?.isFavorite ?? false}
            activeClass="text-[var(--qm-primary)]"
            disabled={favorite.isPending || !detail}
            onClick={handleFavorite}
          />
          <ChromeAction icon={Tag} label={TEXT_TAGS} disabled={!detail} onClick={() => setTagOpen(true)} />
          <ChromeAction
            icon={FolderOpen}
            label={TEXT_MOVE}
            disabled={!detail}
            onClick={() => setMoveOpen(true)}
          />
          <ChromeAction
            icon={Trash2}
            label={TEXT_DELETE}
            destructive
            disabled={!detail}
            onClick={() => setDeleteConfirmOpen(true)}
          />
        </footer>
      )}

      {/* 弹层：标签 / 信息 / 移动 / 删除确认 */}
      <TagManager assetId={assetId} detail={detail} open={tagOpen} onOpenChange={setTagOpen} />
      <InfoSheet open={infoOpen} onOpenChange={setInfoOpen} detail={detail} />
      {detail && (
        <MoveDialog
          assetId={assetId}
          defaultDir={detail.directory}
          open={moveOpen}
          onOpenChange={setMoveOpen}
          settled
          libraryId={defaultLibraryId}
        />
      )}
      <Dialog open={deleteConfirmOpen} onOpenChange={setDeleteConfirmOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{TEXT_DELETE_TITLE}</DialogTitle>
          </DialogHeader>
          <p className="text-sm text-[var(--qm-text-muted)]">{TEXT_DELETE_CONFIRM}</p>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setDeleteConfirmOpen(false)}>
              {TEXT_CANCEL}
            </Button>
            <Button
              variant="destructive"
              disabled={deleteAsset.isPending}
              onClick={() => deleteAsset.mutate()}
            >
              {TEXT_DELETE}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

/** chrome 底部操作按钮（图标+文案纵排；active 时以 activeClass 高亮并填充图标） */
function ChromeAction({
  icon: Icon,
  label,
  onClick,
  active = false,
  activeClass = '',
  destructive = false,
  disabled = false,
}: {
  icon: LucideIcon
  label: string
  onClick: () => void
  active?: boolean
  activeClass?: string
  destructive?: boolean
  disabled?: boolean
}) {
  return (
    <button
      type="button"
      className={cn(
        'flex flex-col items-center gap-1 rounded-lg py-2 text-[11px] text-white/80 transition-colors hover:bg-white/10 disabled:pointer-events-none disabled:opacity-40',
        active && activeClass,
        destructive && 'hover:text-[var(--qm-destructive)]',
      )}
      onClick={onClick}
      disabled={disabled}
    >
      <Icon className="size-5" fill={active ? 'currentColor' : 'none'} aria-hidden />
      <span className="max-w-full truncate">{label}</span>
    </button>
  )
}
