import { useState } from 'react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { getApiV1Discoveries } from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'
import { DISCOVERIES_QUERY_KEY } from '@/lib/query-keys'

const DISCOVERY_COUNT = 6

/** 每次进入/换组用新seed；旧组保留到新组提交，失败显式重试，不由客户端选池。 */
export function useDiscoveries() {
  const [seed, setSeed] = useState(Date.now)
  const query = useQuery({
    queryKey: [...DISCOVERIES_QUERY_KEY, seed],
    queryFn: () => unwrapSdkResult(getApiV1Discoveries({ query: { seed, limit: DISCOVERY_COUNT } })),
    placeholderData: keepPreviousData,
  })
  return { query, items: (query.data ?? []).map(item => item.asset), change: () => setSeed(previous => Math.max(Date.now(), previous + 1)) }
}
