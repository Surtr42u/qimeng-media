/** 失败与空数据分开；旧内容存在时明确标记，重试由消费端传入。 */
export function QueryNotice({ message, retained = false, busy = false, onRetry }: {
  message: string; retained?: boolean; busy?: boolean; onRetry: () => void
}) {
  return <div className="query-notice" role="alert"><span>{message}{retained ? '，暂时显示上次内容' : ''}</span><button className="pill" type="button" disabled={busy} onClick={onRetry}>{busy ? '读取中…' : '重新读取'}</button></div>
}
