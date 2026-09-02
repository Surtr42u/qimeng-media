/**
 * 顶栏（75px）：左侧分类 tabs（推荐/cos/排行榜）+ 居中搜索框（含下拉面板）+
 * 右侧窗口控件（浏览器环境纯装饰）。
 * - tabs 与榜单周期行只在首页显示（原型拍板）：由当前路由推导，非 JS 手动显隐；
 * - 排行榜 tab 激活时在顶栏下方渲染日/月/周/年榜周期行（rank-panel）；
 * - 搜索框回车/点历史词进入搜索结果页（/app/search?q=）。
 */

import { useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate, useSearchParams } from 'react-router'
import {
  MOCK_SEARCH_HISTORY, SEARCH_HISTORY_VISIBLE_COUNT,
} from '@/pages/mock'
import { ChevronDownIcon, ClearIcon, SearchIcon, WinCloseIcon, WinMaxIcon, WinMinIcon } from './icons'

/** 顶栏分类 tab 定义（原型 data-tab 值；hot = 排行榜） */
const HEADER_TABS = [
  { key: 'recommend', label: '推荐' },
  { key: 'cos', label: 'cos' },
  { key: 'hot', label: '排行榜' },
] as const

/** 榜单周期胶囊（原型固定四档，阶段 A 仅切换高亮） */
const RANK_PERIODS = ['日榜', '月榜', '周榜', '年榜']

export function TopBar() {
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const [searchParams, setSearchParams] = useSearchParams()
  const activeTab = searchParams.get('tab') ?? 'recommend'

  const isHome = pathname === '/app/home'
  const showRankPanel = isHome && activeTab === 'hot'

  // 榜单周期胶囊单选（原型语义：点谁谁高亮，默认日榜）
  const [rankPeriod, setRankPeriod] = useState(0)

  // 搜索框状态：输入值 / 面板开关 / 历史块显隐（清空后本会话保持空）/ 历史展开
  const [query, setQuery] = useState('')
  const [popOpen, setPopOpen] = useState(false)
  const [historyVisible, setHistoryVisible] = useState(true)
  const [historyExpanded, setHistoryExpanded] = useState(false)
  const searchBoxRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  // 点击面板外收起下拉（原型 document click 委托语义）
  useEffect(() => {
    const onDocClick = (e: MouseEvent): void => {
      if (!searchBoxRef.current?.contains(e.target as Node)) setPopOpen(false)
    }
    document.addEventListener('click', onDocClick)
    return () => document.removeEventListener('click', onDocClick)
  }, [])

  const enterSearch = (raw: string): void => {
    const q = raw.trim()
    if (!q) return
    setQuery(q) // 原型语义：点历史词进入结果页后词回写搜索框（并显示清除按钮）
    setPopOpen(false)
    inputRef.current?.blur()
    navigate(`/app/search?q=${encodeURIComponent(q)}`)
  }

  const switchTab = (key: string): void => {
    // 原型语义：每次进入排行榜态，周期行重置为日榜
    if (key === 'hot') setRankPeriod(0)
    const next = new URLSearchParams(searchParams)
    if (key === 'recommend') next.delete('tab')
    else next.set('tab', key)
    setSearchParams(next, { replace: true })
  }

  const visibleHistory = historyExpanded
    ? MOCK_SEARCH_HISTORY
    : MOCK_SEARCH_HISTORY.slice(0, SEARCH_HISTORY_VISIBLE_COUNT)

  return (
    <>
      <header className="header">
        <div className="header--left">
          <nav className="tabs" hidden={!isHome}>
            {HEADER_TABS.map((t) => (
              <button
                key={t.key}
                className={`tab${activeTab === t.key ? ' active' : ''}`}
                type="button"
                onClick={() => switchTab(t.key)}
              >
                {t.label}
              </button>
            ))}
          </nav>
        </div>
        <div className="search" ref={searchBoxRef}>
          <input
            ref={inputRef}
            type="text"
            placeholder="搜索你感兴趣的视频"
            autoComplete="off"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onFocus={() => setPopOpen(true)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') enterSearch(query)
            }}
          />
          <button
            className="search-clear"
            type="button"
            aria-label="清空搜索词"
            hidden={query.length === 0}
            onClick={() => {
              setQuery('')
              inputRef.current?.focus()
            }}
          >
            <ClearIcon />
          </button>
          <SearchIcon className="search-icon" />
          {popOpen ? (
            <div className="search-pop">
              {historyVisible ? (
                <div className="pop-block">
                  <div className="pop-head">
                    <h3 className="pop-title">搜索历史</h3>
                    <button
                      className="pop-clear"
                      type="button"
                      onClick={(e) => {
                        e.stopPropagation()
                        setHistoryVisible(false)
                      }}
                    >
                      清空
                    </button>
                  </div>
                  <div className="pop-history">
                    {visibleHistory.map((word) => (
                      <button
                        key={word}
                        className="pop-chip"
                        type="button"
                        onClick={() => enterSearch(word)}
                      >
                        {word}
                      </button>
                    ))}
                  </div>
                  {MOCK_SEARCH_HISTORY.length > SEARCH_HISTORY_VISIBLE_COUNT ? (
                    <button
                      className="pop-more"
                      type="button"
                      onClick={(e) => {
                        e.stopPropagation()
                        setHistoryExpanded((v) => !v)
                      }}
                    >
                      <span className="pop-more-text">{historyExpanded ? '收起' : '展开更多'}</span>
                      <ChevronDownIcon />
                    </button>
                  ) : null}
                </div>
              ) : null}
              <div className="pop-block">
                <h3 className="pop-title">推荐搜索</h3>
                <p className="pop-reco-hint">推荐搜索词将在接入推荐参数后生成</p>
              </div>
            </div>
          ) : null}
        </div>
        <div className="header--right">
          <div className="win-controls">
            <button className="win-btn" title="最小化" type="button"><WinMinIcon /></button>
            <button className="win-btn" title="最大化" type="button"><WinMaxIcon /></button>
            <button className="win-btn" title="关闭" type="button"><WinCloseIcon /></button>
          </div>
        </div>
      </header>
      {showRankPanel ? (
        <div className="rank-panel">
          <div className="rank-tabs">
            {RANK_PERIODS.map((p, i) => (
              <button
                key={p}
                className={`rank-tab${rankPeriod === i ? ' active' : ''}`}
                type="button"
                onClick={() => setRankPeriod(i)}
              >
                {p}
              </button>
            ))}
          </div>
        </div>
      ) : null}
    </>
  )
}
