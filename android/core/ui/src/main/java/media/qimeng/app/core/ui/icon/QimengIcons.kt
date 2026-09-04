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
 * 底部五 Tab 图标：运行时解析 Material Icons 官方 path data（Apache-2.0，fonts.google.com/icons 同源）。
 * 为什么不引 material-icons 依赖：本批仅 5 个图标，extended 全量包体积代价不成比例，
 * core 集又缺 grid_view/photo_library/analytics——自持矢量是零依赖解；后续批次若图标增多再评估。
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

/** 全部（Material Icons "apps" 九宫格） */
val AllFilesIcon: ImageVector = materialIcon(
    name = "QimengAllFiles",
    pathData = "M4 8h4V4H4v4zm6 12h4v-4h-4v4zm-6 0h4v-4H4v4zm0-6h4v-4H4v4zm6 0h4v-4h-4v4zm6-10v4h4V4h-4zm-6 4h4V4h-4v4zm6 6h4v-4h-4v4zm0 6h4v-4h-4v4z",
)

/** 相册（Material Icons "image"） */
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
