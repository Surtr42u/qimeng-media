/**
 * 加载占位行（grid-empty 全宽提示行的共享件，卫生约束 6）：
 * `<p className="grid-empty">加载中…</p>` 此前在 7 文件 9 处手抄，文案或
 * 类名改动需逐处同步——收敛为单一来源。纯展示、零状态、不调 API（铁律 7）。
 */
export function LoadingHint() {
  return <p className="grid-empty">加载中…</p>
}
