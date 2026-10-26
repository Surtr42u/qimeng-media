import { useState } from 'react'
import { toast } from 'sonner'
import { Pill } from '@/components/ui/pill'
import { useSaveSourceVocabulary, useSourceVocabulary } from '@/hooks/use-authors'
import { batchFailureReason } from '@/lib/batch'
import { ManageQueryError } from './ManagePageHeader'

/**
 * 通用来源词表卡（2026-09-28 自设置页迁入文件管理域——词表服务上传挂靠场景，
 * 与上传工作台同页维护更顺）：GET /authors/source-vocabulary 回填 + 本地草稿；
 * 保存一次 PUT 整体替换。条目增删走草稿：逐条点击移除 / 输入框回车添加
 * （trim 非空、重复不加——与服务端 400 去重口径对齐，省一次白打请求）。
 * 资产编辑页来源区（SourceSelectField）的快捷选项共用本词表。
 */
export function SourceVocabularyCard({ onDirtyChange }: { onDirtyChange: (dirty: boolean) => void }) {
  const query = useSourceVocabulary()
  const { data: vocabData, isLoading } = query
  const saveVocab = useSaveSourceVocabulary()
  const [vocabDraft, setVocabDraft] = useState<string[] | null>(null)
  const [vocabInput, setVocabInput] = useState('')
  const vocab: string[] = vocabDraft ?? vocabData?.sources ?? []
  const busy = saveVocab.isPending || isLoading || query.isError || vocabData === undefined

  const addVocabWord = (): void => {
    if (busy) return
    const word = vocabInput.trim()
    if (word === '') return
    if (!vocab.includes(word)) { setVocabDraft([...vocab, word]); onDirtyChange(true) }
    setVocabInput('')
  }

  const removeVocabWord = (word: string): void => {
    if (busy) return
    setVocabDraft(vocab.filter((w) => w !== word))
    onDirtyChange(true)
  }

  const doSaveVocab = (): void => {
    if (busy || vocabDraft === null) return
    saveVocab.mutate(
      { sources: vocab },
      {
        onSuccess: () => {
          setVocabDraft(null)
          onDirtyChange(false)
          toast.success('通用来源词表已保存')
        },
        onError: (err) => toast.error(`通用来源词表保存失败：${batchFailureReason(err)}`),
      },
    )
  }

  return (
    <div className="settings-card">
      <h3>通用来源词表</h3>
      <p>资产编辑页来源区与上传挂靠来源区的建议词（获取渠道/平台名，如「forum-c」）· 全员共享，服务端统一保存</p>
      {query.isError && <ManageQueryError retry={() => { void query.refetch() }}>来源词表读取失败，草稿保留；重新读取后再保存。</ManageQueryError>}
      <div className="source-chips">
        {vocab.map((word) => (
          <Pill
            key={word}
            active
            disabled={busy}
            onClick={() => removeVocabWord(word)}
            title="点击移除该建议词"
          >
            {word}
          </Pill>
        ))}
        {isLoading && vocab.length === 0 ? (
          <span className="source-empty-hint">词表加载中…</span>
        ) : null}
        {!isLoading && vocab.length === 0 ? (
          <span className="source-empty-hint">暂无建议词，在下方输入框回车添加</span>
        ) : null}
      </div>
      <div className="tag-newrow">
        <input
          className="tag-input"
          type="text"
          placeholder="新来源词，回车加入词表草稿"
          autoComplete="off"
          value={vocabInput}
          disabled={busy}
          onChange={(e) => setVocabInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault()
              addVocabWord()
            }
          }}
        />
      </div>
      <small>点击词表条目移除 · 回车添加（自动去空白，重复词不加）· 保存为整体替换</small>
      <div className="settings-actions" style={{ marginTop: 12 }}>
        <button
          className="save-btn"
          type="button"
          disabled={busy || vocabDraft === null}
          onClick={doSaveVocab}
        >
          {saveVocab.isPending ? '保存中…' : '保存来源词表'}
        </button>
        <Pill disabled={busy || vocabDraft === null} onClick={() => { setVocabDraft(null); setVocabInput(''); onDirtyChange(false) }}>放弃修改</Pill>
      </div>
    </div>
  )
}
