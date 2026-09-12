package media.qimeng.app.feature.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import media.qimeng.app.core.model.AssetDetail
import media.qimeng.app.core.model.DetailAuthor
import media.qimeng.app.core.ui.component.detailDirectoryLabel
import media.qimeng.app.core.ui.component.detailTypeLabel
import media.qimeng.app.core.ui.component.formatBytesForDetail
import media.qimeng.app.core.ui.component.formatDurationBadge
import media.qimeng.app.core.ui.component.formatShortDate
import media.qimeng.app.core.ui.icon.ChevronRightIcon
import media.qimeng.app.core.ui.theme.QimengDimens

/**
 * 信息 BottomSheet（任务I I7 → 任务X X3 旧版移植扩行，2026-09-12 用户拍板「详细信息应该
 * 和旧版移植」；行序对齐旧版 showInfoSheet=QimengMedia MediaDetailFragment:1026-1070 + 作品行）：
 * 文件名 / 作品 / 出处 / 日期 / 大小 / 类型 / 尺寸 / 时长 / 目录 / 路径（label 小字 + value
 * 双段式，InfoRow 同构旧版 addInfoRow）。
 * 可选行口径（沿用本 Sheet 既有「null/0 不渲染」）：作品/出处空值不渲染；日期空值
 * （formatShortDate 空串）不渲染；尺寸 width/height 齐备且 >0 才渲染；时长仅视频资产有值；
 * 路径 relPath 空串（服务端未返回）不渲染。恒显行：大小（null 按 Web sizeBytes ?? 0 语义
 * = 0 B）、类型（中文媒体类型+小写扩展名「视频 · mp4」式，无扩展名仅类型名）、目录
 * （空 = 库根「/」）。时长/尺寸 durationMs/width/height 直读协议（服务端扫描已产，旧版
 * 按需解码流已作废，null=未知→行隐藏）。
 * **无「完成」按钮**（L169：BottomSheet 下滑手势 / 点外部空白关闭即可）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailInfoSheet(asset: AssetDetail, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = QimengDimens.ScreenPaddingHorizontal)
                // 底部呼吸空间与页面级留白同档（DETAIL_BOTTOM_SPACER 单源在 DetailScreen.kt）
                .padding(bottom = DETAIL_BOTTOM_SPACER),
        ) {
            Text(
                text = stringResource(R.string.detail_info_sheet_title),
                style = MaterialTheme.typography.titleLarge,
            )
            InfoRow(label = stringResource(R.string.detail_info_filename), value = asset.fileName)
            // 作品（X3 补行）：cosWork 非空才显示——title=cosWork??fileName 是下滑区标题
            // 口径，Sheet 作品行按旧版规格独立展示 cosWork 原值
            asset.cosWork?.takeIf { it.isNotEmpty() }?.let { work ->
                InfoRow(label = stringResource(R.string.detail_info_work), value = work)
            }
            asset.source?.takeIf { it.isNotEmpty() }?.let { source ->
                InfoRow(label = stringResource(R.string.detail_info_source), value = source)
            }
            // 日期（X3 补行）：M-D 短日期（与 Web formatShortDate 同函数口径），空值不渲染
            formatShortDate(asset.modifiedAtMs).takeIf { it.isNotEmpty() }?.let { date ->
                InfoRow(label = stringResource(R.string.detail_info_date), value = date)
            }
            // 大小（X3 补行）：恒显，空值语义 = 0 B（Web sizeBytes ?? 0，口径单源）
            InfoRow(
                label = stringResource(R.string.detail_info_size),
                value = formatBytesForDetail(asset.sizeBytes),
            )
            // 类型（X3 补行）：「视频 · mp4」式（中文媒体类型+小写扩展名；无扩展名仅类型名）
            InfoRow(
                label = stringResource(R.string.detail_info_type),
                value = detailTypeLabel(asset.mediaType, asset.fileName),
            )
            val w = asset.width
            val h = asset.height
            if (w != null && h != null && w > 0 && h > 0) {
                InfoRow(
                    label = stringResource(R.string.detail_info_resolution),
                    // 值格式与原 meta 行同串资源（%1$d×%2$d），口径单源
                    value = stringResource(R.string.detail_meta_resolution, w, h),
                )
            }
            formatDurationBadge(asset.durationMs)?.let { duration ->
                InfoRow(label = stringResource(R.string.detail_info_duration), value = duration)
            }
            // 目录（X3 补行）：空/null = 库根显示「/」（detailDirectoryLabel 单源回退）
            InfoRow(
                label = stringResource(R.string.detail_info_directory),
                value = detailDirectoryLabel(asset.directory),
            )
            // 路径（X3 补行）：库内相对路径；空串（服务端未返回）不渲染
            asset.relPath.takeIf { it.isNotEmpty() }?.let { relPath ->
                InfoRow(label = stringResource(R.string.detail_info_path), value = relPath)
            }
        }
    }
}

/** 信息弹窗行（label 小字次色 + value 正文主色；旧版 addInfoRow 的两段式同构） */
@Composable
private fun InfoRow(label: String, value: String) {
    Column(modifier = Modifier.padding(top = QimengDimens.SpaceM)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = QimengDimens.SpaceXS),
        )
    }
}

