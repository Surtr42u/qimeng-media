package media.qimeng.app.feature.detail.video

import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import media.qimeng.app.core.model.DetailSubtitle
import media.qimeng.app.core.model.SubtitleFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外挂字幕纯映射单测（2026-11-28 外挂字幕批）：
 * 格式 → Media3 MIME、zh 默认语言判定、默认轨选取。
 * 口径 = 协议 AssetSubtitle + Android 端「中文默认、无中文不预选」（与 Web 端同口径）。
 */
class SubtitleConfigTest {

    private fun track(
        lang: String,
        format: SubtitleFormat = SubtitleFormat.ASS,
        isDefault: Boolean = false,
    ) = DetailSubtitle(
        lang = lang,
        format = format,
        sizeBytes = 1L,
        url = "/media/sub/x?lang=$lang&exp=1&sig=s",
        isDefault = isDefault,
        label = lang,
    )

    @Test
    fun assAndSsaShareSsaMime() {
        // ASS/SSA 同族，Media3 走 SsaParser（样式/定位/描边解析）
        assertEquals(MimeTypes.TEXT_SSA, subtitleMimeType(SubtitleFormat.ASS))
        assertEquals(MimeTypes.TEXT_SSA, subtitleMimeType(SubtitleFormat.SSA))
    }

    @Test
    fun srtAndVttUseTheirOwnMime() {
        assertEquals(MimeTypes.APPLICATION_SUBRIP, subtitleMimeType(SubtitleFormat.SRT))
        assertEquals(MimeTypes.TEXT_VTT, subtitleMimeType(SubtitleFormat.VTT))
    }

    @Test
    fun chinesePrimaryTagIsDefaultCaseInsensitively() {
        assertTrue(isDefaultSubtitleLang("zh"))
        assertTrue(isDefaultSubtitleLang("ZH"))
        // BCP 47 子标签（zh-Hans/zh-CN）主标签仍是 zh
        assertTrue(isDefaultSubtitleLang("zh-Hans"))
        assertTrue(isDefaultSubtitleLang("zh-CN"))
        // 其他语言不默认选中（含 zho/yue 等：只认协议下发的语言码主标签，不做等价推断）
        assertFalse(isDefaultSubtitleLang("en"))
        assertFalse(isDefaultSubtitleLang("ja"))
        assertFalse(isDefaultSubtitleLang(""))
        assertFalse(isDefaultSubtitleLang("zhx"))
    }

    @Test
    fun languageLabelsMatchWebTable() {
        assertEquals("中文", subtitleLangLabel("zh"))
        assertEquals("中文", subtitleLangLabel("zh-Hans"))
        assertEquals("英语", subtitleLangLabel("en-US"))
        assertEquals("日语", subtitleLangLabel("ja"))
        assertEquals("韩语", subtitleLangLabel("ko"))
        // 未知语言原样（不猜）
        assertEquals("tlh", subtitleLangLabel("tlh"))
    }

    @Test
    fun defaultSubtitlePicksFirstChineseTrack() {
        val en = track("en", SubtitleFormat.SRT)
        val zh = track("zh", SubtitleFormat.ASS)
        val zhHans = track("zh-Hans", SubtitleFormat.SSA)
        assertEquals(zh, defaultSubtitle(listOf(en, zh, zhHans)))
        assertEquals(zhHans, defaultSubtitle(listOf(zhHans)))
    }

    @Test
    fun serverDefaultFlagWinsOverLanguageHeuristic() {
        // 服务端 isDefault 是三端同源依据：即便语言不是中文也照用（如繁体被标默认）
        val en = track("en")
        val hant = track("zh-Hant", isDefault = true)
        assertEquals(hant, defaultSubtitle(listOf(en, hant)))
        // 旧服务端无 isDefault 且无中文时不预选（回退判定不猜）
        assertNull(defaultSubtitle(listOf(en, track("ja"))))
    }

    @Test
    fun noChineseMeansNoDefaultSelection() {
        // 无中文轨 → 不预选（播放器保持内嵌轨行为，用户可在字幕菜单手动选）
        assertNull(defaultSubtitle(emptyList()))
        assertNull(defaultSubtitle(listOf(track("en"), track("ja"))))
    }

    @Test
    fun subtitleViewportRequiresKnownVideoSize() {
        // 首帧回归（适配点⑯）：尺寸未回调时 SubtitleView 必须保持隐藏，
        // 否则 Media3 按全舞台高度换算 ASS 相对字号（真机大 3~4 倍并裁左缘）
        assertFalse(subtitleViewportReady(0, 0, Player.STATE_BUFFERING))
        assertFalse(subtitleViewportReady(3840, 0, Player.STATE_READY))
        assertFalse(subtitleViewportReady(0, 2160, Player.STATE_READY))
        assertFalse(subtitleViewportReady(-1, 2160, Player.STATE_READY))
        assertTrue(subtitleViewportReady(3840, 2160, Player.STATE_READY))
        assertTrue(subtitleViewportReady(1920, 1080, Player.STATE_BUFFERING))
    }

    @Test
    fun subtitleViewportRejectsIdleLikePlayerViewAspectRatio() {
        // 与 PlayerView.onVideoSizeChanged 同口径：STATE_IDLE 时不采用比例，字幕也不显示
        assertFalse(subtitleViewportReady(3840, 2160, Player.STATE_IDLE))
    }
}
