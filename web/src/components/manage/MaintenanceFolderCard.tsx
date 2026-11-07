import { FolderOpen } from 'lucide-react'
import { toast } from 'sonner'
import { useMaintenanceFolder } from '@/hooks/use-maintenance-folder'
import { batchFailureReason } from '@/lib/batch'

/** 目录卡：两代布局（当前/上次）说明 + 旧三代文件只读提示；授权/失败语义沿用原通道。 */
export function MaintenanceFolderCard() {
  const { root, choose, supported, loadError, legacyFiles } = useMaintenanceFolder()
  const legacyPreview = legacyFiles.slice(0, 4).join('、')
  return <section className="rank-card maintenance-folder-card" aria-label="维护根目录">
    <div className="rank-head"><h3><FolderOpen aria-hidden="true" /> 维护根目录</h3><span className="rank-note">电脑本地目录</span></div>
    <p>{root?.name ?? '选择一个文件夹，统一保存作者资料、备份和上传归档。'}</p>
    <div className="maintenance-folder-layout">
      <span>当前/作者资料 <small>作者主表 · 双语简介 · 来源与作品清单</small></span>
      <span>当前/备份 <small>完整v2资料与离线头像 · 每代独立摘要</small></span>
      <span>上次 <small>内容变化前的上一代 · 当前与上次各自独立</small></span>
      <span>上传归档 <small>按库及目标目录保存原件副本</small></span>
    </div>
    {supported ? <button type="button" className="pill" disabled={choose.isPending} onClick={() => choose.mutate(undefined, {
      onSuccess: () => toast.success('维护目录已设置，已生成自动备份和作者资料'),
      onError: error => { if (!(error instanceof DOMException && error.name === 'AbortError')) toast.error(batchFailureReason(error)) },
    })}>{choose.isPending ? '正在准备目录…' : root ? '更换维护根目录' : '选择根目录并生成资料'}</button>
      : <p className="rank-note">当前浏览器未提供目录读写，可使用下方文件下载与导入。支持目录授权的浏览器可开启统一目录。</p>}
    {loadError != null && <p role="alert">目录记录读取失败，可重新选择目录。{batchFailureReason(loadError)}</p>}
    {choose.isError && <p role="alert">目录绑定失败。{batchFailureReason(choose.error)}</p>}
    {legacyFiles.length > 0 && <p className="rank-note">检测到旧三代布局文件 {legacyFiles.length} 个（如 {legacyPreview}{legacyFiles.length > 4 ? ' 等' : ''}）。这些文件不会自动删除；确认不再需要后可在原目录手动清理，恢复旧布局备份仍可用「选择备份文件导入」。</p>}
    <p className="rank-note">自动与手动共用两代备份：内容变化先把当前整代提交为上次，再写新当前；摘要不符时暂停写回并保留现场。恢复前仍需确认。</p>
  </section>
}
