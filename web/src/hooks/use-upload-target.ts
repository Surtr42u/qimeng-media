/** 上传表单单一目标草稿；库失效时停止新入队，不修改已经冻结的队列目标。 */
import { useState } from 'react'
import type { Library } from '@/api/generated'
import type { AuthorAttachValue } from '@/components/manage/AuthorSuggestField'
import { mergeClientConfig, useConfig } from './use-config'

export function useUploadTarget(libraries: Library[], targetsReady: boolean) {
  const [libId, setLibId] = useState('')
  const [dir, setDir] = useState('')
  const [batchAuthor, setBatchAuthor] = useState<AuthorAttachValue | null>(null)
  const [batchSources, setBatchSources] = useState<string[]>([])
  const config = useConfig()
  const upload = config.data ? mergeClientConfig(config.data).upload : null
  const library = libraries.find(item => item.id === libId)
  const canAttach = library?.capabilities?.authorAttach === true
  const enabled = targetsReady && library !== undefined && library.enabled !== false
  const blockedReason = !targetsReady ? '目标库列表尚不可用，请先重新读取'
    : !libId ? '先选择目标库，再添加文件'
      : !library ? '所选目标库已不在列表中，请重新选择'
        : library.enabled === false ? '所选目标库已停用，请启用或选择其他库'
          : config.isError ? '上传设置读取失败，请重新读取'
            : !upload ? '正在读取上传设置…'
              : !upload.autoAccept ? '服务器已暂停接收上传，请先在设置中开启' : null
  return {
    libId, dir, setDir, batchAuthor, batchSources, setBatchSources, enabled, canAttach,
    canAdd: blockedReason === null, blockedReason,
    maxBytesMb: upload?.maxBytesMb ?? null,
    libraryName: library?.name ?? '尚未选择目标库',
    retryConfig: config.refetch,
    configError: config.isError,
    onLibChange(next: string) {
      setLibId(next); setDir('')
      if (libraries.find(item => item.id === next)?.capabilities?.authorAttach !== true) { setBatchAuthor(null); setBatchSources([]) }
    },
    onAuthorChange(next: AuthorAttachValue | null) {
      if (next?.authorId !== batchAuthor?.authorId) setBatchSources([])
      setBatchAuthor(next)
    },
  }
}
