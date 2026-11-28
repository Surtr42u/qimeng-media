package media.qimeng.app.feature.detail.video

import androidx.media3.common.MimeTypes
import media.qimeng.app.core.model.DetailSubtitle
import media.qimeng.app.core.model.SubtitleFormat

/**
 * 外挂字幕装配的纯映射（2026-11-28 外挂字幕批）：格式 → Media3 外部字幕 MIME、
 * 默认语言判定。抽成纯函数以便 JVM 单测锁定口径（MediaItem 装配依赖
 * android.net.Uri，留在 [VideoPlayerState] 不进单测）。
 */

/** 默认选中的语言主标签（用户口径：中文轨优先，没有则默认关闭——与 Web 端同口径） */
internal const val DEFAULT_SUBTITLE_LANG = "zh"

/**
 * 格式 → Media3 外部字幕 MIME。
 *
 * ass/ssa 共用 `MimeTypes.TEXT_SSA`：Media3 的 DefaultSubtitleParserFactory 对
 * text/x-ssa 走 SsaParser（含样式/定位/描边解析），ASS 与 SSA 是同一格式族。
 * srt/vtt 用各自的官方 MIME，避免解码器按内容猜格式。
 */
internal fun subtitleMimeType(format: SubtitleFormat): String = when (format) {
    SubtitleFormat.ASS, SubtitleFormat.SSA -> MimeTypes.TEXT_SSA
    SubtitleFormat.SRT -> MimeTypes.APPLICATION_SUBRIP
    SubtitleFormat.VTT -> MimeTypes.TEXT_VTT
}

/**
 * 是否默认选中的中文轨：BCP 47 主标签比较（zh / zh-Hans / zh-CN 均命中），
 * 大小写不敏感（语言标签本身大小写不敏感）。
 */
internal fun isDefaultSubtitleLang(lang: String): Boolean =
    lang.substringBefore('-').equals(DEFAULT_SUBTITLE_LANG, ignoreCase = true)

/**
 * 语言码 → 字幕菜单展示名（与 Web 端 externalSubtitleLabel 的语言表同口径；
 * 未知语言原样保留，不猜测）。用于设置面板「字幕」轨选择。
 */
internal fun subtitleLangLabel(lang: String): String =
    when (lang.trim().lowercase().substringBefore('-')) {
        "zh" -> "中文"
        "en" -> "英语"
        "ja" -> "日语"
        "ko" -> "韩语"
        else -> lang
    }

/** 默认轨：第一条中文轨；无中文回 null = 不预选（播放器保持原内嵌轨行为）。 */
internal fun defaultSubtitle(subtitles: List<DetailSubtitle>): DetailSubtitle? =
    subtitles.firstOrNull { isDefaultSubtitleLang(it.lang) }
