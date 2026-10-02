package media.qimeng.app.core.ui.theme

import androidx.compose.ui.graphics.Color
import media.qimeng.app.core.model.ThemeColorPreset

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

// ============================================================================
// 主题色彩预设色板（2026-10-03 主题色彩批，ThemeColorPreset 六档的取值单源）
//
// 设计边界（记档）：
// 1. 每套预设只覆写**强调色三族**（primary/secondary/tertiary 各 主件+on件+容器+on容器
//    四槽）；中性面（背景/表面/表面容器/文字/描边）共用上方基座不随预设变——改面色
//    牵动全 App 玻璃对比度与日夜观感，风险大收益小，故预设只换「强调」不换「底色」。
// 2. 取值手法与鸢尾族对齐：浅色主色取 tone≈40（白字可压）、深色主色取 tone≈80
//    （深底高识别）、容器取 tone≈90/30~40 阶梯；不必分毫不差 M3 算法，但同族内和谐、
//    日夜两组对比都达标。TEAL 偏青（hue≈180）、JADE 偏黄绿（hue≈150），刻意拉开距离。
// 3. AURORA 不设新常量：直接映射 QimengBrandColors 既有鸢尾/雾岚/极光青三族（升级
//    用户观感零变化，见 themePresetRoles 的 AURORA 分支）。
// 4. DYNAMIC 档不在此取值：走 material3 官方 dynamicLight/DarkColorScheme（Material You
//    壁纸取色，API 31+），SDK 判定与回落口径收敛在 Theme.kt 装配单点。
// 5. 氛围层联动记档：AuroraBackdrop 的辉光四色中前两色经 colorScheme.primary/tertiary
//    派生（glowPalette，GlassColors.kt）——会随预设联动换色；霞粉/霓紫两色与玻璃中性
//    材质（GlassColors 的 Dark/LightGlass fill/edge/sheen/shadow）固定不变。氛围底是
//    多彩混合光，预设切换只改辉光前两色相、不破坏氛围层设计，此为有意决策。
// ============================================================================

/**
 * 单套预设的强调色角色组（12 槽 = 三族 × 4）：Theme.kt 装配时以基座 colorScheme.copy
 * 覆写这些槽位，其余槽位保持基座（中性面共用，见上节边界 1）。
 */
data class QimengPresetRoles(
    // 主色族
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    // 次色族
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    // 三色族
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
)

/** 预设·青碧（TEAL）：青绿主色 + 暖金三色对撞 */
private object PresetTeal {
    // 浅色档（昼）
    val Light = QimengPresetRoles(
        primary = Color(0xFF006A60), onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF9CF1E5), onPrimaryContainer = Color(0xFF00201B),
        secondary = Color(0xFF4A6360), onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFCCE8E4), onSecondaryContainer = Color(0xFF06201D),
        tertiary = Color(0xFF7B5900), onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFFDF9E), onTertiaryContainer = Color(0xFF261A00),
    )

    // 深色档（夜）
    val Dark = QimengPresetRoles(
        primary = Color(0xFF83D5C8), onPrimary = Color(0xFF003731),
        primaryContainer = Color(0xFF005049), onPrimaryContainer = Color(0xFF9FF1E6),
        secondary = Color(0xFFB1CCC8), onSecondary = Color(0xFF1C3531),
        secondaryContainer = Color(0xFF334B47), onSecondaryContainer = Color(0xFFCCE8E4),
        tertiary = Color(0xFFECC468), onTertiary = Color(0xFF402D00),
        tertiaryContainer = Color(0xFF5C4300), onTertiaryContainer = Color(0xFFFFDF9E),
    )
}

/** 预设·琥珀（AMBER）：暖金主色 + 钢蓝三色对撞 */
private object PresetAmber {
    val Light = QimengPresetRoles(
        primary = Color(0xFF7A5800), onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFDEA3), onPrimaryContainer = Color(0xFF261A00),
        secondary = Color(0xFF6D5B3F), onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFF7E0BE), onSecondaryContainer = Color(0xFF261904),
        tertiary = Color(0xFF3E6374), onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFC1E8FB), onTertiaryContainer = Color(0xFF001F2B),
    )

    val Dark = QimengPresetRoles(
        primary = Color(0xFFECC365), onPrimary = Color(0xFF402D00),
        primaryContainer = Color(0xFF5C4300), onPrimaryContainer = Color(0xFFFFDEA3),
        secondary = Color(0xFFDBC4A1), onSecondary = Color(0xFF3E2E15),
        secondaryContainer = Color(0xFF564429), onSecondaryContainer = Color(0xFFF7E0BE),
        tertiary = Color(0xFFA5CCDF), onTertiary = Color(0xFF063443),
        tertiaryContainer = Color(0xFF244B5B), onTertiaryContainer = Color(0xFFC1E8FB),
    )
}

