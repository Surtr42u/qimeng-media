import { describe, expect, it } from 'vitest'
import { provenanceNote } from './provenance'

describe('provenanceNote', () => {
  // 时间用本地时区构造（formatDateTime 按本地时区格式化，断言不依赖运行环境时区）
  const ts = new Date(2026, 9, 1, 12, 30).getTime()

  it('词表内 origin + 时间 → 组合注记「标签 · M-D HH:mm」', () => {
    expect(provenanceNote({ origin: 'client', createdAtMillis: ts })).toBe('客户端 · 10-1 12:30')
    expect(provenanceNote({ origin: 'local-sync', createdAtMillis: ts })).toBe('本机同步 · 10-1 12:30')
  })

  it('仅 origin（时间不可考）只出词表标签', () => {
    expect(provenanceNote({ origin: 'txt', createdAtMillis: null })).toBe('TXT 导入')
    expect(provenanceNote({ origin: 'scanner' })).toBe('扫描器')
  })

  it('仅时间（origin 缺省或词表外）只出时间——词表外值不显示（宁不可考不造假）', () => {
    expect(provenanceNote({ origin: null, createdAtMillis: ts })).toBe('10-1 12:30')
    expect(provenanceNote({ origin: 'unknown-channel', createdAtMillis: ts })).toBe('10-1 12:30')
  })

  it('legacy 哨兵 = 不可考，不产出标签（与 null 同待遇）', () => {
    expect(provenanceNote({ origin: 'legacy', createdAtMillis: null })).toBeNull()
  })

  it('完全不可考（null/缺省/空对象）返回 null——调用方不渲染任何标记', () => {
    expect(provenanceNote(null)).toBeNull()
    expect(provenanceNote(undefined)).toBeNull()
    expect(provenanceNote({})).toBeNull()
    expect(provenanceNote({ origin: null, createdAtMillis: null })).toBeNull()
  })

  it('createdAtMillis ≤0 与缺省同待遇（epoch/负值哨兵，DOMAIN_RULES §10 口径）', () => {
    expect(provenanceNote({ origin: 'client', createdAtMillis: 0 })).toBe('客户端')
    expect(provenanceNote({ origin: 'import', createdAtMillis: -5 })).toBe('备份导入')
  })
})
