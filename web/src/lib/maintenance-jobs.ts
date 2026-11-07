/**
 * 维护任务中心纯函数（GET/POST /api/v1/maintenance/jobs 的 Web 展示口径）。
 *
 * 边界：组件只做渲染，状态文案映射/终态判定/展示截断集中在本文件；任务语义以
 * docs/api/maintenance.md 的状态机与终态定义为准（协议字段见 web/src/api/generated）。
 * 本文件不做任何数据获取——请求在 hooks/use-maintenance-jobs.ts。
 */
import type { MaintenanceJobKind, MaintenanceJobStatus, MaintenanceStepStatus } from '@/api/generated'
import { formatDateTime } from './format'

/** 维护任务族根键（列表/详情子键均以本键为首段，取消成功后失效列表族）。 */
export const MAINTENANCE_JOBS_QUERY_KEY = ['api/v1/maintenance/jobs'] as const

/** 任务列表单页条数（协议 GET /maintenance/jobs limit 允许 1–100；取中档减少翻页）。 */
export const MAINTENANCE_JOBS_PAGE_SIZE = 20

/** 步骤明细展示上限（字）：detail 是 worker 定义的自由 JSON，页面不做业务解释。 */
export const MAINTENANCE_STEP_DETAIL_TEXT_LIMIT = 200

/** 任务类型（语义见协议 MaintenanceJobKind 注释：研究/清理/备份/自定义）。 */
export const MAINTENANCE_JOB_KIND_LABELS: Record<MaintenanceJobKind, string> = {
  asset_research: '作者/作品研究',
  author_cleanup: '作者清理',
  backup_export: '备份导出',
  custom: '自定义',
}

/**
 * 任务状态展示顺序 = 协议状态机顺序（排队→…→终态），筛选下拉与文案共用。
 * 语义只做中文映射，不改变任何状态迁移规则。
 */
export const MAINTENANCE_JOB_STATUSES: readonly MaintenanceJobStatus[] = [
  'queued',
  'inventory',
  'research',
  'planned',
  'applying',
  'verifying',
  'waiting_evidence',
  'waiting_authorization',
  'waiting_device',
  'paused',
  'partial',
  'completed',
  'failed',
  'cancelled',
]

/** 状态字符串收窄：筛选下拉的值来自本文件常量，跨组件边界时仍做一次类型守卫。 */
export function isMaintenanceJobStatus(value: string): value is MaintenanceJobStatus {
  return (MAINTENANCE_JOB_STATUSES as readonly string[]).includes(value)
}

/** 任务状态中文文案（与协议 MaintenanceJobStatus 一一对应）。 */
export const MAINTENANCE_JOB_STATUS_LABELS: Record<MaintenanceJobStatus, string> = {
  queued: '排队中',
  inventory: '盘点中',
  research: '研究中',
  planned: '已计划',
  applying: '应用中',
  verifying: '核验中',
  waiting_evidence: '等待证据',
  waiting_authorization: '等待授权',
  waiting_device: '等待设备',
  paused: '已暂停',
  partial: '部分完成',
  completed: '已完成',
  failed: '已失败',
  cancelled: '已取消',
}

/** 步骤状态中文文案（协议 MaintenanceStepStatus）。 */
export const MAINTENANCE_STEP_STATUS_LABELS: Record<MaintenanceStepStatus, string> = {
  pending: '待执行',
  running: '执行中',
  completed: '已完成',
  failed: '失败',
  skipped: '已跳过',
}

/**
 * 终态集合：completed/partial/failed/cancelled。
 * 来源 docs/api/maintenance.md「终态不可再变更」——终态不再提供暂停/恢复/取消。
 */
const TERMINAL_JOB_STATUSES: ReadonlySet<MaintenanceJobStatus> = new Set([
  'completed',
  'partial',
  'failed',
  'cancelled',
])

/** 是否终态（终态任务不可取消、不会再有步骤推进）。 */
export function isMaintenanceJobTerminal(status: MaintenanceJobStatus): boolean {
  return TERMINAL_JOB_STATUSES.has(status)
}

/** 是否可取消：协议允许取消任意非终态任务（含 paused 与 waiting_*）。 */
export function canCancelMaintenanceJob(status: MaintenanceJobStatus): boolean {
  return !isMaintenanceJobTerminal(status)
}

/** 服务端 progress（0–100 向下取整）转展示值；越界/非数值按 0/100 夹取。 */
export function maintenanceJobProgress(progress: number): number {
  if (!Number.isFinite(progress)) return 0
  return Math.min(100, Math.max(0, Math.round(progress)))
}

/** 服务端 ISO 时刻 → 本地「M-D HH:mm」；缺失/非法显示破折号，不把未知写成 0。 */
export function formatMaintenanceTimestamp(iso?: string | null): string {
  if (!iso) return '—'
  const millis = Date.parse(iso)
  if (Number.isNaN(millis)) return '—'
  return formatDateTime(millis) || '—'
}

/** 工件摘要展示：长摘要截短（title 由调用方保留完整值）；空值返回空串。 */
export function formatArtifactDigest(sha256?: string | null): string {
  const digest = sha256?.trim() ?? ''
  return digest.length > 12 ? `${digest.slice(0, 12)}…` : digest
}

/** 任务错误码/错误信息合成（协议 Error：code + message；缺失字段不渲染）。 */
export function maintenanceJobErrorText(error?: { code?: string; message?: string } | null): string {
  const code = error?.code?.trim() ?? ''
  const message = error?.message?.trim() ?? ''
  if (code !== '' && message !== '') return `${code}：${message}`
  return code !== '' ? code : message
}

/**
 * 步骤明细展示文本：只做有界 JSON 序列化，不解释 worker 的业务字段。
 * detail 为空对象/缺失时返回空串；超长截断避免撑破表格（明细过长时注明）。
 */
export function maintenanceStepDetailText(detail?: Record<string, unknown> | null): string {
  if (!detail || Object.keys(detail).length === 0) return ''
  let text: string | undefined
  try {
    text = JSON.stringify(detail)
  } catch {
    return '明细无法展示'
  }
  if (!text) return ''
  return text.length > MAINTENANCE_STEP_DETAIL_TEXT_LIMIT
    ? `${text.slice(0, MAINTENANCE_STEP_DETAIL_TEXT_LIMIT)}…（明细过长已截断）`
    : text
}
