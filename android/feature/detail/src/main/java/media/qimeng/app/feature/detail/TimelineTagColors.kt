package media.qimeng.app.feature.detail

import androidx.compose.ui.graphics.Color

/**
 * 时间轴标签前缀配色（M4-3 3d 拍板②，协议零改动）：标签名前缀决定颜色，
 * 服务端/协议不感知——纯客户端展示约定。
 *
 * - `❤` 前缀 → 红色系；
 * - `⭐` 前缀 → 金色系；
 * - 无前缀 → 调用方传入的主题默认色（通常 MaterialTheme.colorScheme.onSurfaceVariant）。
 *
 * 色值说明：主题色板（ADR-0031 流光玻璃）无红/金既有 token，故在此自持常量；
 * 取 Material 常用红 800 / 红 400、金褐 / 琥珀 300 档位，深浅成对（与主题
 * 深浅两套同惯例：浅色底用深色字保证对比度，深色底反过来）。
 * startsWith 前缀语义天然兼容变体序列（如 ❤️ = ❤ + U+FE0F 变体选择符，同样命中红）。
 */
object TimelineTagColors {

    /** 心动前缀（标签名以此开头 → 红色系） */
    const val HEART_PREFIX = "❤"

    /** 星标前缀（标签名以此开头 → 金色系） */
    const val STAR_PREFIX = "⭐"

    /**
     * 心动快捷写入字面量（❤ + U+FE0F 变体选择符 → 呈现为红心 emoji）。
     * 判定与写入分离：判定恒用裸 [HEART_PREFIX]（兼容手输无变体符的 ❤ 与快捷键写入的 ❤️），
     * 写入恒用本完整字面量（保证键盘呈现为红色 emoji）——2026-09-07 审查 P2 前口径分叉，
     * 快捷键与芯片底色曾各自定义字面量，现统一收敛到本对象单源。
     */
    const val HEART_TAG = "\u2764\uFE0F"

    /** 星标快捷写入字面量（U+2B50 实心星；⭐ 无变体符歧义，判定直用 [STAR_PREFIX]） */
    const val STAR_TAG = "\u2B50"

    /** 红·浅色主题（Material Red 800：浅底上够深，对比度足） */
    val HeartLight: Color = Color(0xFFC62828)

    /** 红·深色主题（Material Red 400：深底上够亮） */
    val HeartDark: Color = Color(0xFFEF5350)

    /** 金·浅色主题（暗金褐：浅底上可读，不刺眼） */
    val StarLight: Color = Color(0xFFB8860B)

    /** 金·深色主题（Material Amber 300：深底上够亮） */
    val StarDark: Color = Color(0xFFFFD54F)

    /**
     * 前缀 → 颜色解析。无前缀返回 [default]（主题默认色）。
     * @param darkTheme 当前是否深色主题
     */
    fun colorFor(name: String, darkTheme: Boolean, default: Color): Color = when {
        name.startsWith(HEART_PREFIX) -> if (darkTheme) HeartDark else HeartLight
        name.startsWith(STAR_PREFIX) -> if (darkTheme) StarDark else StarLight
        else -> default
    }

    /** 前缀判定（供徽标/排序等非取色场景复用）：命中红或金返回 true */
    fun hasColorPrefix(name: String): Boolean =
        name.startsWith(HEART_PREFIX) || name.startsWith(STAR_PREFIX)
}
