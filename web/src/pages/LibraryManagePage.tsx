import { useRef, useState, type ChangeEvent, type FormEvent } from 'react'
import { toast } from 'sonner'
import { LOCALE_ZH } from '@/lib/constants'
import {
  useDeleteLibrary, useDirTree, useLibraries, useRegisterLibrary, useScanLibrary, useSetLibraryEnabled,
} from '@/hooks/use-libraries'
import {
  useDeleteImportedTxt, useImportAuthorTxt, useRebuildAuthorTxt, useTxtImportedFiles,
} from '@/hooks/use-authors'
import type { DirTree, Library } from '@/api/generated'

/**
 * 文件管理页（维护页「文件管理」入口卡 → /app/maintenance/files）。
 * 「管理文件库」的实际功能页：库列表（重扫/删除）+ 注册新库（= 旧 App
 * 添加媒体文件夹）+ 目录浏览 + 作者 TXT 导入卡（= 旧版数据管理「TXT导入
 * 作者」：列表 + 选择 txt 导入 + 删除片段重建关联）。样式复用原型类
 * （log-table/settings-card/pill/rank-card）。
 * 目录浏览走 useDirTree（协议 /api/v1/dirs，libraryId 必填）。
 */

/**
 * 作者 TXT 导入卡（旧版数据管理「TXT导入作者」的 web 对等物）：
 * 选择 .txt → POST /authors/import-txt（三格式自动识别 + 从全部已导入
 * 片段统一重建，DOMAIN_RULES §6）；列表 = 已导入片段名（同名覆盖）；
 * 删除某片段 → 从剩余片段重建作者与文件关联（作者行保留）；
 * 重新匹配 → POST /authors/import-txt/rebuild（幂等重放已存片段重建关联，
 * 库重建/关联丢失后的修复入口）。
 */
function TxtAuthorImportCard() {
  const { data: files = [], isLoading } = useTxtImportedFiles()
  const importTxt = useImportAuthorTxt()
  const deleteTxt = useDeleteImportedTxt()
  const rebuildTxt = useRebuildAuthorTxt()
  const inputRef = useRef<HTMLInputElement>(null)

  const onPick = (e: ChangeEvent<HTMLInputElement>): void => {
    const f = e.target.files?.[0]
    e.target.value = '' // 置空以允许连续导入同名文件
    if (!f) return
    if (!/\.txt$/i.test(f.name)) {
      toast.error('仅支持 .txt 作者清单文件')
      return
    }
    const reader = new FileReader()
    reader.onload = () => {
      importTxt.mutate(
        { filename: f.name, content: String(reader.result ?? '') },
        {
          onSuccess: (res) =>
            toast.success(`「${f.name}」导入完成：作者 ${res.authorsImported ?? 0}，匹配文件 ${res.filesMatched ?? 0}`),
          onError: (err) => toast.error(`导入失败：${err instanceof Error ? err.message : String(err)}`),
        },
      )
    }
    reader.onerror = () => toast.error('读取文件失败，请重试')
    reader.readAsText(f)
  }

  const onDelete = (name: string): void => {
    deleteTxt.mutate(name, {
      onSuccess: () => toast.success(`已移除「${name}」，并从剩余片段重建作者关联`),
      onError: (err) => toast.error(`移除失败：${err instanceof Error ? err.message : String(err)}`),
    })
  }

  const onRebuild = (): void => {
    rebuildTxt.mutate(undefined, {
      onSuccess: (res) =>
        toast.success(`重放完成：${res.authorsImported ?? 0} 位作者 · 关联 ${res.filesMatched ?? 0} 个文件`),
      onError: (err) => toast.error(`重放失败：${err instanceof Error ? err.message : String(err)}`),
    })
  }

  return (
    <div className="rank-card">
      <div className="rank-head">
        <h3>作者 TXT 导入</h3>
        <span className="rank-note">{files.length} 份片段</span>
      </div>
      <p className="rank-note">
        把旧项目导出的作者清单 .txt 交还关联重建（格式 A/B/C 自动识别）：同名文件重复导入为覆盖；
        删除某份后按「剩余片段全部」重算作者与文件关联，作者行保留。
      </p>
      <div style={{ margin: '10px 0 12px' }}>
        <button
          className="save-btn"
          type="button"
          disabled={importTxt.isPending}
          onClick={() => inputRef.current?.click()}
        >
          {importTxt.isPending ? '导入中…' : '选择 TXT 导入'}
        </button>
        <button
          className="pill"
          type="button"
          style={{ marginLeft: 8 }}
          disabled={rebuildTxt.isPending || files.length === 0}
          title="按已导入片段全量重放作者-文件关联（片段不变，幂等）"
          onClick={onRebuild}
        >
          {rebuildTxt.isPending ? '重放中…' : '重新匹配'}
        </button>
        <input
          ref={inputRef}
          type="file"
          accept=".txt,text/plain"
          hidden
          onChange={onPick}
          aria-label="选择作者清单 TXT"
        />
      </div>
      {isLoading ? (
        <p className="grid-empty">加载中…</p>
      ) : files.length === 0 ? (
        <p className="grid-empty">还没有导入片段——选一个 .txt 开始。</p>
      ) : (
        <table className="log-table">
          <thead>
            <tr><th>片段文件</th><th style={{ width: 120 }}>操作</th></tr>
          </thead>
          <tbody>
            {files.map((f) => (
              <tr key={f.filename}>
                <td>{f.filename === '' ? '（匿名导入）' : f.filename}</td>
                <td>
                  <button
                    className="pill"
                    type="button"
                    disabled={deleteTxt.isPending}
                    onClick={() => onDelete(f.filename)}
                  >
                    移除
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}

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
  // /dirs 的 libraryId 协议必填：未选库 enabled=false 挂起查询（挂起语义见 useDirTree）
  const { data: tree, isFetching } = useDirTree(libId, enabled)

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
        <p>管理文件库（注册/重扫/删除/目录浏览）与作者 TXT 导入（旧项目数据管理）</p>
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
      <TxtAuthorImportCard />
    </div>
  )
}
