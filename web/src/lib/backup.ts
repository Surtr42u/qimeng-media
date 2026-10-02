/**
 * 旧版格式备份（qimeng_backup.json）文件解析与校验（DOMAIN_RULES §10）。
 * 逻辑层职责：读文件文本 → JSON 解析 → 格式前置校验（与后端 BAD_REQUEST
 * 同口径，早失败省一次上传）→ 生成确认弹窗摘要计数（对齐旧版 App「检测到
 * 备份数据：X 个媒体文件 / Y 位作者 / Z 个标签 / W 条统计」的确认语义）。
 * 页面组件不内嵌这些规则（铁律 7）。
 */

import type { LegacyBackupImport } from '@/api/generated'

/** 备份导入大小上限（与服务端 legacyImportMaxBody 双写，改动须两侧同步）：
 * 服务端超限回 413，但浏览器在上传中途被掐断时只能看到 "Failed to fetch"，
 * 这里前置拦截给可读文案 */
export const BACKUP_MAX_BYTES = 64 * 1024 * 1024

/** 导入确认摘要（计数取备份内原始条数，与旧版恢复确认弹窗同口径） */
export interface LegacyBackupSummary {
  fileName: string
  mediaFiles: number
  authors: number
  tags: number
  /** 统计条数 = mediaStats + dailyBrowse（导入侧转换回放的两大来源） */
  statsRows: number
}

/** 解析并校验旧版备份文件；格式不符/JSON 损坏抛出中文错误信息 */
export async function parseLegacyBackupFile(file: File): Promise<{
  payload: LegacyBackupImport
  summary: LegacyBackupSummary
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
  const envelope = parsed as Partial<LegacyBackupImport>
  if (envelope.format !== 'qimeng_backup' || !envelope.data) {
    throw new Error('备份格式不符：format 必须为 qimeng_backup（旧版「绮梦影库」全量备份）')
  }
  const data = envelope.data
  return {
    payload: envelope as LegacyBackupImport,
    summary: {
      fileName: file.name,
      mediaFiles: data.mediaFiles?.length ?? 0,
      authors: data.authors?.length ?? 0,
      tags: data.tags?.length ?? 0,
      statsRows: (data.mediaStats?.length ?? 0) + (data.dailyBrowse?.length ?? 0),
    },
  }
}

/** 确认弹窗描述文案（旧版恢复确认语义） */
export function backupSummaryText(s: LegacyBackupSummary): string {
  return (
    `检测到备份数据：${s.mediaFiles.toLocaleString()} 个媒体文件 / ` +
    `${s.authors.toLocaleString()} 位作者 / ${s.tags.toLocaleString()} 个标签 / ` +
    `${s.statsRows.toLocaleString()} 条统计。导入按唯一键合并、不删除现有数据，是否导入恢复？`
  )
}

// ---- 库文件快照调度参数（PUT /api/v1/backups/schedule，2026-10-03 热生效批）----
// 与上面旧版 JSON 备份同属备份域逻辑层：校验规则在 lib、组件只渲染（铁律 7）。

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
