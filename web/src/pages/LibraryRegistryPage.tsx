import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { toast } from 'sonner'
import { LOCALE_ZH } from '@/lib/constants'
import { Pill } from '@/components/ui/pill'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { Select, SelectContent, SelectItem, SelectTrigger } from '@/components/ui/select'
import { Switch } from '@/components/ui/switch'
import {
  useDeleteLibrary, useLibraries, useRegisterLibrary, useScanLibrary, useSetLibraryEnabled,
} from '@/hooks/use-libraries'
import type { Library } from '@/api/generated'
import { MAINTENANCE_FILES_PATH } from '@/lib/route-keys'

/**
 * 库管理子页（数据管理 hub「库管理」卡 → /app/maintenance/files/libraries）：
 * 内容 = 原 LibraryManagePage「媒体库」表格（重扫/启停/删除）+「注册新库」
 * 表单 + 删库二次确认弹窗原样搬移（2026-09-17 hub 拆分，逻辑/文案零改动）。
 */
export default function LibraryRegistryPage() {
  const navigate = useNavigate()
  const { data: libraries = [], isLoading } = useLibraries()
  const registerLib = useRegisterLibrary()
  const scanLib = useScanLibrary()
  const deleteLib = useDeleteLibrary()
  const setEnabled = useSetLibraryEnabled()

  // 注册表单受控态
  const [name, setName] = useState('')
  const [rootPath, setRootPath] = useState('')
  const [kind, setKind] = useState<'normal' | 'cos'>('normal')
  // W-2：删库二次确认弹窗待确认目标（非 danger——索引清除，磁盘文件不受影响）
  const [deleteTarget, setDeleteTarget] = useState<Library | null>(null)

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
    // 删除库为低频破坏性管理操作：W-2 ConfirmDialog 原型风格二次确认（索引清除、磁盘文件不受影响）
    setDeleteTarget(lib)
  }

  const confirmRemove = (): void => {
    const lib = deleteTarget
    setDeleteTarget(null)
    if (!lib) return
    deleteLib.mutate(lib.id ?? '', {
      onSuccess: () => toast.success(`「${lib.name}」已删除`),
      onError: (err) => toast.error(`删除失败：${err instanceof Error ? err.message : String(err)}`),
    })
  }

  const scanStateText: Record<string, string> = { idle: '空闲', scanning: '扫描中', error: '异常' }

  return (
    <div className="page" id="page-maintenance-files-libraries">
      <div className="settings-actions">
        <Pill onClick={() => navigate(MAINTENANCE_FILES_PATH)}>← 返回数据管理</Pill>
      </div>
      <div className="page-head">
        <h2>库管理</h2>
        <p>注册媒体目录，重扫、启停与删除媒体库</p>
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
                    {/* 用户拍板：关闭=只隐藏浏览面，记录全保留（migration 0007）。
                        radix Switch 接管行为（Space 切换/aria-checked/焦点环），
                        滑块视觉走 .settings-switch-track（复刻原隐藏 checkbox+<i>） */}
                    <label className="settings-switch" title={lib.enabled === false ? '已停用（点击启用）' : '已启用（点击停用）'}>
                      <Switch
                        checked={lib.enabled !== false}
                        onCheckedChange={() =>
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
            <Select value={kind} onValueChange={(v) => setKind(v === 'cos' ? 'cos' : 'normal')}>
              <SelectTrigger aria-label="库类型" />
              <SelectContent>
                <SelectItem value="normal">常规</SelectItem>
                <SelectItem value="cos">COS 作者库</SelectItem>
              </SelectContent>
            </Select>
          </label>
        </div>
        <div className="settings-actions" style={{ marginTop: 12 }}>
          <button className="save-btn" type="submit" disabled={registerLib.isPending}>
            {registerLib.isPending ? '注册中…' : '注册并扫描'}
          </button>
        </div>
      </form>

      <ConfirmDialog
        open={deleteTarget !== null}
        title={`删除库「${deleteTarget?.name ?? ''}」？`}
        description={`该库 ${deleteTarget?.fileCount ?? 0} 个文件的索引将被清除，磁盘文件不受影响。`}
        confirmText="删除"
        cancelText="取消"
        onConfirm={confirmRemove}
        onOpenChange={(next) => { if (!next) setDeleteTarget(null) }}
      />
    </div>
  )
}
