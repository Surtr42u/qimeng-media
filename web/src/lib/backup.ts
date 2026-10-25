/** 当前v2便携备份的文件校验与确认摘要；页面只负责选择/确认。 */

import type { LegacyBackupImport } from '@/api/generated'

/** 与OpenAPI的schemaVersion及portablebackup.SchemaVersion同步，协议变化须同批更新。 */
const BACKUP_SCHEMA_VERSION = 2

/** 备份导入大小上限（与portablebackup.ImportMaxBody同步）：
 * 服务端超限回 413，但浏览器在上传中途被掐断时只能看到 "Failed to fetch"，
 * 这里前置拦截给可读文案 */
export const BACKUP_MAX_BYTES = 64 * 1024 * 1024

/** 导入确认摘要（计数取备份内原始条数，用于当前v2确认弹窗） */
export interface BackupSummary {
  fileName: string
  mediaFiles: number
  authors: number
  tags: number
  /** 实际原生事件条数，不把展示用聚合段当作导入事件来源 */
  eventRows: number
}

/** 解析并校验当前v2备份文件；格式不符/JSON 损坏抛出中文错误信息 */
export async function parseBackupFile(file: File): Promise<{
  payload: LegacyBackupImport
  summary: BackupSummary
}> {
  if (file.size > BACKUP_MAX_BYTES) {
    throw new Error(`备份文件超过 ${(BACKUP_MAX_BYTES / 1024 / 1024).toFixed(0)}MB 上限，请确认导出的是完整备份`)
  }
  const text = await file.text()
  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch {
    throw new Error('文件不是合法 JSON，请确认选择的是 qimeng_backup.json 备份文件')
  }
  const formatError = '备份格式不符：format 必须为 qimeng_backup，data 必须为对象'
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) throw new Error(formatError)
  const envelope = parsed as Partial<LegacyBackupImport>
  if (envelope.format !== 'qimeng_backup' || typeof envelope.data !== 'object' || !envelope.data || Array.isArray(envelope.data)) throw new Error(formatError)
  if (envelope.schemaVersion !== BACKUP_SCHEMA_VERSION) {
    throw new Error(`仅支持本项目的 v${BACKUP_SCHEMA_VERSION} 备份，旧版及未知版本不再导入`)
  }
  const data = envelope.data
  if (!Array.isArray(data.viewEvents)) throw new Error('备份格式不完整：v2 必须包含 viewEvents 数组')
  return {
    payload: envelope as LegacyBackupImport,
    summary: {
      fileName: file.name,
      mediaFiles: data.mediaFiles?.length ?? 0,
      authors: data.authors?.length ?? 0,
      tags: data.tags?.length ?? 0,
      eventRows: data.viewEvents.length,
    },
  }
}

/** 确认弹窗描述文案 */
export function backupSummaryText(s: BackupSummary): string {
  return (
    `检测到备份数据：${s.mediaFiles.toLocaleString()} 个媒体文件 / ` +
    `${s.authors.toLocaleString()} 位作者 / ${s.tags.toLocaleString()} 个标签 / ` +
    `${s.eventRows.toLocaleString()} 条事件。导入按事件身份和关联规则合并、不删除媒体原件，是否导入恢复？`
  )
}

// ---- 库文件快照调度参数（PUT /api/v1/backups/schedule，2026-10-03 热生效批）----
// 与上面v2 JSON备份同属备份域逻辑层：校验规则在 lib、组件只渲染（铁律 7）。

/** 调度参数合法范围。三处同值：openapi BackupSchedule minimum/maximum、
 * 服务端 backup 包 Min/Max 常量、此处——协议侧改动须三处同步，反之亦然 */
export const BACKUP_SCHEDULE_BOUNDS = {
  intervalHours: { min: 1, max: 8760 },
  retention: { min: 1, max: 365 },
} as const

/** 服务端返回的调度参数（GET /backups 的 schedule；与生成类型 BackupSchedule
 * 同形，这里用结构化字面量避免逻辑层依赖生成类型细节） */
export interface BackupScheduleDraft {
  enabled: boolean
  intervalHours: number
  retention: number
}

/**
 * 调度参数本地校验（与服务端 400 INVALID_PARAM 同口径，先拦省一次白打请求）。
 * 返回 null = 合法；否则返回中文错误信息（数字输入的空串/非数在组件层已挡，
 * 这里兜底范围面）。
 */
export function validateBackupSchedule(draft: BackupScheduleDraft): string | null {
  const { intervalHours, retention } = BACKUP_SCHEDULE_BOUNDS
  if (!Number.isInteger(draft.intervalHours) || draft.intervalHours < intervalHours.min || draft.intervalHours > intervalHours.max) {
    return `快照间隔须在 ${intervalHours.min}–${intervalHours.max} 小时整数`
  }
  if (!Number.isInteger(draft.retention) || draft.retention < retention.min || draft.retention > retention.max) {
    return `保留份数须在 ${retention.min}–${retention.max} 份整数`
  }
  return null
}
