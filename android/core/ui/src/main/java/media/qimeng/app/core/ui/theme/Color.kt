package media.qimeng.app.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 主题色板（唯一来源：旧仓库 QimengMedia `app/src/main/res/values/colors.xml`（浅色）+
 * `values-night/colors.xml`（深色），2026-09-06 用户拍板（M4-2A-B1）——
 * 从 Web 品牌蓝系换装为旧版 App 的中性极简灰系，深浅两套同源）。
 *
 * 每个颜色注释标注旧版 colors.xml 来源行（如「= values/colors.xml L4 qm_bg」）；
 * 旧版 values 与 values-night 两文件 token 名与行号逐行对应，浅色/深色成对出现。
 * M3 语义角色里旧版没有直接对应的（如 primaryContainer），用旧版透明 token 的实际
 * 合成色近似，注释标明「无旧版不透明 token，近似值」及推导依据。
 *
 * 带 alpha 的 soft 色（primary_soft/accent_soft）不是 M3 色板的槽位（M3 槽位须不透明），
 * 但旧版底栏选中指示器（styles.xml Widget.Qimeng.BottomNavigationView.ActiveIndicator）、
 * 选中态胶囊浸润底都靠它，故登记为具名常量供组件批取用。
 */
object QimengBrandColors {
    // ---------- 页面背景 / 表面（colors.xml「中性极简：微灰白背景 + 纯白卡片」组） ----------

    /** 浅色页面底色 = values/colors.xml L4 qm_bg（windowBackground/statusBar/启动屏背景，微灰白） */
    val BgLight: Color = Color(0xFFFAFAFA)

    /** 深色页面底色 = values-night/colors.xml L4 qm_bg（夜：深灰而非纯黑） */
    val BgDark: Color = Color(0xFF1A1A1A)

    /** 浅色卡片面 = values/colors.xml L5 qm_surface（纯白卡片/navigationBar 底色） */
    val SurfaceLight: Color = Color(0xFFFFFFFF)

    /** 深色卡片面 = values-night/colors.xml L5 qm_surface（略抬起避免压迫感） */
    val SurfaceDark: Color = Color(0xFF242424)

    /** 浅色软表面（统计卡） = values/colors.xml L6 qm_surface_soft */
    val SurfaceSoftLight: Color = Color(0xFFF2F2F4)

    /** 深色软表面 = values-night/colors.xml L6 qm_surface_soft */
    val SurfaceSoftDark: Color = Color(0xFF2E2E2E)

    // ---------- 主色（colors.xml「主色中性深灰，不引入暖色」组） ----------

    /** 浅色主色 = values/colors.xml L8 qm_primary（themes.xml colorPrimary） */
    val PrimaryLight: Color = Color(0xFF3A3A3A)

    /** 深色主色 = values-night/colors.xml L8 qm_primary（夜：浅灰主色） */
    val PrimaryDark: Color = Color(0xFFC8C8C8)

    /** 浅色主色浸润底 = values/colors.xml L9 qm_primary_soft（#3A3A3A @ α0x12≈7%，底栏选中指示器底色） */
    val PrimarySoftLight: Color = Color(0x123A3A3A)

    /** 深色主色浸润底 = values-night/colors.xml L9 qm_primary_soft（#C8C8C8 @ α0x1A≈10%） */
    val PrimarySoftDark: Color = Color(0x1AC8C8C8)

    /** 浅色主色容器 = 无旧版不透明 token，近似值：qm_primary_soft(#123A3A3A，α≈7%) 叠在
     *  qm_bg(#FAFAFA) 上的实际合成色 ≈ #ECECEC（旧版选中态浸润底的视觉等效不透明色） */
    val PrimaryContainerLight: Color = Color(0xFFECECEC)

    /** 深色主色容器 = 无旧版不透明 token，近似值：夜间 qm_primary_soft(#1AC8C8C8，α≈10%) 叠在
     *  夜间 qm_bg(#1A1A1A) 上的实际合成色 ≈ #2C2C2C */
    val PrimaryContainerDark: Color = Color(0xFF2C2C2C)

    // ---------- 强调色（colors.xml「强调色中灰，用于进度条/选中高亮/关键数字」组） ----------

    /** 浅色强调色 = values/colors.xml L11 qm_accent */
    val AccentLight: Color = Color(0xFF6A6A6A)

    /** 深色强调色 = values-night/colors.xml L11 qm_accent */
    val AccentDark: Color = Color(0xFFA8A8A8)

    /** 浅色强调浸润底 = values/colors.xml L12 qm_accent_soft（#6A6A6A @ α0x1A≈10%） */
    val AccentSoftLight: Color = Color(0x1A6A6A6A)

    /** 深色强调浸润底 = values-night/colors.xml L12 qm_accent_soft（#A8A8A8 @ α0x1A≈10%） */
    val AccentSoftDark: Color = Color(0x1AA8A8A8)

    // ---------- 文字（colors.xml「文字色中性灰，不用纯黑避免刺眼」组） ----------

    /** 浅色文字主色 = values/colors.xml L14 qm_text_primary（themes.xml colorOnSurface） */
    val TextPrimaryLight: Color = Color(0xFF3A3A3A)

    /** 深色文字主色 = values-night/colors.xml L14 qm_text_primary */
    val TextPrimaryDark: Color = Color(0xFFC8C8C8)

    /** 浅色文字次级 = values/colors.xml L15 qm_text_secondary */
    val TextSecondaryLight: Color = Color(0xFF9A9A9A)

    /** 深色文字次级 = values-night/colors.xml L15 qm_text_secondary */
    val TextSecondaryDark: Color = Color(0xFF808080)

    // ---------- 胶囊 / 分割线（colors.xml「胶囊和分割线浅灰」组） ----------

    /** 浅色胶囊底 = values/colors.xml L17 qm_chip_bg（bg_capsule_soft 填充色） */
    val ChipBgLight: Color = Color(0xFFF0F0F2)

    /** 深色胶囊底 = values-night/colors.xml L17 qm_chip_bg */
    val ChipBgDark: Color = Color(0xFF2E2E2E)

    /** 浅色分割线 = values/colors.xml L18 qm_divider */
    val DividerLight: Color = Color(0xFFECECEE)

    /** 深色分割线 = values-night/colors.xml L18 qm_divider */
    val DividerDark: Color = Color(0xFF383838)
}
