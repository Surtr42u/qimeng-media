/**
 * 客户端配置 hooks（设置页扫描/上传卡消费；铁律 7：组件不直接调 API）。
 * 数据源 GET/PUT /api/v1/config：PUT 为全量整体替换语义，成功响应返回
 * 存储后全量（直写缓存省一轮 GET）。
 * 生效口径（openapi description 同步写明）：autoAccept实时生效；maxBytesMb为旧SDK兼容。
 * workers接入实际跨库并发，0为自动；thumbEdge仍为预留（与 OpenAPI ClientConfig 同步）。
 */

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  getApiV1Config,
  putApiV1Config,
  type ClientConfig,
} from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'

export const CONFIG_QUERY_KEY = ['api/v1/config'] as const

/** OpenAPI ClientConfigUpload.maxBytesMb旧SDK兼容哨兵，与服务端config常量双向同步。 */
export const LEGACY_UNLIMITED_UPLOAD_MB = 2147483647

/** 服务端缺省（openapi ClientConfig 各字段 default；与 server config.go 对齐） */
export const DEFAULT_CLIENT_CONFIG: ClientConfig = {
  scan: { workers: 0, thumbEdge: 800 },
  upload: { maxBytesMb: LEGACY_UNLIMITED_UPLOAD_MB, autoAccept: true },
}

/** 协议允许范围（openapi minimum/maximum；保存前本地校验用） */
export const CONFIG_BOUNDS = {
  workers: { min: 0, max: 32 },
  thumbEdge: { min: 200, max: 1600 },
} as const

/** 读取当前客户端配置（服务端无记录时回缺省值） */
export function useConfig() {
  return useQuery({
    queryKey: CONFIG_QUERY_KEY,
    queryFn: () => unwrapSdkResult(getApiV1Config()),
  })
}

/** 保存（全量提交四个字段）；成功后用 PUT 返回的存储后全量直写缓存 */
export function useSaveConfig() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (cfg: ClientConfig) => unwrapSdkResult(putApiV1Config({ body: cfg })),
    // 作废保存前的 GET，避免迟到旧值在 PUT 成功后覆盖缓存。
    onMutate: () => qc.cancelQueries({ queryKey: CONFIG_QUERY_KEY }),
    onSuccess: async (saved) => {
      // 保存期间也可能因焦点/SSE 再发 GET，确认响应前再次作废旧读取。
      await qc.cancelQueries({ queryKey: CONFIG_QUERY_KEY })
      qc.setQueryData(CONFIG_QUERY_KEY, saved)
    },
  })
}

/** 服务端值逐组兜底缺省（防老响应缺字段时受控组件拿到 undefined） */
export function mergeClientConfig(c: ClientConfig): ClientConfig {
  return {
    scan: { ...DEFAULT_CLIENT_CONFIG.scan, ...c.scan },
    upload: { ...DEFAULT_CLIENT_CONFIG.upload, ...c.upload },
  }
}
