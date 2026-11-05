import { FolderOpen } from 'lucide-react'
import { toast } from 'sonner'
import { useMaintenanceFolder } from '@/hooks/use-maintenance-folder'
import { batchFailureReason } from '@/lib/batch'

export function MaintenanceFolderCard() {
  const { root, choose, supported, loadError } = useMaintenanceFolder()
  return <section className="rank-card maintenance-folder-card" aria-label="维护根目录">
    <div className="rank-head"><h3><FolderOpen aria-hidden="true" /> 维护根目录</h3><span className="rank-note">电脑本地目录</span></div>
    <p>{root?.name ?? '选择一个文件夹，统一保存作者资料、备份和上传归档。'}</p>
    <div className="maintenance-folder-layout">
      <span>作者资料 <small>原始TXT · 双语简介 · 身份与作品清单</small></span>
      <span>备份 <small>完整v2资料与离线头像</small></span>
      <span>上传归档 <small>按库及目标目录保存原件副本</small></span>
    </div>
    {supported ? <button type="button" className="pill" disabled={choose.isPending} onClick={() => choose.mutate(undefined, {
      onSuccess: () => toast.success('维护目录已设置，已生成自动备份和作者资料'),
      onError: error => { if (!(error instanceof DOMException && error.name === 'AbortError')) toast.error(batchFailureReason(error)) },
    })}>{choose.isPending ? '正在准备目录…' : root ? '更换维护根目录' : '选择根目录并生成资料'}</button>
      : <p className="rank-note">当前浏览器未提供目录读写，可使用下方文件下载与导入。支持目录授权的浏览器可开启统一目录。</p>}
    {loadError != null && <p role="alert">目录记录读取失败，可重新选择目录。{batchFailureReason(loadError)}</p>}
    <p className="rank-note">选择目录生成独立自动备份；“更新全部归档”写入手动备份。恢复前仍需确认。</p>
  </section>
}
