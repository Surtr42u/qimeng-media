import { useState, type MouseEvent } from 'react'
import { useNavigate } from 'react-router'
import {
  AUTHOR_AGG,
  COLLECTION_AUTHOR,
  COLLECTION_TAG,
  RANK_AUTHORS,
  RANK_CONTENT,
  RANK_TAGS,
  TAG_AGG,
} from '@/pages/mock'

/**
 * 数据页（原型 #page-data 移植）：时段胶囊 + 6 指标 + 趋势线 + 双环分布 + 排行榜。
 * 阶段 A 全部为写死的 mock 数字（原型 index.html 260-384 照搬）；时段切换仅样式。
 */

const SEGMENTS = ['7 天', '30 天', '90 天', '全部']

interface Metric {
  label: string
  value: string
  /** 次要说明文案；有 delta 时渲染为「说明 + 涨跌箭头」 */
  sub: string
  delta?: string
  trend?: 'up' | 'down'
}

const METRICS: Metric[] = [
  { label: '浏览', value: '9,842', sub: '较上期', delta: '↑ 8.4%', trend: 'up' },
  { label: '播放', value: '4,217', sub: '较上期', delta: '↓ 2.1%', trend: 'down' },
  { label: '播放时长', value: '1,201 时', sub: '日均 40 时' },
  { label: '点赞', value: '2,006', sub: '较上期', delta: '↑ 15.2%', trend: 'up' },
  { label: '收藏', value: '737', sub: '较上期', delta: '↑ 6.0%', trend: 'up' },
  { label: '独立访客', value: '1,053', sub: '均值 35 人/日' },
]

interface DonutSlice {
  dot: string
  name: string
  value: string
  pct: string
}

const DONUTS: { title: string; sub: string; arcs: string[]; legend: DonutSlice[] }[] = [
  {
    title: '内容类型分布',
    sub: '按资产数量占比',
    arcs: ['arc-1', 'arc-2', 'arc-3'],
    legend: [
      { dot: 'd1', name: '视频', value: '4,821', pct: '65.6%' },
      { dot: 'd2', name: '图片', value: '2,213', pct: '30.1%' },
      { dot: 'd3', name: '音频', value: '313', pct: '4.3%' },
    ],
  },
  {
    title: '浏览量占比',
    sub: '按类型的浏览次数占比 · 近 30 天',
    arcs: ['arc-4', 'arc-2', 'arc-3'],
    legend: [
      { dot: 'd4', name: '视频', value: '6,880', pct: '69.9%' },
      { dot: 'd2', name: '图片', value: '2,540', pct: '25.8%' },
      { dot: 'd3', name: '音频', value: '422', pct: '4.3%' },
    ],
  },
]

const CONTENT_RANK = [
  { cover: '/covers/c-dlss.webp', views: '3,241', title: '样例 2017 高画质合集' },
  { cover: '/covers/c-tokyo.webp', views: '1,982', title: '舞台摄影 · 第一组' },
  { cover: '/covers/c-frog.webp', views: '1,540', title: '场外随拍 · 机动展区' },
  { cover: '/covers/c-jet.webp', views: '1,207', title: '角色特写 · 第三辑' },
  { cover: '/covers/c-mc.webp', views: '986', title: '返场演出 · 完整版' },
]

// Top5 = mock 内容池真实聚合（TAG_AGG/AUTHOR_AGG），与集合子页点击内容自洽（阶段 B 换真聚合接口）
const TAG_RANK: [string, string][] = TAG_AGG.slice(0, 5).map(([n, c]) => [n, String(c)])

const AUTHOR_RANK: [string, string][] = AUTHOR_AGG.slice(0, 5).map((a) => [a.name, String(a.works)])

/** 行式榜单列表（标签榜/作者榜/作者总览共用） */
function RankList({ rows, onSelect }: { rows: [string, string][]; onSelect?: (name: string) => void }) {
  return (
    <ul>
      {rows.map(([name, num]) => (
        // 旧项目语义：点标签/作者行 → 集合子页列出该标签/作者下所有文件
        <li key={name} onClick={onSelect ? () => onSelect(name) : undefined}>
          <span className="rank-name">{name}</span>
          <b>{num}</b>
        </li>
      ))}
    </ul>
  )
}

/** 「查看全部」跳完整榜单子页（保留原型 a 标签与 data-rank，拦截默认锚点跳转） */
function rankMoreProps(navigate: ReturnType<typeof useNavigate>, rank: string) {
  return {
    href: '#',
    className: 'rank-more',
    'data-rank': rank,
    onClick: (e: MouseEvent<HTMLAnchorElement>) => {
      e.preventDefault()
      navigate(`/app/ranks/${rank}`)
    },
  }
}

