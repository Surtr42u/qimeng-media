/**
 * 上传成功后挂靠服务（2026-10-24 自 upload-queue-store.ts 提取，Q7 职责提取批）。
 * 本文件不持有任何队列状态：输入只有 201 响应体与入队时快照的挂靠载荷，输出
 * 「终态补丁」（done / attach-failed 二选一），由调用方（store 的 settle）落回
 * 队列条目——entries/XHR/取消旗标/持久化/订阅等队列状态唯一持有在
 * upload-queue-store，本服务不反向 import store（依赖方向：store → 本服务）。
 *
 * 错误口径（冻结语义，随实现就地保留）：挂靠任何失败都不回滚上传、不重试、
 * 不把条目改判 failed——文件本体已入库，一律转「已入库待挂靠」专项态
 * （attach-failed）并透传失败原因，是否重挂由用户在资产页另行操作。
 */

import {
  putApiV1AssetsByAssetIdAuthors,
  putApiV1AuthorsByAuthorIdSources,
} from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'
import { batchFailureReason } from '@/lib/batch'

/** 201 后自动挂靠载荷（作者必填、来源可选；来源挂靠以作者块为前提，服务端口径） */
export interface UploadAttach {
  authorId: string
  /** 作者展示名（纯 UI 展示；服务端只认 authorId） */
  authorName: string
  /** 并入作者来源区的来源词（mode=append；空数组 = 不动来源） */
  sources: string[]
}

/** 挂靠后处理产出的终态补丁：percent 恒 100（文件本体已入库才走到这里），
 *  attach-failed 必带 errorText（「已入库待挂靠」口径的原因文案）。
 *  store 的 settle 直接按 Partial<UploadItem> 消费本形状 */
export interface UploadAttachOutcome {
  status: 'done' | 'attach-failed'
  percent: number
  errorText?: string
}

/**
 * 201 响应体 → 资产 id（挂靠目标）。协议 201 必回 AssetDetail；解析失败返回
 * null（挂靠缺目标，落 attach-failed 专项态——文件已入库，绝不因此重传）。
 */
export function parseUploadedAssetId(body: string): string | null {
  try {
    const parsed = JSON.parse(body) as { id?: unknown }
    return typeof parsed.id === 'string' && parsed.id !== '' ? parsed.id : null
  } catch {
    return null
  }
}

/**
 * 自动挂靠序列（同 App UploadAttacher）：先 PUT /assets/{id}/authors 单项
 * 整体替换（新上传资产零关联、传单项安全），后 PUT /authors/{authorId}/sources
 * mode=append（并入去重永不覆盖既有来源）——顺序固定（authors 可能触发服务端
 * 建作者块，sources append 依赖块存在）。返回 null = 全部挂上；否则为失败文案。
 * 走生成 SDK（挂靠无进度诉求，token/baseUrl 随全局 client）；任何失败不重试。
 */
async function attachSequence(assetId: string, attach: UploadAttach): Promise<string | null> {
  try {
    await unwrapSdkResult(
      putApiV1AssetsByAssetIdAuthors({
        path: { assetId },
        body: { authorIds: [attach.authorId] },
      }),
    )
    if (attach.sources.length > 0) {
      await unwrapSdkResult(
        putApiV1AuthorsByAuthorIdSources({
          path: { authorId: attach.authorId },
          body: { sources: attach.sources, mode: 'append' },
        }),
      )
    }
    return null
  } catch (err) {
    return batchFailureReason(err)
  }
}

/**
 * 上传成功后的挂靠收口（直传 201 与分片 complete 共用）：无挂靠载荷直通 done；
 * 有载荷则解析响应资产 id → 挂靠序列 → done / attach-failed。await 完成才返回，
 * 调用方在返回后再推进队列终态（串行泵靠此保证「挂靠完成才拾取下一条」）。
 */
export async function postUploadOutcome(
  body: string,
  attach: UploadAttach | undefined,
): Promise<UploadAttachOutcome> {
  if (attach === undefined || attach.authorId === '') {
    return { status: 'done', percent: 100 }
  }
  const assetId = parseUploadedAssetId(body)
  if (assetId === null) {
    return {
      status: 'attach-failed',
      percent: 100,
      errorText: '文件已入库，但响应缺少资产 ID，未能自动挂靠',
    }
  }
  const attachErr = await attachSequence(assetId, attach)
  if (attachErr === null) return { status: 'done', percent: 100 }
  return { status: 'attach-failed', percent: 100, errorText: `文件已入库，但挂靠失败：${attachErr}` }
}
