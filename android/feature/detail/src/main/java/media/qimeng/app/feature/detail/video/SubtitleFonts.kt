package media.qimeng.app.feature.detail.video

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.os.Build
import media.qimeng.app.feature.detail.R

/**
 * 字幕随包字体（2026-11-28 第二批，用户拍板「三端观感一致」）。
 *
 * 问题：.ass 里写的 Fontname 是 `Microsoft YaHei`（中文）/ `Arial`（英文），
 * Windows 有、Android 不一定有，会各自回落成不同字体。做法：把两份 OFL 字体
 * 随包分发（res/font，SHA-256 与许可证见 docs/FONTS.md），在视图层把
 * SubtitleView 的默认 typeface 指过去。
 *
 * 与 Web 端对齐方式：Web 用 @font-face 把 `Arial` 指到 Liberation Sans Bold、
 * `Microsoft YaHei` 指到 Noto Sans SC Bold；Android 用 Typeface 备用链复刻
 * 「拉丁字形优先 Liberation、缺字回退 Noto」——Latin 走 Arial 度量、汉字走
 * Noto，同一份 .ass 的宽度/换行/观感与 Web 基本一致（允许亚像素差异）。
 *
 * 取舍（必须记录）：Media3 1.11 的 SSA 解析器只产出颜色/粗斜体等 span，**不保留
 * Fontname**，也没有按名注入字体的公开 API（libass 才有）。因此无法在 Android
 * 上做到「按 Fontname 逐行分发」；这里用视图级 typeface 覆盖——英文也走
 * Liberation、中文走 Noto，与 Web 端两套字体分工的差异仅限同一 cue 内中西文
 * 混排时的字距微差。
 */
private var cachedSubtitleTypeface: Typeface? = null

/** 取字幕渲染用 typeface（进程内缓存；只在主线程构造，BiliPlayerView 初始化路径）。 */
internal fun subtitleTypeface(context: Context): Typeface {
    cachedSubtitleTypeface?.let { return it }
    // res/font 走资源加载（API 26+ 的 Resources.getFont），不依赖 assets 是否被压缩。
    val cjk = context.resources.getFont(R.font.noto_sans_sc_bold)
    val typeface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // API 29+：CustomFallbackBuilder 做主/备族链（拉丁族在前，汉字缺字回退 CJK 族）。
        // 构造失败（厂商字体栈差异/资源异常）时退回 CJK 单族，不影响可读性。
        runCatching {
            val latinFont = Font.Builder(context.resources, R.font.liberation_sans_bold).build()
            val cjkFont = Font.Builder(context.resources, R.font.noto_sans_sc_bold).build()
            Typeface.CustomFallbackBuilder(FontFamily.Builder(latinFont).build())
                .addCustomFallback(FontFamily.Builder(cjkFont).build())
                .build()
        }.getOrDefault(cjk)
    } else {
        // API 26-28 没有按字形回退的自定义族接口：统一用 Noto（含拉丁字形）。
        cjk
    }
    cachedSubtitleTypeface = typeface
    return typeface
}
