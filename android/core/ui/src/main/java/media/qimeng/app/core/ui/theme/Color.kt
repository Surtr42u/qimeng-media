package media.qimeng.app.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 「流光玻璃」色板（ADR-0031，2026-10-02 设计语言级重做）：
 * 旧「中性极简灰」板（照搬旧仓库 colors.xml）全量退役，换媒体消费场景的三元强调色体系
 * ——鸢尾（Iris 蓝紫·主）、雾岚（Mist 灰紫·次）、极光青（Aurora Teal·三）+ 氛围辉光四色
 * （鸢尾/极光青/霞粉/霓紫，仅 AuroraBackdrop/GlassSurface 氛围层用）。暗色为第一基准
 * （媒体场景暗色优先），浅色为同族低饱和伴随档。
 *
 * 取色依据：主/次/三色按 M3 三角色（primary/secondary/tertiary）的 tone 80/40 明度档手工
 * 校准（暗色取亮档保证深底对比度、浅色取暗档），容器/描边/文字各槽位按 M3 tonal 阶梯
 * 对位；辉光色不进 M3 色板槽位（M3 槽位须不透明且承担 UI 语义），仅供氛围层。
 * 旧板 token 名与色值不再保留——Theme.kt 主题映射与 glass/ 玻璃件是仅有的授权消费方，
 * feature 层一律经 MaterialTheme.colorScheme 取色，禁止直引本对象。
 */
object QimengBrandColors {
    // ---------- 暗色档（第一基准） ----------

    /** 夜基底：近黑的蓝紫夜色（非纯黑，保留层次呼吸） */
    val BgDark: Color = Color(0xFF0A0C14)

    /** 夜卡面（surface）：略抬一档的深靛 */
    val SurfaceDark: Color = Color(0xFF121523)

    /** 夜容器阶梯（surfaceContainerLow→Highest，玻璃面板的不透明基调色） */
    val ContainerLowDark: Color = Color(0xFF171B2B)
    val ContainerDark: Color = Color(0xFF1D2233)
    val ContainerHighDark: Color = Color(0xFF262C40)
    val ContainerHighestDark: Color = Color(0xFF303751)

    /** 主色·鸢尾（暗=亮紫蓝，深底上的高识别强调） */
    val PrimaryDark: Color = Color(0xFFBEC6FF)
    val OnPrimaryDark: Color = Color(0xFF28348F)
    val PrimaryContainerDark: Color = Color(0xFF4050C8)
    val OnPrimaryContainerDark: Color = Color(0xFFE1E5FF)

    /** 次色·雾岚（暗=灰紫，低调的次级强调） */
    val SecondaryDark: Color = Color(0xFFC5C8DF)
    val OnSecondaryDark: Color = Color(0xFF2F3249)
    val SecondaryContainerDark: Color = Color(0xFF454862)
    val OnSecondaryContainerDark: Color = Color(0xFFE2E1F8)

    /** 三色·极光青（暗=浅青，数据/正向语义点缀） */
    val TertiaryDark: Color = Color(0xFF82D8CB)
    val OnTertiaryDark: Color = Color(0xFF003732)
    val TertiaryContainerDark: Color = Color(0xFF1E514B)
    val OnTertiaryContainerDark: Color = Color(0xFF9EF2E5)

    /** 夜文字：主=雾白、次=灰紫 */
    val TextPrimaryDark: Color = Color(0xFFE4E5F0)
    val TextSecondaryDark: Color = Color(0xFFC3C5D8)

    /** 夜描边 */
    val OutlineDark: Color = Color(0xFF8F93A9)
    val OutlineVariantDark: Color = Color(0xFF454858)

    // ---------- 浅色档（同族低饱和伴随） ----------

    /** 昼基底：冷瓷白（微蓝紫倾向） */
    val BgLight: Color = Color(0xFFF4F5FB)
    val SurfaceLight: Color = Color(0xFFFFFFFF)
    val ContainerLowestLight: Color = Color(0xFFFFFFFF)
    val ContainerLowLight: Color = Color(0xFFF4F5FB)
    val ContainerLight: Color = Color(0xFFF0F1F9)
    val ContainerHighLight: Color = Color(0xFFEAECF6)
    val ContainerHighestLight: Color = Color(0xFFE2E5F2)

    /** 主色·鸢尾（昼=靛蓝） */
    val PrimaryLight: Color = Color(0xFF4756C8)
    val OnPrimaryLight: Color = Color(0xFFFFFFFF)
    val PrimaryContainerLight: Color = Color(0xFFDEE2FF)
    val OnPrimaryContainerLight: Color = Color(0xFF0F1E86)

    /** 次色·雾岚（昼=灰蓝紫） */
    val SecondaryLight: Color = Color(0xFF595D73)
    val OnSecondaryLight: Color = Color(0xFFFFFFFF)
    val SecondaryContainerLight: Color = Color(0xFFDEE1F9)
    val OnSecondaryContainerLight: Color = Color(0xFF161B2C)

    /** 三色·极光青（昼=深青） */
    val TertiaryLight: Color = Color(0xFF206A62)
    val OnTertiaryLight: Color = Color(0xFFFFFFFF)
    val TertiaryContainerLight: Color = Color(0xFFA8F2E5)
    val OnTertiaryContainerLight: Color = Color(0xFF00201C)

    /** 昼文字 */
    val TextPrimaryLight: Color = Color(0xFF1B1C2E)
    val TextSecondaryLight: Color = Color(0xFF454A5F)

    /** 昼描边 */
    val OutlineLight: Color = Color(0xFF757A90)
    val OutlineVariantLight: Color = Color(0xFFC6CAD9)

    // ---------- 氛围辉光（AuroraBackdrop 专用；不进 M3 槽位） ----------

    /** 辉光·鸢尾（蓝紫主辉） */
    val GlowIris: Color = Color(0xFF4C5DF0)

    /** 辉光·极光青 */
    val GlowAurora: Color = Color(0xFF1EC8B6)

    /** 辉光·霞粉 */
    val GlowBlossom: Color = Color(0xFFE85C9A)

    /** 辉光·霓紫（深景深处补层） */
    val GlowViolet: Color = Color(0xFF7C4DE8)

    // ---------- 兼容别名（旧灰板退役后保留，新代码禁用） ----------

    // 底栏选中指示器软色（旧「中性极简灰」板 token）。新色板无同义槽位，但壳层 CLASSIC
    // 材质档仍逐字渲染主线现行 M3 NavigationBar（F 批 2026-09-09 指示器接线，行为零回归
    // 要求）——该消费点退役（CLASSIC 档下线）前两个别名不得删除。色值=旧板原值（浅
    // #123A3A3A / 夜 #1AC8C8C8），非新板派生：CLASSIC 档的语义就是「主线旧观感保底」。

    /** 兼容别名，新代码禁用：旧板指示器软色·浅（旧 PrimarySoftLight 原值） */
    val PrimarySoftLight: Color = Color(0x123A3A3A)

    /** 兼容别名，新代码禁用：旧板指示器软色·夜（旧 PrimarySoftDark 原值） */
    val PrimarySoftDark: Color = Color(0x1AC8C8C8)
}
