package media.qimeng.app.core.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import media.qimeng.app.core.ui.theme.QimengDimens

/**
 * 界面图标：运行时解析 Material Icons 官方 path data（Apache-2.0，fonts.google.com/icons 同源）。
 * 为什么不引 material-icons 依赖：当前用量小，extended 全量包体积代价不成比例——自持矢量是零依赖解。
 */
private const val ICON_VIEWPORT = 24f

private fun materialIcon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = QimengDimens.IconDefaultSize,
        defaultHeight = QimengDimens.IconDefaultSize,
        viewportWidth = ICON_VIEWPORT,
        viewportHeight = ICON_VIEWPORT,
    ).apply {
        addPath(
            pathData = addPathNodes(pathData),
            pathFillType = PathFillType.NonZero,
            fill = SolidColor(Color.Black),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
            strokeLineMiter = 4f,
        )
    }.build()

/** 多段 path 的图标（同 viewport 内多个实心形状，fillColor 统一由 Icon tint 着色） */
private fun multiPathIcon(name: String, vararg pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = QimengDimens.IconDefaultSize,
        defaultHeight = QimengDimens.IconDefaultSize,
        viewportWidth = ICON_VIEWPORT,
        viewportHeight = ICON_VIEWPORT,
    ).apply {
        pathData.forEach { data ->
            addPath(
                pathData = addPathNodes(data),
                pathFillType = PathFillType.NonZero,
                fill = SolidColor(Color.Black),
                fillAlpha = 1f,
                stroke = null,
                strokeAlpha = 1f,
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter,
                strokeLineMiter = 4f,
            )
        }
    }.build()

/** 首页（Material Icons "home"） */
val HomeIcon: ImageVector = materialIcon(
    name = "QimengHome",
    pathData = "M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z",
)

/** 相册（Material Icons "image"；2026-09-05 导航四化后相册 Tab 图标语义） */
val AlbumIcon: ImageVector = materialIcon(
    name = "QimengAlbum",
    pathData = "M21 19V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2zM8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z",
)

/** 数据（Material Icons "analytics" 柱状） */
val StatsIcon: ImageVector = materialIcon(
    name = "QimengStats",
    pathData = "M5 9.2h3V19H5V9.2zM10.6 5h2.8v14h-2.8V5zM16.2 13H19v6h-2.8v-6z",
)

/** 我的（Material Icons "person"） */
val ProfileIcon: ImageVector = materialIcon(
    name = "QimengProfile",
    pathData = "M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z",
)

/** 搜索（Material Icons "search"）：首页搜索框与搜索页共用 */
val SearchIcon: ImageVector = materialIcon(
    name = "QimengSearch",
    pathData = "M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z",
)

/** 返回（Material Icons "arrow_back"）：覆盖页顶栏 */
val BackIcon: ImageVector = materialIcon(
    name = "QimengBack",
    pathData = "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z",
)

/** 清除（Material Icons "close"）：搜索框清除/搜索历史清空 */
val ClearIcon: ImageVector = materialIcon(
    name = "QimengClear",
    pathData = "M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z",
)

// ---------- 网格列数图标（旧版 drawable/ic_grid_* 的 pathData 逐字拷贝；2~5 档=M4-2A-B2，1 档=任务Y Y4a 首页顶栏图标化补齐） ----------

/** 1 列（旧版 ic_grid_1：单列双行大方块；首页列数 1~2 档切换用——任务Y Y4a，2026-09-12） */
val Grid1Icon: ImageVector = multiPathIcon(
    name = "QimengGrid1",
    "M4,4h16v7H4Z",
    "M4,13h16v7H4Z",
)

/** 2 列（旧版 ic_grid_2：2x2 实心方块组） */
val Grid2Icon: ImageVector = multiPathIcon(
    name = "QimengGrid2",
    "M3.5,3.5h8v8h-8Z",
    "M12.5,3.5h8v8h-8Z",
    "M3.5,12.5h8v8h-8Z",
    "M12.5,12.5h8v8h-8Z",
)

