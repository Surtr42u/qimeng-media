/**
 * 串行上传队列（整理页上传区编排层）。
 *
 * 为什么串行而非并发：上传进度条是全屏单条最直观（旧项目验证语义），
 * 且并发同名上传会与服务端"同名自动重命名"竞态（服务端按文件存在性生成 "基名 (2).ext"，
 * 两个并发同名文件都可能生成同名结果）——串行从客户端根除该竞态。
 *
 * 数据访问：仅转发 useUploadProgress（hooks 层），本文件不触碰 SDK/API（web/README 分层纪律）。
 */

import { useCallback, useEffect, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useUploadProgress } from '@/hooks/use-upload'
import { apiErrorText } from '@/pages/_shared/error-text'

/** 队列项状态：queued 等待 → uploading 传输中 → 终态 done/error/cancelled */
export type UploadQueueItemStatus = 'queued' | 'uploading' | 'done' | 'error' | 'cancelled'

/** 队列项：file 为原始 File（上传只在成功时才读），终态以 message 携带结果文案 */
export interface UploadQueueItem {
  key: string
  file: File
  libraryId: string
  /** 目标目录（库内相对路径；"库根用空串"由调用方决定） */
  dir: string
  status: UploadQueueItemStatus
  /** 0~100（上传中实时变化；未开始为 0） */
  progress: number
  /** 终态结果文案：done=已上传：<relPath>；error=apiErrorText；cancelled=已取消 */
  message?: string
}

/** 上传成功文案前缀（relPath 为服务端裁决的最终路径——同名自动重命名以响应为准） */
const TEXT_UPLOAD_DONE = '已上传：'
/** 上传被取消的终态文案 */
const TEXT_UPLOAD_CANCELLED = '已取消'

/** 队列项唯一 key（浏览器 crypto.randomUUID；异常环境降级时间戳+随机数） */
function createItemKey(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`
}

export interface UploadQueueControls {
  /** 队列项（顺序 = 上传顺序） */
  items: UploadQueueItem[]
  /** 追加一批文件到队尾并启动调度 */
  enqueue: (files: File[], libraryId: string, dir: string) => void
  /** 取消单项：queued 直接移除；uploading 触发 XHR abort（终态为 cancelled） */
  cancel: (key: string) => void
  /** 当前是否正在传输（DriveZone/按钮禁用场景用） */
  active: boolean
}

export function useUploadQueue(): UploadQueueControls {
  const { upload, progress, abort } = useUploadProgress()
  const queryClient = useQueryClient()
  const [items, setItems] = useState<UploadQueueItem[]>([])
  const queueRef = useRef<UploadQueueItem[]>([])
  const runningRef = useRef(false)
  const currentKeyRef = useRef<string | null>(null)

  /** 用单项终态（成功/失败）更新队列 */
  const finishItem = useCallback(
    (key: string, status: UploadQueueItemStatus, patch: { progress: number; message: string }) => {
      const index = queueRef.current.findIndex((item) => item.key === key)
      if (index < 0) return
      queueRef.current[index] = { ...queueRef.current[index], status, ...patch }
      setItems([...queueRef.current])
      // 上传成功 → 目录树懒刷新（目录内计数/新文件立即出现）；assets 失效已由 hook 完成
      if (status === 'done') {
        void queryClient.invalidateQueries({ queryKey: ['dirs'] })
      }
    },
    [queryClient],
  )

  /** 串行调度：循环处理队首 queued 项（上传中 abort 会走 catch 分支置 cancelled 后继续下一项） */
  const pump = useCallback(async () => {
    if (runningRef.current) return
    runningRef.current = true
    try {
      let head = queueRef.current[0]
      while (head && head.status === 'queued') {
        currentKeyRef.current = head.key
        queueRef.current[0] = { ...head, status: 'uploading', progress: 0 }
        setItems([...queueRef.current])
        try {
          const detail = await upload({
            file: head.file,
            libraryId: head.libraryId,
            dir: head.dir,
            filename: head.file.name,
          })
          finishItem(head.key, 'done', {
            progress: 100,
            message: `${TEXT_UPLOAD_DONE}${detail.relPath ?? head.file.name}`,
          })
        } catch (error) {
          const aborted = error instanceof DOMException && error.name === 'AbortError'
          finishItem(head.key, aborted ? 'cancelled' : 'error', {
            progress: 0,
            message: aborted ? TEXT_UPLOAD_CANCELLED : apiErrorText(error),
          })
        }
        queueRef.current.shift()
        setItems([...queueRef.current])
        currentKeyRef.current = null
        head = queueRef.current[0]
      }
    } finally {
      runningRef.current = false
    }
  }, [upload, finishItem])

  /** 传输进度（hook 单实例进度，仅作用于当前上传项） */
  useEffect(() => {
    if (progress == null || currentKeyRef.current == null) return
    const key = currentKeyRef.current
    const index = queueRef.current.findIndex((item) => item.key === key)
    if (index < 0) return
    queueRef.current[index] = { ...queueRef.current[index], progress }
  }, [progress])

  const enqueue = useCallback(
    (files: File[], libraryId: string, dir: string) => {
      if (files.length === 0) return
      const next: UploadQueueItem[] = files.map((file) => ({
        key: createItemKey(),
        file,
        libraryId,
        dir,
        status: 'queued',
        progress: 0,
      }))
      queueRef.current.push(...next)
      setItems([...queueRef.current])
      void pump()
    },
    [pump],
  )

  const cancel = useCallback(
    (key: string) => {
      const index = queueRef.current.findIndex((item) => item.key === key)
      if (index < 0) return
      if (queueRef.current[index].status === 'queued') {
        queueRef.current.splice(index, 1)
        setItems([...queueRef.current])
      } else if (queueRef.current[index].status === 'uploading') {
        abort()
      }
    },
    [abort],
  )

  return {
    items,
    enqueue,
    cancel,
    active: items.some((item) => item.status === 'uploading'),
  }
}
