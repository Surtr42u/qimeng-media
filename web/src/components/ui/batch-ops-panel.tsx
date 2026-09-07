import type { BatchState } from '@/hooks/use-batch-runner'
import { batchPercent } from '@/lib/batch'

/**
 * 批量执行进度/失败汇总面板（F6 两页共用）。交互语言复用上传队列：
 * .progress 进度条 + 完成计数；失败明细内嵌列表可滚动——失败展示选「页内
 * 内嵌列表」这条稳妥路径（sonner toast 存活期短、自定义展开内容要另做样式，
 * 内嵌面板不依赖 toast 存活且可逐条滚动核对），toast description 只带前几条
 * 引导回面板。total=0 = 尚未跑过批量，不渲染。
 */
export function BatchOpsPanel({ state, onDismiss }: { state: BatchState; onDismiss: () => void }) {
  if (state.total === 0) return null
  return (
    <div className="batch-panel" role="status" aria-label={`批量操作进度：${state.action}`}>
      <div className="batch-panel-head">
        <b>{state.running ? `${state.action}中…` : `${state.action}完成`}</b>
        <span className="batch-panel-count">
          {state.done} / {state.total}
        </span>
        {!state.running && (
          <button className="pill" type="button" onClick={onDismiss}>
            收起
          </button>
        )}
      </div>
      <div className="progress">
        <i style={{ width: `${batchPercent(state.done, state.total)}%` }} />
      </div>
      {state.failures.length > 0 && (
        <ul className="batch-panel-failures">
          {state.failures.map((f) => (
            <li key={f.id}>
              <span className="batch-fail-label" title={f.label}>
                {f.label}
              </span>
              <span className="batch-fail-reason" title={f.reason}>
                {f.reason}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
