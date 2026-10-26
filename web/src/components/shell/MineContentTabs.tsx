import { useRef, type KeyboardEvent } from 'react'
import { Heart, History, Users } from 'lucide-react'
import type { MineTab } from '@/hooks/use-mine-workspace'

const TABS = [{ key: 'follow', label: '关注作者', Icon: Users }, { key: 'fav', label: '收藏作品', Icon: Heart }, { key: 'history', label: '浏览历史', Icon: History }] as const

/** 标准Tab键进入，左右/Home/End切换；URL由页面持有，返回时恢复原分区。 */
export function MineContentTabs({ tab, onChange }: { tab: MineTab; onChange: (next: MineTab) => void }) {
  const buttons = useRef<Array<HTMLButtonElement | null>>([])
  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>, index: number) => {
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? TABS.length - 1 : event.key === 'ArrowRight' ? (index + 1) % TABS.length : event.key === 'ArrowLeft' ? (index + TABS.length - 1) % TABS.length : null
    if (next === null) return
    event.preventDefault()
    onChange(TABS[next].key)
    buttons.current[next]?.focus()
  }
  return <div className="mine-content-tabs" role="tablist" aria-label="我的内容">{TABS.map(({ key, label, Icon }, index) => <button key={key} ref={element => { buttons.current[index] = element }} className={tab === key ? 'active' : ''} type="button" role="tab" aria-selected={tab === key} aria-controls={`mine-panel-${key}`} id={`mine-tab-${key}`} tabIndex={tab === key ? 0 : -1} data-mtab={key} onClick={() => onChange(key)} onKeyDown={event => onKeyDown(event, index)}><Icon aria-hidden="true" />{label}</button>)}</div>
}
