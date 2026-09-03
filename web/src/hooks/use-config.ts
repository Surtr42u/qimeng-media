/**
 * 客户端配置 hooks（设置页扫描/上传卡消费；铁律 7：组件不直接调 API）。
 * 数据源 GET/PUT /api/v1/config：PUT 为全量整体替换语义，成功响应返回
 * 存储后全量（直写缓存省一轮 GET）。
 * 生效口径（openapi description 同步写明）：upload 两项实时生效；
 * scan 两项仅持久化、重启后生效（UI 文案需注明，不假装实时）。
 */

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  getApiV1Config,
  putApiV1Config,
  type ClientConfig,
} from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'

export const CONFIG_QUERY_KEY = ['api/v1/config'] as const

/** 服务端缺省（openapi ClientConfig 各字段 default；与 server config.go 对齐） */
export const DEFAULT_CLIENT_CONFIG: ClientConfig = {
  scan: { workers: 2, thumbEdge: 800 },
  upload: { maxBytesMb: 2048, autoAccept: true },
}

/** 协议允许范围（openapi minimum/maximum；保存前本地校验用） */
export const CONFIG_BOUNDS = {
  workers: { min: 1, max: 4 },
  thumbEdge: { min: 200, max: 1600 },
  maxBytesMb: { min: 64, max: 8192 },
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
    onSuccess: (saved) => qc.setQueryData(CONFIG_QUERY_KEY, saved),
  })
}

/** 服务端值逐组兜底缺省（防老响应缺字段时受控组件拿到 undefined） */
export function mergeClientConfig(c: ClientConfig): ClientConfig {
  return {
    scan: { ...DEFAULT_CLIENT_CONFIG.scan, ...c.scan },
    upload: { ...DEFAULT_CLIENT_CONFIG.upload, ...c.upload },
  }
}
