import { useQueryClient } from '@tanstack/react-query'
import { useSSEEvents } from '@/hooks/use-sse-events'
import {
  ASSETS_QUERY_KEY,
  AUTHORS_QUERY_KEY,
  DIRS_QUERY_KEY,
  LIBRARIES_QUERY_KEY,
  RECOMMENDATIONS_QUERY_KEY,
  SOURCES_QUERY_KEY,
  TAGS_QUERY_KEY,
  TRASH_QUERY_KEY,
} from '@/lib/query-keys'

/**
 * SSE → Query 缓存桥（无 UI）。
 *
 * 为什么单独一层（而非把 invalidate 写进页面）：library.changed/upload.done
 * 是应用级数据事件——多个页面都可能正在显示受影响的列表，由桥统一失效
 * 各查询缓存，页面无需各自订阅（也避免每个页面重复建立 SSE 连接）。
 *
 * 失效键必须取 lib/query-keys.ts 的根键常量（2026-09-05 拍板修复：此前手写
 * ['assets'] 等短键与实际查询键 ['api/v1/…'] 形态不匹配，TanStack 前缀匹配
 * 一条不命中，跨端自动刷新整体失效）。本端操作另有 hooks 本地失效，与此处
 * 幂等重叠无害。
 *
 * 注意 scan.progress 不在此订阅：它是管理页专属的进度展示，由管理页
 * 自己使用 useSSEEvents 订阅（单连接由 hooks 层引用计数保证）。
 */
export function SseBridge() {
  const queryClient = useQueryClient()

  useSSEEvents({
    onLibraryChanged: () => {
      // 库内容增删改（扫描/管理操作）：资产族根键一次覆盖 list/total/detail/
      // facets/timeline-tags；sources/authors 聚合随扫描变化；删除类操作进回收站
      for (const key of [
        ASSETS_QUERY_KEY,
        LIBRARIES_QUERY_KEY,
        DIRS_QUERY_KEY,
        TAGS_QUERY_KEY,
        AUTHORS_QUERY_KEY,
        SOURCES_QUERY_KEY,
        TRASH_QUERY_KEY,
      ]) {
        void queryClient.invalidateQueries({ queryKey: key })
      }
    },
    onUploadDone: () => {
      // 本页上传已由 use-upload 本地失效；这里覆盖"其他端（Android/其他标签页）上传"的场景
      // （含本端：事件同样送达，失效幂等）。推荐流随新资产入库变化，一并失效
      for (const key of [ASSETS_QUERY_KEY, LIBRARIES_QUERY_KEY, DIRS_QUERY_KEY, RECOMMENDATIONS_QUERY_KEY]) {
        void queryClient.invalidateQueries({ queryKey: key })
      }
    },
  })

  return null
}
