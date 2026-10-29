import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router'
import { toast } from 'sonner'
import type { Author } from '@/api/generated'
import { SearchIcon } from '@/components/shell/icons'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { ManagePageHeader } from '@/components/manage/ManagePageHeader'
import { AuthorEditDialog } from '@/components/manage/AuthorEditDialog'
import { LoadingHint } from '@/components/ui/loading-hint'
import {
  useAuthors,
  useCreateAuthor,
  useDeleteAuthor,
  useRenameAuthor,
  useToggleFollow,
} from '@/hooks/use-authors'
import { authorDisplayName } from '@/lib/format'
import { batchFailureReason } from '@/lib/batch'
import { collectionPath, COLLECTION_AUTHOR } from '@/lib/route-keys'

type AuthorZone = '全部' | '常规' | 'cos'
type AuthorSort = 'default' | 'browse' | 'works'

/** 体系胶囊：data-zone 值与作者 type 字段对应（cos 小写），展示文案 COS 大写 */
const ZONES: { zone: AuthorZone; label: string }[] = [
  { zone: '全部', label: '全部' },
  { zone: '常规', label: '常规' },
  { zone: 'cos', label: 'COS' },
]

const SORTS: { key: AuthorSort; label: string }[] = [
  { key: 'default', label: '默认' },
  { key: 'browse', label: '经常浏览' },
  { key: 'works', label: '文件数量' },
]

/** 体系胶囊 → 协议 Author.type（「全部」不筛） */
const ZONE_TO_TYPE: Record<AuthorZone, Author['type'] | undefined> = {
  全部: undefined,
  常规: 'regular',
  cos: 'cos',
}

// 照 app.js A_SORTERS：默认=API 原序、浏览数降序、文件数降序
const A_SORTERS: Record<AuthorSort, (a: Author, b: Author) => number> = {
  default: () => 0,
  browse: (a, b) => (b.viewCount ?? 0) - (a.viewCount ?? 0),
  works: (a, b) => (b.fileCount ?? 0) - (a.fileCount ?? 0),
}


/** 对话框状态：新建 | 改名（预填当前显示名）；null=关闭（按条件挂载，打开即复位） */
type AuthorPromptState = { mode: 'create' } | { mode: 'rename'; author: Author }

/** 管理操作失败文案：统一走 batchFailureReason（Error 实例取 message；生成 client 错误体
 * 形状对象取 message/status/code——unwrapSdkResult 在异常路径会抛非 Error 形状，不能 String()） */
function manageFailureText(err: unknown): string {
  return batchFailureReason(err)
}

/**
 * 作者管理页（原型 #page-authors 移植，阶段 B 已接真实数据）：体系胶囊 + 名字搜索 +
 * 排序三项 + 关注按钮（PUT /authors/{authorId}/follow，状态以服务端 followed 为准）。
 * 列表行可点（2026-09-05 补同族缺口）：进作者集合子页 /app/collection/author/{displayName}
 * （原始 displayName 不带「 ·COS」展示后缀，URL 编码）；可点视觉复用现有类
 * （.rank-card li 自带 cursor:pointer + 悬停底色，不发明新样式）。
 *
 * 作者管理族（2026-10-13，ADR-0024 修订 5）：新建（POST /authors，规范身份命中
 * 已有常规作者则复用）/ 改名（PUT，身份随之迁移）/ 删除（DELETE，TXT 块 + 关联
 * + 行三清、文件保留）——行内 pill 按钮与库管理页操作列同视觉；删除走二次确认
 * （ConfirmDialog danger），改名/新建走共享单输入框（PromptDialog）。4xx 原因
 * 直透 toast（用户可自助修正重试）。
 */
