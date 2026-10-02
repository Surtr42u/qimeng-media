import { useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router'
import { toast } from 'sonner'
import type { Author } from '@/api/generated'
import { AuthorSuggestField, type AuthorAttachValue } from '@/components/manage/AuthorSuggestField'
import { SourceSelectField } from '@/components/manage/SourceSelectField'
import { LoadingHint } from '@/components/ui/loading-hint'
import { Pill } from '@/components/ui/pill'
import { useAssetDetail } from '@/hooks/use-assets'
import { useThumbSize } from '@/hooks/use-thumb-size'
import { applyThumbSize } from '@/lib/thumb-size'
import {
  useAuthorSourcesById,
  useReplaceAssetAuthors,
  useSaveAuthorSources,
} from '@/hooks/use-authors'
import { batchFailureReason } from '@/lib/batch'
import { assetDetail } from '@/lib/route-keys'

/**
 * 资产编辑页（/app/asset/:assetId/edit，详情页「编辑」按钮进入；AppShell 平铺
 * 子路由，不在叠加组内——返回详情走 assetDetail 重开叠加，无底衬上下文）。
 *
 * 编辑面（2026-09-25 协议批，ADR-0024）：
 * - 作者关联 = PUT /assets/{assetId}/authors 整体替换（body=常规作者 ID 全集；
 *   COS 作者关联来自 COS 目录语义、协议拒绝 cos 作者 ID，编辑面直接不收）；
 * - 每作者来源区 = PUT /authors/{authorId}/sources 整体替换，只对有改动的
 *   作者逐个提交；服务端值经 useAuthorSourcesById 回显（草稿态）。
 * 上传三参数挂靠已退役：作者/来源的指定与调整全部收敛到本页。
 * 所有错误文案经 batchFailureReason 提取（unwrapSdkResult 抛 {code,message}
 * 错误体而非 Error 实例，instanceof Error 模式会落 "[object Object]"）。
 */

/** 草稿作者行（id 提交协议；displayName 仅前端展示，缺省回退 id） */
interface AuthorDraft {
  id: string
  displayName: string
}

/** 头图预览边长（编辑页信息卡小缩略图） */
const THUMB_SIZE_PX = 96

/**
 * 单作者来源区行（草稿态）：服务端词表经 useAuthorSourcesById 回显（authorId
 * 空=挂起不发请求），用户未交互时 selected 即服务端值；一交互父层即持有草稿
 * 并标脏。每个作者一行、独立 hook 实例——作者列表动态，不能在父层循环调 hook。
 */
function AuthorSourcesRow({
  authorId,
  draft,
  onChange,
}: {
  authorId: string
  draft: string[] | undefined
  onChange: (authorId: string, next: string[]) => void
}) {
  const { data } = useAuthorSourcesById(authorId)
  const selected = draft ?? data?.sources ?? []
  return <SourceSelectField selected={selected} onChange={(next) => onChange(authorId, next)} />
}

export default function AssetEditPage() {
  // 档位偏好接入选点（与 MediaCard 同口径；auto=服务端下发原样）
  const [thumbSize] = useThumbSize()
  const { assetId } = useParams()
  const navigate = useNavigate()
  const { data: detail } = useAssetDetail(assetId)

  // 存量常规作者（编辑基线）：detail.authors 过滤 COS 作者（协议 404 口径）后
  // 映射为草稿行。detail 未到货时空数组——只有用户新增会进草稿，不影响保存
  // 按钮（detail 未到货时整卡不渲染）。
  const serverAuthors = useMemo<AuthorDraft[]>(
    () =>
      (detail?.authors ?? [])
        .filter(
          (a: Author): a is Author & { id: string } =>
            a.id != null && a.id !== '' && a.type !== 'cos' && !a.id.startsWith('cos_'),
        )
        .map((a) => ({ id: a.id, displayName: a.displayName ?? a.id })),
    [detail],
  )

  // 草稿作者全集（null=未动，跟随服务端值）；保存提交的是最终 ID 全集
  const [draftAuthors, setDraftAuthors] = useState<AuthorDraft[] | null>(null)
  const authors = draftAuthors ?? serverAuthors
  // 来源草稿与脏标记（authorId → 词表/是否改过）：保存只对脏作者发 PUT
  const [sourceDrafts, setSourceDrafts] = useState<Record<string, string[]>>({})
  const [dirtySources, setDirtySources] = useState<Record<string, true>>({})
  // 联想字段受控值：加进草稿即复位为自由输入态，便于连续添加
  const [pickValue, setPickValue] = useState<AuthorAttachValue | null>(null)
  const [saving, setSaving] = useState(false)

  const replaceAuthors = useReplaceAssetAuthors()
  const saveSources = useSaveAuthorSources()

  /** 联想字段回传：authorId=已有作者（进草稿，重复不加）；authorName=新建
   *  意图——本页无法落 ID（协议只收已存在的常规作者 ID），明确告知而非静默
   *  吞掉；null=字段清空动作，不动草稿。 */
  const onPickAuthor = (v: AuthorAttachValue | null): void => {
    setPickValue(null)
    if (!v) return
    if (v.authorId) {
      if (authors.some((a) => a.id === v.authorId)) {
        toast.info(`「${v.displayName ?? v.authorId}」已在列表中`)
        return
      }
      setDraftAuthors([...authors, { id: v.authorId, displayName: v.displayName ?? v.authorId }])
      return
    }
    if (v.authorName) toast.info('本页只能关联已有作者；新建作者请先经 TXT 导入创建')
  }

  /** 移出草稿并清掉该作者的来源草稿/脏标记（重加同作者不残留旧脏态） */
  const removeAuthor = (id: string): void => {
    setDraftAuthors(authors.filter((a) => a.id !== id))
    const nextDrafts = { ...sourceDrafts }
    delete nextDrafts[id]
    setSourceDrafts(nextDrafts)
    const nextDirty = { ...dirtySources }
    delete nextDirty[id]
    setDirtySources(nextDirty)
  }

  /** 来源草稿上浮 + 标脏（保存时只对脏作者发整体替换） */
  const onSourceChange = (authorId: string, next: string[]): void => {
    setSourceDrafts((prev) => ({ ...prev, [authorId]: next }))
    setDirtySources((prev) => ({ ...prev, [authorId]: true }))
  }

  /** 保存：先整体替换作者关联，再对脏作者逐个提交来源区。来源区单作者失败
   *  不阻断其余作者，最后汇总报错并留在本页（草稿保留，可直接重试）。 */
  const doSave = async (): Promise<void> => {
    if (!assetId || saving) return
    setSaving(true)
    try {
      try {
        await replaceAuthors.mutateAsync({ assetId, authorIds: authors.map((a) => a.id) })
      } catch (err) {
        toast.error(`作者关联保存失败：${batchFailureReason(err)}`)
        return
      }
      const failures: string[] = []
      for (const a of authors) {
        if (!dirtySources[a.id]) continue
        try {
          await saveSources.mutateAsync({ authorId: a.id, sources: sourceDrafts[a.id] ?? [] })
        } catch (err) {
          failures.push(`${a.displayName}：${batchFailureReason(err)}`)
        }
      }
      if (failures.length > 0) {
        toast.error(`部分作者来源保存失败：${failures.join('；')}`)
        return
      }
      toast.success('资产作者已保存')
      navigate(assetDetail(assetId))
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="page" id="page-asset-edit">
      <div className="settings-actions">
        <Pill onClick={() => (assetId ? navigate(assetDetail(assetId)) : navigate(-1))}>
          ← 返回详情
        </Pill>
      </div>
      <div className="page-head">
        <h2>编辑资产</h2>
        <p>维护关联作者与每作者来源区（保存为整体替换）</p>
      </div>
      {!detail ? (
        <LoadingHint />
      ) : (
        <>
          <div className="settings-card">
            <h3>资产信息</h3>
            <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
              <img
                src={applyThumbSize(detail.thumbUrlMd ?? detail.thumbUrl ?? '', thumbSize)}
                alt={detail.fileName ?? ''}
                style={{ width: THUMB_SIZE_PX, height: THUMB_SIZE_PX, objectFit: 'cover', borderRadius: 8 }}
              />
              <div>
                <b>{detail.cosWork ?? detail.fileName}</b>
                <p className="rank-note">{detail.fileName ?? ''}</p>
              </div>
            </div>
          </div>
          <div className="settings-card">
            <h3>关联作者</h3>
            <p>在下方输入框联想选择已有作者逐个添加；COS 作者不在此列（由 COS 目录语义决定）</p>
            <AuthorSuggestField value={pickValue} onChange={onPickAuthor} />
            {authors.length === 0 ? (
              <p className="grid-empty">暂无关联作者——联想选择添加，或保存空集解除全部常规作者</p>
            ) : (
              <div>
                {authors.map((a) => (
                  <div key={a.id} style={{ marginTop: 12 }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                      <b>{a.displayName}</b>
                      <Pill onClick={() => removeAuthor(a.id)} title="从本资产移除该作者">
                        移除
                      </Pill>
                    </div>
                    <AuthorSourcesRow
                      authorId={a.id}
                      draft={sourceDrafts[a.id]}
                      onChange={onSourceChange}
                    />
                  </div>
                ))}
              </div>
            )}
            <div className="settings-actions" style={{ marginTop: 12 }}>
              <button className="save-btn" type="button" disabled={saving} onClick={() => void doSave()}>
                {saving ? '保存中…' : '保存'}
              </button>
            </div>
          </div>
        </>
      )}
    </div>
  )
}
