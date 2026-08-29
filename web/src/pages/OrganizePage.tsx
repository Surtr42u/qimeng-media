/**
 * 整理页（/admin/organize）：目录树浏览、上传文件、新建目录。
 *
 * 编排：库选择（useLibraries，默认第一个库）→ 目录树（useDirTree + DirTree 纯渲染，
 * 选中路径为本页 state，空串=库根）；新建目录在当前选中路径下创建子目录；
 * 上传（DropZone 收集文件 → useUploadQueue 串行入队，目标=当前选中路径 + libraryId），
 * 队列渲染与单项取消交给 UploadQueue 纯展示组件。
 */

import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { DirTree } from '@/components/organize/DirTree'
import { DropZone } from '@/components/upload/DropZone'
import { UploadQueue } from '@/components/upload/UploadQueue'
import { useUploadQueue } from '@/components/upload/use-upload-queue'
import { useDirCreate, useDirTree } from '@/hooks/use-dirs'
import { useLibraries } from '@/hooks/use-libraries'
import { apiErrorText } from '@/pages/_shared/error-text'

/* ------------------------------ 文案常量 ------------------------------ */

const TEXT_TITLE = '整理'
const TEXT_NO_LIBRARY = '还没有媒体库'
const TEXT_NO_LIBRARY_HINT = '请先到管理页注册媒体库，再回来整理文件'
const TEXT_GO_ADMIN = '去管理页'
const TEXT_TREE_TITLE = '目录树'
const TEXT_NEW_DIR_TITLE = '新建目录'
const TEXT_NEW_DIR_HINT = '在当前选中目录下创建子目录'
const TEXT_NEW_DIR_PLACEHOLDER = '输入子目录名'
const TEXT_CREATE = '创建'
const TEXT_CREATE_SUCCESS = '目录已创建'
const TEXT_DIR_NAME_INVALID = '目录名不能包含 / 或 \\'
const TEXT_UPLOAD_TITLE = '上传文件'
const TEXT_UPLOAD_FAIL = '文件上传失败'
const TEXT_CURRENT_LOCATION = '当前位置'
const TEXT_ROOT_LOCATION = '库根'

/* ------------------------------ 行为辅助 ------------------------------ */

/** 库内相对路径拼接：根（空串）下直接用子目录名，否则以 / 连接 */
function joinDirPath(parent: string, name: string): string {
  return parent === '' ? name : `${parent}/${name}`
}

