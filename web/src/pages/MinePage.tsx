import { useMemo, useState } from 'react'
import { MediaCard } from '@/components/media/MediaCard'
import { SearchIcon } from '@/components/shell/icons'
import { MOCK_ALBUM_FILES } from '@/pages/mock'

/**
 * 我的页（原型 #page-mine 移植）：资料卡 + 三 Tab（关注/收藏/历史）。
 * 关注按钮为内存态双向切换；历史搜索按标题/作者子串过滤，整组无命中不渲染该组。
 */

type MineTab = 'follow' | 'fav' | 'history'

const TABS: { key: MineTab; label: string }[] = [
  { key: 'follow', label: '关注作者' },
  { key: 'fav', label: '收藏作品' },
  { key: 'history', label: '浏览历史' },
]

/** 关注作者卡（原型 index.html 167-187 写死的 5 位作者，阶段 B 接真实关注列表） */
interface FollowAuthor {
  name: string
  works: number
  followed: boolean
}

const FOLLOW_AUTHORS: FollowAuthor[] = [
  { name: 'Kokorooo_', works: 62, followed: true },
  { name: '纪录片bro', works: 141, followed: true },
  { name: '韩比迪', works: 38, followed: false },
  { name: '红星视频', works: 210, followed: true },
  { name: '关于转生成骡姬这档事', works: 19, followed: false },
]

/** 收藏作品 = 内容池固定下标（与原型 FAV_WORK_INDEXES 一致） */
const FAV_INDEXES = [0, 1, 3, 4, 5, 7]

interface HistCard {
  cover: string
  title: string
  up: string
  done?: string
  time: string
  duration?: string
}

const HIST_GROUPS: { label: string; cards: HistCard[] }[] = [
  {
    label: '今天',
    cards: [
      { cover: '/covers/c-avatar.webp', title: '【kkr】小祥指挥交通', up: '@ Kokorooo_', done: '已看完', time: '今天 00:10' },
    ],
  },
  {
    label: '昨天',
    cards: [
      {
        cover: '/covers/c-dlss.webp',
        title: '在没有 dlls 的游戏中逆编渲染管线实现原生接入 dlss5',
        up: '@ 关于转生成骡姬这档事',
        time: '昨天 23:59',
        duration: '00:14/06:53',
      },
      {
        cover: '/covers/c-tokyo.webp',
        title: '大型纪录片《老农拼死护住儿子的救命钱》持续为您播出！',
        up: '@ 纪录片bro',
        time: '昨天 23:45',
        duration: '00:46/07:44',
      },
      {
        cover: '/covers/c-box.webp',
        title: '《开学自我介绍666》',
        up: '@ 韩比迪',
        time: '昨天 23:44',
        duration: '00:12/04:10',
      },
      {
        cover: '/covers/c-rural.webp',
        title: '马杜罗被强掳了8个月后 狱中近照首次曝光 身形消瘦现身纽约监狱…',
        up: '@ 红星视频',
        time: '昨天 23:44',
        duration: '00:03/00:39',
      },
    ],
  },
]

export default function MinePage() {
  const [tab, setTab] = useState<MineTab>('follow')
  const [follows, setFollows] = useState(FOLLOW_AUTHORS)
  const [query, setQuery] = useState('')

  const toggleFollow = (name: string) =>
    setFollows((list) => list.map((a) => (a.name === name ? { ...a, followed: !a.followed } : a)))

  const favItems = FAV_INDEXES.map((i) => MOCK_ALBUM_FILES[i])

  const histGroups = useMemo(() => {
    const q = query.trim().toLowerCase()
    return HIST_GROUPS.map((g) => ({
      ...g,
      cards: g.cards.filter((c) => !q || `${c.title} ${c.up}`.toLowerCase().includes(q)),
    })).filter((g) => g.cards.length > 0)
  }, [query])

  return (
    <div className="page" id="page-mine">
      <section className="profile-card">
        <div className="profile-main">
          <h2>绮梦</h2>
          <p>本地管理员 · mock 数据</p>
        </div>
        <dl className="profile-stats">
          <div>
            <dd>1</dd>
            <dt>媒体库</dt>
          </div>
          <div>
            <dd>7,347</dd>
            <dt>文件总数</dt>
          </div>
          <div>
            <dd>128.4 GB</dd>
            <dt>总大小</dt>
          </div>
        </dl>
      </section>
      <div className="tabs" role="tablist">
        {TABS.map((t) => (
          <button
            key={t.key}
            className={tab === t.key ? 'tab-btn active' : 'tab-btn'}
            data-mtab={t.key}
            type="button"
            onClick={() => setTab(t.key)}
          >
            {t.label}
          </button>
        ))}
      </div>
      {/* pane 显隐沿用原型 hidden 属性（CSS .m-pane[hidden] 已兜底 display:none） */}
      <div className="m-pane" id="mpane-follow" hidden={tab !== 'follow'}>
        <div className="follow-list">
          {follows.map((a) => (
            <article className="follow-card" key={a.name}>
              <div className="follow-info">
                <p className="follow-name">{a.name}</p>
                <p className="follow-sub">{a.works} 个作品</p>
              </div>
              <button
                className={a.followed ? 'follow-btn' : 'follow-btn follow-btn--idle'}
                type="button"
                onClick={() => toggleFollow(a.name)}
              >
                {a.followed ? '已关注' : '关注'}
              </button>
            </article>
          ))}
        </div>
      </div>
      <div className="m-pane" id="mpane-fav" hidden={tab !== 'fav'}>
        <div className="media-grid" id="favGrid">
          {favItems.map((f) => (
            <MediaCard key={f.name} cover={f.cover} title={f.name} duration={f.duration} up={f.up} date={f.date} />
          ))}
        </div>
      </div>
      <div className="m-pane" id="mpane-history" hidden={tab !== 'history'}>
        <div className="hist-toolbar">
          <p className="m-note">按观看时间分组（mock 数据）</p>
          <div className="hist-search">
            <input
              type="text"
              id="histSearch"
              placeholder="搜索你的历史记录"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
            <SearchIcon />
          </div>
        </div>
        {histGroups.map((g) => (
          <div className="hist-group2" key={g.label}>
            <h3>{g.label}</h3>
            <div className="hist-grid">
              {g.cards.map((c) => (
                <article className="hist-card" key={c.title}>
                  <div className="hc-cover">
                    <img src={c.cover} alt="" />
                    {c.done ? <span className="hc-done">{c.done}</span> : null}
                    <span className="hc-time">{c.time}</span>
                    {c.duration ? <span className="hc-duration">{c.duration}</span> : null}
                  </div>
                  <p className="hc-title">{c.title}</p>
                  <p className="hc-up">{c.up}</p>
                </article>
              ))}
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}
