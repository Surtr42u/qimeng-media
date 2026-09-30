/**
 * 上传队列 hook（文件管理页上传工作台消费；铁律 7：UI 组件不直接调 API）。
 *
 * 本文件是 lib/upload-queue-store（模块级单例 store）的订阅层：
 * useSyncExternalStore 订阅 + 选项/queryClient 注入，队列状态与 XHR 句柄
 * 全部住在 store 里随页面会话存活——切路由（本 hook 卸载）不再取消整队。
 *
 * W-1 冻结记档（2026-10-01 改写原注释，原文见 git 历史）：「切路由（组件
 * 卸载）自动 abort 整队」的论证已失效——上传已成主通道（直传化/自动挂靠/
 * 目录递归连续多批加强），大文件传输中误触路由切换即几 GB 白传。现行为 =
 * 队列随模块存活全页会话有效，abort 只发生在用户显式「全部取消」；401/登出
 * 处置保持既有语义（条目逐个失败落终态，不整队 abort）。页面刷新仍会丢队列
 * （浏览器语义，XHR 句柄不跨页面存活）；刷新存活需 IndexedDB 持久化——另立项
 *（同会话内的弱网断点续传已随 ADR-0028 分片通道落地，见 lib/upload-chunked.ts）。
 *
 * 传输实现口径（裸 XHR + octet-stream、严格串行、挂靠序列、大小上限前置
 * 拦截、目标入队快照、≥16MB 分片断点续传分流——lib/upload-chunked.ts）全部
 * 在 lib/upload-queue-store.ts，本文件不重复。
 */

import { useEffect, useSyncExternalStore } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { uploadQueueStore } from '@/lib/upload-queue-store'

// 类型与换算常量随实现迁至 store（单一来源）；此处 re-export 保持既有
// import 路径不变（UploadWorkbench/UploadQueueTable 零改动）。
export { BYTES_PER_MB } from '@/lib/upload-queue-store'
export type {
  UploadStatus,
  UploadAttach,
  StagedFileInput,
  UploadItem,
  UploadBatchTarget,
  UploadQueueOptions,
} from '@/lib/upload-queue-store'

import type { UploadItem, UploadQueueOptions } from '@/lib/upload-queue-store'

/**
 * 上传队列订阅层：返回签名与拆 store 前完全一致（调用方零适配）。
 * 选项与 queryClient 经 effect 同步进 store（模式同 use-sse-events 的
 * handlerRef——调用方传内联函数也不重启任何东西）；组件卸载后 store 保留
 * 最后一次选项继续服务在传队列（onItemSettled 闭包只引用模块级 toast 等
 * 稳定依赖，卸载后触发安全——后台传完照常提示）。
 */
export function useUploadQueue(options: UploadQueueOptions) {
  useEffect(() => {
    uploadQueueStore.setOptions(options)
  })
  // queryClient 是应用级单例（main.tsx 创建、Provider 下发），store 持引用
  // 即可在组件树外执行上传入库失效；注入先于任何入队（enqueue 唯一入口是
  // 本 hook 的消费组件），防御性判空在 store 内兜底
  const queryClient = useQueryClient()
  useEffect(() => {
    uploadQueueStore.attachQueryClient(queryClient)
  }, [queryClient])

  // useSyncExternalStore：快照 = store 权威副本引用（写入口替换数组保证
  // 引用变化可感知），订阅/解绑由 React 托管——卸载只是停止订阅，不碰传输
  const items = useSyncExternalStore(uploadQueueStore.subscribe, uploadQueueStore.getItems)

  const isBusy = items.some((it) => it.status === 'queued' || it.status === 'uploading')
  const hasFinished = items.some(
    (it: UploadItem) =>
      it.status === 'done' || it.status === 'failed' || it.status === 'attach-failed' || it.status === 'canceled',
  )

  return { items, enqueue: uploadQueueStore.enqueue, cancelAll: uploadQueueStore.cancelAll, clearFinished: uploadQueueStore.clearFinished, isBusy, hasFinished }
}
