package media.qimeng.app.core.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 界面图标：运行时解析 Material Icons 官方 path data（Apache-2.0，fonts.google.com/icons 同源）。
 * 为什么不引 material-icons 依赖：当前用量小，extended 全量包体积代价不成比例——自持矢量是零依赖解。
 */
private const val ICON_VIEWPORT = 24f

private fun materialIcon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
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
