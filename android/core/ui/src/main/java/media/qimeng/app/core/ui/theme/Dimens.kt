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
 *  3. 新版 core/ui 组件现状值（QimengPills / QimengMediaGrid / QimengScaffold——本批只登记不改动组件，组件私有常量迁移此处后以本文件为唯一来源）
 */
object QimengDimens {
    // ---------- 通用间距档（同值多源共用一档，禁止再开平行档位） ----------

    /** 2dp：媒体卡时长角标内层内边距（QimengMediaGrid L169，外层 4dp 内层 2dp 双层描边感） */
    val SpaceXXS: Dp = 2.dp

    /** 4dp：摘要行上距（fragment_all_files.xml L43）/ 列表上距 L147 / 药丸面板纵向内边距 L161-162 /
     *  媒体卡标题上距 L111、角标外层内边距 L167、标题前间隔 L192（QimengMediaGrid） */
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
     *  药丸面板左右内边距 L159-160 / 维度芯片行横向 16dp（QimengChipRow 调用方，M4-2A-B2 起同款）/
     *  页面四周统一留白（component/ComponentDimens.kt Dimens.ScreenPadding 16dp——原 QimengPlaceholderPage 已删除，出处改挂 ComponentDimens） */
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

    // ---------- 胶囊输入框（F 批：用户 2026-09-09 反馈「搜索胶囊过大不符旧版视觉」，压回旧版尺寸） ----------

    /** 34dp：胶囊软底文本输入框固定高度（旧仓库 fragment_search.xml L29 searchInput layout_height=34dp；
     *  bg_capsule_soft 全仓输入面统一 34dp）。不放任 M3 默认：material3 1.4.0（BOM 2026.06.01）的
     *  OutlinedTextField 无高度约束时实测 80dp——内部文本区被 minimumInteractiveComponentSize 撑到
     *  48dp + 无 label 默认 contentPadding 上下各 16dp（TextFieldImplKt TextFieldPadding）= 80dp，
     *  超出 TextFieldDefaults.MinHeight=56dp 的下限直接取内容高。F 批压回 34dp（KDoc 见
     *  QimengCapsuleTextField：用户 2026-09-09 反馈推翻 G6「保留 56dp 触摸目标」取舍） */
    val CapsuleFieldHeight: Dp = 34.dp

    /** 8dp：词丸流换行纵向间隙（BVIS 勘正：旧实录 search_entry.txt 推荐词丸行位 404/528/652px@density3
     *  → 行节距 124px=41.3dp，芯片高 32dp → 纵向间隙 ≈8dp 拍板值。F 批曾记「2dp 行距 = 行节距 34dp
     *  对齐实录」有误——34dp 实为胶囊输入框场高 [CapsuleFieldHeight]，与词丸行节距是两个数混淆，
     *  走查实测 2dp 档节距 33.9dp 偏紧，本批按实录清偿） */
    val WordPillRowSpacing: Dp = 8.dp

    /** 8dp：词丸流横向间隙（BVIS 勘正：旧实录 search_entry.txt 同帧枚缘 329→353px@density3
     *  → 枚间 24px=8dp。旧 styles.xml L8 marginEnd=6dp 是 XML 声明值，以实录渲染值为准） */
    val WordPillSpacing: Dp = 8.dp

    /** 4dp：悬浮药丸面板内 FlowRow 纵横间隙（BVIS：旧实录 all_partition_pills.txt 行位
     *  466/568px@density3 → 行节距 102px=34dp，芯片高 30dp → 间隙 4dp；此前复用 SpaceM=8dp
     *  实测节距 40dp 偏松。仅 QimengFloatingPillPanel 使用——QimengValuePillFlow 保持
     *  8dp（G5 Web 基准拍板保护，走查未判差距不随动） */
    val FloatingPillPanelSpacing: Dp = 4.dp

    /** 40dp：首页搜索框高度（旧仓库 fragment_home.xml L37，bg_capsule_soft 胶囊底同语言；
     *  F 批前实测 48dp，压回旧版 40dp） */
    val HomeSearchFieldHeight: Dp = 40.dp

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

