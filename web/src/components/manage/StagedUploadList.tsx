import { useState } from 'react'
import { AuthorSuggestField } from '@/components/manage/AuthorSuggestField'
import { SourceSelectField } from '@/components/manage/SourceSelectField'
import { Pill } from '@/components/ui/pill'
import { useNameSuggestions } from '@/hooks/use-assets'
import { SUGGEST_DEBOUNCE_MS, useDebouncedValue } from '@/hooks/use-debounced-value'
import { formatBytes } from '@/lib/format'
import { baseNameOf, extensionOf } from '@/lib/upload-naming'
import { effectiveUploadName, type StagedUploadItem } from '@/lib/staged-upload'

/**
 * 上传暂存列表（2026-09-28 上传工作台重做，交互同构 App 上传页暂存区）：
 * 先进暂存、逐项校对——每条可展开编辑「作品名（基名，扩展名锁定）/ 作者 / 来源」，
 * 目标库/目录是批次概念由工作台统一持有（App 逐项库覆盖不在本期 Web 范围）。
 * 新进条目继承批次默认挂靠（作者/来源），「应用到全部」可整批重刷。
 * 条目模型/派生口径在 lib/staged-upload.ts（单源，行为有测试锁定口径的纯函数族）；
 * 铁律 7：联想/词表取数全走 hooks；业务口径（挂靠以作者为前提、扩展名锁定）
 * 以服务端为唯一口径，前端只做提示。
 */

/** 逐项挂靠摘要行（口径同 App AttachmentSummary） */
function attachSummary(item: StagedUploadItem): string {
  const a = item.attachAuthor
  if (a?.authorId) {
    const name = a.displayName ?? a.authorName ?? a.authorId
    return item.attachSources.length > 0
      ? `挂靠：${name} · 来源 ${item.attachSources.join('、')}`
      : `挂靠：${name}`
  }
  if (a?.authorName) return `作者「${a.authorName}」不存在——仅能选择已有作者，请从联想中选择`
  return '未挂靠作者（点「编辑」补挂）'
}

/** 单条暂存行（折叠态摘要 + 展开编辑器；组件实例 = hook 实例，作品名联想按行取数） */
function StagedRow({
  item,
  libraryId,
  onPatch,
  onRemove,
}: {
  item: StagedUploadItem
  /** 作品名联想的目标库（批次目标库；未选库时不发请求） */
  libraryId: string
  onPatch: (id: string, patch: Partial<StagedUploadItem>) => void
  onRemove: (id: string) => void
}) {
  const [expanded, setExpanded] = useState(false)
  // 作品名输入值 = 编辑基名（未编辑显示展示名基名）；清空 = 未编辑（入队回退原名）
  const nameValue = item.uploadBaseName ?? baseNameOf(item.displayName)
  const debouncedName = useDebouncedValue(nameValue, SUGGEST_DEBOUNCE_MS)
  // 序号联想（GET /assets/name-suggestions）：库上下文取批次目标库；无库/空输入不发请求
  const { data: suggestions = [] } = useNameSuggestions(libraryId || undefined, debouncedName, expanded)
  const extension = extensionOf(item.displayName)
  const hasAuthorId = !!item.attachAuthor?.authorId

  /** 点选建议 = 基名回填（展示即最终落库名：建议基名 + 锁定扩展名） */
  const applySuggestion = (base: string): void => {
    onPatch(item.id, { uploadBaseName: base })
  }

  /** 作者变更：清作者连带清来源（来源挂靠以作者块为前提，服务端口径） */
  const onAuthorChange = (next: StagedUploadItem['attachAuthor']): void => {
    onPatch(item.id, {
      attachAuthor: next,
      attachSources: next?.authorId ? item.attachSources : [],
    })
  }

  const name = effectiveUploadName(item)

  return (
    <div className="staged-row">
      <div className="staged-main">
        <div className="staged-name" title={name}>{name}</div>
        {name !== item.displayName && <div className="staged-sub">原文件名：{item.displayName}</div>}
        <div className="staged-sub">
          {formatBytes(item.sizeBytes)} · {attachSummary(item)}
        </div>
        {expanded && (
          <div className="staged-editor">
            <label className="settings-field upload-attach-field">
              <span>作品名（落库文件名）</span>
              <div className="staged-name-input">
                <input
                  type="text"
                  placeholder="作品名"
                  autoComplete="off"
                  value={nameValue}
                  onChange={(e) => onPatch(item.id, { uploadBaseName: e.target.value })}
                />
                {extension !== '' && <span className="staged-sub">{extension}（锁定）</span>}
              </div>
              <small>仅作品名可编辑；建议为库内既有命名风格 + 下一序号{extension !== '' ? `（扩展名 ${extension} 锁定不可改）` : ''}</small>
            </label>
            {suggestions.length > 0 && (
              <div className="source-chips">
                {suggestions.map((base) => (
                  <Pill key={base} onClick={() => applySuggestion(base)} title="点击采用">
                    {base + extension}
                  </Pill>
                ))}
              </div>
            )}
            <AuthorSuggestField
              label="作者"
              attachedHint="已选作者——上传成功后自动挂靠"
              value={item.attachAuthor}
              onChange={onAuthorChange}
            />
            <SourceSelectField
              label="来源"
              hint="留空 = 不改动该作者既有来源；已选来源上传后并入其来源记录（不覆盖）"
              disabledHint="先选择作者后才能设置来源"
              disabled={!hasAuthorId}
              selected={item.attachSources}
              onChange={(next) => onPatch(item.id, { attachSources: next })}
            />
          </div>
        )}
      </div>
      <span className="staged-actions">
        <Pill onClick={() => setExpanded((v) => !v)}>{expanded ? '收起' : '编辑'}</Pill>
        <Pill onClick={() => onRemove(item.id)} title="从暂存区移除">移除</Pill>
      </span>
    </div>
  )
}

/** 暂存列表（含空态引导；常驻显示，不随选文件出现） */
export function StagedUploadList({
  items,
  libraryId,
  onPatch,
  onRemove,
}: {
  items: StagedUploadItem[]
  libraryId: string
  onPatch: (id: string, patch: Partial<StagedUploadItem>) => void
  onRemove: (id: string) => void
}) {
  if (items.length === 0) {
    return (
      <p className="grid-empty">
        暂无待传文件——点击上方上传区选择文件，或把文件/文件夹拖进来（目录递归展开）。
      </p>
    )
  }
  return (
    <div>
      {items.map((item) => (
        <StagedRow key={item.id} item={item} libraryId={libraryId} onPatch={onPatch} onRemove={onRemove} />
      ))}
    </div>
  )
}
