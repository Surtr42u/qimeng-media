import { useState } from 'react'
import { Dialog } from 'radix-ui'
import { toast } from 'sonner'
import type { AssetDetailTag, Tag } from '@/api/generated'
import { Pill } from '@/components/ui/pill'
import { useReplaceAssetTags } from '@/hooks/use-assets'
import { useCreateTag, useTags } from '@/hooks/use-tags'
import { provenanceNote } from '@/lib/provenance'

/**
 * 详情页标签行（B站式互动行下方）：当前标签胶囊展示 + 「管理」弹窗。
 * 管理弹窗 = 全量标签池点选勾选 + 新建（新建成功自动入勾选），保存一次整体
 * 替换提交（PUT tags 是唯一标签端点，保留项也要一并带上——DOMAIN_RULES §7）。
 * 弹窗按 open 条件挂载：勾选态 useState 惰性初始化即"打开时以详情当前标签
 * 复位"，不需要 effect（React「你可能不需要 Effect」模式，规避 set-state-in-effect）。
 * 胶囊内缀溯源注记（ADR-0032 详情面）：词表映射与不可考判定在逻辑层
 * provenanceNote，可考才渲染小字（UI 组件零业务规则，ADR-0008）。
 */
export function AssetTagRow({ assetId, tags }: { assetId: string; tags: AssetDetailTag[] }) {
  const [open, setOpen] = useState(false)

  return (
    <div className="detail-tags">
      {tags.map((t) => {
        const note = provenanceNote(t)
        return (
          <span className="pill" key={t.id ?? t.name} title={note ?? undefined}>
            {t.name}
            {note ? <span className="prov-note">{note}</span> : null}
          </span>
        )
      })}
      <button className="pill detail-tag-manage" onClick={() => setOpen(true)}>
        {tags.length > 0 ? '管理' : '+ 添加标签'}
      </button>

      {open ? (
        <TagDialog assetId={assetId} tags={tags} onClose={() => setOpen(false)} />
      ) : null}
    </div>
  )
}

/** 标签管理弹窗（radix Dialog 统一包封装，样式段 .detail-dialog-* 全走 token） */
function TagDialog({ assetId, tags, onClose }: { assetId: string; tags: Tag[]; onClose: () => void }) {
  const { data: pool } = useTags()
  const createTag = useCreateTag()
  const replaceTags = useReplaceAssetTags()
  const [selected, setSelected] = useState<string[]>(() =>
    tags.map((t) => t.id).filter((v): v is string => !!v),
  )
  const [draft, setDraft] = useState('')

  const toggle = (id: string) =>
    setSelected((prev) => (prev.includes(id) ? prev.filter((v) => v !== id) : [...prev, id]))

  const create = () => {
    const name = draft.trim()
    if (!name) return
    createTag.mutate(name, {
      onSuccess: (tag) => {
        // 守卫内先取常量：TS 收窄不进 setState 闭包，避免闭包内非空断言
        const id = tag?.id
        if (id) setSelected((prev) => (prev.includes(id) ? prev : [...prev, id]))
        setDraft('')
      },
      onError: (err) =>
        toast.error(`新建标签失败：${err instanceof Error ? err.message : String(err)}`),
    })
  }

  const save = () => {
    replaceTags.mutate(
      { assetId, tagIds: selected },
      {
        onSuccess: () => {
          toast.success('标签已保存')
          onClose()
        },
        onError: (err) =>
          toast.error(`标签保存失败：${err instanceof Error ? err.message : String(err)}`),
      },
    )
  }

  return (
    <Dialog.Root open onOpenChange={(next) => !next && onClose()}>
      <Dialog.Portal>
        <Dialog.Overlay className="detail-dialog-overlay" />
        <Dialog.Content className="detail-dialog">
          <Dialog.Title className="detail-dialog-title">标签管理</Dialog.Title>
          <Dialog.Description className="detail-dialog-desc">
            点选标签挂到本文件，保存时整体替换当前标签。
          </Dialog.Description>
          <div className="detail-dialog-body">
            {(pool ?? []).map((t) => (
              <Pill
                key={t.id ?? t.name}
                active={Boolean(t.id && selected.includes(t.id))}
                onClick={() => t.id && toggle(t.id)}
              >
                {t.name}
              </Pill>
            ))}
            {(pool ?? []).length === 0 ? <p className="grid-empty">暂无标签</p> : null}
          </div>
          <div className="tag-newrow">
            <input
              className="tag-input"
              value={draft}
              placeholder="新建标签，回车确认"
              onChange={(e) => setDraft(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') create()
              }}
            />
            <button className="confirm-btn confirm-btn--cancel" onClick={create}>
              新建
            </button>
          </div>
          <div className="detail-dialog-foot">
            <button className="confirm-btn confirm-btn--cancel" onClick={onClose}>
              取消
            </button>
            <button className="confirm-btn confirm-btn--primary" onClick={save} disabled={replaceTags.isPending}>
              保存
            </button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
