/**
 * 播放进度上报 + 时间轴标签 hooks（视频播放器专用网络层，铁律 7：组件不直调 API）。
 *
 * 进度走 PUT /assets/{id}/progress（心跳式，服务端只保留最新值；不进事件流、
 * 不计 playCount——GUIDE_API「播放进度与编码字段」）；时间轴标签走
 * GET /assets/{id}/timeline-tags（视频内时间点标记，独立于文件标签）。
 *
 * 纯规则（节流 shouldSend / 切资产补报 / flush 配对 / 标签排序）在
 * lib/progress-report.ts，本 hook 只留 React 生命周期与网络（ADR-0017）。
 */

import { useCallback, useEffect, useMemo, useRef } from 'react'
import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query'
import {
  getApiV1AssetsByAssetIdTimelineTags,
  putApiV1AssetsByAssetIdProgress,
  type TimelineTag,
} from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'
import {
  resolveFlushPosition,
  resolveSwitchFlush,
  resolveUnloadFlush,
  shouldSendProgress,
  sortTimelineTagsByTime,
  type LastPositionPair,
} from '@/lib/progress-report'
import { ASSETS_QUERY_KEY } from '@/lib/query-keys'

/** 时间轴标签（协议 TimelineTag；timeMillis 毫秒→播放器秒由消费方换算） */
export type AssetTimelineTag = TimelineTag

/** 时间轴标签列表（按时间升序——进度条打点渲染与回看跳转依赖稳定时间序） */
export function useTimelineTags(assetId?: string) {
  return useQuery({
    queryKey: [...ASSETS_QUERY_KEY, 'timeline-tags', assetId],
    queryFn: () =>
      unwrapSdkResult(getApiV1AssetsByAssetIdTimelineTags({ path: { assetId: assetId! } })),
    enabled: !!assetId,
    // F2：详情→详情换件换键重取期间保留上一件的标签占位（与 useAssetDetail
    // 同款 TanStack v5 keepPreviousData 模式，web/src/hooks/use-assets.ts 先例）
    // ——不闪「标签空一拍」。P1-1 契约（消费方必读）：占位期 v5 把 status 乐观
    // 置为 'success'（isPending/isLoading 同拍为 false），故消费方必须以
    // isPending || isPlaceholderData 作挂载闸（AssetDetailPage 的播放器挂载守卫
    // 即此口径）——占位中的旧资产标签严禁喂给新挂载的 VideoPlayer：打点仅在
    // 构造/loadedmetadata 时消费，旧标签会定格在新播放器进度条上、seek 落到
    // 旧资产时间戳。换键重取失败（error）期占位被丢弃，data 回 undefined
    placeholderData: keepPreviousData,
    select: (tags) => sortTimelineTagsByTime(tags),
  })
}

/**
 * 断点续播进度上报：tick = 播放中心跳（内部按 5s 节流）；
 * flush = 暂停/离开立即上报最后已知位置（不受节流限制，可显式传位置）。
 * 组件卸载时自动补报一次（协议语义：暂停/离开播放页各补一次）。
 * enabled=false（P2 占位闸门）：详情→详情换件的 keepPreviousData 窗口，
 * 播放器里还是上一资产的画面——tick/flush 一律 no-op，防旧资产的播放位置
 * 错记进 URL 上的新资产；切资产补报（下方 effect）不受此门限制，照常把
 * 旧资产自己的最后位置报给旧资产。
 */
export function useProgress(assetId: string, enabled = true) {
  // 上报载荷必须带显式 assetId：详情→详情导航（同路由参数变化，组件不卸载）时
  // mutationFn 闭包里的 assetId 会随 render 切到新资产——补报旧位置若走闭包，
  // 就会把旧资产的进度写进新资产（跨资产数据污染）。
  const mutation = useMutation({
    mutationFn: (payload: { assetId: string; positionSeconds: number }) =>
      unwrapSdkResult(
        putApiV1AssetsByAssetIdProgress({
          path: { assetId: payload.assetId },
          body: { positionSeconds: payload.positionSeconds },
        }),
      ),
  })

  // 最后已知位置与「它所属的资产」配对存储：补报只允许写回配对里的那个资产
  const lastRef = useRef<LastPositionPair | null>(null)
  const lastSentAtRef = useRef(0)
  const assetIdRef = useRef(assetId)

  // latest-ref 模式（lint 合规：ref 更新放 effect）：send 无依赖，
  // 经 ref 间接调用 mutate，避免对 useMutation result 对象的依赖抖动
  const mutateRef = useRef(mutation.mutate)
  useEffect(() => {
    mutateRef.current = mutation.mutate
  })

  const send = useCallback((targetAssetId: string, positionSeconds: number) => {
    lastSentAtRef.current = Date.now()
    mutateRef.current({ assetId: targetAssetId, positionSeconds })
  }, [])

  // 切资产（详情→详情）：先用旧资产身份补报旧位置，再整体重置——新资产的
  // 进度从零积累，旧位置不得在后续卸载补报中归属到新资产
  useEffect(() => {
    if (assetIdRef.current === assetId) return
    // 切资产补报：仅配对仍归属旧资产时才报（规则在 lib/progress-report）
    const switchFlush = resolveSwitchFlush(lastRef.current, assetIdRef.current)
    if (switchFlush) send(switchFlush.assetId, switchFlush.positionSeconds)
    lastRef.current = null
    lastSentAtRef.current = 0
    assetIdRef.current = assetId
  }, [assetId, send])

  const tick = useCallback(
    (positionSeconds: number) => {
      if (!enabled) return // P2 占位闸门：窗口期画面属于上一资产，不记账不上报
      lastRef.current = { assetId: assetIdRef.current, positionSeconds }
      if (shouldSendProgress(lastSentAtRef.current, Date.now())) {
        send(assetIdRef.current, positionSeconds)
      }
    },
    [send, enabled],
  )

  const flush = useCallback(
    (positionSeconds?: number) => {
      if (!enabled) return // P2 占位闸门：窗口期无本资产位置可报（旧资产已在切资产 effect 补报）
      const position = resolveFlushPosition(positionSeconds, lastRef.current, assetIdRef.current)
      if (position !== null) send(assetIdRef.current, position)
    },
    [send, enabled],
  )

  // 离开播放页立即补报（协议语义：暂停/离开各补一次）：仅卸载时触发一次；
  // 只认带资产配对的记录，配对归属谁就报给谁
  useEffect(
    () => () => {
      const last = resolveUnloadFlush(lastRef.current)
      if (last) mutateRef.current({ assetId: last.assetId, positionSeconds: last.positionSeconds })
    },
    [],
  )

  return useMemo(() => ({ tick, flush }), [tick, flush])
}
