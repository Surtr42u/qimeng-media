import { describe, expect, it } from 'vitest'
import {
  batchFailureReason,
  batchPercent,
  failureListText,
  formatBatchSummary,
  type BatchFailure,
} from './batch'

const fail = (id: string, label: string, reason: string): BatchFailure => ({ id, label, reason })

describe('batchPercent', () => {
  it('常规换算并取整（0–100）', () => {
    expect(batchPercent(0, 10)).toBe(0)
    expect(batchPercent(1, 3)).toBe(33)
    expect(batchPercent(2, 3)).toBe(67)
    expect(batchPercent(10, 10)).toBe(100)
  })

  it('total≤0 防御兜底为 100；done 超发不越 100', () => {
    expect(batchPercent(0, 0)).toBe(100)
    expect(batchPercent(5, 0)).toBe(100)
    expect(batchPercent(11, 10)).toBe(100)
  })
})

describe('batchFailureReason', () => {
  it('生成 client 错误体形状对象（非 Error）取 message', () => {
    expect(batchFailureReason({ code: 'CONFLICT', message: '目标位置已有同名文件', status: 409 })).toBe(
      '目标位置已有同名文件',
    )
  })

  it('message 为空串/缺失时兜底', () => {
    expect(batchFailureReason({ message: '' })).toBe('[object Object]')
    expect(batchFailureReason({})).toBe('[object Object]')
    expect(batchFailureReason(new Error('网络错误'))).toBe('网络错误')
    expect(batchFailureReason('直接字符串')).toBe('直接字符串')
  })
})

describe('formatBatchSummary', () => {
  it('全部成功：只报成功数', () => {
    expect(formatBatchSummary('移入回收站', { succeeded: 3, failures: [] })).toBe(
      '移入回收站完成：成功 3 项',
    )
  })

  it('部分失败：成功数 + 失败数并列', () => {
    expect(
      formatBatchSummary('批量移动', { succeeded: 2, failures: [fail('a', 'x.jpg', '409 冲突')] }),
    ).toBe('批量移动完成：成功 2 项，失败 1 项')
  })

  it('全部失败：成功 0 项也如实报', () => {
    expect(
      formatBatchSummary('批量恢复', { succeeded: 0, failures: [fail('a', 'x.jpg', 'e')] }),
    ).toBe('批量恢复完成：成功 0 项，失败 1 项')
  })
})

describe('failureListText', () => {
  it('不超 max：逐行列出，无折叠行', () => {
    const text = failureListText([fail('a', 'a.jpg', '冲突'), fail('b', 'b.jpg', '超时')])
    expect(text).toBe('· a.jpg：冲突\n· b.jpg：超时')
  })

  it('超 max：只列前 max 行 + 折叠提示行', () => {
    const failures = Array.from({ length: 7 }, (_, i) => fail(String(i), `f${i}.jpg`, 'e'))
    const text = failureListText(failures, 5)
    const lines = text.split('\n')
    expect(lines).toHaveLength(6)
    expect(lines[0]).toBe('· f0.jpg：e')
    expect(lines[4]).toBe('· f4.jpg：e')
    expect(lines[5]).toBe('…另有 2 项失败（见页内失败列表）')
  })

  it('恰好 max：不出现折叠行', () => {
    const failures = Array.from({ length: 5 }, (_, i) => fail(String(i), `f${i}.jpg`, 'e'))
    expect(failureListText(failures, 5).split('\n')).toHaveLength(5)
  })
})
