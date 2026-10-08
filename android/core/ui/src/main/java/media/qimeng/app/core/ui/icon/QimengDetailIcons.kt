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
 * 任务Y Y2（2026-09-12）：收藏由星形对（star/star_border）换心形对（favorite 系）对齐
 * 旧版字形——旧仓库收藏钮一年即心形，星形系 G1a 对 Web lucide 的近似（用户真机反馈纠偏）。
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

/**
 * 收藏（Material Icons "favorite" 实心心形；任务Y Y2 由 star 星形换心形对齐旧版——
 * 旧仓库详情页收藏钮 drawable 即 ic_detail_favorite_filled，与 Material favorite
 * 24px.svg 几何同源（仅逗号格式差异，2026-09-12 与旧仓库逐字核对）；星形仅本处使用，
 * 随替换退役）。实心=已收藏态，空心见 [FavoriteBorderIcon]。
 */
val FavoriteFilledIcon: ImageVector = materialIcon(
    name = "QimengFavoriteFilled",
    pathData = "M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z",
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
 * 收藏未激活态（Material Icons "favorite_border" 空心心形，G1a 引入星形对、任务Y Y2 换
 * 心形对齐旧版）：与 [FavoriteFilledIcon]（实心）配对，未收藏=空心 / 已收藏=实心
 * （旧仓库 ic_detail_favorite / ic_detail_favorite_filled 同语义双 drawable，几何同源
 * Material favorite_border 24px）。
 */
val FavoriteBorderIcon: ImageVector = materialIcon(
    name = "QimengFavoriteBorder",
    pathData = "M16.5 3c-1.74 0-3.41.81-4.5 2.09C10.91 3.81 9.24 3 7.5 3 4.42 3 2 5.42 2 8.5c0 3.78 3.4 6.86 8.55 11.54L12 21.35l1.45-1.32C18.6 15.36 22 12.28 22 8.5 22 5.42 19.58 3 16.5 3zm-4.4 15.55l-.1.1-.1-.1C7.14 14.24 4 11.39 4 8.5 4 6.5 5.5 5 7.5 5c1.54 0 3.04.99 3.57 2.36h1.87C13.46 5.99 14.96 5 16.5 5c2 0 3.5 1.5 3.5 3.5 0 2.89-3.14 5.74-7.9 10.05z",
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

/** 编辑（Material Icons "edit"）：详情页作者弹层「编辑作者与来源」等入口使用 */
val EditPencilIcon: ImageVector = materialIcon(
    name = "QimengEditPencil",
    pathData = "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z",
)
