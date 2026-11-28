/**
 * 外挂字幕传输层（2026-11-28 外挂字幕批）：拉取 sidecar 正文 + SRT→WebVTT 归一 +
 * 随包字体预载 + 默认轨判定。
 *
 * 为什么放在 lib 而不是播放器组件：Web 架构门禁（tests/architecture.test.ts）禁止
 * `src/components/**` 直接发请求（UI 不直接请求 API / 不绕过静态依赖）。字幕正文
 * 走的是**服务端签好的媒体直链**（`/media/sub/...?exp=&sig=`，与 `<video src>`
 * 同款鉴权、无需 header），不是 JSON API，无法也不该经生成 SDK；把它收在这一层
 * 与播放器组件分离，既守住门禁也保留单一入口。
 *
 * ASS/SSA 解析与渲染在组件侧（assjs），本文件不依赖 DOM。
 */

/** 中文主语言标签（zh / zh-Hans / zh-CN；服务端未给 isDefault 时的兜底口径） */
export function isChineseSubtitle(lang: string): boolean {
  return lang.trim().toLowerCase().split('-')[0] === 'zh'
}

/**
 * SRT → WebVTT：浏览器只原生渲染 VTT。SRT 与 VTT 的差异仅时间轴的
 * `,` 毫秒分隔（其余序号/`<i>`/`<b>` 标签 WebVTT 兼容），故只做该替换，
 * 不重写正文——避免二次解析改变用户字幕文本。
 */
export function srtToVtt(srt: string): string {
  const body = srt
    .replace(/^\uFEFF/, '')
    .replace(/\r\n/g, '\n')
    .replace(/(\d{2}:\d{2}:\d{2}),(\d{3})/g, '$1.$2')
  return `WEBVTT\n\n${body}`
}

/** 拉字幕正文：签名 URL 原样请求（与 <video src> 同款鉴权，无需额外 header）。 */
export async function fetchSubtitleText(url: string): Promise<string> {
  const res = await fetch(url)
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.text()
}

/**
 * 字幕随包字体预载（2026-11-28 第二批）：assjs 在构造时按 CSS 家族名量字体度量，
 * 字体未就绪会先按系统回落字体量宽、渲染位置/行高跑偏。渲染 ASS 前必须等
 * document.fonts.load 完成（@font-face 见 styles/subtitle-fonts.css）。
 * 失败（字体缺失/老浏览器）不阻塞字幕——退回系统字体，观感降级但不空白。
 */
let subtitleFontsPromise: Promise<void> | null = null

export function ensureSubtitleFonts(): Promise<void> {
  if (subtitleFontsPromise) return subtitleFontsPromise
  if (typeof document === 'undefined' || !document.fonts) {
    subtitleFontsPromise = Promise.resolve()
    return subtitleFontsPromise
  }
  subtitleFontsPromise = Promise.all([
    document.fonts.load('700 16px "Microsoft YaHei"'),
    document.fonts.load('700 16px "微软雅黑"'),
    document.fonts.load('700 16px "Arial"'),
  ])
    .then(() => undefined)
    .catch(() => undefined)
  return subtitleFontsPromise
}

/**
 * 默认外挂轨下标：服务端 isDefault 优先（三端同源）；旧服务端无该字段时退回
 * 「第一条中文轨」。都没有返回 -1（保持关闭，内嵌轨行为不变）。
 */
export function defaultSubtitleIndex(subtitles: { lang: string; isDefault?: boolean }[]): number {
  const marked = subtitles.findIndex((sub) => sub.isDefault === true)
  if (marked >= 0) return marked
  return subtitles.findIndex((sub) => isChineseSubtitle(sub.lang))
}

/** 字幕菜单关闭档的值（恒第一项） */
export const SUBTITLE_OFF = 'off'

/** 外挂轨菜单值前缀（后接服务端 subtitles 数组下标） */
export const EXTERNAL_PREFIX = 'ext:'

/** 内嵌轨菜单值前缀（后接 video.textTracks 过滤后的下标） */
export const EMBEDDED_PREFIX = 'emb:'

/**
 * 字幕挂载令牌（2026-11-28 审查修复）：每次 applySubtitle 递增，异步回调只认自己那次
 * 的令牌。只比较“当前选中值”挡不住 A→关闭→A：旧请求回来时值又变成 A，会再挂一层
 * assjs/track，旧节点无引用则关不掉。纯逻辑，单测覆盖连续切轨序列。
 */
export interface SubtitleApplyGate {
  /** 开始一次新的应用，返回本次令牌（单调递增） */
  begin(): number
  /** 该令牌是否仍是最近一次应用（false = 已被后续选择取代） */
  isCurrent(token: number): boolean
}

export function createSubtitleApplyGate(): SubtitleApplyGate {
  let current = 0
  return {
    begin: () => {
      current += 1
      return current
    },
    isCurrent: (token) => token === current,
  }
}

export interface SubtitleMenuEntry {
  /** 选项值：off / ext:<i> / emb:<i> */
  value: string
  /** 菜单与按钮展示名（服务端 label 或本地回退） */
  label: string
  /** 是否默认选中（仅外挂轨可能命中，直接采用 defaultSubtitleIndex 结果） */
  isDefault: boolean
}

/**
 * 字幕菜单条目装配（2026-11-28 播控修复）：`关闭` 恒第一项、外挂在前、内嵌在后，
 * 默认标记只落在服务端/中文兜底选出的外挂轨上。抽成纯函数锁定顺序与默认语义，
 * 组件只负责 DOM 渲染与点击应用（applySubtitle 的 ext:/emb: 值编码不变）。
 */
export function buildSubtitleMenu(
  external: readonly { label: string }[],
  embedded: readonly { label: string }[],
  defaultExternalIndex: number,
): { entries: SubtitleMenuEntry[]; defaultLabel: string } {
  const entries: SubtitleMenuEntry[] = [
    { value: SUBTITLE_OFF, label: '关闭', isDefault: defaultExternalIndex < 0 },
  ]
  external.forEach((sub, index) => {
    entries.push({
      value: EXTERNAL_PREFIX + index,
      label: sub.label,
      isDefault: index === defaultExternalIndex,
    })
  })
  embedded.forEach((track, index) => {
    entries.push({
      value: EMBEDDED_PREFIX + index,
      label: track.label,
      isDefault: false,
    })
  })
  const defaultLabel =
    defaultExternalIndex >= 0 && external[defaultExternalIndex]
      ? external[defaultExternalIndex].label
      : '字幕'
  return { entries, defaultLabel }
}
