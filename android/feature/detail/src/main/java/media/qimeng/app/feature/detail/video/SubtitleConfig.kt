package media.qimeng.app.feature.detail.video

import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
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

/**
 * 默认轨：服务端 isDefault 优先（三端同源，简体 > 繁体）；旧服务端缺该字段时
 * 回退「第一条中文轨」；都没有返回 null = 不预选（播放器保持原内嵌轨行为）。
 */
internal fun defaultSubtitle(subtitles: List<DetailSubtitle>): DetailSubtitle? =
    subtitles.firstOrNull { it.isDefault } ?: subtitles.firstOrNull { isDefaultSubtitleLang(it.lang) }

/**
 * 字幕首帧尺寸门（2026-11-28 用户真机反馈）：Media3 的 SSA 解析器把 ASS
 * `Fontsize / PlayResY` 存成 cue 的相对字号，[androidx.media3.ui.SubtitleView]
 * 再按**自身 View 高度**换算像素。首帧视频尺寸尚未回调时，PlayerView 的
 * `exo_content_frame` aspectRatio=0，SubtitleView 铺满整个舞台（远高于 16:9
 * 画面），字号按舞台高度算出来会是正常值的 3~4 倍，左侧还会被裁掉；等
 * `onVideoSizeChanged` 把画面比例落到布局后，同一条 cue 重绘才恢复正常。
 *
 * 因此视频尺寸未知（或播放器仍在 IDLE、PlayerView 不会采用尺寸）时先不显示
 * SubtitleView，宁可首帧无字幕也不画错尺寸。上层 [BiliPlayerView] 在
 * `onVideoSizeChanged` / READY / 挂载已有视频尺寸的播放器时复查本判定。
 *
 * 与 PlayerView 自身 `ComponentListener.onVideoSizeChanged` 的门禁同口径：
 * UNKNOWN 或 STATE_IDLE 时它不会调用 updateAspectRatio。
 */
internal fun subtitleViewportReady(width: Int, height: Int, playbackState: Int): Boolean =
    width > 0 && height > 0 && playbackState != Player.STATE_IDLE
