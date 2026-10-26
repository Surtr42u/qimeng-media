import { useId, useState } from 'react'
import { FolderOpen, ChevronDown, Plus } from 'lucide-react'
import type { Library } from '@/api/generated'
import { useDirTree } from '@/hooks/use-libraries'
import type { UploadTarget } from '@/hooks/use-upload-target'
import { Pill } from '@/components/ui/pill'
import { CreateDirDialog } from './CreateDirDialog'
import { DirTreeNodes } from './DirTree'

/** 根目录可直接上传，只有修改位置时才展开目录树，避免把浏览器铺满工作台。 */
export function UploadDestinationCard({ libraries, target }: { libraries: Library[]; target: UploadTarget }) {
  const [browsing, setBrowsing] = useState(false)
  const [creating, setCreating] = useState(false)
  const panelId = useId()
  const query = useDirTree(target.libId, target.enabled && browsing)
  return <section className="chart-card upload-setup-card">
    <div className="upload-section-head"><span className="upload-step">01</span><h3>上传位置</h3><FolderOpen aria-hidden="true" /></div>
    <div className="upload-field-label">目标库 <span>必选</span></div>
    <div className="source-chips upload-libraries" role="group" aria-label="目标库">
      {libraries.map(library => {
        const id = library.id
        return id ? <Pill key={id} active={target.libId === id} disabled={library.enabled === false} onClick={() => target.onLibChange(id)}>{library.name}{library.enabled === false ? ' · 已停用' : ''}</Pill> : null
      })}
      {libraries.length === 0 && <p className="rank-note">{target.targetsReady ? '暂无可选库，请先到库管理登记媒体文件夹。' : '目标库列表尚不可用，请查看上方提示。'}</p>}
    </div>
    <div className="upload-field-label">目标目录</div>
    <div className="upload-directory-choice"><div><strong>{target.dir || '库根目录'}</strong><small>{target.libId ? '新文件保存在此位置' : '选定目标库后可修改目录'}</small></div><button className="pill" type="button" disabled={!target.enabled} aria-expanded={browsing} aria-controls={panelId} onClick={() => setBrowsing(!browsing)}>{browsing ? '收起目录' : '选择目录'}<ChevronDown aria-hidden="true" /></button></div>
    {browsing && target.enabled && <div className="upload-directory-panel" id={panelId}>
      {query.isError ? <div className="manage-notice manage-notice--warning" role="alert"><span>目录读取失败，当前目标未改变。</span><Pill onClick={() => { void query.refetch() }}>重试目录</Pill></div> : query.data ? <div className="dir-tree"><ul><DirTreeNodes node={query.data} depth={0} selectedPath={target.dir} onSelect={node => { target.setDir(node.path ?? ''); setBrowsing(false) }} /></ul></div> : <p className="rank-note">正在读取目录…</p>}
      <button className="manage-text-link" type="button" disabled={!query.data || query.isError} onClick={() => setCreating(true)}><Plus aria-hidden="true" />在当前目录下新建</button>
    </div>}
    {target.enabled && <CreateDirDialog open={creating} libraryId={target.libId} parentPath={target.dir} onClose={() => setCreating(false)} />}
  </section>
}
