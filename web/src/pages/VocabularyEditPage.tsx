import { useEffect, useState } from 'react'
import { useBlocker, useNavigate } from 'react-router'
import { VocabularyGroupsCard } from '@/components/manage/VocabularyGroupsCard'
import {
  VocabularyPromptDialog,
  type VocabularyPromptRequest,
} from '@/components/manage/VocabularyEditDialogs'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { LoadingHint } from '@/components/ui/loading-hint'
import { Pill } from '@/components/ui/pill'
import { useSaveSourceGroups, useSourceGroups } from '@/hooks/use-source-groups'
import { batchFailureReason } from '@/lib/batch'
import { MAINTENANCE_FILES_PATH } from '@/lib/route-keys'
import {
  addAlias,
  addCharacter,
  addGroup,
  addStopWord,
  addVariant,
  payloadToDraft,
  removeAlias,
  removeCharacter,
  removeGroup,
  removeStopWord,
  removeVariant,
  renameCharacter,
  renameGroup,
  toPayload,
  VOCABULARY_LIMITS,
  type VocabularyDraft,
} from '@/lib/vocabulary-edit'

/**
 * 词表维护页（App ADR-0035「词表维护」的 web 移植，2026-10-04；功能与 UI 布局
 * 对齐 App 端 VocabularyEditScreen）：数据管理 hub「词表维护」入口 → 本页，
 * 当前连接服务器检索词表（自定义出处组 + 停用词追加层）的直接编辑面。进页
 * GET 全量 → 内存编辑（draft 覆盖在服务端数据上，任一编辑非 no-op 即置 draft
 * = 脏态）→ 整体保存（PUT 显式 groups+stopWords，协议整体替换语义=用户看到
 * 什么就保存什么）→ 成功回落服务端形态（mutation 同步写缓存）+ 失效重拉带回
 * 规范化形态（App「静默回读」的 web 等价）。与「来源词表」子页
 * （/authors/source-vocabulary，上传挂靠建议词）是两张不同的表。有未保存修改
 * 时一切离开路径（返回钮/浏览器返回/换路由）走放弃确认；标签页关闭走
 * beforeunload 原生确认。编辑操作全是 lib/vocabulary-edit 纯函数（铁律 7）。
 */

/** 页面文案（App 端 VocabularyEditScreen 常量对位；「本机」改述为服务器语境） */
const SCREEN_TITLE = '词表维护'
const SCREEN_DESC = '出处组与停用词的检索词表追加层编辑（内置基线恒生效）'
const STOP_WORDS_CARD_TITLE = '停用词'
const STOP_WORDS_EMPTY = '没有自定义停用词（内置冻结基线始终生效，这里只维护追加层）'
const STOP_WORDS_ADD = '添加停用词'
const SAVE_BUTTON = '保存修改'
const SAVE_BUSY = '保存中…'
const LOADING_NOTE = '正在读取词表…'
const ERROR_LOAD = '词表加载失败，请稍后重试'
const NOTICE_SAVED = '已保存：%d 组出处、%d 个停用词，服务端将后台重算'

/** 对话框标题（单输入框机制的全部文案，构建点在本页；App 端同款） */
const PROMPT_ADD_GROUP = '新增出处组（规范名）'
const PROMPT_RENAME_GROUP = '修改组规范名'
const PROMPT_ADD_VARIANT = '新增变体写法'
const PROMPT_ADD_CHARACTER = '新增角色（规范名）'
const PROMPT_RENAME_CHARACTER = '修改角色规范名'
const PROMPT_ADD_ALIAS = '新增角色别名'
const PROMPT_ADD_STOP_WORD = '新增停用词（兜底提取将跳过该词）'

/** 放弃确认文案（App 端 VocabularyDiscardDialog 逐字对齐） */
const DISCARD_TITLE = '有未保存的修改'
const DISCARD_DESC = '离开将丢失本次编辑，确定离开吗？'
const DISCARD_CONFIRM = '放弃修改离开'
const DISCARD_CANCEL = '继续编辑'

/** 规则说明（协议语义的用户侧投影；权威口径 DOMAIN_RULES §4 / ADR-0033/0035。
 *  内置组数取 133（2026-10-04 固化后权威口径，App 端规则行的 130 系陈旧文案） */
