import { describe, expect, it } from 'vitest'
import {
  MAINTENANCE_JOB_KIND_LABELS,
  MAINTENANCE_JOB_STATUSES,
  MAINTENANCE_JOB_STATUS_LABELS,
  MAINTENANCE_STEP_DETAIL_TEXT_LIMIT,
  MAINTENANCE_STEP_STATUS_LABELS,
  canCancelMaintenanceJob,
  formatArtifactDigest,
  formatMaintenanceTimestamp,
  isMaintenanceJobStatus,
  isMaintenanceJobTerminal,
  maintenanceJobErrorText,
  maintenanceJobProgress,
  maintenanceStepDetailText,
} from './maintenance-jobs'

describe('维护任务展示口径', () => {
  it('任务类型/状态/步骤状态都有中文文案，状态顺序覆盖协议全集', () => {
    expect(Object.keys(MAINTENANCE_JOB_KIND_LABELS).sort()).toEqual(['asset_research', 'author_cleanup', 'backup_export', 'custom'])
    expect(MAINTENANCE_JOB_STATUSES).toHaveLength(14)
    expect(new Set(MAINTENANCE_JOB_STATUSES).size).toBe(14)
    for (const status of MAINTENANCE_JOB_STATUSES) expect(MAINTENANCE_JOB_STATUS_LABELS[status]).toBeTruthy()
    expect(Object.keys(MAINTENANCE_STEP_STATUS_LABELS).sort()).toEqual(['completed', 'failed', 'pending', 'running', 'skipped'])
  })

  it('状态字符串类型守卫生效，未知状态不冒充协议枚举', () => {
    expect(isMaintenanceJobStatus('queued')).toBe(true)
    expect(isMaintenanceJobStatus('waiting_authorization')).toBe(true)
    expect(isMaintenanceJobStatus('unknown_status')).toBe(false)
    expect(isMaintenanceJobStatus('')).toBe(false)
  })

  it('终态不可取消；非终态（含 paused 与 waiting_*）可取消', () => {
    for (const status of ['completed', 'partial', 'failed', 'cancelled'] as const) {
      expect(isMaintenanceJobTerminal(status)).toBe(true)
      expect(canCancelMaintenanceJob(status)).toBe(false)
    }
    for (const status of MAINTENANCE_JOB_STATUSES.filter((item) => !isMaintenanceJobTerminal(item))) {
      expect(canCancelMaintenanceJob(status)).toBe(true)
    }
  })

  it('进度夹取在 0–100 的整数；非法值不伪装成有效进度', () => {
    expect(maintenanceJobProgress(0)).toBe(0)
    expect(maintenanceJobProgress(42.4)).toBe(42)
    expect(maintenanceJobProgress(100)).toBe(100)
    expect(maintenanceJobProgress(101)).toBe(100)
    expect(maintenanceJobProgress(-1)).toBe(0)
    expect(maintenanceJobProgress(Number.NaN)).toBe(0)
    expect(maintenanceJobProgress(Number.POSITIVE_INFINITY)).toBe(0)
  })

  it('时间戳缺失/非法显示破折号，合法 ISO 转本地短时间', () => {
    expect(formatMaintenanceTimestamp()).toBe('—')
    expect(formatMaintenanceTimestamp(null)).toBe('—')
    expect(formatMaintenanceTimestamp('not-a-date')).toBe('—')
    // 本地时区无关断言：只校验非破折号且含「月-日 时:分」空格
    expect(formatMaintenanceTimestamp('2026-11-07T09:05:00Z')).toMatch(/^\d{1,2}-\d{1,2} \d{2}:\d{2}$/)
  })

  it('工件摘要只截短展示，短值/空值原样', () => {
    expect(formatArtifactDigest()).toBe('')
    expect(formatArtifactDigest('  ')).toBe('')
    expect(formatArtifactDigest('abc123')).toBe('abc123')
    expect(formatArtifactDigest('0123456789abcdef')).toBe('0123456789ab…')
  })

  it('错误码与信息合成，缺失字段不留分隔符', () => {
    expect(maintenanceJobErrorText()).toBe('')
    expect(maintenanceJobErrorText(null)).toBe('')
    expect(maintenanceJobErrorText({ code: 'STATE_CONFLICT' })).toBe('STATE_CONFLICT')
    expect(maintenanceJobErrorText({ message: '任务已结束' })).toBe('任务已结束')
    expect(maintenanceJobErrorText({ code: 'INTERNAL', message: '内部错误' })).toBe('INTERNAL：内部错误')
  })

  it('步骤明细空值不渲染；超长只截断展示并注明', () => {
    expect(maintenanceStepDetailText()).toBe('')
    expect(maintenanceStepDetailText({})).toBe('')
    expect(maintenanceStepDetailText({ count: 3 })).toBe('{"count":3}')
    const long = maintenanceStepDetailText({ text: 'x'.repeat(MAINTENANCE_STEP_DETAIL_TEXT_LIMIT + 50) })
    expect(long.endsWith('…（明细过长已截断）')).toBe(true)
    expect(long.length).toBeLessThan(MAINTENANCE_STEP_DETAIL_TEXT_LIMIT + 20)
  })
})