    // ---------- 榜单卡（任务G G2：对齐 Web .rank-card，prototype.css「卡片通用」规则） ----------

    /** 12dp：榜单卡圆角（Web .rank-card border-radius 12px——卡片通用规则，与媒体卡 16dp 档区分） */
    val RankCardCornerRadius: Dp = 12.dp

    /** 1dp：榜单卡描边宽（Web .rank-card border 1px solid var(--border)→outlineVariant，以描边区分卡片） */
    val RankCardBorderWidth: Dp = 1.dp

    /** 16dp：榜单卡内边距（Web .rank-card padding 16px，卡内全部内容的统一内缩值） */
    val RankCardInnerPadding: Dp = 16.dp

    /** 1dp：榜单卡行分隔线宽（Web .rank-card li border-bottom 1px solid var(--border)，末行无线） */
    val RankCardRowDividerThickness: Dp = 1.dp

    // ---------- 空态 / 加载（QimengScaffold） ----------

    /** 48dp：空态纵向内边距（QimengScaffold QimengEmptyState 的 Box padding，按符号定位防行号漂移） */
    val EmptyStateVerticalPadding: Dp = 48.dp

    /** 96dp：加载占位顶部内边距（QimengScaffold QimengLoadingState L105，避开顶部标题区） */
    val LoadingTopPadding: Dp = 96.dp

    /** 20dp：按钮内 LoadingIndicator 直径（V8 #5：16dp 时 expressive 叶片强缩成「小齿轮」观感，
     * 与并排按钮文字失衡——对齐 M3 Button 默认文字样式 labelLarge 的 20sp 行高，加载中保持
     * 「文字不消失、指示器与文字行高协调」。页级/整页加载仍用组件默认 48dp，不共用本档；
     * feature 层按钮内嵌 loading（login/detail/upload）一律引用本 token，禁止再写局部 dp） */
    val ButtonLoadingIndicatorSize: Dp = 20.dp

    // ---------- 图标 ----------

    /** 24dp：矢量图标默认边长（Material 图标标准档；QimengIcons 全部自持矢量共用，M4-2A-B2 收编） */
    val IconDefaultSize: Dp = 24.dp

    // ---------- 筛选面板（M4-2A-B3；来源=旧仓库 MediaFilterSheet.kt 布局代码，无旧 dimens.xml） ----------

    /** 0.62：滚动区最大高度占屏比（旧版 show()：scroll 高 = heightPixels * 0.62f；无量纲系数） */
    const val FilterSheetHeightFraction: Float = 0.62f

    /** 20dp：面板内容左右内边距（旧版 content setPadding(20,12,20,12) 横段） */
    val FilterSheetPaddingHorizontal: Dp = 20.dp

    /** 10dp：面板标题「筛选」下内边距（旧版 headerLabel setPadding(0,4,0,10) 下段；上段=SpaceXS） */
    val FilterTitleBottomPadding: Dp = 10.dp

    /** 14dp：分区标题上距（旧版 section setPadding(0,14,0,4) 上段；下段=SpaceXS） */
    val FilterSectionTopSpacing: Dp = 14.dp

    /** 24dp：底部按钮栏下内边距（旧版 footer setPadding(20,12,20,24) 下段；上段=SpaceL） */
    val FilterFooterBottomPadding: Dp = 24.dp

    /** 48dp：底部「重置/应用筛选」按钮高度（旧版 footer 按钮 LayoutParams height 48dp） */
    val FilterButtonHeight: Dp = 48.dp

    // ---------- 详情弹层（U10-2：详情页标签弹层排版对齐旧版；来源=旧仓库 MediaDetailFragment.kt） ----------

    /** 20dp：详情标签弹层横向内边距（旧 MediaDetailFragment.kt:1254 sheetContainer 横向 padding；
     *  与旧版 DetailSheet 底距 28dp 同出该旧文件。特设独立 token 而非借用筛选面板
     *  FilterSheetPaddingHorizontal——同值不同源，防止后续两处口径互相牵连） */
    val DetailSheetPaddingHorizontal: Dp = 20.dp
}
