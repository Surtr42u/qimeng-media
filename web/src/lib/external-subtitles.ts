/**
 * 外挂字幕传输层（2026-11-28 外挂字幕批）：拉取 sidecar 正文 + SRT→WebVTT 归一 +
 * 中文默认轨判定。
 *
 * 为什么放在 lib 而不是播放器组件：Web 架构门禁（tests/architecture.test.ts）禁止
 * `src/components/**` 直接发请求（UI 不直接请求 API / 不绕过静态依赖）。字幕正文
 * 走的是**服务端签好的媒体直链**（`/media/sub/...?exp=&sig=`，与 `<video src>`
 * 同款鉴权、无需 header），不是 JSON API，无法也不该经生成 SDK；把它收在这一层
 * 与播放器组件分离，既守住门禁也保留单一入口。
 *
 * ASS/SSA 解析与渲染在组件侧（assjs），本文件不依赖 DOM。
 */

/** 中文主语言标签（zh / zh-Hans / zh-CN；与 Android 端默认轨同口径） */
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