export default function OrganizePage() {
  const librariesQuery = useLibraries()
  const libraries = (librariesQuery.data ?? []).filter((lib) => lib.id)

  // 当前库：默认取第一个库（M2 以单库为主）；显式选择仅在库列表内有效
  const [selectedLibraryId, setSelectedLibraryId] = useState('')
  const libraryId =
    selectedLibraryId !== '' && libraries.some((lib) => lib.id === selectedLibraryId)
      ? selectedLibraryId
      : (libraries[0]?.id ?? '')

  // 当前选中目录（库内相对路径；空串 = 库根），切库时重置回库根
  const [selectedPath, setSelectedPath] = useState('')
  const handleSelectLibrary = (id: string) => {
    setSelectedLibraryId(id)
    setSelectedPath('')
  }

  const dirTreeQuery = useDirTree(libraryId || undefined)
  const dirCreate = useDirCreate()
  const uploadQueue = useUploadQueue()

  const [newDirName, setNewDirName] = useState('')

  const locationLabel = selectedPath === '' ? TEXT_ROOT_LOCATION : selectedPath

  /** 新建目录：当前选中路径下创建子目录（服务端校验合法性，客户端只拦明显错误） */
  const handleCreateDir = async () => {
    const name = newDirName.trim()
    if (!name || dirCreate.isPending || !libraryId) return
    if (name.includes('/') || name.includes('\\')) {
      toast.error(TEXT_DIR_NAME_INVALID)
      return
    }
    const targetPath = joinDirPath(selectedPath, name)
    try {
      await dirCreate.mutateAsync({ path: targetPath, libraryId })
      setNewDirName('')
      toast.success(TEXT_CREATE_SUCCESS, { description: targetPath })
    } catch (error) {
      toast.error(apiErrorText(error))
    }
  }

  /** 上传入队：目标 = 当前选中路径 + 当前库（队列内部串行调度与错误文案） */
  const handleFiles = (files: File[]) => {
    if (!libraryId) return
    uploadQueue.enqueue(files, libraryId, selectedPath)
  }

  // 上传失败额外 toast（队列项内已展示同一文案；ref 去重避免重渲染重复弹）
  const seenUploadErrorKeysRef = useRef<Set<string>>(new Set())
  useEffect(() => {
    for (const item of uploadQueue.items) {
      if (item.status === 'error' && !seenUploadErrorKeysRef.current.has(item.key)) {
        seenUploadErrorKeysRef.current.add(item.key)
        toast.error(item.message ?? TEXT_UPLOAD_FAIL)
      }
    }
  }, [uploadQueue.items])

  return (
    <div className="flex flex-col gap-4 px-4 py-4">
      <div className="flex items-center gap-3">
        <h1 className="text-lg font-bold">{TEXT_TITLE}</h1>
        {libraries.length > 0 && (
          <Select value={libraryId} onValueChange={handleSelectLibrary}>
            <SelectTrigger size="sm" className="ml-auto w-48 shrink-0">
              <SelectValue />
            </SelectTrigger>
            <SelectContent align="end">
              {libraries.map((lib) => (
                <SelectItem key={lib.id} value={lib.id as string}>
                  {lib.name ?? lib.id}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        )}
      </div>

      {librariesQuery.isLoading ? (
        <Skeleton className="h-40 w-full rounded-xl" />
      ) : libraries.length === 0 ? (
        <div className="flex flex-col items-center gap-2 py-20 text-center">
          <p className="text-sm font-semibold">{TEXT_NO_LIBRARY}</p>
          <p className="text-xs text-[var(--qm-text-muted)]">{TEXT_NO_LIBRARY_HINT}</p>
          <Button variant="outline" size="sm" asChild>
            <Link to="/admin">{TEXT_GO_ADMIN}</Link>
          </Button>
        </div>
      ) : (
        <div className="grid grid-cols-1 items-start gap-4 lg:grid-cols-[280px_minmax(0,1fr)]">
          {/* 左：目录树 */}
          <section className="flex min-w-0 flex-col gap-3 rounded-xl border border-[var(--qm-border)] bg-[var(--qm-surface)] p-4">
            <h2 className="text-sm font-semibold">{TEXT_TREE_TITLE}</h2>
            <p className="text-xs text-[var(--qm-text-muted)]">
              {TEXT_CURRENT_LOCATION}：{locationLabel}
            </p>
            <div className="max-h-[420px] overflow-y-auto">
              {dirTreeQuery.isLoading ? (
                <div className="flex flex-col gap-1.5">
                  {Array.from({ length: 4 }).map((_, i) => (
                    <Skeleton key={i} className="h-7 w-full rounded-lg" />
                  ))}
                </div>
              ) : dirTreeQuery.data ? (
                <DirTree root={dirTreeQuery.data} selectedPath={selectedPath} onSelect={setSelectedPath} />
              ) : null}
            </div>
          </section>

          {/* 右：新建目录 + 上传 */}
          <div className="flex min-w-0 flex-col gap-4">
            <section className="flex flex-col gap-3 rounded-xl border border-[var(--qm-border)] bg-[var(--qm-surface)] p-4">
              <h2 className="text-sm font-semibold">{TEXT_NEW_DIR_TITLE}</h2>
              <p className="text-xs text-[var(--qm-text-muted)]">
                {TEXT_NEW_DIR_HINT}（{TEXT_CURRENT_LOCATION}：{locationLabel}）
              </p>
              <div className="flex gap-2">
                <Input
                  value={newDirName}
                  onChange={(e) => setNewDirName(e.target.value)}
                  placeholder={TEXT_NEW_DIR_PLACEHOLDER}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') void handleCreateDir()
                  }}
                />
                <Button
                  onClick={() => void handleCreateDir()}
                  disabled={newDirName.trim() === '' || dirCreate.isPending || !libraryId}
                >
                  {TEXT_CREATE}
                </Button>
              </div>
            </section>

            <section className="flex flex-col gap-3 rounded-xl border border-[var(--qm-border)] bg-[var(--qm-surface)] p-4">
              <h2 className="text-sm font-semibold">{TEXT_UPLOAD_TITLE}</h2>
              <DropZone onFiles={handleFiles} disabled={!libraryId} />
              <UploadQueue items={uploadQueue.items} onCancel={uploadQueue.cancel} />
            </section>
          </div>
        </div>
      )}
    </div>
  )
}
