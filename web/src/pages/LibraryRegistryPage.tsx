import { useState, type FormEvent } from 'react'
import { FolderOpen, Plus, RefreshCw } from 'lucide-react'
import { toast } from 'sonner'
import type { Library } from '@/api/generated'
import { ManagePageHeader, ManageQueryError } from '@/components/manage/ManagePageHeader'
import { Pill } from '@/components/ui/pill'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { Select, SelectContent, SelectItem, SelectTrigger } from '@/components/ui/select'
import { Switch } from '@/components/ui/switch'
import { useDeleteLibrary, useLibraries, useRegisterAndScanLibrary, useScanLibrary, useSetLibraryEnabled } from '@/hooks/use-libraries'
import { batchFailureReason } from '@/lib/batch'
import { LOCALE_ZH } from '@/lib/constants'

/** 注册/扫描分别记账，破坏性变更明确边界；表单与确认框直到成功才清理。 */
export default function LibraryRegistryPage() {
  const query = useLibraries()
  const libraries = query.data ?? []
  const register = useRegisterAndScanLibrary()
  const scan = useScanLibrary()
  const remove = useDeleteLibrary()
  const toggle = useSetLibraryEnabled()
  const [name, setName] = useState('')
  const [rootPath, setRootPath] = useState('')
  const [kind, setKind] = useState<'normal' | 'cos'>('normal')
  const [deleteTarget, setDeleteTarget] = useState<Library | null>(null)
  const [disableTarget, setDisableTarget] = useState<Library | null>(null)
  const [scanRetry, setScanRetry] = useState<{ library: Library; reason: string } | null>(null)
  const pending = register.isPending || scan.isPending || remove.isPending || toggle.isPending
  const blocked = pending || query.isLoading || query.isError

  const submitRegister = (event: FormEvent) => {
    event.preventDefault()
    if (blocked) return
    const submitted = { name: name.trim(), rootPath: rootPath.trim(), kind }
    if (!submitted.name || !submitted.rootPath) { toast.error('名称与路径均必填'); return }
    register.mutate(submitted, {
      onSuccess: ({ library, scanError }) => {
        setName(''); setRootPath(''); setKind('normal')
        setScanRetry(scanError ? { library, reason: scanError } : null)
        if (scanError) toast.warning(`「${submitted.name}」已注册，扫描未触发；请单独重试扫描`)
        else toast.success(`「${submitted.name}」已注册，扫描请求已受理`)
      },
      onError: error => toast.error(`注册失败：${batchFailureReason(error)}`),
    })
  }
  const rescan = (library: Library) => {
    if (blocked || !library.id) return
    scan.mutate(library.id, {
      onSuccess: () => { setScanRetry(current => current?.library.id === library.id ? null : current); toast.success(`「${library.name}」扫描请求已受理`) },
      onError: error => { setScanRetry({ library, reason: batchFailureReason(error) }); toast.error(`扫描未触发：${batchFailureReason(error)}`) },
    })
  }
  const setEnabled = (library: Library, enabled: boolean) => {
    if (blocked || !library.id) return
    toggle.mutate({ libraryId: library.id, enabled }, {
      onSuccess: () => { setDisableTarget(null); toast.success(enabled ? `「${library.name}」已启用` : `「${library.name}」已停用，文件与记录保留`) },
      onError: error => toast.error(`操作失败：${batchFailureReason(error)}`),
    })
  }
  const confirmRemove = () => {
    if (blocked || !deleteTarget?.id) return
    const id = deleteTarget.id
    remove.mutate(id, {
      onSuccess: () => { setDeleteTarget(null); setScanRetry(current => current?.library.id === id ? null : current); toast.success('库登记已移除，磁盘文件保留') },
      onError: error => toast.error(`移除失败：${batchFailureReason(error)}`),
    })
  }

  return <div className="page manage-workspace" id="page-maintenance-files-libraries">
    <ManagePageHeader title="库管理" description="登记服务器文件夹；临时不看请停用，移除登记会清除资产关联" actions={<a className="manage-text-link" href="#register-library"><Plus aria-hidden="true" />添加媒体库</a>} />
    {query.isError && <ManageQueryError retry={() => { void query.refetch() }} busy={query.isFetching}>媒体库更新失败{query.data ? '，以下为上次列表；操作暂不可用' : ''}</ManageQueryError>}
    {scanRetry && <div className="manage-notice manage-notice--warning" role="alert"><span>「{scanRetry.library.name}」已注册，扫描未触发：{scanRetry.reason}。无需重复注册。</span><Pill disabled={blocked || !scanRetry.library.id} onClick={() => rescan(scanRetry.library)}>重试扫描</Pill></div>}
    <div className="manage-library-list">
      {query.isLoading ? <div className="settings-card"><p className="rank-note">正在读取媒体库…</p></div> : !query.isError && libraries.length === 0 ? <div className="settings-card manage-empty"><FolderOpen aria-hidden="true" /><h3>先登记一个媒体文件夹</h3><p>填入服务器可访问的目录；登记成功后会申请扫描。</p><a href="#register-library">添加第一个库 →</a></div> : libraries.map(library => <section className="settings-card manage-library" key={library.id}>
        <div className="manage-library-head"><span className="manage-library-icon"><FolderOpen aria-hidden="true" /></span><div><h3>{library.name}</h3><span className="rank-note">{library.kind === 'cos' ? 'COS 作者库' : '常规库'} · {(library.fileCount ?? 0).toLocaleString(LOCALE_ZH)} 个文件</span></div><span className={`manage-state${library.scanState === 'error' ? ' manage-state--warning' : ''}`}>{library.scanState === 'scanning' ? '扫描中' : library.scanState === 'error' ? '扫描异常' : '空闲'}</span></div>
        <p className="manage-path">{library.rootPath}</p>
        <div className="manage-library-actions"><label className="settings-switch"><Switch aria-label={`启用媒体库 ${library.name}`} checked={library.enabled !== false} disabled={blocked || !library.id} onCheckedChange={checked => checked ? setEnabled(library, true) : setDisableTarget(library)} /><span>{library.enabled === false ? '已停用' : '已启用'}</span></label><div><Pill disabled={blocked || !library.id || library.scanState === 'scanning'} onClick={() => rescan(library)}><RefreshCw aria-hidden="true" />{scan.isPending && scan.variables === library.id ? '提交中…' : '重新扫描'}</Pill><Pill disabled={blocked || !library.id} onClick={() => setDeleteTarget(library)}>移除库登记</Pill></div></div>
      </section>)}
    </div>
    <form className="settings-card manage-registration" id="register-library" onSubmit={submitRegister}>
      <div className="manage-group-head"><h3>添加媒体库</h3><p>填写服务器上的文件夹路径，不是当前浏览器的本地目录</p></div>
      <div className="settings-grid">
        <label className="settings-field"><span>库名称</span><input required disabled={pending} value={name} onChange={event => setName(event.target.value)} placeholder="例如：图集" /></label>
        <label className="settings-field"><span>服务器根路径</span><input aria-label="服务器根路径" required disabled={pending} value={rootPath} onChange={event => setRootPath(event.target.value)} placeholder="例如 D:\Media\Photos 或 /media/photos" /><small>目录必须已存在且在允许范围内，不能位于服务数据目录。</small></label>
        <label className="settings-field"><span>库类型</span><Select value={kind} disabled={pending} onValueChange={value => setKind(value === 'cos' ? 'cos' : 'normal')}><SelectTrigger aria-label="库类型" /><SelectContent><SelectItem value="normal">常规</SelectItem><SelectItem value="cos">COS 作者库</SelectItem></SelectContent></Select><small>COS 目录按「作者/作品/文件」组织。</small></label>
      </div>
      <div className="settings-actions"><button className="save-btn" type="submit" disabled={blocked}>{register.isPending ? '登记并申请扫描中…' : '注册并扫描'}</button><span className="save-tip">扫描是后台任务，受理成功不代表扫描完成。</span></div>
    </form>
    <ConfirmDialog open={deleteTarget !== null} title={`移除库「${deleteTarget?.name}」的登记？`} description={`将清除该库 ${deleteTarget?.fileCount ?? 0} 个资产的登记及收藏、标签、作者等关联；磁盘文件和原始浏览事件保留。只想暂时隐藏时，请改用停用。`} confirmText={remove.isPending ? '移除中…' : '确认移除登记'} cancelText="取消" busy={remove.isPending} keepOpenOnConfirm onConfirm={confirmRemove} onOpenChange={open => { if (!open && !remove.isPending) setDeleteTarget(null) }} />
    <ConfirmDialog open={disableTarget !== null} title={`停用「${disableTarget?.name}」？`} description="该库将从浏览、搜索和推荐中隐藏，文件与记录保留，可随时重新启用。" confirmText="确认停用" cancelText="取消" busy={toggle.isPending} keepOpenOnConfirm onConfirm={() => { if (disableTarget) setEnabled(disableTarget, false) }} onOpenChange={open => { if (!open && !toggle.isPending) setDisableTarget(null) }} />
  </div>
}
