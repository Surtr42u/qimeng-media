import { useState } from 'react'
import { useSourceVocabulary } from '@/hooks/use-authors'
import { Pill } from '@/components/ui/pill'

/**
 * 作者来源/出处多选字段（资产编辑页每作者来源区消费；2026-09-25 协议批）。
 * 快捷选项词表 = GET /authors/source-vocabulary 通用来源词表（hooks 封装，
 * 铁律 7）——服务端手动维护的来源建议小清单（获取渠道/平台名，如「老王论坛」
 * ），全员共享；个人片段词表（GET /authors/sources）已删除，不再有第二词表。
 * 服务端已按固定序返回，前端不再排。自由输入行允许加入词表外新站点/URL
 * （回车确认）。props 契约不变：selected/onChange/disabled 仍由调用方持有
 * 草稿态，本组件不做任何请求之外的副作用。
 */
export function SourceSelectField({
  selected,
  onChange,
  disabled = false,
  label = '来源/出处（可选，并入该作者的来源记录）',
  hint = '留空 = 不改动该作者既有来源；已选来源保存时整体替换（点击已选可移除）',
  disabledHint = '来源字段当前不可用',
}: {
  selected: string[]
  onChange: (next: string[]) => void
  disabled?: boolean
  /** 字段标题（编辑页/上传工作台语境不同；缺省 = 编辑页原文案） */
  label?: string
  /** 提示行（编辑页=整体替换语义，上传=并入语义，调用方按语境给） */
  hint?: string
  /** 禁用态提示行（上传工作台提示先选作者） */
  disabledHint?: string
}) {
  const { data: vocab, isLoading } = useSourceVocabulary()
  const [input, setInput] = useState('')

  const selectedSet = new Set(selected)
  // 词表序=服务端返回序；词表外已选词（自由输入的）排在后面
  const vocabNames = vocab?.sources ?? []
  const customNames = selected.filter((n) => !vocabNames.includes(n))

  const toggle = (name: string): void => {
    onChange(selectedSet.has(name) ? selected.filter((s) => s !== name) : [...selected, name])
  }

  const addTyped = (): void => {
    const word = input.trim()
    if (word === '') return
    if (!selectedSet.has(word)) onChange([...selected, word]) // trim + 去重
    setInput('')
  }

  return (
    <div className={`settings-field upload-attach-field${disabled ? ' disabled' : ''}`}>
      <span>{label}</span>
      <div className="source-chips">
        {vocabNames.map((name) => (
          <Pill
            key={name}
            active={selectedSet.has(name)}
            disabled={disabled || isLoading}
            onClick={() => toggle(name)}
          >
            {name}
          </Pill>
        ))}
        {customNames.map((name) => (
          <Pill key={name} active disabled={disabled} onClick={() => toggle(name)} title="点击移除">
            {name}
          </Pill>
        ))}
        {isLoading && vocabNames.length === 0 && selected.length === 0 ? (
          <span className="source-empty-hint">词表加载中…</span>
        ) : null}
      </div>
      <div className="tag-newrow">
        <input
          className="tag-input"
          type="text"
          placeholder="新站点或 URL，回车加入"
          autoComplete="off"
          disabled={disabled}
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault()
              addTyped()
            }
          }}
        />
      </div>
      <small>{disabled ? disabledHint : hint}</small>
    </div>
  )
}
