import { useEffect, useMemo, useRef, useState } from 'react'
import { useBlocker, useNavigate, useParams } from 'react-router'
import { toast } from 'sonner'
import type { AssetDetail } from '@/api/generated'
import { AuthorSuggestField, type AuthorAttachValue } from '@/components/manage/AuthorSuggestField'
import { SourceSelectField } from '@/components/manage/SourceSelectField'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
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
import {
  addPendingAuthorName,
  authorDraftsFromDetail,
  pendingAuthorRejectionText,
  removePendingAuthorName,
  type AuthorDraft,
} from '@/lib/asset-authors'
import { batchFailureReason } from '@/lib/batch'
import { assetDetail } from '@/lib/route-keys'

/**
 * 资产编辑页（/app/asset/:assetId/edit，详情页「编辑」按钮进入；AppShell 平铺
 * 子路由，不在叠加组内——返回详情走 assetDetail 重开叠加，无底衬上下文）。
 *
 * 编辑面（2026-09-25 协议批，ADR-0024）：
 * - 作者关联 = PUT /assets/{assetId}/authors 整体替换（body=常规作者 ID 全集
 *   ∪ 待新建名 newAuthorNames；COS 作者关联来自 COS 目录语义、协议拒绝 cos
 *   作者 ID，编辑面直接不收）；
 * - 2026-10-11 修订「编辑页支持新建作者」：联想未命中的输入名进「待新建」清单
 *   （去重/长度/项数拦截走 lib/asset-authors，单测锁定），随保存交服务端按规范
 *   身份复用或新建——不再要求先经 TXT 导入；保存成功后以响应里的作者全集刷新
 *   草稿（新作者成为正常条目，可继续编辑来源区）；
 * - 每作者来源区 = PUT /authors/{authorId}/sources 整体替换，只对有改动的
 *   作者逐个提交；服务端值经 useAuthorSourcesById 回显（草稿态）。
 * 上传三参数挂靠已退役：作者/来源的指定与调整全部收敛到本页。
 * 所有错误文案经 batchFailureReason 提取（unwrapSdkResult 抛 {code,message}
 * 错误体而非 Error 实例，instanceof Error 模式会落 "[object Object]"）。
 */

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
  disabled,
}: {
  authorId: string
  draft: string[] | undefined
  onChange: (authorId: string, next: string[]) => void
  disabled: boolean
}) {
  const { data, isError, isFetching, refetch } = useAuthorSourcesById(authorId)
  // 来源端点是整体替换语义：尚未读到服务端原值前不能把空数组当基线开放编辑，
  // 否则用户只点一个新词保存就可能覆盖此前已有的来源区。
  if (data === undefined || isFetching || isError) {
    return (
      <div
        className="rank-note"
        role={isError ? 'alert' : 'status'}
        style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8, marginTop: 8 }}
      >
        <span>
          {isError
            ? '无法读取该作者的既有来源，已暂时禁用编辑以免覆盖旧数据。'
            : '正在读取该作者的既有来源…'}
        </span>
        {isError ? (
          <button type="button" className="pill" disabled={disabled} onClick={() => void refetch()}>
            重试
          </button>
        ) : null}
      </div>
    )
  }
  const selected = draft ?? data.sources ?? []
  return <SourceSelectField selected={selected} disabled={disabled} onChange={(next) => onChange(authorId, next)} />
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
  const serverAuthors = useMemo(() => authorDraftsFromDetail(detail?.authors), [detail])

  // 草稿作者全集（null=未动，跟随服务端值）；保存提交的是最终 ID 全集
  const [draftAuthors, setDraftAuthors] = useState<AuthorDraft[] | null>(null)
  // 单独记账：关联 PUT 成功后即使来源保存失败，重试也不应重复提交已成功的关联事务。
  const [authorLinksNeedSave, setAuthorLinksNeedSave] = useState(false)
  const authors = draftAuthors ?? serverAuthors
  // 待新建作者名（保存随 newAuthorNames 交服务端建/复用；成功清空、失败保留可重试）
  const [pendingAuthors, setPendingAuthors] = useState<string[]>([])
  // 来源草稿与脏标记（authorId → 词表/是否改过）：保存只对脏作者发 PUT
  const [sourceDrafts, setSourceDrafts] = useState<Record<string, string[]>>({})
  const [dirtySources, setDirtySources] = useState<Record<string, true>>({})
  // 联想字段受控值：加进草稿即复位为自由输入态，便于连续添加
  const [pickValue, setPickValue] = useState<AuthorAttachValue | null>(null)
  const [saving, setSaving] = useState(false)

  // 编辑页含作者全集、待新建名单与逐作者来源草稿；任一有改动时离开都需确认。
  const hasUnsavedChanges =
    authorLinksNeedSave || pendingAuthors.length > 0 || Object.keys(dirtySources).length > 0
  // 正常保存成功后由页面主动导航，绕过放弃确认；用户手动导航仍走 blocker。
  const allowExitAfterSave = useRef(false)
  const blocker = useBlocker(
    ({ currentLocation, nextLocation }) =>
      !allowExitAfterSave.current &&
      hasUnsavedChanges &&
      currentLocation.pathname !== nextLocation.pathname,
  )
  // 异步保存期间可能又发生一次路由拦截；成功回调读取最新 blocker，而不是保存启动时的旧快照。
  const blockerRef = useRef(blocker)
  blockerRef.current = blocker

  useEffect(() => {
    if (!hasUnsavedChanges) return
    const handler = (event: BeforeUnloadEvent): void => {
      if (allowExitAfterSave.current) return
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', handler)
    return () => window.removeEventListener('beforeunload', handler)
  }, [hasUnsavedChanges])

  const replaceAuthors = useReplaceAssetAuthors()
  const saveSources = useSaveAuthorSources()

  /** 联想字段回传：authorId=已有作者（进草稿，重复不加）；authorName=待新建
   *  （进待新建清单——重名/超长/超项按 lib 口径出提示不入列）；null=字段清空
   *  动作，不动草稿。 */
  const onPickAuthor = (v: AuthorAttachValue | null): void => {
    if (saving) return
    setPickValue(null)
    if (!v) return
    if (v.authorId) {
      if (authors.some((a) => a.id === v.authorId)) {
        toast.info(`「${v.displayName ?? v.authorId}」已在列表中`)
        return
      }
      setDraftAuthors([...authors, { id: v.authorId, displayName: v.displayName ?? v.authorId }])
      setAuthorLinksNeedSave(true)
      return
    }
    if (v.authorName) {
      const added = addPendingAuthorName(
        { attached: authors.map((a) => a.displayName), pending: pendingAuthors },
        v.authorName,
      )
      if (!added.ok) {
        toast.info(pendingAuthorRejectionText(added.reason, v.authorName))
        return
      }
      setPendingAuthors(added.names)
      setAuthorLinksNeedSave(true)
    }
  }

  /** 移出草稿并清掉该作者的来源草稿/脏标记（重加同作者不残留旧脏态） */
  const removeAuthor = (id: string): void => {
    if (saving) return
    setDraftAuthors(authors.filter((a) => a.id !== id))
    setAuthorLinksNeedSave(true)
    const nextDrafts = { ...sourceDrafts }
    delete nextDrafts[id]
    setSourceDrafts(nextDrafts)
    const nextDirty = { ...dirtySources }
    delete nextDirty[id]
    setDirtySources(nextDirty)
  }

  /** 来源草稿上浮 + 标脏（保存时只对脏作者发整体替换） */
  const onSourceChange = (authorId: string, next: string[]): void => {
    if (saving) return
    setSourceDrafts((prev) => ({ ...prev, [authorId]: next }))
    setDirtySources((prev) => ({ ...prev, [authorId]: true }))
  }

  /** 保存：先整体替换作者关联（含待新建名），再对脏作者逐个提交来源区。来源区
   *  单作者失败不阻断其余作者，最后汇总报错并留在本页（草稿保留，可直接重试）。 */
  const doSave = async (): Promise<void> => {
    if (!assetId || saving) return
    setSaving(true)
    try {
      let nextAuthors = authors
      // 只改来源时不重复 PUT 作者全集；只有关联草稿/待新建作者发生变更才写整体关联。
      if (authorLinksNeedSave || pendingAuthors.length > 0) {
        let saved: AssetDetail | null = null
        try {
          saved = await replaceAuthors.mutateAsync({
            assetId,
            authorIds: authors.map((a) => a.id),
            newAuthorNames: pendingAuthors,
          })
        } catch (err) {
          // 失败保留草稿与待新建清单，用户可直接重试（不落 toast 之外的副作用）
          toast.error(`作者关联保存失败：${batchFailureReason(err)}`)
          return
        }
        if (saved === null) return
        // 关联已落库：以响应作者全集刷新草稿（待新建名已成真作者，作为正常条目
        // 出现、可继续编辑来源），待新建清单清空
        nextAuthors = authorDraftsFromDetail(saved.authors)
        setDraftAuthors(nextAuthors)
        setPendingAuthors([])
        setAuthorLinksNeedSave(false)
      }
      const failures: string[] = []
      for (const a of nextAuthors) {
        if (!dirtySources[a.id]) continue
        try {
          await saveSources.mutateAsync({ authorId: a.id, sources: sourceDrafts[a.id] ?? [] })
          setDirtySources((prev) => {
            const next = { ...prev }
            delete next[a.id]
            return next
          })
        } catch (err) {
          failures.push(`${a.displayName}：${batchFailureReason(err)}`)
        }
      }
      if (failures.length > 0) {
        toast.error(`部分作者来源保存失败：${failures.join('；')}`)
        return
      }
      toast.success('资产作者已保存')
      allowExitAfterSave.current = true
      const latestBlocker = blockerRef.current
      if (latestBlocker.state === 'blocked') latestBlocker.reset()
      navigate(assetDetail(assetId))
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="page" id="page-asset-edit">
      <div className="settings-actions">
        <Pill disabled={saving} onClick={() => (assetId ? navigate(assetDetail(assetId)) : navigate(-1))}>
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
            <p>
              在下方输入框联想选择已有作者，或对没有的作者点「新建：xxx」直接创建；
              COS 作者不在此列（由 COS 目录语义决定）
            </p>
            <AuthorSuggestField value={pickValue} onChange={onPickAuthor} allowCreate disabled={saving} />
            {authors.length === 0 && pendingAuthors.length === 0 ? (
              <p className="grid-empty">暂无关联作者——联想选择添加，或保存空集解除全部常规作者</p>
            ) : (
              <div>
                {authors.map((a) => (
                  <div key={a.id} style={{ marginTop: 12 }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                      <b>{a.displayName}</b>
                      <Pill disabled={saving} onClick={() => removeAuthor(a.id)} title="从本资产移除该作者">
                        移除
                      </Pill>
                    </div>
                    <AuthorSourcesRow
                      authorId={a.id}
                      draft={sourceDrafts[a.id]}
                      disabled={saving}
                      onChange={onSourceChange}
                    />
                  </div>
                ))}
              </div>
            )}
            {pendingAuthors.length > 0 && (
              <div style={{ marginTop: 12 }}>
                <p className="rank-note">待新建作者（保存后自动创建并关联）</p>
                {pendingAuthors.map((name) => (
                  <div
                    key={name}
                    style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}
                  >
                    <b>{name}</b>
                    <Pill
                      disabled={saving}
                      onClick={() => {
                        setPendingAuthors(removePendingAuthorName(pendingAuthors, name))
                        setAuthorLinksNeedSave(true)
                      }}
                      title="取消新建该作者"
                    >
                      移除
                    </Pill>
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
      <ConfirmDialog
        open={blocker.state === 'blocked'}
        title="有未保存的修改"
        description={saving ? '正在保存，请稍候。为避免丢失部分已提交的修改，请等待保存结束。' : '离开将丢失本次作者与来源编辑，确定离开吗？'}
        confirmText={saving ? '保存中…' : '放弃修改离开'}
        cancelText="继续编辑"
        danger
        busy={saving}
        keepOpenOnConfirm
        onConfirm={() => blocker.proceed?.()}
        onOpenChange={(next) => {
          if (!next && !saving) blocker.reset?.()
        }}
      />
    </div>
  )
}
