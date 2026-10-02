import { describe, expect, it } from 'vitest'
import {
  BACKUP_MAX_BYTES,
  backupSummaryText,
  parseLegacyBackupFile,
  validateBackupSchedule,
  type LegacyBackupSummary,
} from './backup'

/** 旧版备份的最小合法载荷（DOMAIN_RULES §10 17 段 schema 的截断版——
 *  校验只看 envelope.format + envelope.data，段内字段缺省=计数 0） */
function backupJson(data: Record<string, unknown> = {}): string {
  return JSON.stringify({ format: 'qimeng_backup', version: 1, exportedAtMillis: 1, data })
}

/** File 替身：parseLegacyBackupFile 只消费 name/size/text 三员，鸭子类型
 *  即可（size 可虚报——测超限拦截不必真的构造 64MB 内容） */
function fakeFile(text: string, name = 'qimeng_backup.json', size?: number): File {
  return { name, size: size ?? text.length, text: async () => text } as unknown as File
}

describe('parseLegacyBackupFile', () => {
  it('合法备份：解析成功 + 摘要计数（mediaStats+dailyBrowse 合计为统计条数）', async () => {
    const payload = backupJson({
      mediaFiles: [{}, {}, {}],
      authors: [{}],
      tags: [{}, {}, {}, {}],
      mediaStats: [{}, {}],
      dailyBrowse: [{}],
    })
    const { payload: p, summary } = await parseLegacyBackupFile(fakeFile(payload))
    expect(p.format).toBe('qimeng_backup')
    expect(summary).toEqual({
      fileName: 'qimeng_backup.json',
      mediaFiles: 3,
      authors: 1,
      tags: 4,
      statsRows: 3,
    })
  })

  it('段缺省：计数全 0（不抛错——旧版各段可选）', async () => {
    const { summary } = await parseLegacyBackupFile(fakeFile(backupJson({})))
    expect(summary.mediaFiles).toBe(0)
    expect(summary.authors).toBe(0)
    expect(summary.tags).toBe(0)
    expect(summary.statsRows).toBe(0)
  })

  it('超限前置拦截：给可读中文错误（服务端 413 浏览器只见 Failed to fetch 的补救）', async () => {
    const big = fakeFile('{}', 'big.json', BACKUP_MAX_BYTES + 1)
    await expect(parseLegacyBackupFile(big)).rejects.toThrow(/超过 \d+MB 上限/)
  })

  it('非合法 JSON：中文错误引导确认文件', async () => {
    await expect(parseLegacyBackupFile(fakeFile('{oops'))).rejects.toThrow('文件不是合法 JSON')
  })

  it('format 不符 / data 缺失：格式校验拒绝', async () => {
    await expect(parseLegacyBackupFile(fakeFile('{"format":"other","data":{}}'))).rejects.toThrow('备份格式不符')
    await expect(parseLegacyBackupFile(fakeFile('{"format":"qimeng_backup"}'))).rejects.toThrow('备份格式不符')
  })
})

describe('backupSummaryText', () => {
  it('对齐旧版确认弹窗文案（四段计数 + 合并不删除说明）', () => {
    const s: LegacyBackupSummary = {
      fileName: 'b.json', mediaFiles: 1234, authors: 56, tags: 7, statsRows: 89,
    }
    expect(backupSummaryText(s)).toBe(
      '检测到备份数据：1,234 个媒体文件 / 56 位作者 / 7 个标签 / 89 条统计。导入按唯一键合并、不删除现有数据，是否导入恢复？',
    )
  })
})

describe('validateBackupSchedule', () => {
  it('合法值（含两端边界）返回 null', () => {
    expect(validateBackupSchedule({ enabled: true, intervalHours: 24, retention: 7 })).toBeNull()
    expect(validateBackupSchedule({ enabled: false, intervalHours: 1, retention: 1 })).toBeNull()
    expect(validateBackupSchedule({ enabled: true, intervalHours: 8760, retention: 365 })).toBeNull()
  })
  it('间隔越界/非整数被拦（与服务端 400 INVALID_PARAM 同口径）', () => {
    expect(validateBackupSchedule({ enabled: true, intervalHours: 0, retention: 7 })).toMatch(/间隔/)
    expect(validateBackupSchedule({ enabled: true, intervalHours: 8761, retention: 7 })).toMatch(/间隔/)
    expect(validateBackupSchedule({ enabled: true, intervalHours: 1.5, retention: 7 })).toMatch(/间隔/)
  })
  it('保留份数越界/非整数被拦', () => {
    expect(validateBackupSchedule({ enabled: true, intervalHours: 24, retention: 0 })).toMatch(/保留/)
    expect(validateBackupSchedule({ enabled: true, intervalHours: 24, retention: 366 })).toMatch(/保留/)
    expect(validateBackupSchedule({ enabled: true, intervalHours: 24, retention: 2.5 })).toMatch(/保留/)
  })
})
