package media.qimeng.app.feature.author

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.clickable
import media.qimeng.app.core.model.AuthorType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import media.qimeng.app.core.ui.theme.softDaylightBackground
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.qimeng.app.core.model.AuthorSortOption
import media.qimeng.app.core.model.AuthorSummary
import media.qimeng.app.core.model.Zone
import media.qimeng.app.core.ui.component.QimengCapsuleTextField
import media.qimeng.app.core.ui.component.QimengChipRow
import media.qimeng.app.core.ui.component.QimengMessageCard
import media.qimeng.app.core.ui.component.QimengPill
import media.qimeng.app.core.ui.component.QimengPullToRefresh
import media.qimeng.app.core.ui.component.QimengSegPill
import media.qimeng.app.core.ui.component.QimengTopBar
import media.qimeng.app.core.ui.icon.MoreVertIcon
import media.qimeng.app.core.ui.icon.EditPencilIcon
import media.qimeng.app.core.ui.icon.DeleteIcon
import media.qimeng.app.core.ui.icon.LabelTagIcon
import media.qimeng.app.core.ui.icon.SearchIcon
import media.qimeng.app.core.ui.theme.QimengDimens

/** 作者管理胶囊（全部/常规/COS）+ 排序三项（默认/浏览数/文件数） */
private val ZONE_OPTIONS = listOf("全部" to Zone.ALL, "常规" to Zone.REGULAR, "COS" to Zone.COS)

/** 排序单钮面文字（GUIDE_UI §芯片栏配置对比 L73 作者管理行「排序 ▾」逐字） */
private const val SORT_BUTTON_LABEL = "排序 ▾"

/** 计数行文案前缀（G2：Web AuthorsPage page-head 副行「全部作者 · N 位」） */
private const val COUNT_ROW_PREFIX = "全部作者"

/** 空态文案（Web .a-empty：列表无匹配行时卡内小字） */
private const val EMPTY_AUTHORS = "暂无作者"

// —— 作者管理族文案（2026-10-13，ADR-0024 修订 5）——

/** 新建作者入口胶囊 */
private const val CREATE_AUTHOR_LABEL = "新建作者"

/** 行操作菜单入口无障碍描述 */
private const val AUTHOR_MENU_DESC = "作者操作"

/** 行操作菜单项：改名 */
private const val RENAME_MENU_LABEL = "改名"

/** 行操作菜单项/删除确认钮：删除 */
private const val DELETE_MENU_LABEL = "删除"

/** 新建对话框标题 */
private const val CREATE_DIALOG_TITLE = "新建作者"

/** 改名对话框标题 */
private const val RENAME_DIALOG_TITLE = "修改作者名"

/** 删除确认对话框标题 */
private const val DELETE_DIALOG_TITLE = "删除作者"

/** 输入框占位 */
private const val AUTHOR_NAME_PLACEHOLDER = "作者名"

/** 别名分隔说明（与服务端规范化口径一致：显示名以「 / 」连接别名） */
private const val AUTHOR_ALIAS_HINT = "多个别名用「 / 」分隔（如：hornyherring / Horny Herring Studios）"

/** 对话框确定/取消/进行中文案 */
private const val CONFIRM_LABEL = "确定"
private const val CANCEL_LABEL = "取消"
private const val SUBMITTING_LABEL = "处理中…"
private const val DELETING_LABEL = "删除中…"

/**
 * 作者管理页（M4-2 覆盖页，G2 对齐 Web AuthorsPage 形态重排）：计数行 + 体系胶囊 +
 * 名字搜索 + 排序三项 + 关注 toggle（GET /authors 全量 + PUT /authors/{authorId}/follow）。
 * 独立作者卡片按可见项读取头像；点击进入现有作者作品页。
 *
 * @param onAuthorClick 行点击（authorId + 原始 displayName 不带「 ·COS」后缀——Web 跳转
 *   用原始名 + URL 编码同语义）。
 */
