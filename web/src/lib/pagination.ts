/**
 * offset 游标分页的续页判据（单一来源；use-assets 推荐流与 use-stats 排行流
 * 共用，此前两处各写一份同式判据与口径注释）。
 * 仅兼容没有续页响应头的旧服务端：原始页长度等于 limit 时继续；
 * 新服务端必须按原始顺序的下一 offset 续页，不能拿复核/去重后的长度判断。
 * 形态适配 TanStack useInfiniteQuery 的 getNextPageParam（第三参起为 pageParam）。
 */
export function lengthCursorNext(limit: number) {
  return (lastPage: unknown[], _allPages: unknown[], lastPageParam: number): number | undefined =>
    lastPage.length === limit ? lastPageParam + limit : undefined
}

// 与 OpenAPI components.headers.NextOffset 双向同步；旧服务端无头才按页长兼容。
export const NEXT_OFFSET_HEADER = 'X-Qimeng-Next-Offset'
const END_OFFSET = -1

export interface OffsetPage<T> {
  items: T[]
  nextOffset: number | undefined
}

export function offsetPage<T>(items: T[], headers: Headers | undefined, offset: number, limit: number): OffsetPage<T> {
  const raw = headers?.get(NEXT_OFFSET_HEADER)
  if (raw == null) return { items, nextOffset: lengthCursorNext(limit)(items, [], offset) }
  const next = Number(raw)
  if (raw.trim() === '' || !Number.isSafeInteger(next) || (next !== END_OFFSET && next <= offset)) {
    throw new Error('服务端续页信息无效，请刷新重试')
  }
  return { items, nextOffset: next === END_OFFSET ? undefined : next }
}

/** 页面继续消费数组清单，分页元数据留在 hook 的原始缓存供续页判定。 */
export function offsetPageItems<T, P>(data: { pages: OffsetPage<T>[]; pageParams: P[] }) {
  return { ...data, pages: data.pages.map((page) => page.items) }
}
