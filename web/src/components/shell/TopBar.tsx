/**
 * 顶栏（75px）：左侧分类 tabs（推荐/cos/排行榜）+ 居中搜索框（含下拉面板）+
 * 右侧窗口控件（浏览器环境纯装饰）。
 * - tabs 与榜单周期行只在首页显示（原型拍板）：由当前路由推导，非 JS 手动显隐；
 * - tab/周期收敛到 URL 参数（?tab=/period=）：TopBar 只写、HomePage 只读，
 *   刷新/直达不丢态；排行榜 tab 激活时在顶栏下方渲染日/月/周/年榜周期行；
 * - 搜索框回车/点面板项进入搜索结果页（/app/search?q=）；
 * - 搜索历史 = localStorage（最多 20 条去重最新在前，打开面板显示前 8 条）；
 * - 下拉面板双形态（对齐旧版语义）：输入非空 = 补全列表（服务端五维候选，
 *   一行 = 搜索图标 + 名称 + 类型徽标，点行即搜）；输入为空 = 搜索历史 +
 *   推荐搜索（recommend=1 随机五维词，服务端随机、每次打开换一批）。
 */

import { useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate, useSearchParams } from 'react-router'
import type { SearchSuggestion, SearchSuggestionType } from '@/api/generated'
import { useRecommendSearchWords, useSearchSuggestions } from '@/hooks/use-suggestions'
import { HOME_TABS, RANK_PERIODS, parseRankPeriod } from '@/lib/home-tabs'
import { ChevronDownIcon, ClearIcon, SearchIcon, WinCloseIcon, WinMaxIcon, WinMinIcon } from './icons'

/** 搜索历史 localStorage 键名（本次接真引入，无协议联动） */
const HISTORY_STORAGE_KEY = 'qimeng_search_history'
/** 历史条数上限（超出淘汰最旧） */
const HISTORY_MAX_ENTRIES = 20
/** 面板默认展示条数（超出收进「展开更多」；MOCK 常量 SEARCH_HISTORY_VISIBLE_COUNT 已废弃） */
const HISTORY_VISIBLE_COUNT = 8

/** 补全请求防抖（ms）：逐键请求是无意义的请求风暴，停顿 200ms 才取数 */
const SUGGEST_DEBOUNCE_MS = 200

/** 补全候选维度 → 徽标文案（协议 SearchSuggestionType 五个法值，缺一不可） */
const SUGGEST_TYPE_LABELS: Record<SearchSuggestionType, string> = {
  source: '出处',
  character: '角色',
  cosAuthor: 'COS作者',
  cosWork: 'COS作品',
  author: '作者',
}

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

/** 输入防抖：值稳定 delayMs 后才同步给消费方（TopBar 内联实现，仅此一处使用） */
function useDebouncedValue<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState(value)
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs)
    return () => clearTimeout(timer)
  }, [value, delayMs])
  return debounced
}

/** 推荐词去重：跨维同名（作者与角色同名等）在 chip 流里只保留一个词 */
function uniqueNames(items: SearchSuggestion[]): string[] {
  const seen = new Set<string>()
  const names: string[] = []
  for (const item of items) {
    if (!seen.has(item.name)) {
      seen.add(item.name)
      names.push(item.name)
    }
  }
  return names
}

export function TopBar() {
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const [searchParams, setSearchParams] = useSearchParams()
  const activeTab = searchParams.get('tab') ?? 'recommend'

  const isHome = pathname === '/app/home'
  const showRankPanel = isHome && activeTab === 'hot'
  // 周期行当前值 = URL ?period=（URL 驱动，刷新/直达不丢态；缺省日榜）
  const rankPeriod = parseRankPeriod(searchParams.get('period'))

  // 搜索框状态：输入值 / 面板开关 / 历史块 / 历史展开
  const [query, setQuery] = useState('')
  const [popOpen, setPopOpen] = useState(false)
  const [history, setHistory] = useState<string[]>(loadHistory)
  const [historyExpanded, setHistoryExpanded] = useState(false)
  const searchBoxRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  // 下拉双形态开关：输入非空（trim 后）= 补全列表态；空 = 历史 + 推荐词态
  const suggestMode = popOpen && query.trim() !== ''

  // 补全数据：防抖后取数。响应按 queryKey 隔离，慢响应不会污染新词的结果
  // （TanStack 逐键缓存天然免乱序竞争，无需手动 AbortController）
  const debouncedQuery = useDebouncedValue(query, SUGGEST_DEBOUNCE_MS)
  const {
    data: suggestions,
    isPending: suggestionsPending,
  } = useSearchSuggestions(debouncedQuery, popOpen)
  // 推荐词只在空态面板需要（输入态切走即停取）
  const { data: recoSuggestion } = useRecommendSearchWords(popOpen && !suggestMode)
  const recoWords = uniqueNames(recoSuggestion?.items ?? [])

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
    const next = new URLSearchParams(searchParams)
    if (key === 'recommend') next.delete('tab')
    else next.set('tab', key)
    // 周期只在排行榜态有意义：离开 hot 时清掉（原型：每次重新进入重置日榜，
    // 即不带 period 参数 = 日榜）
    if (key !== 'hot') next.delete('period')
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
            {HOME_TABS.map((t) => (
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
            suggestMode ? (
              <div className="search-pop">
                {suggestionsPending ? (
                  <p className="pop-suggest-hint">正在获取补全…</p>
                ) : suggestions && suggestions.items.length > 0 ? (
                  <div className="pop-suggest-list">
                    {suggestions.items.map((item) => (
                      <button
                        key={`${item.type}|${item.name}`}
                        className="pop-suggest-item"
                        type="button"
                        onClick={() => enterSearch(item.name)}
                      >
                        <SearchIcon className="pop-suggest-icon" />
                        <span className="pop-suggest-name">{item.name}</span>
                        <span className="pop-suggest-badge">{SUGGEST_TYPE_LABELS[item.type]}</span>
                      </button>
                    ))}
                  </div>
                ) : (
                  <p className="pop-suggest-hint">没有匹配的补全</p>
                )}
              </div>
            ) : (
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
                    <p className="pop-reco-hint">暂无推荐词——先扫描媒体库生成补全索引</p>
                  )}
                </div>
              </div>
            )
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
            {RANK_PERIODS.map((p) => (
              <button
                key={p.key}
                className={`rank-tab${rankPeriod === p.key ? ' active' : ''}`}
                type="button"
                onClick={() => {
                  const next = new URLSearchParams(searchParams)
                  next.set('tab', 'hot')
                  next.set('period', p.key)
                  setSearchParams(next, { replace: true })
                }}
              >
                {p.label}
              </button>
            ))}
          </div>
        </div>
      ) : null}
    </>
  )
}