/** 3 列（旧版 ic_grid_3：3x2 实心方块组） */
val Grid3Icon: ImageVector = multiPathIcon(
    name = "QimengGrid3",
    "M2.5,3.5h6v8h-6Z",
    "M9.5,3.5h5v8h-5Z",
    "M15,3.5h6.5v8H15Z",
    "M2.5,12.5h6v8h-6Z",
    "M9.5,12.5h5v8h-5Z",
    "M15,12.5h6.5v8H15Z",
)

/** 4 列（旧版 ic_grid_4：4x2 实心方块组） */
val Grid4Icon: ImageVector = multiPathIcon(
    name = "QimengGrid4",
    "M2,3.5h4.5v8H2Z",
    "M7,3.5h4.5v8H7Z",
    "M12.5,3.5h4.5v8h-4.5Z",
    "M18,3.5h4v8h-4Z",
    "M2,12.5h4.5v8H2Z",
    "M7,12.5h4.5v8H7Z",
    "M12.5,12.5h4.5v8h-4.5Z",
    "M18,12.5h4v8h-4Z",
)

/** 5 列（旧版 ic_grid_5：5x2 实心方块组） */
val Grid5Icon: ImageVector = multiPathIcon(
    name = "QimengGrid5",
    "M1.5,3.5h3.5v8H1.5Z",
    "M5.5,3.5h3.5v8H5.5Z",
    "M9.5,3.5h5v8h-5Z",
    "M15,3.5h3.5v8H15Z",
    "M19,3.5h3.5v8H19Z",
    "M1.5,12.5h3.5v8H1.5Z",
    "M5.5,12.5h3.5v8H5.5Z",
    "M9.5,12.5h5v8h-5Z",
    "M15,12.5h3.5v8H15Z",
    "M19,12.5h3.5v8H19Z",
)

/** 列数 → 网格图标（旧版「列数图标随列数切换」；越界 clamp 到 2..5 档） */
fun gridIconFor(columns: Int): ImageVector = when (columns.coerceIn(MIN_GRID_ICON_COLUMNS, MAX_GRID_ICON_COLUMNS)) {
    2 -> Grid2Icon
    3 -> Grid3Icon
    4 -> Grid4Icon
    else -> Grid5Icon
}

/** Material Icons "filter_list" 三横不等长线（M4-2A-B3 曾用作标题行筛选钮；U10-3 勘正旧版
 *  筛选钮实为 [HomeFilterIcon] 软底胶囊款后本图标无消费方，留档勿轻删） */
val FilterListIcon: ImageVector = materialIcon(
    name = "QimengFilterList",
    pathData = "M10 18h4v-2h-4v2zM3 6v2h18V6H3zm3 7h12v-2H6v2z",
)

/**
 * 万能筛选入口（旧版 drawable/ic_home_filter 的 pathData 逐字拷贝：行首圆点+不等长线形，
 * 任务Y Y4a 2026-09-12 引入）。U10-3 勘正：原「首页专用」注释与旧证据矛盾——旧版
 * fragment_home.xml L56 / fragment_all_files.xml L56 / fragment_album_detail.xml L56
 * （另 author_files.xml L66）同用 ic_home_filter，三页通用；新库首页/相册页等筛选入口
 * 统一此款（软底胶囊容器见 QimengTitleRow），勿换 [FilterListIcon]。
 */
val HomeFilterIcon: ImageVector = materialIcon(
    name = "QimengHomeFilter",
    pathData = "M3,7h2v2h-2zM7,7h14v2h-14zM3,11h2v2h-2zM7,11h10v2h-10zM3,15h2v2h-2zM7,15h6v2h-6z",
)

private const val MIN_GRID_ICON_COLUMNS = 2
private const val MAX_GRID_ICON_COLUMNS = 5
