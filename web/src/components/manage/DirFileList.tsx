import { useState } from 'react'
import { FolderInput, Pencil, Trash2 } from 'lucide-react'
import type { AssetSummary, MediaType } from '@/api/generated'
import {
  FileOpsDialogs,
  type FileOpsMode,
  type FileOpsTarget,
} from '@/components/manage/FileOpsDialogs'
import { useAssetsInDirectory } from '@/hooks/use-assets'
import { dirLabel, formatBytes } from '@/lib/format'

/**
 * 目录树选中目录的直接子文件行（B-5 目录树文件行接线）：数据源
 * useAssetsInDirectory（GET /assets 的 B-4 directory 过滤，一次拉全 limit=200，
 * query key 含 directory——切目录换键重取；挂资产根键，移动/删除的根键失效
 * 直接命中本清单，行随操作结果即时刷新）。行 = 文件名 + 类型/大小 metric +
 * 行内 hover 三操作（重命名/移动/删除）；弹窗编排复用共享 FileOpsDialogs
 * （MoveDialog / ConfirmDialog 删除语义，文案含「移入回收站」），本组件零
 * 直调 API（铁律 7）。行容器复用 .dir-row（与目录行同一 hover 操作语言）。
 */

/** MediaType 协议枚举 → 行内类型文案（单点定义；协议加枚举须同步此处） */
const MEDIA_TYPE_LABEL: Record<MediaType, string> = {
  image: '图片',
  animated_image: '动图',
  video: '视频',
}

/** FileOpsDialogs 常驻挂载（confirm 关闭动画依赖常驻），无操作时的空目标 */
const NO_TARGET: FileOpsTarget = { assetId: '', libraryId: '', directory: '', fileName: '' }

export function DirFileList({ libraryId, directory }: { libraryId: string; directory: string }) {
  // isLoading（非 isFetching，B-8 P3 清偿）：失效重取/切目录换键期间保留旧列表
  // （hook 配 placeholderData: keepPreviousData），不再整段替换成「加载中…」闪烁；
  // 仅首次无数据时显示加载态
  const { data: files = [], isLoading } = useAssetsInDirectory(libraryId, directory, true)
  // 行内操作的打开弹窗态：mode + 操作目标（打开时刻快照，与上传队列表同口径）
  const [ops, setOps] = useState<{ mode: FileOpsMode; target: FileOpsTarget } | null>(null)

  const openOps = (mode: FileOpsMode, f: AssetSummary): void => {
    setOps({
      mode,
      target: { assetId: f.id ?? '', libraryId, directory, fileName: f.fileName ?? '' },
    })
  }

  return (
    <div className="dir-files">
      <p className="dir-files-title">
        「{dirLabel(directory)}」的直接子文件
        <b>{files.length}</b>
      </p>
      {isLoading ? (
        <p className="grid-empty">加载中…</p>
      ) : files.length === 0 ? (
        <p className="grid-empty">该目录下暂无文件。</p>
      ) : (
        <ul className="dir-file-list">
          {files.map((f) => (
            <li key={f.id} className="dir-row dir-file-row">
              <span className="dir-file-name" title={f.fileName ?? ''}>{f.fileName}</span>
              <span className="dir-file-meta">
                {f.mediaType ? MEDIA_TYPE_LABEL[f.mediaType] : '未知'} · {formatBytes(f.sizeBytes ?? 0)}
              </span>
              <span className="dir-actions">
                <button
                  className="dir-action-btn"
                  type="button"
                  title="重命名"
                  onClick={() => openOps('move', f)}
                >
                  <Pencil width={14} height={14} />
                  重命名
                </button>
                <button
                  className="dir-action-btn"
                  type="button"
                  title="移动到其他目录"
                  onClick={() => openOps('move', f)}
                >
                  <FolderInput width={14} height={14} />
                  移动
                </button>
                <button
                  className="dir-action-btn"
                  type="button"
                  title="移入回收站（可在维护页恢复）"
                  onClick={() => openOps('delete', f)}
                >
                  <Trash2 width={14} height={14} />
                  删除
                </button>
              </span>
            </li>
          ))}
        </ul>
      )}
      <FileOpsDialogs
        mode={ops?.mode ?? 'none'}
        target={ops?.target ?? NO_TARGET}
        onClose={() => setOps(null)}
      />
    </div>
  )
}