export default function AuthorsPage() {
  const navigate = useNavigate()
  const { data: authors = [], isLoading, isError, refetch } = useAuthors()
  const toggleFollow = useToggleFollow()
  const createAuthor = useCreateAuthor()
  const renameAuthor = useRenameAuthor()
  const deleteAuthor = useDeleteAuthor()
  const [zone, setZone] = useState<AuthorZone>('全部')
  const [keyword, setKeyword] = useState('')
  const [sort, setSort] = useState<AuthorSort>('default')
  const [prompt, setPrompt] = useState<AuthorPromptState | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<Author | null>(null)

  // 先 filter 后 sort，slice 避免原地排序污染 filter 结果顺序基准（照 app.js renderAuthors）；
  // 关键词大小写不敏感（2026-10-12 用户要求，App applyAuthorRows 同口径）
  const rows = useMemo(() => {
    const kw = keyword.trim().toLowerCase()
    const type = ZONE_TO_TYPE[zone]
    return authors
      .filter(
        (a) =>
          (type === undefined || a.type === type) &&
          (!kw || (a.displayName ?? '').toLowerCase().includes(kw)),
      )
      .slice()
      .sort(A_SORTERS[sort])
  }, [authors, zone, keyword, sort])

  /** 改名/新建提交（无先行 trim：服务端归一；空白已被对话框确认钮拦下） */
  const submitPrompt = (text: string): void => {
    if (prompt === null) return
    if (prompt.mode === 'create') {
      createAuthor.mutate(text, {
        onSuccess: (author) => { setPrompt(null); toast.success(`已添加作者：${author.displayName ?? text}`) },
        onError: (err) => toast.error(`添加失败：${manageFailureText(err)}`),
      })
    } else if (prompt.author.id) {
      renameAuthor.mutate(
        { authorId: prompt.author.id, displayName: text },
        {
          onSuccess: (author) => { setPrompt(null); toast.success(`已改名：${author.displayName ?? text}`) },
          onError: (err) => toast.error(`改名失败：${manageFailureText(err)}`),
        },
      )
    }
  }

  /** 删除确认（ConfirmDialog danger；领域口径：关联文件保留、仅解除关联） */
  const confirmDelete = (): void => {
    if (deleteTarget?.id) {
      deleteAuthor.mutate(deleteTarget.id, {
        onSuccess: () => { setDeleteTarget(null); toast.success('已删除作者，关联文件已保留') },
        onError: (err) => toast.error(`删除失败：${manageFailureText(err)}`),
      })
    }
  }

  return (
    <div className="page" id="page-authors">
      <ManagePageHeader title="作者管理" description={`全部作者 · ${authors.length} 位`} />
      <p className="a-guide">管理作者名称、别名与来源记录；COS 作者随媒体目录自动维护。</p>
      <div className="a-toolbar">
        <div className="a-zones">
          {ZONES.map((z) => (
            <button
              key={z.zone}
              type="button"
              className={`stype${zone === z.zone ? ' active' : ''}`}
              data-zone={z.zone}
              onClick={() => setZone(z.zone)}
            >
              {z.label}
            </button>
          ))}
        </div>
        <div className="hist-search">
          <input
            type="text"
            placeholder="搜索作者"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
          />
          <SearchIcon />
        </div>
      </div>
      <div className="a-sortrow">
        {SORTS.map((s) => (
          <button
            key={s.key}
            type="button"
            className={`sort-pill${sort === s.key ? ' active' : ''}`}
            data-sort={s.key}
            onClick={() => setSort(s.key)}
          >
            {s.label}
          </button>
        ))}
        <button
          type="button"
          className="pill"
          style={{ marginLeft: 'auto' }}
          onClick={() => setPrompt({ mode: 'create' })}
        >
          新建作者
        </button>
      </div>
      <div className="rank-card a-list">
        {isError ? (<p className="a-empty" role="alert">作者列表读取失败 <button className="pill" onClick={() => void refetch()}>重试</button></p>) : isLoading ? (
          <LoadingHint className="a-empty" />
        ) : rows.length ? (
          <ul>
            {rows.map((a) => (
              <li key={a.id}>
                <button type="button" className="author-identity" onClick={() => a.displayName && navigate(collectionPath(COLLECTION_AUTHOR, a.displayName))}>
                  <span className="author-avatar" aria-hidden="true">{Array.from(a.displayName ?? '?')[0]}</span>
                  <span className="author-info"><strong>{authorDisplayName(a)}</strong><small>{a.fileCount ?? 0} 个文件 · {a.type === 'cos' ? '目录管理' : '常规作者'}</small></span>
                </button>
                <button type="button" className={`follow-btn${a.followed ? '' : ' follow-btn--idle'}`} disabled={toggleFollow.isPending}
                  onClick={() => a.id && toggleFollow.mutate({ authorId: a.id, follow: !a.followed }, { onError: err => toast.error(manageFailureText(err)) })}>
                  {a.followed ? '已关注' : '关注'}
                </button>
                {a.type !== 'cos' && <>
                  <button type="button" className="pill" onClick={() => setPrompt({ mode: 'rename', author: a })}>编辑资料</button>
                  <button type="button" className="pill" onClick={() => setDeleteTarget(a)}>删除</button>
                </>}
              </li>
            ))}
          </ul>
        ) : (
          <p className="a-empty">没有匹配的作者</p>
        )}
      </div>

      {/* 新建/改名：共享单输入框（词表维护页同源组件）；同名复用由服务端语义决定、
          toast 以返回作者显示名如实反馈 */}
      {prompt !== null ? (
        <AuthorEditDialog
          author={prompt.mode === 'rename' ? prompt.author : undefined}
          busy={createAuthor.isPending || renameAuthor.isPending}
          onConfirm={submitPrompt}
          onDismiss={() => setPrompt(null)}
        />
      ) : null}

      {/* 删除二次确认（danger 深色强调；明示「关联文件保留、仅解除关联」领域口径） */}
      <ConfirmDialog
        open={deleteTarget !== null}
        title={`删除作者「${deleteTarget ? authorDisplayName(deleteTarget) : ''}」？`}
        description={`关联的 ${deleteTarget?.fileCount ?? 0} 个文件会保留、仅解除关联；作者总表里的对应条目一并移除。此操作不可撤销。`}
        confirmText="删除"
        cancelText="取消"
        danger
        busy={deleteAuthor.isPending}
        keepOpenOnConfirm
        onConfirm={confirmDelete}
        onOpenChange={(open) => {
          if (!open) setDeleteTarget(null)
        }}
      />
    </div>
  )
}
