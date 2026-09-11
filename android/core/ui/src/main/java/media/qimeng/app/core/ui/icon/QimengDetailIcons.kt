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
 * 详情页图标（M4-3）：点赞/收藏/播放；任务G G1a 补批次 pager 双 chevron 与互动行
 * 未激活态双 outlined 变体（对齐 Web 详情页 active=图标实底填充 / 未激活=描边形态）。
 * 为什么不进 QimengIcons.kt：其 materialIcon helper 是 file-private，且该文件属 M4-2A
 * 并行任务已改文件（B2 列数图标）禁碰——helper 在本文件复制同款（注释互指，合并后可收拢）。
 * path data 一律 Material Icons 官方（fonts.google.com/icons 同源，与 QimengIcons 注释口径一致；
 * G1a 新增四枚逐字取自 google/material-design-icons 仓库 src/ 目录 24px.svg，2026-09-08 核对）。
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

/**
 * 点赞未激活态（Material Icons "thumb_up" materialiconsoutlined 变体，G1a）：
 * Web 详情互动行 lucide ThumbsUp 默认描边形态——active 才 fill:currentColor，
 * Compose 侧以 outlined/filled 双矢量表达同一形态切换。
 */
val ThumbUpOutlinedIcon: ImageVector = materialIcon(
    name = "QimengThumbUpOutlined",
    pathData = "M9 21h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23.14-.47.14-.73v-2c0-1.1-.9-2-2-2h-6.31l.95-4.57.03-.32c0-.41-.17-.79-.44-1.06L14.17 1 7.58 7.59C7.22 7.95 7 8.45 7 9v10c0 1.1.9 2 2 2zM9 9l4.34-4.34L12 10h9v2l-3 7H9V9zM1 9h4v12H1z",
)

/**
 * 收藏未激活态（Material Icons "star_border"，G1a）：与 [StarIcon]（实心）配对，
 * 未收藏=描边星 / 已收藏=实心星（Web lucide Star + active fill 同语义）。
 */
val StarOutlinedIcon: ImageVector = materialIcon(
    name = "QimengStarOutlined",
    pathData = "M22 9.24l-7.19-.62L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21 12 17.27 18.18 21l-1.63-7.03L22 9.24zM12 15.4l-3.76 2.27 1-4.28-3.32-2.88 4.38-.38L12 6.1l1.71 4.04 4.38.38-3.32 2.88 1 4.28L12 15.4z",
)

/** 左 chevron（Material Icons "chevron_left"，G1a）：详情批次 pager「上一件」钮前缀图标（Web ChevronLeft 同源） */
val ChevronLeftIcon: ImageVector = materialIcon(
    name = "QimengChevronLeft",
    pathData = "M15.41 7.41L14 6l-6 6 6 6 1.41-1.41L10.83 12z",
)

/** 右 chevron（Material Icons "chevron_right"，G1a）：详情批次 pager「下一件」钮后缀图标（Web ChevronRight 同源） */
val ChevronRightIcon: ImageVector = materialIcon(
    name = "QimengChevronRight",
    pathData = "M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z",
)

/**
 * 删除（Material Icons "delete"，任务G G1b）：互动行「删除」钮图标（Web FileOpsButton 用
 * lucide Trash2 同语义）；语义 = 移入回收站（铁律 4），非物理删除。
 */
val DeleteIcon: ImageVector = materialIcon(
    name = "QimengDelete",
    pathData = "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z",
)