@Composable
fun AuthorScreen(
    onBack: () -> Unit,
    onAuthorClick: (authorId: String, displayName: String) -> Unit = { _, _ -> },
    viewModel: AuthorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().softDaylightBackground()) {
        QimengTopBar(title = "作者管理", onBack = onBack, actions = {
            if (state.zone != Zone.COS) TextButton(onClick = viewModel::openCreateDialog) { Text(CREATE_AUTHOR_LABEL) }
        })

        // 计数行（G2：Web page-head 副行；N=全量作者数不随体系/关键词过滤，
        // 计数口径在 ViewModel.authorCount——UI 不内嵌业务规则）
        // 任务Z Z3 排版放松：旧版三段节奏 12/8/8（计数行→搜索 12dp）——新此前与搜索框
        // 零纵向间距显得拥挤，补 SpaceL=12dp 下距（现 Dimens 档）
        Text(
            text = "$COUNT_ROW_PREFIX · ${viewModel.authorCount(state)} 位",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(horizontal = QimengDimens.ScreenPaddingHorizontal)
                .padding(bottom = QimengDimens.SpaceL),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = QimengDimens.ScreenPaddingHorizontal)
                // 任务Z Z3：搜索框→芯片行补 8dp（旧节奏 12/8/8 第二段）
                .padding(bottom = QimengDimens.SpaceM),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QimengCapsuleTextField(
                value = state.keyword,
                onValueChange = viewModel::onKeywordChange,
                placeholder = "按名字搜索",
                // 对齐 Web .hist-search：胶囊搜索框带前置放大镜（decorative，placeholder 已表意）
                leadingIcon = {
                    Icon(imageVector = SearchIcon, contentDescription = null)
                },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )

        }
        // 体系胶囊行 + 排序单钮同行（GUIDE_UI §芯片栏配置对比 L73 作者管理行：左侧
        // 全部/常规/COS、右侧「排序 ▾」——排序对所有分类生效）。任务I I6 回改：G2 的
        // 三枚排序胶囊直排（Web AuthorsPage 形态，无拍板保护）按 GUIDE_UI 回改单钮+下拉，
        // 排序功能语义不变（applyAuthorRows 对体系+关键词后排序生效，ViewModel 零改动）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = QimengDimens.ScreenPaddingHorizontal)
                // 任务Z Z3：芯片行→列表卡补 8dp（旧节奏 12/8/8 第三段）
                .padding(bottom = QimengDimens.SpaceM),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QimengChipRow(
                pills = ZONE_OPTIONS.map { (label, zone) ->
                    QimengPill(text = label, selected = zone == state.zone)
                },
                onPillClick = { index -> viewModel.selectZone(ZONE_OPTIONS[index].second) },
                modifier = Modifier.weight(1f),
            )
            AuthorSortMenuButton(
                current = state.sort,
                onSelect = viewModel::selectSort,
                modifier = Modifier.padding(start = QimengDimens.SpaceS),
            )
        }

        state.errorMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = QimengDimens.ScreenPaddingHorizontal),
            )
        }
        // 管理操作成功提示（一次性横幅，点击消除；新建/改名/删除共用）
        state.noticeMessage?.let { message ->
            QimengMessageCard(
                text = message,
                container = MaterialTheme.colorScheme.tertiaryContainer,
                onDismiss = viewModel::clearNotice,
                modifier = Modifier.padding(
                    horizontal = QimengDimens.ScreenPaddingHorizontal,
                    vertical = QimengDimens.SpaceS,
                ),
            )
        }

        QimengPullToRefresh(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.weight(1f),
        ) {
            val rows = remember(state.authors, state.zone, state.keyword, state.sort) {
                viewModel.visibleRows(state)
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = QimengDimens.ScreenPaddingHorizontal, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (rows.isEmpty() && !state.isLoading) item {
                    Text(
                        text = if (state.errorMessage != null) "读取失败，请下拉重试" else if (state.keyword.isNotBlank()) "没有匹配的作者" else EMPTY_AUTHORS,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                items(rows, key = { it.id }) { author ->
                    AuthorRow(
                        author = author,
                        loadAvatar = viewModel::avatar,
                        avatarKey = viewModel.avatarKey(author.id),
                        initialAvatar = viewModel.cachedAvatar(viewModel.avatarKey(author.id)),
                        onToggleFollow = { viewModel.toggleFollow(author) },
                        onAuthorClick = onAuthorClick,
                        onSources = { viewModel.openSourcesEditor(author) },
                        onRename = { viewModel.openRenameDialog(author) },
                        onDelete = { viewModel.openDeleteDialog(author) },
                    )
                }
            }
        }
    }

    state.sourcesEditor?.let { editor ->
        AuthorSourcesDialog(editor, viewModel::changeSources, viewModel::saveSources,
            { viewModel.openSourcesEditor(editor.author) }, viewModel::dismissSourcesEditor)
    }

    // 作者管理对话框（新建/改名共用输入框；删除走确认框）——状态机与提交在 ViewModel
    when (val dialog = state.manageDialog) {
        null -> Unit

        is AuthorManageDialog.Delete -> AuthorDeleteDialog(
            displayName = dialog.displayName,
            fileCount = dialog.fileCount,
            error = state.errorMessage,
            submitting = state.manageSubmitting,
            onConfirm = viewModel::submitManageDialog,
            onDismiss = viewModel::dismissManageDialog,
        )

        else -> AuthorNameDialog(
            title = if (dialog is AuthorManageDialog.Rename) RENAME_DIALOG_TITLE else CREATE_DIALOG_TITLE,
            initial = (dialog as? AuthorManageDialog.Rename)?.currentDisplayName ?: "",
            value = state.manageInput,
            error = state.errorMessage,
            submitting = state.manageSubmitting,
            onValueChange = viewModel::onManageInputChange,
            onConfirm = viewModel::submitManageDialog,
            onDismiss = viewModel::dismissManageDialog,
        )
    }
}