const RULE_LINES = [
  '· 编辑对象是当前连接服务器的检索词表，保存后整体替换并自动后台重算',
  '· 组规范名与内置 133 组同名 = 并入扩词条，新名 = 追加新组',
  '· 保存时服务端自动去空去重；改组名不影响既有匹配（旧名自动并入变体）',
  '· 手机端词条经 App「备份导入导出 → 词表合并同步」并入本库，两端只增不删互不覆盖',
]

export default function VocabularyEditPage() {
  const navigate = useNavigate()
  const { data, isLoading, isError, refetch } = useSourceGroups()
  const saveGroups = useSaveSourceGroups()

  /** 内存编辑副本：null = 未编辑（回落服务端缓存形态）。任一非 no-op 编辑即置
   *  值 = 脏态；保存成功置回 null（mutation 已同步写缓存，「所见即所存」无闪变） */
  const [draft, setDraft] = useState<VocabularyDraft | null>(null)
  const [prompt, setPrompt] = useState<VocabularyPromptRequest | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)

  const dirty = draft !== null
  const busy = isLoading || saveGroups.isPending
  const base: VocabularyDraft | null = draft ?? (data === undefined ? null : payloadToDraft(data))

  /** 编辑入口：跑纯函数状态机，no-op（空白/越界）静默不动（App addMeaningful 对位） */
  const run = (fn: (draft: VocabularyDraft) => VocabularyDraft | null): void => {
    if (base === null) return
    const next = fn(base)
    if (next !== null) setDraft(next)
  }

  const doSave = (): void => {
    if (base === null || !dirty || busy) return
    saveGroups.mutate(toPayload(base), {
      onSuccess: (_res, body) => {
        setDraft(null)
        setErrorMsg(null)
        setNotice(
          NOTICE_SAVED.replace('%d', String(body.groups.length)).replace(
            '%d',
            String(body.stopWords?.length ?? 0),
          ),
        )
      },
      onError: (err) => setErrorMsg(`保存失败：${batchFailureReason(err)}，请重试`),
    })
  }

  // 离开拦截：返回钮/浏览器返回/任意路由切换统一走 useBlocker（数据路由，
  // createBrowserRouter 下可用）——App 端 BackHandler + 返回钮同口径的 web 对位
  const blocker = useBlocker(
    ({ currentLocation, nextLocation }) =>
      dirty && currentLocation.pathname !== nextLocation.pathname,
  )
  const blocked = blocker.state === 'blocked'

  // 标签页/窗口关闭的原生确认（SPA 内导航 useBlocker 管不到这层）
  useEffect(() => {
    if (!dirty) return
    const handler = (e: BeforeUnloadEvent): void => {
      e.preventDefault()
    }
    window.addEventListener('beforeunload', handler)
    return () => window.removeEventListener('beforeunload', handler)
  }, [dirty])

  return (
    <div className="page" id="page-maintenance-files-vocabulary-edit">
      <div className="settings-actions">
        <Pill onClick={() => navigate(MAINTENANCE_FILES_PATH)}>← 返回数据管理</Pill>
      </div>
      <div className="page-head">
        <h2>{SCREEN_TITLE}</h2>
        <p>{SCREEN_DESC}</p>
      </div>

      {errorMsg !== null ? (
        <div className="vocab-banner vocab-banner--error">
          <span>{errorMsg}</span>
          <button type="button" className="vocab-text-btn" onClick={() => setErrorMsg(null)}>
            关闭
          </button>
        </div>
      ) : null}
      {notice !== null ? (
        <div className="vocab-banner vocab-banner--notice">
          <span>{notice}</span>
          <button type="button" className="vocab-text-btn" onClick={() => setNotice(null)}>
            关闭
          </button>
        </div>
      ) : null}

      {isLoading ? (
        <div className="settings-card">
          <LoadingHint>{LOADING_NOTE}</LoadingHint>
        </div>
      ) : null}
      {isError ? (
        <div className="vocab-banner vocab-banner--error">
          <span>{ERROR_LOAD}</span>
          <button type="button" className="vocab-text-btn" onClick={() => void refetch()}>
            重试
          </button>
        </div>
      ) : null}

      {base !== null ? (
        <>
          <StopWordsCard
            stopWords={base.stopWords}
            busy={busy}
            onAdd={() =>
              setPrompt({
                title: PROMPT_ADD_STOP_WORD,
                onConfirm: (text) => run((d) => addStopWord(d, text)),
              })
            }
            onRemove={(index) => run((d) => removeStopWord(d, index))}
          />

          <VocabularyGroupsCard
            groups={base.groups}
            busy={busy}
            onAddGroup={() =>
              setPrompt({
                title: PROMPT_ADD_GROUP,
                onConfirm: (text) => run((d) => addGroup(d, text)),
              })
            }
            onRemoveGroup={(index) => run((d) => removeGroup(d, index))}
            onRenameGroup={(index, current) =>
              setPrompt({
                title: PROMPT_RENAME_GROUP,
                initial: current,
                onConfirm: (text) => run((d) => renameGroup(d, index, text)),
              })
            }
            onAddVariant={(index) =>
              setPrompt({
                title: PROMPT_ADD_VARIANT,
                onConfirm: (text) => run((d) => addVariant(d, index, text)),
              })
            }
            onRemoveVariant={(groupIndex, variantIndex) =>
              run((d) => removeVariant(d, groupIndex, variantIndex))
            }
            onAddCharacter={(index) =>
              setPrompt({
                title: PROMPT_ADD_CHARACTER,
                onConfirm: (text) => run((d) => addCharacter(d, index, text)),
              })
            }
            onRemoveCharacter={(groupIndex, characterIndex) =>
              run((d) => removeCharacter(d, groupIndex, characterIndex))
            }
            onRenameCharacter={(groupIndex, characterIndex, current) =>
              setPrompt({
                title: PROMPT_RENAME_CHARACTER,
                initial: current,
                onConfirm: (text) => run((d) => renameCharacter(d, groupIndex, characterIndex, text)),
              })
            }
            onAddAlias={(groupIndex, characterIndex) =>
              setPrompt({
                title: PROMPT_ADD_ALIAS,
                onConfirm: (text) => run((d) => addAlias(d, groupIndex, characterIndex, text)),
              })
            }
            onRemoveAlias={(groupIndex, characterIndex, aliasIndex) =>
              run((d) => removeAlias(d, groupIndex, characterIndex, aliasIndex))
            }
          />

          <button
            type="button"
            className="save-btn vocab-save"
            disabled={!dirty || busy}
            onClick={doSave}
          >
            {saveGroups.isPending ? SAVE_BUSY : SAVE_BUTTON}
          </button>
        </>
      ) : null}

      <ul className="vocab-rules">
        {RULE_LINES.map((line) => (
          <li key={line}>{line}</li>
        ))}
      </ul>

      {prompt !== null ? (
        <VocabularyPromptDialog request={prompt} onDismiss={() => setPrompt(null)} />
      ) : null}
      <ConfirmDialog
        open={blocked}
        title={DISCARD_TITLE}
        description={DISCARD_DESC}
        confirmText={DISCARD_CONFIRM}
        cancelText={DISCARD_CANCEL}
        danger
        onConfirm={() => blocker.proceed?.()}
        onOpenChange={(next) => {
          if (!next) blocker.reset?.()
        }}
      />
    </div>
  )
}

/** 停用词卡：追加层清单（逐词可移除）+ 新增入口；内置冻结基线不在此列恒生效（App StopWordsCard 对位） */
function StopWordsCard({
  stopWords,
  busy,
  onAdd,
  onRemove,
}: {
  stopWords: string[]
  busy: boolean
  onAdd: () => void
  onRemove: (index: number) => void
}) {
  return (
    <div className="settings-card">
      <div className="vocab-head">
        <h3>{STOP_WORDS_CARD_TITLE}</h3>
        <button
          type="button"
          className="vocab-text-btn"
          disabled={busy || stopWords.length >= VOCABULARY_LIMITS.STOP_WORDS_MAX}
          onClick={onAdd}
        >
          {STOP_WORDS_ADD}
        </button>
      </div>
      {stopWords.length === 0 ? (
        <p className="vocab-note">{STOP_WORDS_EMPTY}</p>
      ) : (
        stopWords.map((word, index) => (
          <div key={index} className="vocab-row">
            <span className="vocab-item-text">{word}</span>
            <button
              type="button"
              className="vocab-text-btn"
              disabled={busy}
              onClick={() => onRemove(index)}
            >
              移除
            </button>
          </div>
        ))
      )}
    </div>
  )
}
