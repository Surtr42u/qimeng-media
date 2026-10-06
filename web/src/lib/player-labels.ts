/**
 * 原生内核控制条的**文案与档位**纯函数（无 DOM、无 IPC；由单测锁定）。
 *
 * 为什么单独成文件：控制条里"1080P 高清"这类标签、倍速文案、时间格式、字幕轨
 * 显示名都是**可判定的映射**，按铁律 3/7 的口径属纯函数——放进组件里就只能靠
 * 肉眼走查。这里全部是纯映射，配 player-labels.test.ts。
 *
 * 清晰度口径（重要）：本项目**明确不做实时转码**（ADR-0002 + 用户拍板「查看永远
 * 发原件」），所以没有转码档位可切——控制条上的「1080P 高清」是**原件真实分辨率**
 * 的展示（数据来自 mpv video-params），菜单里只有一项「原画」。文案对齐用户
 * 2026-10-06 给的外观参照（1080P 粗体 + 高清小字）。
 */

/** 倍速档位（与浏览器模式 ArtPlayer 的 PLAYBACK_RATES 同表；1x 文案「正常」；移除 3x 档） */
export const PLAYBACK_RATES = [0.5, 0.75, 1, 1.25, 1.5, 2] as const

/** 画面比例档位（value 是壳侧 parse_action 白名单里的字面量） */
export const ASPECT_OPTIONS = [
  { value: 'no', label: '默认' },
  { value: '16:9', label: '16:9' },
  { value: '4:3', label: '4:3' },
  { value: 'fill', label: '拉伸铺满' },
] as const

/** 清晰度标签：主文案（粗体）+ 副文案（小字） */
export interface QualityLabel {
  /** 主文案：「1080P」；分辨率未知时空串 */
  main: string
  /** 副文案：「高清」/「超清」/「4K」…；无对应档时空串 */
  tag: string
}

/**
 * 视频**真实高度** → 清晰度标签。
 *
 * 分档按行业惯例（同时也是用户给的外观参照的文案）：≥2160 记 4K 超清、
 * ≥1440 记 2K 超清、≥1080 记 1080P 高清、≥720 记 720P 高清、≥480 记 480P 标清、
 * 其余记「<高>P」不带副标；未就绪（0/NaN/负）返回空标签（控制条显示占位）。
 * 注意用**高度**而不是宽度：宽屏视频（2.35:1）宽度很大但不高，按宽度会把
 * 720p 的宽片记成 1080P。
 */
export function qualityLabel(height: number): QualityLabel {
  if (!Number.isFinite(height) || height <= 0) return { main: '', tag: '' }
  const h = Math.round(height)
  if (h >= 2160) return { main: '4K', tag: '超清' }
  if (h >= 1440) return { main: `${h}P`, tag: '超清' }
  if (h >= 1080) return { main: '1080P', tag: '高清' }
  if (h >= 720) return { main: '720P', tag: '高清' }
  if (h >= 480) return { main: '480P', tag: '标清' }
  return { main: `${h}P`, tag: '' }
}

/** 倍速文案：1x 显示「正常」（旧版 ArtPlayer zh-cn i18n 的原话） */
export function rateLabel(rate: number): string {
  return Math.abs(rate - 1) < 1e-6 ? '正常' : `${rate}x`
}

/**
 * 视频编码 → 显示名（mpv `video-format` 给的是小写短名：h264/hevc/av1/vp9…）。
 * 只做**显示层**归一；未知编码原样大写，不猜（猜错比不显示更糟）。
 */
export function codecLabel(codec: string): string {
  const c = codec.trim().toLowerCase()
  if (!c) return ''
  const known: Record<string, string> = {
    h264: 'H.264',
    avc1: 'H.264',
    hevc: 'H.265',
    h265: 'H.265',
    av1: 'AV1',
    vp9: 'VP9',
    vp8: 'VP8',
    mpeg4: 'MPEG-4',
    mpeg2video: 'MPEG-2',
    wmv3: 'WMV3',
    vc1: 'VC-1',
  }
  return known[c] ?? c.toUpperCase()
}

/**
 * 秒 → ArtPlayer 同款时间串（`mm:ss`，分秒都补前导零；≥1h 走 `h:mm:ss`）。
 *
 * 为什么补零：旧版控制条实测渲染文本是 `00:09 / 02:59`（不是 `0:09 / 2:59`），
 * 差异在像素级复刻里肉眼可见（宽度差 1 个字）。非有限值/负值归 `00:00`。
 */
