package media.qimeng.app.feature.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import media.qimeng.app.core.ui.component.QimengCapsuleTextField
import media.qimeng.app.core.ui.glass.GlassSurface
import media.qimeng.app.core.ui.glass.pressScale
import media.qimeng.app.core.ui.glass.rememberPressScaleSource
import media.qimeng.app.core.ui.theme.QimengDimens
import media.qimeng.app.core.ui.theme.qimengFilledButtonColors
import media.qimeng.app.feature.detail.video.TimelineTagEntity
import media.qimeng.app.feature.detail.video.formatDurationMs

/** 快捷标记胶囊圆角（S1c 对齐旧版快捷键胶囊 16dp 档；与芯片 100dp 胶囊语言区分，任务书冻结） */
private val TAG_QUICK_CAPSULE_CORNER_RADIUS = 16.dp

/** 快捷标记胶囊内边距（S1c 对齐旧版快捷键：横 16dp / 纵 10dp；纵 10dp 无既有 token，本文件单源） */
private val TAG_QUICK_CAPSULE_PADDING = PaddingValues(horizontal = 16.dp, vertical = 10.dp)

/**
 * 时间轴标签添加 BottomSheet（3d → S1c 2026-09-13 对齐旧版添加标记形态）：底部弹出（顶部
 * 圆角 + surface 底、无拖拽把手；点外部/下滑/返回键关闭），**无「取消」按钮**。内容自上
 *而下：居中粗体标题 + 「当前时间」副标题（[currentPositionMs] =调用方打开瞬间捕获的位置
 * 快照，本组件不读播放器）+「快捷标记」两枚浅灰胶囊（点击**立即**写入完整名并收起，不再
 * 经过输入框）+「自定义标记」输入行（非空才放行「添加」，写入 trim 值）。
 * 快捷写入字面量 = [TimelineTagColors.HEART_TAG]/[STAR_TAG] + 空格 + 标签名（单源口径
 * 延续 2026-09-07 审查 P2，芯片底色按裸前缀判定兼容变体符，见 TimelineTagColors KDoc）；
 * 胶囊文字用主题 onSurface 对齐旧版浅灰胶囊（emoji 自带色，不再红/金染色）。
 * Sheet 陷阱处置沿用 DetailAuthorSheet 现口径（alpha15 短内容 sheet 三陷阱：dragHandle=
 * null + standardWindowInsets + 点外/返回可关，沿革与实证见 DetailSheets.kt KDoc）；
 * 底部导航栏遮挡由 standardWindowInsets 底部段承担（DetailSheets 既有同款，不再叠加
 * navigationBarsPadding 双计入）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimelineTagAddDialog(
    currentPositionMs: Long,
    onAdd: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 输入态随 Sheet 销毁复位（Sheet 由调用方关闭，卸载即清，无需 onAdd 后手动清）
    var input by remember { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // 短内容 sheet 三陷阱处置同 DetailAuthorSheet（S1b 现口径），沿革见其 KDoc
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
                // 去 handle 后内容顶到圆角边，补顶距档（DetailAuthorSheet 同款）；底部 24dp
                // 呼吸与页面级留白同档（DETAIL_BOTTOM_SPACER 单源在 DetailScreen.kt）
                .padding(top = QimengDimens.ScreenPaddingTop)
                .padding(horizontal = QimengDimens.ScreenPaddingHorizontal)
                .padding(bottom = DETAIL_BOTTOM_SPACER),
        ) {
            // ① 标题 + ② 当前时间（Column 默认 Start 对齐小节标签，居中文本走
            // fillMaxWidth + textAlign 局部居中）
            Text(
                text = stringResource(R.string.detail_video_tag_add_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = QimengDimens.SpaceM),
            )
            Text(
                text = stringResource(
                    R.string.detail_video_tag_current_time,
                    formatDurationMs(currentPositionMs),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            // ③④ 快捷标记：点击立即写入完整名并收起（旧版同款，不碰输入框）；
            // 胶囊显示文本同写入字面量（❤️/⭐ + 空格 + 名，旧版 BottomSheet 同观感）
            TagSectionLabel(text = stringResource(R.string.detail_video_tag_section_quick))
            val quickLike = stringResource(R.string.detail_video_tag_quick_like)
            val quickFavorite = stringResource(R.string.detail_video_tag_quick_favorite)
            Row(horizontalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM)) {
                TagQuickCapsule(label = TimelineTagColors.HEART_TAG + " " + quickLike) {
                    onAdd(TimelineTagColors.HEART_TAG + " " + quickLike)
                }
                TagQuickCapsule(label = TimelineTagColors.STAR_TAG + " " + quickFavorite) {
                    onAdd(TimelineTagColors.STAR_TAG + " " + quickFavorite)
                }
            }
            // ⑤⑥ 自定义标记：非空才放行，写入 trim 值后由调用方收起
            TagSectionLabel(text = stringResource(R.string.detail_video_tag_section_custom))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM),
            ) {
                QimengCapsuleTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    // 胶囊输入框 label 走 placeholder 语义（G6：对齐 Web，组件不支持 label 浮动）
                    placeholder = stringResource(R.string.detail_video_tag_input_hint),
                    modifier = Modifier.weight(1f),
                )
                Button(
                    enabled = input.isNotBlank(),
                    onClick = { onAdd(input.trim()) },
                    // 可禁用实底按钮统一入口（W6 #49：夜间禁用态防 dither 横带，禁止裸 buttonColors）
                    colors = qimengFilledButtonColors(),
                    shape = RoundedCornerShape(QimengDimens.PillCornerRadius),
                ) {
                    Text(text = stringResource(R.string.detail_video_tag_add_action))
                }
            }
        }
    }
}

/** 添加 Sheet 小节标签（③/⑤：左对齐灰色小字，旧版 section label 同款） */
@Composable
private fun TagSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = QimengDimens.SpaceL, bottom = QimengDimens.SpaceM),
    )
}

