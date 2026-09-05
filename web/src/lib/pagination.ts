/**
 * offset 游标分页的续页判据（单一来源；use-assets 推荐流与 use-stats 排行流
 * 共用，此前两处各写一份同式判据与口径注释）。
 * 判到底口径：「原始返回页长度 === limit」即还有下一页——协议无总数字段，
 * 渲染层去重后的列表长度不代表到底，不能拿去判断。
 * 形态适配 TanStack useInfiniteQuery 的 getNextPageParam（第三参起为 pageParam）。
 */
export function lengthCursorNext(limit: number) {
  return (lastPage: unknown[], _allPages: unknown[], lastPageParam: number): number | undefined =>
    lastPage.length === limit ? lastPageParam + limit : undefined
}
