import { useState } from 'react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { getApiV1Discoveries } from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'

const DISCOVERY_COUNT = 6
const DISCOVERY_LABELS = { unseen: '未看发现', rediscover: '久别重逢', interest: '近期兴趣' } as const

/** 每次进入/换组用新seed；旧组保留到新组提交，失败显式重试，不由客户端选池。 */
export function useDiscoveries() {
  const [seed, setSeed] = useState(Date.now)
  const query = useQuery({
    queryKey: ['api/v1/discoveries', seed],
    queryFn: () => unwrapSdkResult(getApiV1Discoveries({ query: { seed, limit: DISCOVERY_COUNT } })),
    placeholderData: keepPreviousData,
  })
  const notes = Object.fromEntries((query.data ?? []).map(item => [item.asset.id, DISCOVERY_LABELS[item.reason]]))
  return { query, items: (query.data ?? []).map(item => item.asset), notes, change: () => setSeed(previous => Math.max(Date.now(), previous + 1)) }
}
