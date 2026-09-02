import { useState, type FormEvent } from 'react'
import { toast } from 'sonner'
import { LOCALE_ZH } from '@/lib/constants'
import {
  useDeleteLibrary, useLibraries, useRegisterLibrary, useScanLibrary, useSetLibraryEnabled,
} from '@/hooks/use-libraries'
import type { DirTree, Library } from '@/api/generated'
import { useQuery } from '@tanstack/react-query'
import { getApiV1Dirs } from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'

/**
 * 文件管理页（维护页「文件管理」入口卡 → /app/maintenance/files）。
 * 「管理文件库」的实际功能页：库列表（重扫/删除）+ 注册新库（= 旧 App
 * 添加媒体文件夹）+ 目录浏览。样式复用原型类（log-table/settings-card/pill）。
 * 目录浏览依赖生成 SDK getApiV1Dirs（libraryId 必填，协议 /api/v1/dirs）。
 */

/** 目录树递归节点（库根为顶，子目录缩进；fileCount 为该目录直接文件数） */
function DirNode({ node, depth }: { node: DirTree; depth: number }) {
  const seg = (node.path ?? '').split(/[\\/]/).filter(Boolean)
  return (
    <li>
      <span className="rank-name" style={{ paddingLeft: depth * 14 }}>
        {seg[seg.length - 1] || node.path}{' '}
        <b style={{ color: 'var(--text-sub)', fontWeight: 400 }}>{node.fileCount ?? 0} 文件</b>
      </span>
      {(node.dirs ?? []).length > 0 && (
        <ul style={{ listStyle: 'none' }}>
          {(node.dirs ?? []).map((child) => (
            <DirNode key={child.path} node={child} depth={depth + 1} />
          ))}
        </ul>
      )}
    </li>
  )
}

/** 目录浏览卡（选库 → 拉取该库目录树） */
function DirBrowser({ libraries }: { libraries: Library[] }) {
  const [libId, setLibId] = useState('')
  const enabled = libId !== ''
  // /dirs 的 libraryId 协议必填：未选库禁用查询（与旧 useDirTree 同纪律）
  const { data: tree, isFetching } = useQuery({
    queryKey: ['api/v1/dirs', libId],
    queryFn: () => unwrapSdkResult(getApiV1Dirs({ query: { libraryId: libId } })),
    enabled,
  })

  return (
    <div className="rank-card">
      <div className="rank-head">
        <h3>目录浏览</h3>
        <span className="f-years">
          <select value={libId} onChange={(e) => setLibId(e.target.value)} aria-label="选择库">
            <option value="">选择库…</option>
            {libraries.map((l) => (
              <option key={l.id} value={l.id}>{l.name}</option>
            ))}
          </select>
        </span>
      </div>
      <p className="rank-note">按目录查看各库的文件分布（磁盘现实，含空目录）</p>
      {!enabled ? (
        <p className="grid-empty">先在上方选择一个库。</p>
      ) : isFetching ? (
        <p className="grid-empty">加载中…</p>
      ) : tree ? (
        <ul>
          <DirNode node={tree} depth={0} />
        </ul>
      ) : (
        <p className="grid-empty">该库暂无目录。</p>
      )}
    </div>
  )
}

