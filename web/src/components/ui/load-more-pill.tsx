/**
 * 手动翻页「加载更多」胶囊（收拢各网格页尾部逐字同构的 5 处重复实现）：
 * <p className="grid-empty"><button className="pill">加载中…/加载更多</button>
 * <span className="pill-count">共 N 项</span></p>。
 *
 * 与首页 useAutoMore（触底自动加载）是两种并存的加载策略，本组件只收
 * 手动翻页形态，不承担触底自动翻页。
 *
 * 外层渲染条件经 when 传参消化（缺省 true，false 时不渲染）——调用方把
 * hasNextPage / `q !== '' && hasNextPage` 等条件直接传 when，零特判。
 */
export function LoadMorePill({
  fetching,
  count,
  onNext,
  when = true,
}: {
  fetching: boolean
  count: number
  onNext: () => void
  when?: boolean
}) {
  if (!when) return null
  return (
    <p className="grid-empty">
      <button className="pill" type="button" disabled={fetching} onClick={onNext}>
        {fetching ? '加载中…' : '加载更多'}
      </button>
      <span className="pill-count">共 {count} 项</span>
    </p>
  )
}
