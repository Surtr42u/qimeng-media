/**
 * 外挂字幕传输层纯函数单测（2026-11-28 外挂字幕批）：
 * SRT→VTT 时间轴归一与中文默认轨判定（与 Android 端同口径）。
 */
import { describe, expect, it } from 'vitest'
import { isChineseSubtitle, srtToVtt } from './external-subtitles'

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
