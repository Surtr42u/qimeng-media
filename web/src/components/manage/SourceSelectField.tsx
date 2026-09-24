import { useState } from 'react'
import { useAuthorSources } from '@/hooks/use-authors'
import { Pill } from '@/components/ui/pill'

/**
 * 作者来源/出处多选字段（上传卡消费；REQ-上传指定作者与来源 §3.1②）。
 * 快捷选项词表 = GET /authors/sources（hooks 封装，铁律 7）——全部已导入
 * TXT 片段「来源/出处」区解析出的去重词汇；常用优先 = 服务端已按 authorCount
 * 降序，前端不再排。注意这是作者级来源词表，与资产出处分区（§4 SourceMatcher/
 * custom_sources）互不相干。自由输入行允许加入词表外新站点/URL（回车确认）。
 * 来源必须依附作者：未选作者时整字段 disabled（协议 source 仅指定作者时合法）。
 */
export function SourceSelectField({
  selected,
  onChange,
  disabled = false,
}: {
  selected: string[]
  onChange: (next: string[]) => void
  disabled?: boolean
}) {
  const { data: vocab, isLoading } = useAuthorSources()
  const [input, setInput] = useState('')

  const selectedSet = new Set(selected)
  // 词表序=服务端常用优先；词表外已选词（本批自由输入的）排在后面
  const vocabNames = (vocab?.sources ?? [])
    .map((s) => s.name)
    .filter((n): n is string => n != null)
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
      <span>来源/出处（可选，并入该作者的来源记录）</span>
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
      <small>
        {disabled
          ? '先选择作者，来源必须依附作者'
          : '留空 = 不改动该作者既有来源；已选来源并入且不重复（点击已选可移除）'}
      </small>
    </div>
  )
}
