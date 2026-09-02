/**
 * 媒体卡（首页卡片流 / 相册网格 / 收藏 / 搜索结果共用）。
 *
 * 合并原型 app.js 的 renderCard 与 mediaCardHtml（HANDOVER_UI §5 记账债：
 * 两者输出结构相同，移植进 web 前抽共享函数——本组件即合并结果）。
 * 类名与原型逐字一致（styles/prototype.css 消费），阶段 B 接真实数据时
 * props 增加 assetId 等字段，展示结构保持不变。
 */

export interface MediaCardProps {
  /** 资产标识（阶段 A mock 传视频 id；阶段 B 换 assetId，用于点击跳详情） */
  id?: string
  cover: string
  title: string
  /** 时长/张数角标（图片传"48 张"式计数，视频传 mm:ss） */
  duration?: string
  /** 作者行（@ 前缀由调用方拼好） */
  up?: string
  date?: string
  onClick?: () => void
}

export function MediaCard({ id, cover, title, duration, up, date, onClick }: MediaCardProps) {
  return (
    <div
      className="card"
      data-id={id}
      onClick={onClick}
      role={onClick ? 'button' : undefined}
    >
      <div className="card--cover">
        <img src={cover} alt={title} loading="lazy" />
        {duration ? <span className="card--duration">{duration}</span> : null}
      </div>
      <div className="card--title">{title}</div>
      {up || date ? (
        <div className="card--meta">
          {up ? <span className="card--up">{up}</span> : null}
          {up && date ? <span className="dot">·</span> : null}
          {date ? <span>{date}</span> : null}
        </div>
      ) : null}
    </div>
  )
}
