package media.qimeng.app.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.qimeng.app.core.model.DetailAuthor
import media.qimeng.app.core.ui.component.QimengAuthorSuggestSection
import media.qimeng.app.core.ui.component.QimengMessageCard
import media.qimeng.app.core.ui.component.QimengSourceSection
import media.qimeng.app.core.ui.component.QimengTopBar
import media.qimeng.app.core.ui.theme.QimengDimens
import media.qimeng.app.core.ui.theme.qimengFilledButtonColors

/**
 * 资产编辑页（2026-09-25 上传挂靠退役批）：作者关联 + 逐作者来源维护。
 * 入口 = 详情作者 Sheet「编辑作者与来源」（壳层 pushed 路由，入栈隐藏底栏）。
 * 结构（Card 分节 + MessageCard 双槽提示）：错误/提示横幅 → 已关联作者（行内移除 +
 * 每作者来源区）→ 添加作者（联想选择，仅既有作者）→ 保存（PUT 全集 + 脏来源逐 PUT，
 * 成功返回）。加载与保存编排全在 [AssetEditViewModel]（ADR-0008 铁律 7）。
 *
 * @param onBack 返回（未保存退出，无改动确认——本页改动粒度小且可重进）
 * @param onSaved 保存成功后的返回（与 onBack 同为 popBackStack，语义分开供测试/预览注入）
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AssetEditScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: AssetEditViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 保存成功一次性出口（popBackStack；saved 无复位必要——路由销毁即状态消亡）
    LaunchedEffect(state.saved) {
        if (state.saved) onSaved()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        QimengTopBar(title = stringResource(R.string.asset_edit_title), onBack = onBack)
        if (state.loading) {
            LoadingIndicator(modifier = Modifier.padding(24.dp))
            return@Column
        }
        AssetEditForm(
            state = state,
            viewModel = viewModel,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 编辑表单滚动主体：横幅 → 已关联作者（含逐作者来源）→ 添加作者 → 保存 */
@Composable
private fun AssetEditForm(
    state: AssetEditUiState,
    viewModel: AssetEditViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = QimengDimens.ScreenPaddingHorizontal)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM),
    ) {
        state.errorMessage?.let { message ->
            QimengMessageCard(
                text = message,
                container = MaterialTheme.colorScheme.errorContainer,
            ) { viewModel.dismissError() }
        }
        state.noticeMessage?.let { message ->
            QimengMessageCard(
                text = message,
                container = MaterialTheme.colorScheme.tertiaryContainer,
            ) { viewModel.dismissNotice() }
        }

        // —— 已关联作者 ——
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.asset_edit_authors_section) +
                        "（${state.authors.size}）",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (state.authors.isEmpty()) {
                    Text(
                        text = stringResource(R.string.asset_edit_no_authors),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = QimengDimens.SpaceS),
                    )
                }
                state.authors.forEach { author ->
                    AuthorEditRow(
                        author = author,
                        sources = state.sourcesByAuthor[author.id].orEmpty(),
                        sourceOptions = state.sourceOptions,
                        onRemove = { viewModel.removeAuthor(author.id) },
                        onToggleSource = { name -> viewModel.toggleSource(author.id, name) },
                        onAddCustomSource = { raw -> viewModel.addCustomSource(author.id, raw) },
                    )
                }
                Text(
                    text = stringResource(R.string.asset_edit_sources_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = QimengDimens.SpaceS),
                )
            }
        }

        // —— 添加作者（联想选择；仅能关联既有作者——服务端无按名新建端点） ——
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                QimengAuthorSuggestSection(
                    title = stringResource(R.string.asset_edit_add_author_section),
                    query = state.authorQuery,
                    committedName = null,
                    committedIsExisting = false,
                    suggestions = state.authorSuggestions,
                    seeds = state.authorSeeds,
                    onQueryChange = viewModel::onAddAuthorQueryChange,
                    onPickSuggestion = viewModel::addAuthor,
                    onCommitInput = viewModel::commitAddAuthor,
                    onClear = { },
                )
            }
        }

        // —— 保存 ——
        Button(
            onClick = viewModel::save,
            enabled = state.canSave,
            colors = qimengFilledButtonColors(),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text(
                text = stringResource(
                    if (state.saving) R.string.asset_edit_saving else R.string.asset_edit_save,
                ),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(modifier = Modifier.height(QimengDimens.SpaceL))
    }
}

/** 已关联作者行：显示名（·COS 后缀同作者 Sheet 口径）+ 移除 + 该作者来源编辑区 */
@Composable
private fun AuthorEditRow(
    author: DetailAuthor,
    sources: List<String>,
    sourceOptions: List<String>,
    onRemove: () -> Unit,
    onToggleSource: (String) -> Unit,
    onAddCustomSource: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = QimengDimens.SpaceM),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (author.isCos) {
                        author.displayName + stringResource(R.string.detail_author_cos_suffix)
                    } else {
                        author.displayName
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRemove) {
                    Text(
                        text = stringResource(R.string.asset_edit_remove_author),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            // 每作者来源区（编辑页作者恒「已存在」，无禁用态——disabledHint 传空串不渲染）
            QimengSourceSection(
                title = stringResource(R.string.asset_edit_sources_label),
                selectedSources = sources,
                options = sourceOptions,
                enabled = true,
                disabledHint = "",
                onToggle = onToggleSource,
                onAddCustom = onAddCustomSource,
            )
        }
    }
}
