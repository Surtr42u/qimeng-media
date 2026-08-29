/**
 * 上传 hooks（协议 POST /api/v1/assets/upload）：
 * body = 原始字节流（File），元数据全在 query（libraryId/dir/filename）。
 *
 * 为什么坚持 XHR 而不是 fetch：fetch 不暴露上传进度事件（Request 流分片进度
 * 尚无稳定 API），而上传进度条是上传页的核心交互（大视频/压缩包可达数 GB），
 * 进度反馈缺失会让用户以为卡死。XHR 是唯一可靠的进度方案。
 */

import { useCallback, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { type AssetDetail } from '@/api/generated'
import { getAuthHeaders } from '@/lib/api-client'
import { UPLOAD_PATH } from '@/lib/constants'

/** 上传入参：元数据全在 query，body 是文件本身 */
export interface UploadInput {
  file: File
  /** 目标库 ID（多库场景必须显式指定——协议要求） */
  libraryId: string
  /** 目标目录（库内相对路径；空串 = 库根） */
  dir: string
  filename: string
}

/**
 * 上传进度 hook。
 * @returns { upload, progress, uploading, abort }
 *   - upload(input): Promise<AssetDetail>（成功 resolve，resolve 后自动失效资产/库列表；失败 reject）
 *   - progress: 0~100 或 null（未上传/结束）
 *   - uploading: 是否在上传中
 *   - abort: 中断当前上传（触发 reject DOMException AbortError）
 */
export function useUploadProgress() {
  const queryClient = useQueryClient()
  const [progress, setProgress] = useState<number | null>(null)
  const [uploading, setUploading] = useState(false)
  const xhrRef = useRef<XMLHttpRequest | null>(null)

  const upload = useCallback(
    (input: UploadInput): Promise<AssetDetail> =>
      new Promise((resolve, reject) => {
        const xhr = new XMLHttpRequest()
        xhrRef.current = xhr

        // 元数据放 query（协议形状）；URLSearchParams 负责编码（文件名可能含中文/空格）
        const url = new URL(UPLOAD_PATH, window.location.origin)
        url.search = new URLSearchParams({
          libraryId: input.libraryId,
          dir: input.dir,
          filename: input.filename,
        }).toString()

        xhr.open('POST', url)
        for (const [name, value] of Object.entries(getAuthHeaders())) {
          xhr.setRequestHeader(name, value)
        }
        // 原始字节流：显式声明，服务端按 octet-stream 读取（协议上传端点）
        xhr.setRequestHeader('Content-Type', 'application/octet-stream')

        setProgress(0)
        setUploading(true)

        xhr.upload.onprogress = (event) => {
          // lengthComputable 为 false（分块传输未知总长）时保持现状，避免 NaN
          if (event.lengthComputable) {
            setProgress(Math.round((event.loaded / event.total) * 100))
          }
        }
        xhr.onload = () => {
          if (xhr.status >= 200 && xhr.status < 300) {
            let detail: AssetDetail
            try {
              detail = JSON.parse(xhr.responseText) as AssetDetail
            } catch {
              reject(new Error('上传成功但响应解析失败'))
              return
            }
            // 上传即入库（upload.done 事件也会广播，但本页已拿到结果，无需等 SSE）
            void queryClient.invalidateQueries({ queryKey: ['assets'] })
            void queryClient.invalidateQueries({ queryKey: ['libraries'] })
            resolve(detail)
          } else {
            reject(new Error(`上传失败（HTTP ${xhr.status}）`))
          }
        }
        xhr.onerror = () => reject(new Error('网络错误，上传中断'))
        xhr.onabort = () => reject(new DOMException('上传已取消', 'AbortError'))
        xhr.onloadend = () => {
          setProgress(null)
          setUploading(false)
          xhrRef.current = null
        }

        xhr.send(input.file)
      }),
    [queryClient],
  )

  const abort = useCallback(() => {
    xhrRef.current?.abort()
  }, [])

  return { upload, progress, uploading, abort }
}
