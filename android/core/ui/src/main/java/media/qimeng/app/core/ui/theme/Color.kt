package media.qimeng.app.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 品牌色板（唯一对照来源：web/src/styles/prototype.css 的 :root 与 .dark token，2026-09-04 换算）。
 * 每个颜色注释标注 token 名；oklch 值按标准 OKLab->sRGB 公式换算（与 CSS Color 4 一致）。
 * M3 语义角色里 Web 端没有直接对应的（如 primaryContainer），用主色同色相的明/暗 tone 近似，
 * 注释标明「无 Web token，近似值」。
 */
object QimengBrandColors {
    /** 浅色主色 = prototype.css :root --qm-primary #4250af */
    val PrimaryLight: Color = Color(0xFF4250AF)

    /** 主色上的文字 = :root --invert #ffffff */
    val OnPrimaryLight: Color = Color(0xFFFFFFFF)

    /** 深色主色 = .dark --qm-primary oklch(0.836 0.074 258.58) -> #accbfa */
    val PrimaryDark: Color = Color(0xFFACCBFA)

    /** 深色主色上的文字 = 主色同色相暗 tone（无 Web token，近似值） */
    val OnPrimaryDark: Color = Color(0xFF152E6E)

    /** 主色激活/按压态 = :root --accent-deep #35428f */
    val AccentDeep: Color = Color(0xFF35428F)

    /** 辅助主色（渐变/图表第二色）= :root --accent-2 #8b5cf6 */
    val Accent2: Color = Color(0xFF8B5CF6)

    /** 浅色背景 = :root --bg #ffffff */
    val BackgroundLight: Color = Color(0xFFFFFFFF)

    /** 深色背景 = .dark --bg #0f0f0f */
    val BackgroundDark: Color = Color(0xFF0F0F0F)

    /** 浅色正文 = :root --text-main #18191c */
    val TextMainLight: Color = Color(0xFF18191C)

    /** 深色正文 = .dark --text-main #f1f1f3 */
    val TextMainDark: Color = Color(0xFFF1F1F3)

    /** 浅色次级文字 = :root --text-mid #61666d */
    val TextMidLight: Color = Color(0xFF61666D)

    /** 深色次级文字 = .dark --text-mid #a8adb3 */
    val TextMidDark: Color = Color(0xFFA8ADB3)

    /** 浅色弱分隔/描边 = :root --faint #c9ccd1 */
    val FaintLight: Color = Color(0xFFC9CCD1)

    /** 深色弱分隔/描边 = .dark --faint #3a3a3f */
    val FaintDark: Color = Color(0xFF3A3A3F)

    /** 浅色边框 = :root --border #e3e5e7 */
    val BorderLight: Color = Color(0xFFE3E5E7)

    /** 深色边框 = .dark --border #2a2a2a */
    val BorderDark: Color = Color(0xFF2A2A2A)

    /** 浅色 hover/浅面 = :root --hover-bg #f4f5f7 */
    val HoverBgLight: Color = Color(0xFFF4F5F7)

    /** 深色 hover/浅面 = .dark --hover-bg #1f1f1f */
    val HoverBgDark: Color = Color(0xFF1F1F1F)

    /** 浅色主色容器 = 主色向白混合约 87% 的 tone-90 近似（无 Web token） */
    val PrimaryContainerLight: Color = Color(0xFFDFE3F9)

    /** 深色主色容器 = 复用浅色系容器色（M3 明暗互换惯例；无 Web token） */
    val PrimaryContainerDark: Color = Color(0xFFDFE3F9)
}