export function formatTime(sec: number): string {
  if (!Number.isFinite(sec) || sec <= 0) return '00:00'
  const total = Math.floor(sec)
  const s = String(total % 60).padStart(2, '0')
  const m = String(Math.floor(total / 60) % 60).padStart(2, '0')
  const h = Math.floor(total / 3600)
  return h > 0 ? `${h}:${m}:${s}` : `${m}:${s}`
}

/** 常见语言码/英文名到中文的归一映射 */
const LANG_MAP: Record<string, string> = {
  chi: '中文',
  zho: '中文',
  zh: '中文',
  chs: '简体中文',
  cht: '繁体中文',
  chinese: '中文',
  eng: '英语',
  en: '英语',
  english: '英语',
  jpn: '日语',
  ja: '日语',
  japanese: '日语',
  kor: '韩语',
  ko: '韩语',
  korean: '韩语',
  fre: '法语',
  fra: '法语',
  fr: '法语',
  french: '法语',
  ger: '德语',
  deu: '德语',
  de: '德语',
  german: '德语',
  rus: '俄语',
  ru: '俄语',
  russian: '俄语',
  spa: '西班牙语',
  es: '西班牙语',
  spanish: '西班牙语',
}

/** 语言代码或英文名称 → 友好中文名（未知语言原样保留） */
export function humanLanguageLabel(raw: string): string {
  const s = raw.trim().toLowerCase()
  return LANG_MAP[s] ?? raw.trim()
}

/** 一条字幕轨的显示名：标题优先（支持语言归一），其次语言码归一，都没有则「轨道 <id>」 */
export function subtitleLabel(track: { id: number; title?: string; lang?: string }): string {
  const title = (track.title ?? '').trim()
  if (title) return humanLanguageLabel(title)
  const lang = (track.lang ?? '').trim()
  if (lang) return humanLanguageLabel(lang)
  return `轨道 ${track.id}`
}

/** 全角/宽字符判定（CJK 与全角标点：视觉宽度按 2 记，其余按 1） */
function isWide(ch: string): boolean {
  return /[\u1100-\u115F\u2E80-\u9FFF\uA960-\uA97F\uAC00-\uD7A3\uF900-\uFAFF\uFE10-\uFE6F\uFF00-\uFF60\uFFE0-\uFFE6]/.test(
    ch,
  )
}

/** 视觉宽度（CJK=2、拉丁=1）；用于中英混排轨名的截断尺度 */
export function visualWidth(s: string): number {
  let w = 0
  for (const ch of s) w += isWide(ch) ? 2 : 1
  return w
}

/** 字幕入口的副标：有选中轨时显示轨名（按视觉宽度截断，默认 12 个单位），否则空串 */
export function subtitleBadge(
  tracks: { id: number; title?: string; lang?: string }[],
  sid: number,
  maxWidth = 12,
): string {
  if (sid <= 0) return ''
  const hit = tracks.find((t) => t.id === sid)
  if (!hit) return ''
  const label = subtitleLabel(hit)
  if (visualWidth(label) <= maxWidth) return label
  // 截断：留 1 个单位给省略号，宽字符不会被切半
  let out = ''
  let w = 0
  for (const ch of label) {
    const cw = isWide(ch) ? 2 : 1
    if (w + cw > maxWidth - 1) break
    out += ch
    w += cw
  }
  return `${out}…`
}

/** 控制条组件吃的媒体信息（camelCase；壳侧 IPC 是 snake_case，映射只此一处） */
export interface ControlsMediaInfo {
  width: number
  height: number
  codec: string
  fps: number
  aspect: string
  loopFile: boolean
  sid: number
  tracks: { id: number; title: string; lang: string; external: boolean }[]
}

/** 轮询快照的 media 段 → 控制条 media props（缺字段=未就绪，全部走零值兜底） */
export function toMediaInfo(
  m:
    | {
        video_width?: number
        video_height?: number
        video_codec?: string
        fps?: number
        aspect?: string
        loop_file?: boolean
        sid?: number
        tracks?: { id: number; title: string; lang: string; external: boolean }[]
      }
    | undefined,
): ControlsMediaInfo {
  return {
    width: m?.video_width ?? 0,
    height: m?.video_height ?? 0,
    codec: m?.video_codec ?? '',
    fps: m?.fps ?? 0,
    aspect: m?.aspect ?? 'no',
    loopFile: m?.loop_file ?? false,
    sid: m?.sid ?? 0,
    tracks: m?.tracks ?? [],
  }
}
