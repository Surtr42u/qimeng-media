package media.qimeng.app.feature.detail

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import media.qimeng.app.core.ui.theme.QimengDimens

/**
 * 详情页沉浸 chrome 图标（任务I I7，GUIDE_UI §详情页 L171/172）：信息/标签/作者三枚。
 * （原第三枚快速转跳 DetailPeopleIcon 已随任务V V3 四胶囊重排删除——「快速转跳」不进
 * 四胶囊，首屏入口随旧图标行消失，孤儿图标一并清偿；ic_detail_jump.xml 不在此列——
 * 它是 BiliPlayerView 播放器内部控件资源，与本文件无关仍被引用。）
 *
 * 为什么不进 :core:ui QimengDetailIcons.kt：I 卷共享文件冻结（本批独占面 = feature:detail 目录），
 * materialIcon helper 在本文件复制 QimengDetailIcons 同款（构造参数逐字一致，注释互指——
 * I9 收官后的共享文件窗口可收拢）。path data 一律 Material Icons 官方
 * （fonts.google.com/icons 同源 24px.svg，2026-09-09 核对）。
 */
private const val ICON_VIEWPORT = 24f

/** 与 :core:ui QimengDetailIcons 的 file-private helper 同款（见该文件注释互指） */
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

/** 信息（Material Icons "info"）：顶部 chrome 信息钮——点开信息 BottomSheet（文件名/出处/尺寸/时长） */
val DetailInfoIcon: ImageVector = materialIcon(
    name = "QimengDetailInfo",
    pathData = "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z",
)

/**
 * 标签（Material Icons "sell"）：底部 chrome 标签管理入口钮——点开标签管理弹窗
 * （与内容区标签行「管理」入口同链）。
 */
val DetailSellIcon: ImageVector = materialIcon(
    name = "QimengDetailSell",
    pathData = "M21.41 11.58l-9-9C12.05 2.22 11.55 2 11 2H4c-1.1 0-2 .9-2 2v7c0 .55.22 1.05.59 1.42l9 9c.36.36.86.58 1.41.58.55 0 1.05-.22 1.41-.59l7-7c.37-.36.59-.86.59-1.41 0-.55-.23-1.06-.59-1.42zM5.5 7C4.67 7 4 6.33 4 5.5S4.67 4 5.5 4 7 4.67 7 5.5 6.33 7 5.5 7z",
)

/**
 * 作者（Material Icons "person"）：底部 chrome 作者胶囊钮（任务W W3）——点开作者
 * BottomSheet（DetailAuthorSheet：displayName·COS、关注/取关、进入作者主页）。
 */
val DetailAuthorIcon: ImageVector = materialIcon(
    name = "QimengDetailAuthor",
    pathData = "M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z",
)
