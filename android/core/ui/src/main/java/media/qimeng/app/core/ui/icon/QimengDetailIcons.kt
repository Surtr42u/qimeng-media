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
 * 详情页图标（M4-3）：点赞/收藏/播放。
 * 为什么不进 QimengIcons.kt：其 materialIcon helper 是 file-private，且该文件属 M4-2A
 * 并行任务已改文件（B2 列数图标）禁碰——helper 在本文件复制同款（注释互指，合并后可收拢）。
 * path data 一律 Material Icons 官方（fonts.google.com/icons 同源，与 QimengIcons 注释口径一致）。
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

/** 点赞（Material Icons "thumb_up"；Web 详情互动行 ThumbsUp 同源） */
val ThumbUpIcon: ImageVector = materialIcon(
    name = "QimengThumbUp",
    pathData = "M1 21h4V9H1v12zm22-11c0-1.1-.9-2-2-2h-6.31l.95-4.57.03-.32c0-.41-.17-.79-.44-1.06L14.17 1 7.59 7.59C7.22 7.95 7 8.45 7 9v10c0 1.1.9 2 2 2h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23.14-.47.14-.73v-2z",
)

/** 收藏（Material Icons "star"；实心=已收藏态，空心由 tint/描边层表达） */
val StarIcon: ImageVector = materialIcon(
    name = "QimengStar",
    pathData = "M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z",
)

/** 播放（Material Icons "play_arrow"）：视频舞台中央占位播放钮（3a），3b 接播放器 */
val PlayIcon: ImageVector = materialIcon(
    name = "QimengPlay",
    pathData = "M8 5v14l11-7z",
)
