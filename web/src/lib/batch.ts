/**
 * 批量操作共享纯函数（F6 文件管理页/回收站页批量多选）：进度换算、失败原因
 * 提取、结果汇总文案、失败清单文本。行为由 batch.test.ts 锁定（代码卫生 6：
 * 两页共用一份口径，禁止复制粘贴）；IO 与逐条循环在 hooks/use-batch-runner.ts。
 */

/** 批量单条失败记录（label = 展示用文件名，reason = 服务端/网络错误文案） */
export interface BatchFailure {
  id: string
  label: string
  reason: string
}

/** 批量执行结果：成功数 + 逐条失败清单 */
export interface BatchOutcome {
  succeeded: number
  failures: BatchFailure[]
}

/** 进度百分比（0–100 整数）；total≤0 视为已完成（空批不产生批量面板，防御兜底） */
export function batchPercent(done: number, total: number): number {
  if (total <= 0) return 100
  return Math.min(100, Math.round((done / total) * 100))
}

/**
 * 失败原因文案提取：unwrapSdkResult 抛的是生成 client 错误体形状对象
 * （{code,message,status}，非 Error 实例），优先取 message 字段；Error 与
 * 其他值按序兜底。失败清单要求逐条可读，不能落成 "[object Object]"。
 */
export function batchFailureReason(err: unknown): string {
  if (err instanceof Error) {
    const message = err.message.trim()
    return message !== '' ? message : '操作失败，请重试'
  }
  if (typeof err === 'string') {
    const message = err.trim()
    return message !== '' ? message : '操作失败，请重试'
  }
  if (err !== null && typeof err === 'object') {
    const value = err as { message?: unknown; status?: unknown; code?: unknown }
    if (typeof value.message === 'string' && value.message.trim() !== '') {
      return value.message.trim()
    }
    // 只在明确的 4xx/5xx 上打 HTTP 标签：非错误路径的对象也常带 status（如 200/0），
    // 不加判定会输出「请求失败（HTTP 200）」这类自相矛盾的文案。
    if (typeof value.status === 'number' && Number.isFinite(value.status) && value.status >= 400) {
      const code = typeof value.code === 'string' && value.code.trim() !== '' ? '，' + value.code.trim() : ''
      return '请求失败（HTTP ' + value.status + code + '）'
    }
    if (typeof value.code === 'string' && value.code.trim() !== '') {
      return '请求失败（' + value.code.trim() + '）'
    }
  }
  return '发生未知错误，请稍后重试'
}

/** 批量完成 toast 主文案：全部成功与部分失败两种口径 */
export function formatBatchSummary(action: string, outcome: BatchOutcome): string {
  const { succeeded, failures } = outcome
  if (failures.length === 0) return `${action}完成：成功 ${succeeded} 项`
  return `${action}完成：成功 ${succeeded} 项，失败 ${failures.length} 项`
}

/**
 * toast description 用的失败清单文本：只列前 max 行（toast 存活期短，全量
 * 清单留在页内 BatchOpsPanel 可滚动查看），余量折叠提示去面板看。
 */
export function failureListText(failures: BatchFailure[], max = 5): string {
  const lines = failures.slice(0, max).map((f) => `· ${f.label}：${f.reason}`)
  const rest = failures.length - lines.length
  if (rest > 0) lines.push(`…另有 ${rest} 项失败（见页内失败列表）`)
  return lines.join('\n')
}
