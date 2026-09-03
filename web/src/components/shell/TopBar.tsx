/**
 * 顶栏（75px）：左侧分类 tabs（推荐/cos/排行榜）+ 居中搜索框（含下拉面板）+
 * 右侧窗口控件（浏览器环境纯装饰）。
 * - tabs 与榜单周期行只在首页显示（原型拍板）：由当前路由推导，非 JS 手动显隐；
 * - 排行榜 tab 激活时在顶栏下方渲染日/月/周/年榜周期行（rank-panel）；
 * - 搜索框回车/点历史词进入搜索结果页（/app/search?q=）；
 * - 搜索历史 = localStorage（最多 20 条去重最新在前，打开面板显示前 8 条）；
 * - 推荐搜索词 = 标签/作者按 fileCount 前若干名组合（真实数据，点击同历史词行为）。
 */

import { useEffect, useMemo, useRef, useState } from 'react'
import { useLocation, useNavigate, useSearchParams } from 'react-router'
import { useAuthors } from '@/hooks/use-authors'
import { useTags } from '@/hooks/use-tags'
import { ChevronDownIcon, ClearIcon, SearchIcon, WinCloseIcon, WinMaxIcon, WinMinIcon } from './icons'

/** 顶栏分类 tab 定义（原型 data-tab 值；hot = 排行榜） */
const HEADER_TABS = [
  { key: 'recommend', label: '推荐' },
  { key: 'cos', label: 'cos' },
  { key: 'hot', label: '排行榜' },
] as const

/** 榜单周期胶囊（原型固定四档，阶段 A 仅切换高亮） */
const RANK_PERIODS = ['日榜', '月榜', '周榜', '年榜']

/** 搜索历史 localStorage 键名（本次接真引入，无协议联动） */
const HISTORY_STORAGE_KEY = 'qimeng_search_history'
/** 历史条数上限（超出淘汰最旧） */
const HISTORY_MAX_ENTRIES = 20
/** 面板默认展示条数（超出收进「展开更多」；MOCK 常量 SEARCH_HISTORY_VISIBLE_COUNT 已废弃） */
const HISTORY_VISIBLE_COUNT = 8

/** 推荐词取各池的 top N（tags 6 + authors 6，合并去重后最多展示 8 个） */
const RECO_PER_SOURCE = 6
const RECO_MAX_COUNT = 8

/** 读取搜索历史（localStorage 损坏/类型不对时回退空数组，不抛中断渲染） */
function loadHistory(): string[] {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(HISTORY_STORAGE_KEY) ?? '[]')
    return Array.isArray(parsed) ? parsed.filter((w): w is string => typeof w === 'string') : []
  } catch {
    return []
  }
}

/** 写回搜索历史（去重最新在前 + 上限截断） */
function saveHistory(list: string[]): void {
  localStorage.setItem(HISTORY_STORAGE_KEY, JSON.stringify(list.slice(0, HISTORY_MAX_ENTRIES)))
}

export function TopBar() {
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const [searchParams, setSearchParams] = useSearchParams()
  const activeTab = searchParams.get('tab') ?? 'recommend'

  const isHome = pathname === '/app/home'
  const showRankPanel = isHome && activeTab === 'hot'

  // 榜单周期胶囊单选（原型语义：点谁谁高亮，默认日榜）
  const [rankPeriod, setRankPeriod] = useState(0)

  // 搜索框状态：输入值 / 面板开关 / 历史块 / 历史展开
  const [query, setQuery] = useState('')
  const [popOpen, setPopOpen] = useState(false)
  const [history, setHistory] = useState<string[]>(loadHistory)
  const [historyExpanded, setHistoryExpanded] = useState(false)
  const searchBoxRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  // 推荐搜索词：标签/作者按 fileCount 降序各取前 N，合并按名称去重（tag 名与作者名可能相同）
  const { data: tags = [] } = useTags()
  const { data: authors = [] } = useAuthors()
  const recoWords = useMemo(() => {
    const topTags = [...tags]
      .sort((a, b) => (b.fileCount ?? 0) - (a.fileCount ?? 0))
      .slice(0, RECO_PER_SOURCE)
      .map((t) => t.name ?? '')
      .filter(Boolean)
    const topAuthors = [...authors]
      .sort((a, b) => (b.fileCount ?? 0) - (a.fileCount ?? 0))
      .slice(0, RECO_PER_SOURCE)
      .map((a) => a.displayName ?? '')
      .filter(Boolean)
    const seen = new Set<string>()
    return [...topTags, ...topAuthors]
      .filter((w) => {
        if (seen.has(w)) return false
        seen.add(w)
        return true
      })
      .slice(0, RECO_MAX_COUNT)
  }, [tags, authors])

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
    // 新词写入历史（去重置顶，最新在前），localStorage 同步
    const next = [q, ...history.filter((h) => h !== q)].slice(0, HISTORY_MAX_ENTRIES)
    saveHistory(next)
    setHistory(next)
    navigate(`/app/search?q=${encodeURIComponent(q)}`)
  }

  const clearHistory = (): void => {
    localStorage.removeItem(HISTORY_STORAGE_KEY)
    setHistory([])
    setHistoryExpanded(false)
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
    ? history
    : history.slice(0, HISTORY_VISIBLE_COUNT)

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
              {history.length > 0 ? (
                <div className="pop-block">
                  <div className="pop-head">
                    <h3 className="pop-title">搜索历史</h3>
                    <button
                      className="pop-clear"
                      type="button"
                      onClick={(e) => {
                        e.stopPropagation()
                        clearHistory()
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
                  {history.length > HISTORY_VISIBLE_COUNT ? (
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
                {recoWords.length > 0 ? (
                  <div className="pop-history">
                    {recoWords.map((word) => (
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
                ) : (
                  <p className="pop-reco-hint">暂无推荐词——先扫描媒体库生成标签与作者</p>
                )}
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
