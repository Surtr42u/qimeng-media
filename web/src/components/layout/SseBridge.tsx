import { useQueryClient } from '@tanstack/react-query'
import { useSSEEvents } from '@/hooks/use-sse-events'

/**
 * SSE → Query 缓存桥（无 UI）。
 *
 * 为什么单独一层（而非把 invalidate 写进页面）：library.changed/upload.done
 * 是应用级数据事件——多个页面都可能正在显示受影响的列表，由桥统一失效
 * 各查询缓存，页面无需各自订阅（也避免每个页面重复建立 SSE 连接）。
 *
 * 注意 scan.progress 不在此订阅：它是管理页专属的进度展示，由管理页
 * 自己使用 useSSEEvents 订阅（单连接由 hooks 层引用计数保证）。
 */
export function SseBridge() {
  const queryClient = useQueryClient()

  useSSEEvents({
    onLibraryChanged: () => {
      void queryClient.invalidateQueries({ queryKey: ['assets'] })
      void queryClient.invalidateQueries({ queryKey: ['libraries'] })
      void queryClient.invalidateQueries({ queryKey: ['tags'] })
      void queryClient.invalidateQueries({ queryKey: ['trash'] })
    },
    onUploadDone: () => {
      // 本页上传已由 use-upload 本地失效；这里覆盖"其他端（Android/其他标签页）上传"的场景
      void queryClient.invalidateQueries({ queryKey: ['assets'] })
      void queryClient.invalidateQueries({ queryKey: ['libraries'] })
    },
  })

  return null
}
