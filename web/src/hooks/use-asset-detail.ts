/**
 * 资产详情与浏览打点 hooks（协议 GET /api/v1/assets/{id}、POST /api/v1/events/view）。
 */

import { useMutation, useQuery } from '@tanstack/react-query'
import { getApiV1AssetsByAssetId, postApiV1EventsView, type ViewEventReport } from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'
import { ensureSessionId } from './use-session'

/** 资产详情（含签名媒体直链、标签、作者——详情页唯一数据源） */
export function useAssetDetail(assetId: string) {
  return useQuery({
    queryKey: ['asset-detail', assetId],
    queryFn: () => unwrapSdkResult(getApiV1AssetsByAssetId({ path: { assetId } })),
  })
}

/** 浏览打点（open/play/dwell 上报）。
 * sessionId 由本层自动携带（ensureSessionId），调用方只报行为和时长。
 * 设计取舍：打点失败静默（retry 0）——上报是尽力而为的统计数据，
 * 报错弹窗会打断"看看媒体"的核心体验（服务端返回 202 已接受即完成）。
 */
export function useReportView() {
  return useMutation({
    mutationFn: (input: { assetId: string; kind: ViewEventReport['kind']; seconds?: number }) =>
      unwrapSdkResult(
        postApiV1EventsView({
          body: {
            assetId: input.assetId,
            kind: input.kind,
            startedAt: new Date().toISOString(),
            seconds: input.seconds,
            sessionId: ensureSessionId(),
          },
        }),
      ),
    retry: 0,
  })
}