/**
 * 排序单钮+下拉菜单（GUIDE_UI §芯片栏配置对比 L73「排序 ▾」单钮形态，M3 DropdownMenu
 * 标准件）：钮面文字恒「排序 ▾」（锚定胶囊语言 QimengSegPill 渲染），当前选中项在菜单内
 * 以 ✓ 标识；点选即生效并收起。
 */
@Composable
private fun AuthorSortMenuButton(
    current: AuthorSortOption,
    onSelect: (AuthorSortOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        QimengSegPill(
            text = SORT_BUTTON_LABEL,
            selected = false,
            onClick = { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 6.dp,
        ) {
            AuthorSortOption.entries.forEach { sort ->
                DropdownMenuItem(
                    text = { Text(text = sort.label) },
                    onClick = {
                        expanded = false
                        onSelect(sort)
                    },
                    trailingIcon = if (sort == current) {
                        { Text(text = "✓", color = MaterialTheme.colorScheme.primary) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/**
 * 独立作者卡展示头像、单行名称、COS标记及文件数，关注和管理菜单保持独立点击。
 * 整行进入原作者作品页，删除仍须确认且保留关联媒体。
 */
@Composable
private fun AuthorRow(
    author: AuthorSummary,
    loadAvatar: suspend (String) -> AuthorAvatarState,
    avatarKey: String,
    initialAvatar: AuthorAvatarState,
    onToggleFollow: () -> Unit,
    onAuthorClick: (authorId: String, displayName: String) -> Unit,
    onSources: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onAuthorClick(author.id, author.displayName) }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AuthorListAvatar(author.id, author.displayName, avatarKey, initialAvatar, loadAvatar)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = author.displayName,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    // 任务Z Z3：对齐旧版 AuthorListFragment L479 名字 16sp Bold
                    //（Y3 批裁决：Typography 角色不改，页面级差异走 copy 覆盖；
                    // M3 titleMedium 基线即 16sp，只覆写字重）
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                Row(
                    Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (author.type == AuthorType.COS) Surface(
                        shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text("COS", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                    Text("${author.fileCount ?: 0} 个文件", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedButton(
                onClick = onToggleFollow,
                modifier = Modifier.height(36.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
                contentPadding = PaddingValues(horizontal = 12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (author.followed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )) {
                Text(if (author.followed) "已关注" else "关注", style = MaterialTheme.typography.labelMedium)
            }
            if (author.type != AuthorType.COS) Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = MoreVertIcon,
                        contentDescription = "${author.displayName}：$AUTHOR_MENU_DESC",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    modifier = Modifier.width(168.dp),
                    shape = RoundedCornerShape(16.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                    shadowElevation = 6.dp,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                ) {
                    DropdownMenuItem(
                        modifier = Modifier.height(48.dp),
                        text = { Text("来源 / 出处", style = MaterialTheme.typography.bodyMedium) },
                        leadingIcon = { Icon(LabelTagIcon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        onClick = { menuOpen = false; onSources() },
                    )
                    DropdownMenuItem(
                        modifier = Modifier.height(48.dp),
                        text = { Text(text = RENAME_MENU_LABEL, style = MaterialTheme.typography.bodyMedium) },
                        leadingIcon = { Icon(EditPencilIcon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    DropdownMenuItem(
                        modifier = Modifier.height(48.dp),
                        text = { Text(text = DELETE_MENU_LABEL, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) },
                        leadingIcon = { Icon(DeleteIcon, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp)) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

/**
 * 作者名对话框（新建/改名共用；2026-10-13 作者管理族）：单输入框 + 「 / 」别名
 * 分隔说明；确定钮空白禁用、提交中禁用；提交失败保持打开（错误横幅在页面层，
 * 用户可改名字直接重按，对齐编辑页自助修正口径）。容器色显式 surface（同
 * VocabularyPromptDialog 的夜间胶囊同色根因修复）。
 */
@Composable
private fun AuthorNameDialog(
    title: String,
    initial: String,
    value: String,
    error: String?,
    submitting: Boolean,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var discard by remember { mutableStateOf(false) }
    val dismiss = {
        if (!submitting) {
            if (value != initial) discard = true else onDismiss()
        }
    }
    if (discard) AlertDialog(
        onDismissRequest = { discard = false }, title = { Text("放弃未保存的修改？") },
        text = { Text("本次名称修改将丢弃。") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } },
    )
    AlertDialog(
        onDismissRequest = dismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(text = title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(QimengDimens.SpaceS)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                QimengCapsuleTextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = AUTHOR_NAME_PLACEHOLDER,
                    singleLine = true,
                    enabled = !submitting,
                )
                Text(
                    text = AUTHOR_ALIAS_HINT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = value.isNotBlank() && !submitting) {
                Text(text = if (submitting) SUBMITTING_LABEL else CONFIRM_LABEL)
            }
        },
        dismissButton = {
            TextButton(onClick = dismiss, enabled = !submitting) {
                Text(text = CANCEL_LABEL)
            }
        },
    )
}

/**
 * 删除作者确认对话框（2026-10-13 作者管理族）：明示领域口径——关联文件保留、
 * 仅解除关联（DOMAIN_RULES §6「删除作者保留文件」）；删除钮红色。
 */
@Composable
private fun AuthorDeleteDialog(
    displayName: String,
    fileCount: Int,
    error: String?,
    submitting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(text = DELETE_DIALOG_TITLE) },
        text = {
            Column {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(text = "确定删除「$displayName」吗？\n关联的 $fileCount 个文件会保留，仅解除关联。")
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !submitting) {
                Text(
                    text = if (submitting) DELETING_LABEL else DELETE_MENU_LABEL,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) {
                Text(text = CANCEL_LABEL)
            }
        },
    )
}
