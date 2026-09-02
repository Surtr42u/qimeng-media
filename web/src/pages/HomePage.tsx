import { MediaCard } from '@/components/media/MediaCard'
import { MOCK_HOME_CARDS } from '@/pages/mock'

/**
 * 首页：推荐卡片流（原型 #page-home 移植）。
 * 顶栏分类 tab（推荐/cos/排行榜）由 TopBar 驱动（searchParams），页面本身
 * 阶段 A 三个 tab 渲染同款 mock 流（与原型行为一致：tab 只影响周期行显隐）；
 * 阶段 B 接推荐/热度/排行接口时按 tab 分流。
 */
export default function HomePage() {
  return (
    <div className="page" id="page-home">
      <div className="grid">
        {MOCK_HOME_CARDS.map((v) => (
          <MediaCard
            key={v.id}
            id={v.id}
            cover={v.cover}
            title={v.title}
            duration={v.duration}
            up={v.up}
            date={v.date}
          />
        ))}
      </div>
    </div>
  )
}