export default function DataPage() {
  const navigate = useNavigate()
  const [seg, setSeg] = useState('7 天')

  return (
    <div className="page" id="page-data">
      <div className="seg-row">
        {SEGMENTS.map((s) => (
          <button key={s} className={seg === s ? 'seg active' : 'seg'} type="button" onClick={() => setSeg(s)}>
            {s}
          </button>
        ))}
        <span className="seg-note">近 30 天 · mock 数据</span>
      </div>
      <div className="metric-grid">
        {METRICS.map((m) => (
          <div className="metric-card" key={m.label}>
            <p className="m-label">{m.label}</p>
            <p className="m-value">{m.value}</p>
            {m.delta ? (
              <p className="m-sub">
                {m.sub} <b className={m.trend}>{m.delta}</b>
              </p>
            ) : (
              <p className="m-sub">{m.sub}</p>
            )}
          </div>
        ))}
      </div>
      <div className="chart-card">
        <h3>浏览与播放趋势</h3>
        <p>近 30 天 · mock 数据</p>
        <svg className="trend-svg" viewBox="0 0 600 200" preserveAspectRatio="none" aria-label="浏览与播放趋势">
          <polyline
            points="0,150 60,132 120,140 180,110 240,118 300,86 360,96 420,64 480,76 540,48 600,60"
            className="line-a"
          />
          <polyline
            points="0,170 60,162 120,166 180,150 240,155 300,138 360,144 420,126 480,132 540,116 600,124"
            className="line-b"
          />
        </svg>
      </div>
      <div className="donut-grid">
        {DONUTS.map((d) => (
          <div className="donut-card" key={d.title}>
            <h3>{d.title}</h3>
            <p>{d.sub}</p>
            <div className="donut-row">
              <svg className="donut" viewBox="0 0 120 120" aria-hidden="true">
                <circle cx="60" cy="60" r="44" className="ring" />
                {d.arcs.map((arc) => (
                  <circle key={arc} cx="60" cy="60" r="44" className={`arc ${arc}`} />
                ))}
              </svg>
              <ul className="legend">
                {d.legend.map((s) => (
                  <li key={s.dot}>
                    <i className={`dot ${s.dot}`} />
                    {s.name} <b>{s.value}</b> <span>{s.pct}</span>
                  </li>
                ))}
              </ul>
            </div>
          </div>
        ))}
      </div>
      <div className="rank-stack">
        <div className="rank-card">
          <div className="rank-head">
            <h3>内容榜</h3>
            <a {...rankMoreProps(navigate, RANK_CONTENT)}>查看全部</a>
          </div>
          <p className="rank-note">Top 5 · 按浏览量</p>
          <div className="rank-cards">
            {CONTENT_RANK.map((r) => (
              <div className="rank-item" key={r.title}>
                <div className="rank-cover">
                  <img src={r.cover} alt="" loading="lazy" />
                  <span className="rank-views">{r.views}</span>
                </div>
                <p className="rank-title">{r.title}</p>
              </div>
            ))}
          </div>
        </div>
        <div className="rank-grid rank-grid--2">
          <div className="rank-card">
            <div className="rank-head">
              <h3>标签榜</h3>
              <a {...rankMoreProps(navigate, RANK_TAGS)}>查看全部</a>
            </div>
            <p className="rank-note">Top 5 · 按关联文件数</p>
            <RankList rows={TAG_RANK} onSelect={(n) => navigate(`/app/collection/${COLLECTION_TAG}/${encodeURIComponent(n)}`)} />
          </div>
          <div className="rank-card">
            <div className="rank-head">
              <h3>作者榜</h3>
              <a {...rankMoreProps(navigate, RANK_AUTHORS)}>查看全部</a>
            </div>
            <p className="rank-note">Top 5 · 按作品数</p>
            <RankList rows={AUTHOR_RANK} onSelect={(n) => navigate(`/app/collection/${COLLECTION_AUTHOR}/${encodeURIComponent(n)}`)} />
          </div>
          <div className="rank-card">
            <div className="rank-head">
              <h3>作者总览</h3>
              <a
                href="#"
                id="authorManage"
                onClick={(e) => {
                  e.preventDefault()
                  navigate('/app/authors')
                }}
              >
                管理
              </a>
            </div>
            <p className="rank-note">15 位作者 · 已关注 3</p>
            <RankList rows={AUTHOR_RANK} onSelect={(n) => navigate(`/app/collection/${COLLECTION_AUTHOR}/${encodeURIComponent(n)}`)} />
          </div>
        </div>
      </div>
    </div>
  )
}