export default function LibraryManagePage() {
  const { data: libraries = [], isLoading } = useLibraries()
  const registerLib = useRegisterLibrary()
  const scanLib = useScanLibrary()
  const deleteLib = useDeleteLibrary()
  const setEnabled = useSetLibraryEnabled()

  // 注册表单受控态
  const [name, setName] = useState('')
  const [rootPath, setRootPath] = useState('')
  const [kind, setKind] = useState<'normal' | 'cos'>('normal')

  const submitRegister = (e: FormEvent) => {
    e.preventDefault()
    if (!name.trim() || !rootPath.trim()) {
      toast.error('名称与路径均必填')
      return
    }
    registerLib.mutate(
      { name: name.trim(), rootPath: rootPath.trim(), kind },
      {
        onSuccess: (lib) => {
          // 注册不自动扫描（POST /libraries 语义），这里补一发扫描让"注册并扫描"名副其实
          if (lib.id) scanLib.mutate(lib.id)
          toast.success(`已注册「${name.trim()}」，开始扫描`)
          setName('')
          setRootPath('')
          setKind('normal')
        },
        onError: (err) => toast.error(`注册失败：${err instanceof Error ? err.message : String(err)}`),
      },
    )
  }

  const rescan = (lib: Library): void => {
    scanLib.mutate(lib.id ?? '', {
      onSuccess: () => toast.success(`「${lib.name}」扫描已触发`),
      onError: (err) => toast.error(`触发失败：${err instanceof Error ? err.message : String(err)}`),
    })
  }

  const remove = (lib: Library): void => {
    // 删除库为低频破坏性管理操作：confirm 二次确认（原型弹窗组件阶段 B 补）
    if (!window.confirm(`删除库「${lib.name}」？\n该库 ${lib.fileCount ?? 0} 个文件的索引将被清除，磁盘文件不受影响。`)) return
    deleteLib.mutate(lib.id ?? '', {
      onSuccess: () => toast.success(`「${lib.name}」已删除`),
      onError: (err) => toast.error(`删除失败：${err instanceof Error ? err.message : String(err)}`),
    })
  }

  const scanStateText: Record<string, string> = { idle: '空闲', scanning: '扫描中', error: '异常' }

  return (
    <div className="page" id="page-maintenance-files">
      <div className="page-head">
        <h2>文件管理</h2>
        <p>管理文件库：注册/删除媒体目录、触发扫描、浏览目录结构</p>
      </div>

      <div className="rank-card">
        <div className="rank-head">
          <h3>媒体库</h3>
          <span className="rank-note">{libraries.length} 个库</span>
        </div>
        {isLoading ? (
          <p className="grid-empty">加载中…</p>
        ) : libraries.length === 0 ? (
          <p className="grid-empty">还没有库，先在下方注册一个媒体文件夹。</p>
        ) : (
          <table className="log-table">
            <thead>
              <tr><th>名称</th><th>类型</th><th>路径</th><th>文件</th><th>状态</th><th>启用</th><th>操作</th></tr>
            </thead>
            <tbody>
              {libraries.map((lib) => (
                <tr key={lib.id}>
                  <td>{lib.name}</td>
                  <td>{lib.kind === 'cos' ? 'COS' : '常规'}</td>
                  <td style={{ maxWidth: 260, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={lib.rootPath}>{lib.rootPath}</td>
                  <td>{(lib.fileCount ?? 0).toLocaleString(LOCALE_ZH)}</td>
                  <td>{scanStateText[lib.scanState ?? 'idle'] ?? lib.scanState}</td>
                  <td>
                    {/* 用户拍板：关闭=只隐藏浏览面，记录全保留（migration 0007） */}
                    <label className="settings-switch" title={lib.enabled === false ? '已停用（点击启用）' : '已启用（点击停用）'}>
                      <input
                        type="checkbox"
                        checked={lib.enabled !== false}
                        onChange={() =>
                          setEnabled.mutate(
                            { libraryId: lib.id ?? '', enabled: lib.enabled === false },
                            {
                              onSuccess: () =>
                                toast.success(lib.enabled === false ? `「${lib.name}」已启用` : `「${lib.name}」已停用（浏览面隐藏，记录保留）`),
                              onError: (err) => toast.error(`操作失败：${err instanceof Error ? err.message : String(err)}`),
                            },
                          )
                        }
                      />
                      <i aria-hidden="true" />
                    </label>
                  </td>
                  <td>
                    <button className="pill" type="button" onClick={() => rescan(lib)}>重新扫描</button>
                    <button className="pill" type="button" onClick={() => remove(lib)}>删除</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <form className="settings-card" onSubmit={submitRegister}>
        <h3>注册新库</h3>
        <p>把一个磁盘文件夹登记为媒体库（与旧 App「添加媒体文件夹」同义），注册后自动扫描入库</p>
        <div className="settings-grid">
          <label className="settings-field">
            <span>库名称</span>
            <input value={name} onChange={(e) => setName(e.target.value)} placeholder="例如：1 图集" />
          </label>
          <label className="settings-field">
            <span>根路径（服务端可访问的绝对路径）</span>
            <input value={rootPath} onChange={(e) => setRootPath(e.target.value)} placeholder="C:\Users\你\Desktop\某目录" />
            <small>不能位于服务端数据目录内；COS 库按「作者/作品/文件」目录结构组织</small>
          </label>
          <label className="settings-field">
            <span>库类型</span>
            <select value={kind} onChange={(e) => setKind(e.target.value === 'cos' ? 'cos' : 'normal')}>
              <option value="normal">常规</option>
              <option value="cos">COS 作者库</option>
            </select>
          </label>
        </div>
        <div className="settings-actions" style={{ marginTop: 12 }}>
          <button className="save-btn" type="submit" disabled={registerLib.isPending}>
            {registerLib.isPending ? '注册中…' : '注册并扫描'}
          </button>
        </div>
      </form>

      <DirBrowser libraries={libraries} />
    </div>
  )
}
