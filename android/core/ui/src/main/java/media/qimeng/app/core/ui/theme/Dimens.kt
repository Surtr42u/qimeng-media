package media.qimeng.app.core.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 布局尺寸 token（M4-2A-B1 收编建档，后续批随改随收，不必穷尽）。
 * 单位一律 dp；文字字号（sp）归 Type.kt 排版域，不在此登记（Type.kt 本批禁碰）。
 * 来源核实：旧仓库 QimengMedia **无 values/dimens.xml**（values 目录只有 attrs/colors/strings/styles/themes），
 * 条目逐个来自下列三处，注释标注「文件 + 行号/属性」：
 *  1. 旧仓库 `app/src/main/res/layout/fragment_all_files.xml`（页面级 padding/margin/芯片行）
 *  2. 旧仓库 `app/src/main/res/values/styles.xml` + `drawable/bg_capsule_soft.xml` / `bg_profile_row.xml`（胶囊/卡片尺寸）
 *  3. 新版 core/ui 组件现状值（QimengPills / QimengMediaGrid / QimengScaffold / QimengFourDimSection /
 *     QimengPlaceholderPage——本批只登记不改动组件，组件私有常量迁移此处后以本文件为唯一来源）
 */
object QimengDimens {
    // ---------- 通用间距档（同值多源共用一档，禁止再开平行档位） ----------

    /** 2dp：媒体卡时长角标内层内边距（QimengMediaGrid L169，外层 4dp 内层 2dp 双层描边感） */
    val SpaceXXS: Dp = 2.dp

    /** 4dp：摘要行上距（fragment_all_files.xml L43）/ 列表上距 L147 / 药丸面板纵向内边距 L161-162 /
     *  四维筛选行纵向 4dp（QimengFourDimSection L45）/ 媒体卡标题上距 L111、角标外层内边距 L167、
     *  标题前间隔 L192（QimengMediaGrid） */
    val SpaceXS: Dp = 4.dp

    /** 6dp：筛选芯片横向间距（fragment_all_files.xml L96/L107）/ 底部胶囊切换组件右距（styles.xml L7）/
     *  药丸纵向内边距（QimengPills PILL_VERTICAL_PADDING 6dp，30dp 高胶囊的近似档）/
     *  媒体卡 meta 行横纵 6dp（QimengMediaGrid L179/L181） */
    val SpaceS: Dp = 6.dp

    /** 8dp：芯片行上距（fragment_all_files.xml L72）/ 图标按钮外距 L53 与内边距 L55/L64 /
     *  分隔线外距 L119-120 / 网格间距（QimengMediaGrid GRID_SPACING 8dp）/ 药丸间距（QimengPills PILL_SPACING 8dp） */
    val SpaceM: Dp = 8.dp

    /** 12dp：个人页行项下距（styles.xml QimengProfileRow marginBottom 12dp L46） */
    val SpaceL: Dp = 12.dp

    // ---------- 页面结构（fragment_all_files.xml） ----------

    /** 16dp：页面左右内边距（fragment_all_files.xml L14/L16 root paddingStart/End）/
     *  药丸面板左右内边距 L159-160 / 四维筛选行横向 16dp（QimengFourDimSection L38/L45）/
     *  占位页边距（QimengPlaceholderPage ScreenPadding 16dp L49） */
    val ScreenPaddingHorizontal: Dp = 16.dp

    /** 12dp：页面顶部内边距（fragment_all_files.xml L15 root paddingTop） */
    val ScreenPaddingTop: Dp = 12.dp

    /** 40dp：筛选/列数切换图标按钮边长（fragment_all_files.xml L51-52/L61-62，40x40） */
    val IconButtonSize: Dp = 40.dp

    /** 1dp：筛选行竖分隔线宽（fragment_all_files.xml L117） */
    val DividerThickness: Dp = 1.dp

    /** 18dp：筛选行竖分隔线高（fragment_all_files.xml L118） */
    val DividerHeight: Dp = 18.dp

    /** 180dp：列表底部预留（fragment_all_files.xml L149 RecyclerView paddingBottom，clipToPadding=false
     *  场景——防悬浮药丸面板遮挡末行） */
    val ListBottomContentPadding: Dp = 180.dp

    /** 4dp：悬浮药丸面板海拔（fragment_all_files.xml L157 elevation） */
    val PillsPanelElevation: Dp = 4.dp

    // ---------- 芯片 / 药丸（旧版胶囊语言） ----------

    /** 30dp：筛选芯片高度（fragment_all_files.xml L84/L95/L106/L127）/
     *  标签胶囊 chipMinHeight 30dp（styles.xml L27）——旧版胶囊语言统一 30dp 高 */
    val ChipHeight: Dp = 30.dp

    /** 32dp：底部胶囊切换组件高度（styles.xml QimengCapsuleChip L6，比筛选胶囊高一档） */
    val CapsuleChipHeight: Dp = 32.dp

    /** 14dp：芯片/药丸横向内边距（fragment_all_files.xml L86-87 等 paddingStart/End 14dp）/
     *  QimengCapsuleChip paddingHorizontal 14dp（styles.xml L11）/ 标签胶囊 chipStart/EndPadding 14dp（styles.xml L29-30）/
     *  药丸横向内边距（QimengPills PILL_HORIZONTAL_PADDING 14dp） */
    val ChipHorizontalPadding: Dp = 14.dp

    /** 48dp：底部胶囊切换组件最小宽（styles.xml QimengCapsuleChip minWidth 48dp L10） */
    val ChipMinWidth: Dp = 48.dp

    /** 100dp：胶囊圆角（drawable/bg_capsule_soft.xml corners radius 100dp）/
     *  标签胶囊 chipCornerRadius 100dp（styles.xml L25）/ 药丸圆角（QimengPills PILL_CORNER_RADIUS 100dp） */
    val PillCornerRadius: Dp = 100.dp

    // ---------- 卡片 / 行项 ----------

    /** 16dp：媒体卡圆角（QimengMediaGrid CARD_CORNER_RADIUS 16dp L46）/
     *  个人页行项圆角（drawable/bg_profile_row.xml corners radius 16dp） */
    val CardCornerRadius: Dp = 16.dp

    /** 72dp：个人页行项高度（styles.xml QimengProfileRow L45） */
    val ProfileRowHeight: Dp = 72.dp

    /** 18dp：个人页行项左右内边距（styles.xml QimengProfileRow paddingHorizontal 18dp L49） */
    val ProfileRowHorizontalPadding: Dp = 18.dp

    /** 1dp：媒体卡 tonalElevation（QimengMediaGrid L149；配合 surfaceTint=纯白保持旧版平面白卡观感） */
    val CardTonalElevation: Dp = 1.dp

    /** 4dp：媒体卡时长角标圆角（QimengMediaGrid L168 RoundedCornerShape(4.dp)） */
    val BadgeCornerRadius: Dp = 4.dp

    // ---------- 空态 / 加载（QimengScaffold） ----------

    /** 48dp：空态纵向内边距（QimengScaffold QimengEmptyState L67） */
    val EmptyStateVerticalPadding: Dp = 48.dp

    /** 96dp：加载占位顶部内边距（QimengScaffold QimengLoadingState L105，避开顶部标题区） */
    val LoadingTopPadding: Dp = 96.dp

    // ---------- 图标 ----------

    /** 24dp：矢量图标默认边长（Material 图标标准档；QimengIcons 全部自持矢量共用，M4-2A-B2 收编） */
    val IconDefaultSize: Dp = 24.dp
}
