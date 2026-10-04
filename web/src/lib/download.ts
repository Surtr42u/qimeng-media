/**
 * Blob 触发浏览器下载的共享实现（卫生约束 6：抽自 use-backup 导出与
 * 快照下载两处手抄——后者 2026-10-04 随 DbBackupCard 移除退役，本函数
 * 由备份导入导出等继续消费）。
 *
 * 隐式锚点 <a download> 同源免确认；objectURL 的释放兜底在 finally——
 * click() 同步返回后即可 revoke，无需等浏览器下载完成。
 */
export function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  try {
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    document.body.appendChild(a)
    a.click()
    a.remove()
  } finally {
    URL.revokeObjectURL(url)
  }
}
