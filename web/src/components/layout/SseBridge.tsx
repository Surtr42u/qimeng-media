import { useQueryClient } from '@tanstack/react-query'
import type { QueryClient } from '@tanstack/react-query'
import { useSSEEvents } from '@/hooks/use-sse-events'
import {
  ASSETS_QUERY_KEY,
  AUTHORS_QUERY_KEY,
  DIRS_QUERY_KEY,
  LIBRARIES_QUERY_KEY,
  RANKINGS_QUERY_KEY,
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
 * favorite.changed/like.changed 同理由桥承接（ADR-0029）：收藏/点赞字段面
 * 散布在列表/详情/排行/推荐多族查询，同样不适合页面各自订阅。
 *
 * 失效键必须取 lib/query-keys.ts 的根键常量（2026-09-05 拍板修复：此前手写
 * ['assets'] 等短键与实际查询键 ['api/v1/…'] 形态不匹配，TanStack 前缀匹配
 * 一条不命中，跨端自动刷新整体失效）。本端操作另有 hooks 本地失效，与此处
 * 幂等重叠无害。
 *
 * 重连补偿（2026-10-01）：SSE 断线窗口（服务重启/代理超时）错过的
 * library.changed 等事件不可补发，连接（重）建立时走与事件处理完全相同的
 * 失效映射重新校验本地缓存（主流语义：重连后必须重验缓存）。首次连接也
 * 触发 = 多拉一次，幂等无害。
 *
 * 注意 scan.progress 不在此订阅：它是管理页专属的进度展示，由管理页
 * 自己使用 useSSEEvents 订阅（单连接由 hooks 层引用计数保证）。
 */

/**
 * library.changed 的失效映射（事件处理与重连补偿共用同一份，禁止两处手抄
 * ——AI_README 代码卫生约束 2）：资产族根键一次覆盖 list/total/detail/
 * facets/timeline-tags；sources/authors 聚合随扫描变化；删除类操作进回收站。
 * 推荐流一并失效：删除/移动后推荐流卡片集合变化（P2-3 审查清偿，缺失时
 * 其他标签页在 staleTime 窗口内继续显示已删资产）。
 */
function invalidateLibraryContent(queryClient: QueryClient): void {
  for (const key of [
    ASSETS_QUERY_KEY,
    LIBRARIES_QUERY_KEY,
    DIRS_QUERY_KEY,
    TAGS_QUERY_KEY,
    AUTHORS_QUERY_KEY,
    SOURCES_QUERY_KEY,
    TRASH_QUERY_KEY,
    RECOMMENDATIONS_QUERY_KEY,
  ]) {
    void queryClient.invalidateQueries({ queryKey: key })
  }
}

/**
 * favorite.changed 的失效映射（ADR-0029；事件处理与重连补偿共用，禁止两处手抄）。
 * 收藏不改变资产集合（不 bump 修订号，ADR-0026 语义边界），故不碰 library.changed
 * 的库内容根键（LIBRARIES/DIRS/TAGS/…），只失效收藏数据依赖面——全部挂在 ASSETS
 * 根键下，一根覆盖：
 * - 收藏筛选流（我的页收藏 tab：favorite=true 子键）
 * - AssetDetail.isFavorite（详情收藏按钮初始态）
 * - AssetSummary.isFavorite 列表字段面（list/dir-files/total/facets 全部子键，多拉无害）
 * 推荐流不失效：十维公式无收藏维度（DOMAIN_RULES §1.1），卡片亦不展示收藏态。
 * 载荷不消费（单 assetId 精确失效收益不值复杂度，ADR-0029），整面失效重拉权威状态。
 */
function invalidateFavoriteData(queryClient: QueryClient): void {
  for (const key of [ASSETS_QUERY_KEY]) {
    void queryClient.invalidateQueries({ queryKey: key })
  }
}

/**
 * like.changed 的失效映射（ADR-0029；共用口径同上）。点赞数据依赖面三根：
 * - ASSETS 根键：AssetDetail 的 likedToday/likeCount（详情点赞按钮与计数）、
 *   AssetSummary 点赞字段面、点赞筛选流（liked=true 子键）
 * - RANKINGS：排行热度含窗口内点赞计数（DOMAIN_RULES §2），点赞即改榜——
 *   此前 query-keys「SSE 桥不失效排行」指 library.changed 范畴，点赞面例外
 * - RECOMMENDATIONS：十维含 likeScore（likeCount 归一，DOMAIN_RULES §1.1），点赞即改序
 * stats/history 无点赞字段（view/play 口径），不失效。载荷不消费（同上）。
 */
function invalidateLikeData(queryClient: QueryClient): void {
  for (const key of [ASSETS_QUERY_KEY, RANKINGS_QUERY_KEY, RECOMMENDATIONS_QUERY_KEY]) {
    void queryClient.invalidateQueries({ queryKey: key })
  }
}

export function SseBridge() {
  const queryClient = useQueryClient()

  useSSEEvents({
    onLibraryChanged: () => invalidateLibraryContent(queryClient),
    // 载荷不消费：事件只当变更信号（见 invalidateFavoriteData/invalidateLikeData 注释）
    onFavoriteChanged: () => invalidateFavoriteData(queryClient),
    onLikeChanged: () => invalidateLikeData(queryClient),
    onOpen: () => {
      // 重连成功（含首次连接）：断线窗口错过的事件无法补发，整面重新校验
      // 本地缓存——复用与事件处理完全相同的失效映射（见文件头「重连补偿」），
      // favorite/like 面同样补偿（错过的事件同样不可追，多拉幂等无害）
      invalidateLibraryContent(queryClient)
      invalidateFavoriteData(queryClient)
      invalidateLikeData(queryClient)
    },
    onUploadDone: () => {
      // 本端上传已由 upload-queue-store 本地失效；这里覆盖"其他端（Android/
      // 其他标签页）上传"的场景（含本端：事件同样送达，失效幂等）。
      // 推荐流随新资产入库变化，一并失效
      for (const key of [ASSETS_QUERY_KEY, LIBRARIES_QUERY_KEY, DIRS_QUERY_KEY, RECOMMENDATIONS_QUERY_KEY]) {
        void queryClient.invalidateQueries({ queryKey: key })
      }
    },
  })

  return null
}
