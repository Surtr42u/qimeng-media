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
