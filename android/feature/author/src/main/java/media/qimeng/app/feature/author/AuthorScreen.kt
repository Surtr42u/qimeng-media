package media.qimeng.app.feature.author

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import media.qimeng.app.core.model.displayLabel
import media.qimeng.app.core.ui.component.QimengCapsuleTextField
import media.qimeng.app.core.ui.component.QimengChipRow
import media.qimeng.app.core.ui.component.QimengMessageCard
import media.qimeng.app.core.ui.component.QimengPill
import media.qimeng.app.core.ui.component.QimengPullToRefresh
import media.qimeng.app.core.ui.component.QimengRankCard
import media.qimeng.app.core.ui.component.QimengSegPill
import media.qimeng.app.core.ui.component.QimengTopBar
import media.qimeng.app.core.ui.icon.MoreVertIcon
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
 * 列表套榜单卡（QimengRankCard 单源卡盒）；行点击回调 [onAuthorClick] 留接口占位，
 * 作者集合子页（Web /app/collection/author/{name} 等价物）归 G1b 批接线实现。
 *
 * @param onAuthorClick 行点击（authorId + 原始 displayName 不带「 ·COS」后缀——Web 跳转
 *   用原始名 + URL 编码同语义）；本批壳层接空实现
 */
@Composable
fun AuthorScreen(
    onBack: () -> Unit,
    onAuthorClick: (authorId: String, displayName: String) -> Unit = { _, _ -> },
    viewModel: AuthorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        QimengTopBar(title = "作者管理", onBack = onBack)

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
            // 新建作者入口（2026-10-13 作者管理族，ADR-0024 修订 5）：胶囊语言与排序钮同源
            QimengSegPill(
                text = CREATE_AUTHOR_LABEL,
                selected = false,
                onClick = viewModel::openCreateDialog,
                modifier = Modifier.padding(start = QimengDimens.SpaceS),
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
            // 列表套榜单卡（G2：Web .rank-card.a-list——描边卡盒单源在 core:ui QimengRankCard）
            QimengRankCard(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = QimengDimens.ScreenPaddingHorizontal),
            ) {
                // 全量过滤+排序按四输入 memo（2026-09-20 全库审查）：此前每次重组
                // （下拉刷新指示器/其他 state tick）都重算 applyAuthorRows——作者量
                // 有界但纯浪费；四键不变时直接复用上次结果
                val rows = remember(state.authors, state.zone, state.keyword, state.sort) {
                    viewModel.visibleRows(state)
                }
                if (rows.isEmpty()) {
                    // 首载中不占位（既有行为保持）；无匹配行给卡内小字空态（Web .a-empty 口径）
                    if (!state.isLoading) {
                        Text(
                            text = EMPTY_AUTHORS,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = QimengDimens.SpaceL),
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(rows, key = { _, author -> author.id }) { index, author ->
                            // 行间分隔线（Web .a-list li border-bottom，末行无线）
                            if (index > 0) {
                                HorizontalDivider(
                                    thickness = QimengDimens.RankCardRowDividerThickness,
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                            }
                            AuthorRow(
                                author = author,
                                onToggleFollow = { viewModel.toggleFollow(author) },
                                onAuthorClick = onAuthorClick,
                                onRename = { viewModel.openRenameDialog(author) },
                                onDelete = { viewModel.openDeleteDialog(author) },
                            )
                        }
                    }
                }
            }
        }
    }

    // 作者管理对话框（新建/改名共用输入框；删除走确认框）——状态机与提交在 ViewModel
    when (val dialog = state.manageDialog) {
        null -> Unit

        is AuthorManageDialog.Delete -> AuthorDeleteDialog(
            displayName = dialog.displayName,
            fileCount = dialog.fileCount,
            submitting = state.manageSubmitting,
            onConfirm = viewModel::submitManageDialog,
            onDismiss = viewModel::dismissManageDialog,
        )

        else -> AuthorNameDialog(
            title = if (dialog is AuthorManageDialog.Rename) RENAME_DIALOG_TITLE else CREATE_DIALOG_TITLE,
            value = state.manageInput,
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
 * 作者行（G2 对齐 Web .a-list li 两行结构）：首行 = 作者名（·COS 标记，[displayLabel] 单源）
 * + 关注胶囊（G6 形状单源保持不变）；副行 = 「N 个文件」单计数（对齐 Web 现版运行时口径：
 * 浏览次数 2026-09-05 反馈⑤拍板不展示、F7 批统一「N 个文件」文案；null 计 0 同 Web `?? 0`）。
 * 整行可点进作者集合页（回调占位待 G1b）；关注胶囊在行内为子 clickable，
 * 点胶囊不触发行点击（Compose 子组件优先消费点击，等价 Web stopPropagation）。
 *
 * 行尾「⋮」操作菜单（2026-10-13 作者管理族，ADR-0024 修订 5）：改名/删除两操作；
 * 删除文案红色（破坏性操作视觉预警），确认对话框再给「关联文件保留」的领域口径。
 */
@Composable
private fun AuthorRow(
    author: AuthorSummary,
    onToggleFollow: () -> Unit,
    onAuthorClick: (authorId: String, displayName: String) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onAuthorClick(author.id, author.displayName) }
            // 任务Z Z3 排版放松（用户反馈「作者管理的排版太拥挤」）：旧版行节距 83dp
            // （卡内上下 16dp + 卡间 10dp），新此前纵向 8dp 节距仅 53dp（-36%）——放松到
            // SpaceL=12dp 档（横段保持 SpaceXXS 对齐 Web .a-list li 2px）
            .padding(horizontal = QimengDimens.SpaceXXS, vertical = QimengDimens.SpaceL),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = author.displayLabel,
                // 任务Z Z3：对齐旧版 AuthorListFragment L479 名字 16sp Bold
                //（Y3 批裁决：Typography 角色不改，页面级差异走 copy 覆盖；
                // M3 titleMedium 基线即 16sp，只覆写字重）
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                text = "${author.fileCount ?: 0} 个文件",
                // 任务Z Z3：对齐旧版副行 12sp（M3 labelSmall 基线 11sp）；与名字间
                // 补 SpaceXS=4dp（旧版名字与计数 4dp 间距）；色保持 onSurfaceVariant 系
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = QimengDimens.SpaceXS),
            )
        }
        Surface(
            // 胶囊圆角收敛单源 token（G6：原硬编码 RoundedCornerShape(100.dp)）
            shape = RoundedCornerShape(QimengDimens.PillCornerRadius),
            color = if (author.followed) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.clickable(onClick = onToggleFollow),
        ) {
            Text(
                text = if (author.followed) "已关注" else "关注",
                style = MaterialTheme.typography.labelLarge,
                color = if (author.followed) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onPrimary
                },
                modifier = Modifier.padding(horizontal = QimengDimens.ChipHorizontalPadding, vertical = QimengDimens.SpaceS),
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    imageVector = MoreVertIcon,
                    contentDescription = AUTHOR_MENU_DESC,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text(text = RENAME_MENU_LABEL) },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text(text = DELETE_MENU_LABEL, color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
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
    value: String,
    submitting: Boolean,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(text = title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(QimengDimens.SpaceS)) {
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
            TextButton(onClick = onDismiss, enabled = !submitting) {
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
    submitting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(text = DELETE_DIALOG_TITLE) },
        text = {
            Text(text = "确定删除「$displayName」吗？\n关联的 $fileCount 个文件会保留，仅解除关联。")
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
