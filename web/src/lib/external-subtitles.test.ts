/**
 * 外挂字幕传输层纯函数单测（2026-11-28 外挂字幕批）：
 * SRT→VTT 时间轴归一与中文默认轨判定（与 Android 端同口径）。
 */
import { describe, expect, it } from 'vitest'
import {
  buildSubtitleMenu,
  defaultSubtitleIndex,
  createSubtitleApplyGate,
  EMBEDDED_PREFIX,
  EXTERNAL_PREFIX,
  isChineseSubtitle,
  srtToVtt,
  SUBTITLE_OFF,
} from './external-subtitles'

describe('srtToVtt（SRT 时间轴 → WebVTT）', () => {
  it('只把毫秒逗号换成点，正文与序号原样保留', () => {
    const srt = '1\n00:00:01,000 --> 00:00:02,500\n你好 <i>世界</i>\n'
    expect(srtToVtt(srt)).toBe('WEBVTT\n\n1\n00:00:01.000 --> 00:00:02.500\n你好 <i>世界</i>\n')
  })

  it('去 BOM 并把 CRLF 归一为 LF（Windows 生成的 SRT 常见）', () => {
    const srt = '\uFEFF1\r\n00:00:03,000 --> 00:00:04,000\r\n行一\r\n'
    expect(srtToVtt(srt)).toBe('WEBVTT\n\n1\n00:00:03.000 --> 00:00:04.000\n行一\n')
  })
})

describe('isChineseSubtitle（默认轨判定）', () => {
  it('zh 主标签命中（含 BCP 47 子标签，大小写不敏感）', () => {
    expect(isChineseSubtitle('zh')).toBe(true)
    expect(isChineseSubtitle('ZH')).toBe(true)
    expect(isChineseSubtitle('zh-Hans')).toBe(true)
    expect(isChineseSubtitle('zh-CN')).toBe(true)
  })

  it('其他语言/空串不命中（不猜 zho/yue 等别名）', () => {
    expect(isChineseSubtitle('en')).toBe(false)
    expect(isChineseSubtitle('zho')).toBe(false)
    expect(isChineseSubtitle('')).toBe(false)
  })
})

describe('defaultSubtitleIndex（默认轨选择）', () => {
  it('服务端 isDefault 优先（三端同源）', () => {
    expect(defaultSubtitleIndex([{ lang: 'en' }, { lang: 'zh-Hans', isDefault: true }])).toBe(1)
    // 即便排序在前的是中文，也认服务端标记（繁体被标默认时同样生效）
    expect(defaultSubtitleIndex([{ lang: 'zh-Hans' }, { lang: 'zh-Hant', isDefault: true }])).toBe(1)
  })

  it('旧服务端无 isDefault 时回退第一条中文轨；无中文返回 -1', () => {
    expect(defaultSubtitleIndex([{ lang: 'en' }, { lang: 'zh-Hans' }])).toBe(1)
    expect(defaultSubtitleIndex([{ lang: 'en' }, { lang: 'ja' }])).toBe(-1)
    expect(defaultSubtitleIndex([])).toBe(-1)
  })
})

describe('buildSubtitleMenu（播控菜单顺序与默认语义）', () => {
  it('关闭恒第一项，外挂在前、内嵌在后，值编码保持 ext:/emb:', () => {
    const menu = buildSubtitleMenu(
      [{ label: 'English' }, { label: '中文（简体）' }],
      [{ label: '内嵌中文' }],
      1,
    )
    expect(menu.entries.map((entry) => entry.value)).toEqual([
      SUBTITLE_OFF,
      EXTERNAL_PREFIX + '0',
      EXTERNAL_PREFIX + '1',
      EMBEDDED_PREFIX + '0',
    ])
    expect(menu.entries.map((entry) => entry.label)).toEqual([
      '关闭',
      'English',
      '中文（简体）',
      '内嵌中文',
    ])
    // 默认标记只落外挂默认轨；关闭与内嵌不抢
    expect(menu.entries.filter((entry) => entry.isDefault).map((entry) => entry.value)).toEqual([
      EXTERNAL_PREFIX + '1',
    ])
    expect(menu.defaultLabel).toBe('中文（简体）')
  })

  it('无默认外挂轨时关闭项为默认，按钮回退「字幕」', () => {
    const menu = buildSubtitleMenu([{ label: 'English' }], [], -1)
    expect(menu.entries[0]).toEqual({ value: SUBTITLE_OFF, label: '关闭', isDefault: true })
    expect(menu.defaultLabel).toBe('字幕')
  })
})

describe('createSubtitleApplyGate（外挂层并发挂载防护）', () => {
  it('A→关闭→A 序列只有最后一次令牌生效，旧异步回调不得再挂层', () => {
    const gate = createSubtitleApplyGate()
    const firstA = gate.begin()
    const off = gate.begin()
    const secondA = gate.begin()
    // 旧请求（A 与关闭）回来时都已失效；即便选中值又变回 A 也不能放行
    expect(gate.isCurrent(firstA)).toBe(false)
    expect(gate.isCurrent(off)).toBe(false)
    expect(gate.isCurrent(secondA)).toBe(true)
  })

  it('单调递增且每次 apply 都换新令牌（同值重复选择也算新一次）', () => {
    const gate = createSubtitleApplyGate()
    const first = gate.begin()
    const second = gate.begin()
    expect(second).toBeGreaterThan(first)
    expect(gate.isCurrent(first)).toBe(false)
    expect(gate.isCurrent(second)).toBe(true)
  })
})
