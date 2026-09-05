import { useState } from 'react'
import { FolderPlus } from 'lucide-react'
import type { Library } from '@/api/generated'
import { DirTreeNodes } from '@/components/manage/DirTree'
import { CreateDirDialog } from '@/components/manage/CreateDirDialog'
import { DirFileList } from '@/components/manage/DirFileList'
import { Select, SelectContent, SelectItem, SelectTrigger } from '@/components/ui/select'
import { useDirTree } from '@/hooks/use-libraries'

/**
 * 目录浏览卡（B-1 从 LibraryManagePage 拆出独立组件——页面已超 500 行
 * 警戒线，本批「目录树操作化」的主要增量都落在本卡）。
 * 选库 → 拉取目录树（useDirTree）→ 目录行 hover 显示「新建子目录」操作
 * （POST /dirs 幂等）。行渲染复用共享 DirTreeNodes 的只读+操作模式。
 * B-5 文件行接线：目录行升级为「选中 + 行尾操作」组合模式（DirTreeNodes
 * 同传 onSelect/renderActions）——点击目录（含根行「库根」）选中该目录，
 * 树下方 DirFileList 列出其直接子文件（GET /assets directory 过滤），
 * 文件行 hover 三操作（重命名/移动/删除）复用 B-1 既有件。选中态缺省
 * 库根（''），选库即看库根文件；换库重置回库根。
 */
export function DirBrowser({ libraries }: { libraries: Library[] }) {
  const [libId, setLibId] = useState('')
  const enabled = libId !== ''
  // 选中目录（库内相对路径，'' = 库根）：缺省库根，换库时一并复位
  const [selectedDir, setSelectedDir] = useState('')
  // /dirs 的 libraryId 协议必填：未选库 enabled=false 挂起查询（挂起语义见 useDirTree）
  const { data: tree, isFetching } = useDirTree(libId, enabled)

  // 待创建子目录的父目录（非 null 即打开 CreateDirDialog）
  const [createUnder, setCreateUnder] = useState<string | null>(null)

  return (
    <div className="rank-card">
      <div className="rank-head">
        <h3>目录浏览</h3>
        <span className="f-years">
          <Select
            value={libId}
            onValueChange={(v) => {
              setLibId(v)
              setSelectedDir('')
            }}
          >
            <SelectTrigger aria-label="选择库" placeholder="选择库…" />
            <SelectContent>
              {/* l.id 协议可选，radix SelectItem 要求非空 string：无 id 的库行
                  （正常不会出现）本就无法作为操作目标，直接不进下拉 */}
              {libraries.map((l) =>
                l.id ? (
                  <SelectItem key={l.id} value={l.id}>{l.name}</SelectItem>
                ) : null,
              )}
            </SelectContent>
          </Select>
        </span>
      </div>
      <p className="rank-note">点击目录查看其中的文件；目录行可新建子目录，文件行 hover 可重命名/移动/删除</p>
      {!enabled ? (
        <p className="grid-empty">先在上方选择一个库。</p>
      ) : isFetching ? (
        <p className="grid-empty">加载中…</p>
      ) : tree ? (
        <ul>
          <DirTreeNodes
            node={tree}
            depth={0}
            selectedPath={selectedDir}
            onSelect={(node) => setSelectedDir(node.path ?? '')}
            renderActions={(node) => (
              <button
                className="dir-action-btn"
                type="button"
                title="在此目录下新建子目录"
                onClick={() => setCreateUnder(node.path ?? '')}
              >
                <FolderPlus width={14} height={14} />
                新建子目录
              </button>
            )}
          />
        </ul>
      ) : (
        <p className="grid-empty">该库暂无目录。</p>
      )}
      {enabled && <DirFileList libraryId={libId} directory={selectedDir} />}
      {enabled && (
        <CreateDirDialog
          open={createUnder !== null}
          libraryId={libId}
          parentPath={createUnder ?? ''}
          onClose={() => setCreateUnder(null)}
        />
      )}
    </div>
  )
}
