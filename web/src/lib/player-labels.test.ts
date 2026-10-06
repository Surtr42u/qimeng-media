/**
 * 控制条文案/档位纯函数锁定（清晰度分档、倍速文案、时间格式、字幕轨显示名）。
 *
 * 清晰度分档是本批最容易被"顺手改成按宽度"的一处：宽片（2.35:1）宽度大而不高，
 * 按宽度会把 720p 记成 1080P——故用一条宽片用例把这个口径钉死。
 */
import { describe, expect, it } from 'vitest'
import {
  ASPECT_OPTIONS,
  PLAYBACK_RATES,
  codecLabel,
  formatTime,
  qualityLabel,
  rateLabel,
  subtitleBadge,
  subtitleLabel,
  toMediaInfo,
} from './player-labels'

describe('qualityLabel（原件真实分辨率 → 清晰度标签）', () => {
  it('主档位：4K/2K/1080P/720P/480P 分档与副标', () => {
    expect(qualityLabel(2160)).toEqual({ main: '4K', tag: '超清' })
    expect(qualityLabel(1440)).toEqual({ main: '1440P', tag: '超清' })
    expect(qualityLabel(1080)).toEqual({ main: '1080P', tag: '高清' })
    expect(qualityLabel(720)).toEqual({ main: '720P', tag: '高清' })
    expect(qualityLabel(480)).toEqual({ main: '480P', tag: '标清' })
  })

  it('宽片按高度分档：1920×800（2.4:1）不是 1080P —— 按宽度分档就会错记', () => {
    expect(qualityLabel(800)).toEqual({ main: '720P', tag: '高清' })
  })

  it('界内非整数（如 1088 = 1080 编码对齐高度）落在对应档', () => {
    expect(qualityLabel(1088)).toEqual({ main: '1080P', tag: '高清' })
    expect(qualityLabel(1079).main).toBe('720P')
  })

  it('低位档不带副标；末档保留真实高度', () => {
    expect(qualityLabel(360)).toEqual({ main: '360P', tag: '' })
    expect(qualityLabel(240)).toEqual({ main: '240P', tag: '' })
  })

  it('未就绪/脏值（0/负/NaN/∞）返回空标签，控制条显示占位', () => {
    for (const bad of [0, -1, Number.NaN, Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY]) {
      expect(qualityLabel(bad)).toEqual({ main: '', tag: '' })
    }
  })
})

describe('rateLabel（倍速文案）', () => {
  it('1x 记「正常」（旧版 ArtPlayer zh-cn 原话），其余记 n x', () => {
    expect(rateLabel(1)).toBe('正常')
    expect(rateLabel(0.75)).toBe('0.75x')
    expect(rateLabel(1.25)).toBe('1.25x')
    expect(rateLabel(3)).toBe('3x')
  })

  it('浮点误差容忍：1.0000001 仍记「正常」', () => {
    expect(rateLabel(1.0000001)).toBe('正常')
  })
})

describe('formatTime（时间文案）', () => {
  it('ArtPlayer 同款：分秒都补零（实测旧版渲染是 00:09 / 02:59）；≥1 小时走 h:mm:ss', () => {
    expect(formatTime(0)).toBe('00:00')
    expect(formatTime(5)).toBe('00:05')
    expect(formatTime(9)).toBe('00:09')
    expect(formatTime(67)).toBe('01:07')
    expect(formatTime(179)).toBe('02:59')
    expect(formatTime(3725)).toBe('1:02:05')
  })

  it('小数向下取整；脏值归 00:00', () => {
    expect(formatTime(59.9)).toBe('00:59')
    expect(formatTime(-3)).toBe('00:00')
    expect(formatTime(Number.NaN)).toBe('00:00')
  })
})

describe('subtitleLabel / subtitleBadge（字幕轨显示名）', () => {
  it('标题优先（语言归一），其次语言码归一，最后兜底「轨道 <id>」', () => {
    expect(subtitleLabel({ id: 3, title: '简体中文', lang: 'chi' })).toBe('简体中文')
    expect(subtitleLabel({ id: 3, title: '  ', lang: 'chi' })).toBe('中文')
    expect(subtitleLabel({ id: 3, title: 'Chinese', lang: '' })).toBe('中文')
    expect(subtitleLabel({ id: 3, title: '', lang: 'eng' })).toBe('英语')
    expect(subtitleLabel({ id: 3 })).toBe('轨道 3')
  })

  it('入口副标只在选中轨存在时给出，且按视觉宽度截断（CJK 记 2）', () => {
    const tracks = [{ id: 2, title: 'Chinese' }, { id: 3, title: '很长很长的字幕轨名字' }]
    expect(subtitleBadge(tracks, 2)).toBe('中文')
    expect(subtitleBadge(tracks, 3)).toBe('很长很长的…')
    expect(subtitleBadge(tracks, 0)).toBe('')
    expect(subtitleBadge(tracks, 9)).toBe('')
    // 阈值可调：给足宽度就不截断
    expect(subtitleBadge(tracks, 3, 40)).toBe('很长很长的字幕轨名字')
  })
})

describe('档位表常量（与浏览器模式同表，防手抄漂移）', () => {
  it('倍速六档含 1x；比例四档首项为默认', () => {
    expect([...PLAYBACK_RATES]).toEqual([0.5, 0.75, 1, 1.25, 1.5, 2])
    expect(PLAYBACK_RATES).toContain(1)
    expect(ASPECT_OPTIONS[0]).toEqual({ value: 'no', label: '默认' })
    expect(ASPECT_OPTIONS.map((o) => o.value)).toEqual(['no', '16:9', '4:3', 'fill'])
  })
})

describe('codecLabel（编码显示名）', () => {
  it('常见编码走对照表；未知原样大写；空串空出', () => {
    expect(codecLabel('h264')).toBe('H.264')
    expect(codecLabel('HEVC')).toBe('H.265')
    expect(codecLabel('av1')).toBe('AV1')
    expect(codecLabel('mpeg4')).toBe('MPEG-4')
    expect(codecLabel('theora')).toBe('THEORA')
    expect(codecLabel('  ')).toBe('')
  })
})

describe('toMediaInfo（IPC snake_case → 控制条 props）', () => {
  it('缺段/未就绪一律零值兜底（控制条显示占位而不是 NaN）', () => {
    expect(toMediaInfo(undefined)).toEqual({
      width: 0,
      height: 0,
      codec: '',
      fps: 0,
      aspect: 'no',
      loopFile: false,
      sid: 0,
      tracks: [],
    })
    expect(toMediaInfo({})).toEqual(toMediaInfo(undefined))
  })

  it('字段逐个搬运（含字幕轨与外挂标志）', () => {
    const info = toMediaInfo({
      video_width: 1920,
      video_height: 1080,
      video_codec: 'h264',
      fps: 25,
      aspect: '16:9',
      loop_file: true,
      sid: 2,
      tracks: [{ id: 2, title: 'Chinese', lang: 'chi', external: false }],
    })
    expect(info.width).toBe(1920)
    expect(info.height).toBe(1080)
    expect(info.aspect).toBe('16:9')
    expect(info.loopFile).toBe(true)
    expect(info.sid).toBe(2)
    expect(info.tracks).toHaveLength(1)
    expect(qualityLabel(info.height)).toEqual({ main: '1080P', tag: '高清' })
  })
})