/** 预设·绯樱（ROSE）：玫红主色 + 蜜桃棕三色对撞 */
private object PresetRose {
    val Light = QimengPresetRoles(
        primary = Color(0xFF8E4955), onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFD9DD), onPrimaryContainer = Color(0xFF3A0714),
        secondary = Color(0xFF75565B), onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFFFD9DE), onSecondaryContainer = Color(0xFF2B1518),
        tertiary = Color(0xFF7B5633), onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFFDBCA), onTertiaryContainer = Color(0xFF2E1501),
    )

    val Dark = QimengPresetRoles(
        primary = Color(0xFFFFB2B9), onPrimary = Color(0xFF5E1222),
        primaryContainer = Color(0xFF75293B), onPrimaryContainer = Color(0xFFFFD9DD),
        secondary = Color(0xFFE3BDC1), onSecondary = Color(0xFF43292D),
        secondaryContainer = Color(0xFF5B3F43), onSecondaryContainer = Color(0xFFFFD9DE),
        tertiary = Color(0xFFECBF90), onTertiary = Color(0xFF452B12),
        tertiaryContainer = Color(0xFF5F4126), onTertiaryContainer = Color(0xFFFFDBCA),
    )
}

/** 预设·苍翠（JADE）：松石黄绿主色 + 紫李三色对撞（与青碧拉开色相距离） */
private object PresetJade {
    val Light = QimengPresetRoles(
        primary = Color(0xFF226B4A), onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFAAF2CC), onPrimaryContainer = Color(0xFF002114),
        secondary = Color(0xFF4D6357), onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFCEE9DA), onSecondaryContainer = Color(0xFF0A2015),
        tertiary = Color(0xFF6C5677), onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFF4D9FF), onTertiaryContainer = Color(0xFF261431),
    )

    val Dark = QimengPresetRoles(
        primary = Color(0xFF8ED7AF), onPrimary = Color(0xFF003922),
        primaryContainer = Color(0xFF005233), onPrimaryContainer = Color(0xFFAAF2CC),
        secondary = Color(0xFFB2CCBE), onSecondary = Color(0xFF20352A),
        secondaryContainer = Color(0xFF364B40), onSecondaryContainer = Color(0xFFCEE9DA),
        tertiary = Color(0xFFDABDE4), onTertiary = Color(0xFF3D2949),
        tertiaryContainer = Color(0xFF553F62), onTertiaryContainer = Color(0xFFF4D9FF),
    )
}

/**
 * 预设 →（日夜）强调色角色组映射（Theme.kt 装配唯一消费口；Color.kt 内聚取值单源）。
 * AURORA 映射 QimengBrandColors 既有三族（观感零变化）；DYNAMIC 在此保底回落 AURORA
 * ——正常路径 Theme 装配层已先行拦截 DYNAMIC 走官方动态取色，仅 SDK<31 降级会落进本
 * 函数的 DYNAMIC 分支（口径见 Theme.kt）。
 */
internal fun themePresetRoles(preset: ThemeColorPreset, dark: Boolean): QimengPresetRoles =
    when (preset) {
        ThemeColorPreset.AURORA, ThemeColorPreset.DYNAMIC ->
            if (dark) {
                QimengPresetRoles(
                    primary = QimengBrandColors.PrimaryDark,
                    onPrimary = QimengBrandColors.OnPrimaryDark,
                    primaryContainer = QimengBrandColors.PrimaryContainerDark,
                    onPrimaryContainer = QimengBrandColors.OnPrimaryContainerDark,
                    secondary = QimengBrandColors.SecondaryDark,
                    onSecondary = QimengBrandColors.OnSecondaryDark,
                    secondaryContainer = QimengBrandColors.SecondaryContainerDark,
                    onSecondaryContainer = QimengBrandColors.OnSecondaryContainerDark,
                    tertiary = QimengBrandColors.TertiaryDark,
                    onTertiary = QimengBrandColors.OnTertiaryDark,
                    tertiaryContainer = QimengBrandColors.TertiaryContainerDark,
                    onTertiaryContainer = QimengBrandColors.OnTertiaryContainerDark,
                )
            } else {
                QimengPresetRoles(
                    primary = QimengBrandColors.PrimaryLight,
                    onPrimary = QimengBrandColors.OnPrimaryLight,
                    primaryContainer = QimengBrandColors.PrimaryContainerLight,
                    onPrimaryContainer = QimengBrandColors.OnPrimaryContainerLight,
                    secondary = QimengBrandColors.SecondaryLight,
                    onSecondary = QimengBrandColors.OnSecondaryLight,
                    secondaryContainer = QimengBrandColors.SecondaryContainerLight,
                    onSecondaryContainer = QimengBrandColors.OnSecondaryContainerLight,
                    tertiary = QimengBrandColors.TertiaryLight,
                    onTertiary = QimengBrandColors.OnTertiaryLight,
                    tertiaryContainer = QimengBrandColors.TertiaryContainerLight,
                    onTertiaryContainer = QimengBrandColors.OnTertiaryContainerLight,
                )
            }

        ThemeColorPreset.TEAL -> if (dark) PresetTeal.Dark else PresetTeal.Light
        ThemeColorPreset.AMBER -> if (dark) PresetAmber.Dark else PresetAmber.Light
        ThemeColorPreset.ROSE -> if (dark) PresetRose.Dark else PresetRose.Light
        ThemeColorPreset.JADE -> if (dark) PresetJade.Dark else PresetJade.Light
    }