/**
 * 快速转跳 BottomSheet（任务I I7，GUIDE_UI §详情页 L172）：当前文件关联的作者列表，
 * 点击作者名跳转作者集合页（既有路由，与 DetailAuthorSheet「进入作者主页」同一条
 * onOpenAuthor 链——Web「快速转跳跳作者文件浏览页」的新架构对应落点）。V3 起无入口
 * （「快速转跳」不进四胶囊，组件保留裁决）。无作者=空态提示；无「完成」按钮（同信息弹窗口径）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailJumpSheet(
    authors: List<DetailAuthor>,
    onOpenAuthor: (authorId: String, displayName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = QimengDimens.ScreenPaddingHorizontal)
                .padding(bottom = DETAIL_BOTTOM_SPACER),
        ) {
            Text(
                text = stringResource(R.string.detail_jump_sheet_title),
                style = MaterialTheme.typography.titleLarge,
            )
            if (authors.isEmpty()) {
                Text(
                    text = stringResource(R.string.detail_jump_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = QimengDimens.SpaceM),
                )
            } else {
                val cosSuffix = stringResource(R.string.detail_author_cos_suffix)
                authors.forEach { author ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onOpenAuthor(author.id, author.displayName)
                                onDismiss()
                            }
                            .padding(vertical = QimengDimens.SpaceM),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (author.isCos) author.displayName + cosSuffix else author.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = ChevronRightIcon,
                            contentDescription = null, // 行整体可点，chevron 纯装饰
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 作者 BottomSheet（任务W W3）：内容=原下滑区作者卡（W3 随下滑区裁剪退役）整体移植——
 * displayName·COS、关注/取关钮（toggleFollow 既有链闭环可用）、「进入作者主页」（壳层
 * onOpenAuthor → authorCollection 既有路由，跳转后随 Sheet 关闭）。无作者不渲染条目（胶囊
 * 恒可点开空 Sheet，与「该文件暂无关联作者」空态口径一致）。无「完成」按钮（返回键/下滑
 * 手势/点外 scrim 关闭，见下）。
 *
 * 为什么不用 ModalBottomSheet 缺省形态（任务W W3 P1 修复，2026-09-12 模拟器走查实证）：
 * material3 1.5.0-alpha15 重写件对**短内容** sheet 有三个叠加陷阱——①拖拽 handle 变为
 * 「点击即收起」（Expanded 态点 handle = animateToDismiss）；②show 动画是会过冲回弹的
 * spring（组件内部 verticalScaleUp 注释明言 bounce），初开窗口期内容布局与视觉位置存在
 * 百像素级瞬态偏差；③缺省 modalWindowInsets 给内容加 statusBars 顶部死区。作者 Sheet
 * 内容仅两行，三者叠加导致按视觉坐标点「+关注」/「进入作者主页」命中 scrim/handle/惰性
 * 区，按钮 onClick 从未收到事件（实证：服务端零 follow 请求、零导航，Sheet 反被关闭）。
 * 处置沿革：W3 当时三陷阱全规避——去 handle + scrim 点击不再收起（防误触吞 Sheet）+
 * insets 收窄为仅底部并补顶部小间距。现口径（S1b 2026-09-12 用户拍板「全修复 W9 三项
 * 之二」）：dragHandle=null（防 handle 点击即收起）与 standardWindowInsets（防 statusBars
 * 死区吞 onClick）两陷阱处置仍生效；点外关闭已恢复——shouldDismissOnClickOutside 按拍板
 * 回到 true（scrim 点击收起），返回键与下滑手势出口本就保留、不动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailAuthorSheet(
    authors: List<DetailAuthor>,
    followPending: Boolean,
    onToggleFollow: (String) -> Unit,
    onOpenAuthor: (authorId: String, displayName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // 三个缺省行为陷阱的处置沿革与现口径（S1b 点外关闭已恢复）见上 KDoc（alpha15 短内容 sheet P1 实证）
        dragHandle = null,
        contentWindowInsets = { BottomSheetDefaults.standardWindowInsets },
        properties = ModalBottomSheetProperties(
            shouldDismissOnBackPress = true,
            shouldDismissOnClickOutside = true,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 去 handle 后内容顶到圆角边，补屏幕顶距档间距（QimengDimens 单源）
                .padding(top = QimengDimens.ScreenPaddingTop)
                .padding(horizontal = QimengDimens.ScreenPaddingHorizontal)
                .padding(bottom = DETAIL_BOTTOM_SPACER),
        ) {
            Text(
                text = stringResource(R.string.detail_authors_title),
                style = MaterialTheme.typography.titleLarge,
            )
            if (authors.isEmpty()) {
                Text(
                    text = stringResource(R.string.detail_jump_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = QimengDimens.SpaceM),
                )
            } else {
                val cosSuffix = stringResource(R.string.detail_author_cos_suffix)
                val enterHomeLabel = stringResource(R.string.detail_author_open_collection)
                authors.forEach { author ->
                    // 名行（displayName·COS + 关注钮）=原卡行结构移植（名字不再是链接——
                    // 跳转语义收敛到下方专用钮，单入口不重复）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = QimengDimens.SpaceM),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (author.isCos) author.displayName + cosSuffix else author.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        FollowButton(
                            followed = author.followed,
                            enabled = !followPending,
                            onClick = { onToggleFollow(author.id) },
                        )
                    }
                    // 进入作者主页（authorCollection 路由）：跳转即收 Sheet（避免返回栈中间
                    // 残留打开态，返回时重弹）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onOpenAuthor(author.id, author.displayName)
                                onDismiss()
                            }
                            .padding(vertical = QimengDimens.SpaceM),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = enterHomeLabel,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = ChevronRightIcon,
                            contentDescription = null, // 行整体可点，chevron 纯装饰
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 关注按钮（Web .follow-btn：已关注=灰底、「+ 关注」=主色底；原作者卡同名件随迁） */
@Composable
private fun FollowButton(followed: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(QimengDimens.PillCornerRadius),
        color = if (followed) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.primary
        },
        contentColor = if (followed) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onPrimary
        },
    ) {
        Text(
            text = stringResource(if (followed) R.string.detail_followed else R.string.detail_follow),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(
                horizontal = QimengDimens.ChipHorizontalPadding,
                vertical = QimengDimens.SpaceS,
            ),
        )
    }
}
