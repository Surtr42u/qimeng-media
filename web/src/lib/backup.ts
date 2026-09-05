/**
 * 旧版格式备份（qimeng_backup.json）文件解析与校验（DOMAIN_RULES §10）。
 * 逻辑层职责：读文件文本 → JSON 解析 → 格式前置校验（与后端 BAD_REQUEST
 * 同口径，早失败省一次上传）→ 生成确认弹窗摘要计数（对齐旧版 App「检测到
 * 备份数据：X 个媒体文件 / Y 位作者 / Z 个标签 / W 条统计」的确认语义）。
 * 页面组件不内嵌这些规则（铁律 7）。
 */

import type { LegacyBackupImport } from '@/api/generated'

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
