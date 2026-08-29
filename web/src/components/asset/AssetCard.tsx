/**
 * 资产卡片（列表/推荐流共用）。
 *
 * 纯展示 + 收藏交互：任何数据获取都经 hooks（useFavorite），组件层不 import api（分层铁律）。
 * 展示约定（交互规格）：
 * - 卡片 = 缩略图（thumbUrl 是签名 md 档直链，直接 <img src> 用）
 * - 左上角媒体类型角标（图片/动图/视频以图标区分；列表无 durationMs，视频时长角标不做）
 * - hover / 触摸时显示收藏角标（右上角），点击直接改收藏（列表都消费同源数据，
 *   useFavorite 的 invalidate 会让各页角标同步）
 *
 * 媒体叠加层颜色说明：角标底色/前景/收藏色用固定黑/白/红（bg-black/50、text-white、
 * fill-red-500 等）。原因：叠加层永远压在图片之上，须与图片内容（任意明暗）保持对比度，
 * 不随主题反转——若走 --qm-* 语义色，浅色主题下白色卡面会盖住亮色图片。这是媒体查看
 * 场景的标准惯例（视频播放器控制条同此处理，见 viewer 组件）。
 */

import { Image as ImageIcon, Images as ImagesIcon, Film as FilmIcon, Heart } from 'lucide-react'
import type { AssetSummary, MediaType } from '@/api/generated'
import { useFavorite } from '@/hooks/use-engagement'
import { cn } from '@/lib/utils'

/** 媒体类型 → 角标图标（动图=多帧，用 Images 表示"多张连续帧"；视频=胶片） */
const MEDIA_TYPE_ICON: Record<MediaType, typeof ImageIcon> = {
  image: ImageIcon,
  animated_image: ImagesIcon,
  video: FilmIcon,
}

/** 角标图标通用尺寸 */
const BADGE_ICON_CLASS = 'size-3.5'

/** 卡片缩略图比例：方形（媒体库列表惯例，剪裁由 object-cover 完成） */
const THUMB_ASPECT_CLASS = 'aspect-square'

interface AssetCardProps {
  asset: AssetSummary
  /** 点击卡片（进入详情；父级携带批次 state 由父级负责） */
  onOpen: (asset: AssetSummary, index: number) => void
  /** 卡片的序号（列表内 index；收藏后列表重排可能变化，仅用于点击传参） */
  index: number
}

export function AssetCard({ asset, onOpen, index }: AssetCardProps) {
  const favorite = useFavorite(asset.id ?? '')
  const isFavorite = asset.isFavorite ?? false

  const mediaType = asset.mediaType ?? 'image'
  const MediaIcon = MEDIA_TYPE_ICON[mediaType] ?? ImageIcon

  const handleFavorite = (event: React.MouseEvent<HTMLButtonElement>) => {
    // 收藏按钮不冒泡触发卡片点击（进入详情）
    event.stopPropagation()
    favorite.mutate(!isFavorite)
  }

  return (
    <button
      type="button"
      onClick={() => onOpen(asset, index)}
      className="group relative block w-full overflow-hidden rounded-[var(--qm-radius)] bg-[var(--qm-surface-soft)] text-left"
      aria-label={`打开 ${asset.fileName ?? '媒体'}`}
    >
      <div className={cn(THUMB_ASPECT_CLASS, 'relative w-full overflow-hidden bg-[var(--qm-chip-bg)]')}>
        {asset.thumbUrl ? (
          <img
            src={asset.thumbUrl}
            alt={asset.fileName ?? ''}
            loading="lazy"
            className="size-full object-cover"
          />
        ) : (
          // 无缩略图（缩略图未生成/生成失败）时以图标占位，页面不报错
          <div className="flex size-full items-center justify-center">
            <MediaIcon className="size-8 text-[var(--qm-text-muted)]" aria-hidden />
          </div>
        )}
        {/* 媒体类型角标：列表无 durationMs，只以图标区分类型（交互规格） */}
        <span className="absolute top-1.5 left-1.5 rounded-md bg-black/50 p-1 text-white">
          <MediaIcon className={BADGE_ICON_CLASS} aria-hidden />
        </span>
        {/* 收藏角标：hover（桌面）/ 触摸（可点击按钮本身）显示 */}
        <span
          className={cn(
            'absolute top-1.5 right-1.5 transition-opacity duration-[var(--qm-duration-base)]',
            isFavorite ? 'opacity-100' : 'opacity-0 group-hover:opacity-100',
          )}
        >
          <button
            type="button"
            onClick={handleFavorite}
            aria-label={isFavorite ? '取消收藏' : '加入收藏'}
            className="rounded-md bg-black/50 p-1 text-white hover:bg-black/70"
          >
            <Heart
              className={cn(BADGE_ICON_CLASS, isFavorite && 'fill-red-500 text-red-500')}
              aria-hidden
            />
          </button>
        </span>
      </div>
    </button>
  )
}
