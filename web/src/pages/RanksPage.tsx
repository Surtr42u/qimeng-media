import { useParams } from 'react-router'
import { RANK_AUTHORS, RANK_CONTENT, RANK_PAGE_TITLES, RANK_TAGS } from '@/pages/mock'

type RankKey = typeof RANK_CONTENT | typeof RANK_TAGS | typeof RANK_AUTHORS

const RANK_KEYS: readonly string[] = [RANK_CONTENT, RANK_TAGS, RANK_AUTHORS]

/** 内容榜 15 项（原型 #page-ranks data-panel=content 照搬） */
const CONTENT_ITEMS = [
  { cover: '/covers/c-dlss.webp', views: '3,241', title: '样例 2017 高画质合集' },
  { cover: '/covers/c-tokyo.webp', views: '1,982', title: '舞台摄影 · 第一组' },
  { cover: '/covers/c-frog.webp', views: '1,540', title: '场外随拍 · 机动展区' },
  { cover: '/covers/c-jet.webp', views: '1,207', title: '角色特写 · 第三辑' },
  { cover: '/covers/c-mc.webp', views: '986', title: '返场演出 · 完整版' },
  { cover: '/covers/c-milk.webp', views: '874', title: '夜景长曝光精选' },
  { cover: '/covers/c-rural.webp', views: '763', title: '同人作品 · 春季场' },
  { cover: '/covers/c-box.webp', views: '658', title: '后台花絮合辑' },
  { cover: '/covers/c-crab.webp', views: '571', title: '首日入场实况' },
  { cover: '/covers/c-frog-s.webp', views: '489', title: '舞台灯光全记录' },
  { cover: '/covers/c-mc-s.webp', views: '421', title: '特写镜头补录' },
  { cover: '/covers/c-avatar.webp', views: '366', title: '应援夜全景' },
  { cover: '/covers/c-dlss.webp', views: '302', title: '场刊扫描合集' },
  { cover: '/covers/c-tokyo.webp', views: '254', title: '路人视角混剪' },
  { cover: '/covers/c-frog.webp', views: '187', title: '收摊散场记录' },
]

/** 标签榜 15 项：[名称, 关联文件数] */
const TAG_ITEMS: [string, string][] = [
  ['高清', '1,892'], ['样例', '1,470'], ['舞台', '866'], ['Cosplay', '724'], ['夜景', '410'],
  ['摄影', '388'], ['同人', '356'], ['特写', '301'], ['日常', '276'], ['布景', '243'],
  ['户外', '215'], ['合集', '187'], ['随拍', '154'], ['演出', '132'], ['纪实', '98'],
]

/** 作者榜 15 项：[名称, 作品数] */
const AUTHOR_ITEMS: [string, string][] = [
  ['绮梦', '126'], ['夜空机位', '88'], ['舞台捕手', '57'], ['展会实录', '41'], ['样例日记', '33'],
  ['快门手', '29'], ['镜头后', '24'], ['夜行者', '21'], ['布光师', '18'], ['场记', '15'],
  ['追焦', '12'], ['侧台', '10'], ['观众席', '8'], ['通宵剪', '6'], ['档案员', '4'],
]

/**
 * 完整榜单页（原型 #page-ranks 移植）：数据页排行卡「查看全部」按榜进入，
 * 只渲染点进的那一个榜（原型 data-panel 匹配语义），非法 rank 按 content 处理。
 */
export default function RanksPage() {
  const { rank } = useParams()
  const key: RankKey = RANK_KEYS.includes(rank ?? '') ? (rank as RankKey) : RANK_CONTENT

  return (
    <div className="page" id="page-ranks">
      <div className="page-head">
        <h2>{RANK_PAGE_TITLES[key]}</h2>
        <p>全量排行 · mock 数据</p>
      </div>
      {key === RANK_CONTENT ? (
        <div className="rank-card">
          <div className="rank-head">
            <h3>内容榜</h3>
          </div>
          <p className="rank-note">按浏览量</p>
          <div className="rank-cards">
            {CONTENT_ITEMS.map((it) => (
              <div key={it.title} className="rank-item">
                <div className="rank-cover">
                  <img src={it.cover} alt="" loading="lazy" />
                  <span className="rank-views">{it.views}</span>
                </div>
                <p className="rank-title">{it.title}</p>
              </div>
            ))}
          </div>
        </div>
      ) : (
        <div className="rank-card">
          <div className="rank-head">
            <h3>{key === 'tags' ? '标签榜' : '作者榜'}</h3>
          </div>
          <p className="rank-note">{key === 'tags' ? '按关联文件数' : '按作品数'}</p>
          <ul>
            {(key === 'tags' ? TAG_ITEMS : AUTHOR_ITEMS).map(([name, count]) => (
              <li key={name}>
                <span className="rank-name">{name}</span>
                <b>{count}</b>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}
