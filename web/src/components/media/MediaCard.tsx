/**
 * 媒体卡（首页卡片流 / 相册网格 / 收藏 / 搜索结果共用）。
 *
 * 合并原型 app.js 的 renderCard 与 mediaCardHtml（HANDOVER_UI §5 记账债：
 * 两者输出结构相同，移植进 web 前抽共享函数——本组件即合并结果）。
 * 类名与原型逐字一致（styles/prototype.css 消费），阶段 B 接真实数据时
 * props 增加 assetId 等字段，展示结构保持不变。
 */

import { memo } from 'react'

export interface MediaCardProps {
  /** 资产标识（阶段 A mock 传视频 id；阶段 B 换 assetId，用于点击跳详情） */
  id?: string
  cover: string
  title: string
  /** 时长角标（右下角，仅视频传 m:ss / h:mm:ss；图片/动图不传不渲染） */
  duration?: string
  /** 作者行（第一行，作者名/「名 等N」/出处回退值由调用方经 formatCardUp 组装） */
  up?: string
  /** 日期（次行，浅色） */
  date?: string
  onClick?: () => void
  /** 稳定打开回调（列表热点场景用它替代 onClick）：本组件已 memo 化，只认
   *  浅等 props——onClick 每渲染新建闭包会让 memo 形同虚设；onOpen 由调用方
   *  useCallback 稳定后 id 绑定移进卡片内部，兄弟 state 变化（面板开合/
   *  进度 tick 等）不再引发数百张卡片全量重渲染（2026-09-20 全库审查）。 */
  onOpen?: (id?: string) => void
}

function MediaCardImpl({ id, cover, title, duration, up, date, onClick, onOpen }: MediaCardProps) {
  const handleClick = onClick ?? (onOpen ? () => onOpen(id) : undefined)
  return (
    <div
      className="card"
      data-id={id}
      onClick={handleClick}
      role={handleClick ? 'button' : undefined}
    >
      <div className="card--cover">
        {/* decoding=async：缩略图解码移出主线程（滚动中大量卡片时避免掉帧） */}
        <img src={cover} alt={title} loading="lazy" decoding="async" />
        {duration ? <span className="card--duration">{duration}</span> : null}
      </div>
      <div className="card--title">{title}</div>
      {up || date ? (
        <div className="card--meta">
          {up ? <span className="card--up">{up}</span> : null}
          {date ? <span className="card--date">{date}</span> : null}
        </div>
      ) : null}
    </div>
  )
}

/** memo 隔离：大列表（无限滚动数百卡）中任一兄弟 state 变化只重渲染变化源，
 *  卡片按 props 浅等跳过（生效前提=调用方 onOpen/onClick 引用稳定）。 */
export const MediaCard = memo(MediaCardImpl)
