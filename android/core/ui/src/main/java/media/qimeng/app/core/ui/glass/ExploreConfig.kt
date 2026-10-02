// EXPLORE(2026-10-03)：ui/app-aurora-explore 实验分支专属文件（主线不存在）——
// 全面玻璃化重做探索的开关总板，逐项语义见 [ExploreConfig] KDoc 与 docs/EXPLORATION-AURORA.md。
package media.qimeng.app.core.ui.glass

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * EXPLORE（2026-10-03，ui/app-aurora-explore 实验分支专属，**不属于主线**）：
 * 全面玻璃化重做探索的开关总板。所有实验项在此单点翻转，便于实机逐项评估；
 * 每个开关的动机/做法/风险见 docs/EXPLORATION-AURORA.md。
 *
 * 开关默认值口径：
 * - 探索项 1~4（视觉重做本体）默认开——本分支存在的意义就是让这些实验可见，
 *   逐项翻 false 即精确回到主线观感（每个消费点都写了回退分支）；
 * - 探索项 5（Tab 进入转场）默认**关**——主线红线（任务U9「fade*(snap()) 瞬切防叠影」、
 *   任务L L2「NavHost 顶层切换无转场」）明确反对常驻层动画化，此项仅供用户实机
 *   评估后拍板，未经拍板不得翻 true 合入主线。
 *
 * 本文件消费方：QimengNavHost（壳层）、FloatingTabDock（坞变体参数经壳层传入）、
 * QimengMediaGrid（玻璃网格卡）、StatsScreen（玻璃统计卡）、HomeScreen（玻璃顶行）。
 */
object ExploreConfig {

    // ---------- 探索 1：全面极光画布 ----------

    /**
     * 极光氛围底提升为全局常驻背景：SOLID/CLASSIC 材质档也铺 AuroraBackdrop
     * （主线口径=仅玻璃档 usesBackdrop 时渲染）。内容面半透明化（玻璃顶行）配套开关联动：
     * 玻璃面下若无极光垫底，透出的是纯色 background，玻璃观感不成立。
     */
    const val GLOBAL_AURORA_CANVAS: Boolean = true

    /**
     * 首页顶行内容面半透明化：搜索胶囊 + 两枚图标钮从 surfaceVariant/secondaryContainer
     * 实色面换 GlassSurface 玻璃面（极光从内容缝隙透出的最小切口；相册页 QimengTitleRow
     * 为多页共用件，本探索不动，见笔记「候选未做」）。
     */
    const val GLASS_TOP_ROW: Boolean = true

    // ---------- 探索 2：玻璃卡片上内容 ----------

    /** 统计页全部卡（6 指标格/趋势卡/分布入口卡/内容榜卡/常看作者标签卡）玻璃化 */
    const val GLASS_STAT_CARDS: Boolean = true

    /** 媒体网格卡玻璃化：缩略图外圈垫玻璃衬底（GlassSurface 首个网格消费方） */
    const val GLASS_GRID_CARDS: Boolean = true

    // ---------- 探索 3：坞体形态实验（参数经 FloatingTabDock 新增可选参数生效，默认全关=主线） ----------

    /** 3a 胶囊液态拉伸：滑动中胶囊随「距目标距离比例」横向拉伸（视觉等价速度映射，见笔记） */
    const val DOCK_LIQUID_PILL_STRETCH: Boolean = true

    /** 3b 标签渐隐式：未选中项标签 alpha 0 只留图标，选中项标签淡入+微放大 */
    const val DOCK_LABEL_FADE: Boolean = true

    /** 3b 配套：标签渐隐时坞体降档（62dp→54dp 紧凑档）；仅 DOCK_LABEL_FADE 开时生效 */
    const val DOCK_COMPACT_HEIGHT: Boolean = true

    /** 3c 选中图标点缀：图标上方小圆点指示 + 图标着色 150ms 渐变过渡 */
    const val DOCK_ICON_ACCENT: Boolean = true

    // ---------- 探索 4：微交互打磨 ----------

    /** tab 点击触觉反馈（TextHandleMove 轻档；项目内无既有 haptics 惯例，grep 已核） */
    const val DOCK_HAPTICS: Boolean = true

    // ---------- 探索 5：内容转场实验（高风险，默认关） ----------

    /**
     * 常驻层 Tab 翻转时给进入屏加 fade+微 scale（4~6 帧）。**默认关，红线依据**：
     * 主线任务U9 把 NavHost 转场定为 fade*(snap()) 瞬切、任务L L2 拍板「无内容转场」——
     * 常驻层交换机制依赖瞬时翻转防叠影/残留。本实验只对**进入屏**做 0.35→1 alpha +
     * 0.98→1 scale（退出屏仍瞬时 alpha=0，两页永不全不透明同屏，叠影根因不复活），
     * 但「进入屏半透明帧」露出的底是极光画布而非旧页——观感是否可接受须实机评估，
     * 未经用户拍板禁止翻 true 合入主线。
     */
    const val TAB_ENTER_FADE_TRANSITION: Boolean = false

    // ---------- 参数档 ----------

    /** 坞体紧凑档高度（标签渐隐+降档时用；主线档 62dp 见 TabDockDefaults.DockHeight） */
    val COMPACT_DOCK_HEIGHT: Dp = 54.dp
}
