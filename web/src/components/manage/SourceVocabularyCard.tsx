import { useState } from 'react'
import { toast } from 'sonner'
import { Pill } from '@/components/ui/pill'
import { useSaveSourceVocabulary, useSourceVocabulary } from '@/hooks/use-authors'
import { batchFailureReason } from '@/lib/batch'

/**
 * 通用来源词表卡（2026-09-28 自设置页迁入文件管理域——词表服务上传挂靠场景，
 * 与上传工作台同页维护更顺）：GET /authors/source-vocabulary 回填 + 本地草稿；
 * 保存一次 PUT 整体替换。条目增删走草稿：逐条点击移除 / 输入框回车添加
 * （trim 非空、重复不加——与服务端 400 去重口径对齐，省一次白打请求）。
 * 资产编辑页来源区（SourceSelectField）的快捷选项共用本词表。
 */
export function SourceVocabularyCard() {
  const { data: vocabData, isLoading } = useSourceVocabulary()
  const saveVocab = useSaveSourceVocabulary()
  const [vocabDraft, setVocabDraft] = useState<string[] | null>(null)
  const [vocabInput, setVocabInput] = useState('')
  const vocab: string[] = vocabDraft ?? vocabData?.sources ?? []

  const addVocabWord = (): void => {
    const word = vocabInput.trim()
    if (word === '') return
    if (!vocab.includes(word)) setVocabDraft([...vocab, word])
    setVocabInput('')
  }

  const removeVocabWord = (word: string): void => {
    setVocabDraft(vocab.filter((w) => w !== word))
  }

  const doSaveVocab = (): void => {
    saveVocab.mutate(
      { sources: vocab },
      {
        onSuccess: () => {
          setVocabDraft(null)
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
      <div className="source-chips">
        {vocab.map((word) => (
          <Pill
            key={word}
            active
            disabled={saveVocab.isPending}
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
          disabled={saveVocab.isPending || (vocabDraft === null && vocab.length === 0)}
          onClick={doSaveVocab}
        >
          {saveVocab.isPending ? '保存中…' : '保存来源词表'}
        </button>
      </div>
    </div>
  )
}
