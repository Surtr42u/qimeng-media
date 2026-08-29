/**
 * 信息来源（出处分区）hooks：协议 GET /api/v1/sources（migration 0004 后批量生成）。
 *
 * 用途：相册页按出处分组、全部页筛选面板的"来源"维度。
 * 口径与 GET /assets 一致（默认排除 COS 作者关联文件，includeCos=true 重新包含）。
 */

import { useQuery } from '@tanstack/react-query'
import { getApiV1Sources, type SourceCount } from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'

/** 信息来源列表（name=null 由显示层兜底"其他"） */
export function useSources(options: { includeCos?: boolean; enabled?: boolean } = {}) {
  return useQuery({
    queryKey: ['sources', options.includeCos ?? false],
    queryFn: () =>
      unwrapSdkResult(
        getApiV1Sources({
          query: {
            includeCos: options.includeCos,
          },
        }),
      ),
    enabled: options.enabled ?? true,
  })
}

export type { SourceCount }
