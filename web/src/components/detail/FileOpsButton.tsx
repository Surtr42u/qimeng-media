import { useState } from 'react'
import { useNavigate } from 'react-router'
import { FolderInput, Trash2 } from 'lucide-react'
import type { AssetDetail } from '@/api/generated'
import { FileOpsDialogs } from '@/components/manage/FileOpsDialogs'

/**
 * 详情页文件整理入口（B-1 文件管理操作化）——互动行「整理/删除」按钮。
 * 背景：目录树协议（GET /dirs）只回目录与计数，文件行操作此前在文件管理页
 * 无处挂载；详情页有 assetId/libraryId/directory 完整上下文，是文件三操作
 * （重命名/移动/移入回收站）的可行挂载点（B-4 协议补 GET /assets directory
 * 过滤后，文件管理页目录树文件行同款操作见 DirBrowser/DirFileList）。
 * B-5：弹窗编排（MoveDialog/删除确认/toast/删除流）抽共享 FileOpsDialogs
 * （目录树文件行复用同一份），本组件只保留触发按钮与「删除后 navigate(-1)
 * 离开已删资产」的详情页收尾（toast 挂根 Toaster，跳转后仍可见）。
 */
export function FileOpsButton({ asset }: { asset: AssetDetail }) {
  const [mode, setMode] = useState<'none' | 'move' | 'delete'>('none')
  const navigate = useNavigate()

  return (
    <>
      <button
        className="detail-act"
        onClick={() => setMode('move')}
        title="移动到其他目录 / 重命名"
      >
        <FolderInput />
        <b>整理</b>
      </button>
      <button
        className="detail-act"
        onClick={() => setMode('delete')}
        title="移入回收站（可在维护页恢复）"
      >
        <Trash2 />
        <b>删除</b>
      </button>

      <FileOpsDialogs
        mode={mode}
        target={{
          assetId: asset.id ?? '',
          libraryId: asset.libraryId ?? '',
          directory: asset.directory ?? '',
          fileName: asset.fileName ?? '',
        }}
        onClose={() => setMode('none')}
        onDeleted={() => navigate(-1)}
      />
    </>
  )
}