/** 快捷标记胶囊（③：玻璃圆角胶囊 + 主题色文字；emoji 自带色不染色，对齐旧版；
 *  2026-10-03 玻璃语言统一批：surfaceVariant 实底 → 素玻璃 compact 档） */
@Composable
private fun TagQuickCapsule(label: String, onClick: () -> Unit) {
    val interaction = rememberPressScaleSource()
    GlassSurface(
        shape = RoundedCornerShape(TAG_QUICK_CAPSULE_CORNER_RADIUS),
        compact = true,
        // 无 indication（默认矩形 ripple 框出方框）+ spring 缩放反馈
        modifier = Modifier
            .pressScale(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(TAG_QUICK_CAPSULE_PADDING),
        )
    }
}

/**
 * 时间轴标签长按菜单（3d 旧版语义「跳转/删除」而非直接删）：
 * [domainTagId] 为空（实体映射失配，理论不发生的兜底）时删除键置灰。
 */
@Composable
internal fun TimelineTagMenuDialog(
    tag: TimelineTagEntity,
    domainTagId: String?,
    onSeekToTag: () -> Unit,
    onDelete: (tagId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.detail_video_tag_menu_title)) },
        text = {
            // 文案展示桥接件同款「时间 + 名」（formatMs internal 于 video 包，此处复刻最小展示）
            Text(text = "${tag.timeMillis / 1000}s ${tag.name}")
        },
        confirmButton = {
            TextButton(onClick = onSeekToTag) { Text(stringResource(R.string.detail_video_tag_menu_jump)) }
        },
        dismissButton = {
            TextButton(
                enabled = domainTagId != null,
                onClick = { domainTagId?.let(onDelete) },
            ) { Text(stringResource(R.string.detail_video_tag_menu_delete)) }
        },
    )
}
