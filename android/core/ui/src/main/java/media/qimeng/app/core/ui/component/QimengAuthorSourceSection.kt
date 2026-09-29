package media.qimeng.app.core.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import media.qimeng.app.core.model.AuthorSuggestion

/**
 * 作者联想段（无状态；2026-09-25 上传挂靠退役批自 UploadScreen 抽出，上传页与资产
 * 编辑页共用）：未确定 = 胶囊输入框 + 浮层联想菜单；已确定 = 选中胶囊（点按清除）。
 * 状态与业务规则（防抖/互斥/归并）全在调用方 ViewModel，本组件零逻辑（ADR-0008 铁律 7）。
 *
 * 联想弹层（2026-09-29 用户拍板「搜索那种」）：输入非空时建议以 DropdownMenu 浮层
 * 展示在输入框正下方——原内联 Card 会把整张长表单往下顶（每敲一字全表单重排，
 * 用户实测「提取太卡」且观感是个胶囊框），浮层零布局位移、与搜索补全同范式。
 *
 * @param committedName 已确定作者显示名（点选既有 or 待新建）；null = 未确定（渲染输入态）
 * @param committedIsExisting true = 点选既有作者（副文案「已选作者」）；false = 待新建
 * @param seeds 空输入种子列表（调用方从全量常规作者拉取；2026-09-29 起上传页不传，
 *   资产编辑页保留空输入全显）：输入为空且非空时默认全显
 */
@Composable
fun QimengAuthorSuggestSection(
    title: String,
    query: String,
    committedName: String?,
    committedIsExisting: Boolean,
    suggestions: List<AuthorSuggestion>,
    seeds: List<AuthorSuggestion> = emptyList(),
    onQueryChange: (String) -> Unit,
    onPickSuggestion: (AuthorSuggestion) -> Unit,
    onCommitInput: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SectionTitleText(title)
        val committed = committedName
        if (committed != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 胶囊单源（QimengSegPill）：点按即清除；新建作者前缀区分两种确定态
                QimengSegPill(
                    text = if (committedIsExisting) "$committed ✕" else "新建：$committed ✕",
                    selected = true,
                    onClick = onClear,
                )
                Text(
                    text = if (committedIsExisting) "已选作者" else "将新建作者",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            // 浮层开合状态（用户手动关闭后，继续输入即重新打开——搜索补全惯例）
            var menuOpen by rememberSaveable { mutableStateOf(true) }
            LaunchedEffect(query) {
                if (query.isNotBlank()) menuOpen = true
            }
            // 输入框与菜单必须同包一个 Box：DropdownMenu 锚定其**直接父容器**的矩形，
            // 父容器是大表单 Column 时菜单会翻转到屏幕顶部（2026-09-29 真机实测）；锚定
            // 输入框自身的 Box 才能贴着输入框正下方展开。
            Box {
                QimengCapsuleTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = "输入作者名（联想选择，回车新建）",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onCommitInput() }),
                )
                if (query.isNotBlank() && suggestions.isNotEmpty()) {
                    AuthorSuggestionMenu(
                        suggestions = suggestions,
                        visible = menuOpen,
                        onDismiss = { menuOpen = false },
                        onPick = onPickSuggestion,
                    )
                }
            }
            if (query.isBlank() && seeds.isNotEmpty()) {
                // 空输入不输入也能看到可选作者（仅资产编辑页传入；suggest 空查询无结果）
                QimengAuthorSeedList(seeds = seeds, onPick = onPickSuggestion)
            }
        }
    }
}

/**
 * 联想浮层菜单（无状态；搜索式下拉，不占表单布局空间）：命中行 = displayName +
 * 文件数（照 AuthorScreen 作者行口径）。列表上限 = 协议 limit 10，超出菜单内部自滚。
 */
@Composable
private fun AuthorSuggestionMenu(
    suggestions: List<AuthorSuggestion>,
    visible: Boolean,
    onDismiss: () -> Unit,
    onPick: (AuthorSuggestion) -> Unit,
) {
    DropdownMenu(
        expanded = visible,
        onDismissRequest = onDismiss,
    ) {
        suggestions.forEach { suggestion ->
            DropdownMenuItem(
                text = { Text(suggestion.displayName, style = MaterialTheme.typography.bodyMedium) },
                trailingIcon = {
                    Text(
                        text = "${suggestion.fileCount} 个文件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = {
                    onDismiss()
                    onPick(suggestion)
                },
            )
        }
    }
}

/**
 * 空输入种子列表（无状态）：与联想列表同一行渲染，但数据源是全量常规作者
 * （无协议条数上限），必须限高内滚防把外层表单撑爆——限高内滚用 LazyColumn
 * 而非联想列表的固定 Column（全量作者可能数百行，全组合不可接受）。
 */
@Composable
private fun QimengAuthorSeedList(
    seeds: List<AuthorSuggestion>,
    onPick: (AuthorSuggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        LazyColumn(modifier = Modifier.heightIn(max = AUTHOR_SEED_LIST_MAX_HEIGHT_DP.dp)) {
            items(seeds, key = { it.id }) { seed ->
                AuthorSuggestionRow(suggestion = seed, onPick = onPick)
            }
        }
    }
}

/** 联想/种子共用的命中行（displayName + 文件数副文案，照 AuthorScreen 作者行双行口径） */
@Composable
private fun AuthorSuggestionRow(
    suggestion: AuthorSuggestion,
    onPick: (AuthorSuggestion) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick(suggestion) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = suggestion.displayName,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${suggestion.fileCount} 个文件",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 来源段（无状态）：已选胶囊流 + 快捷词表横滚胶囊 + 自由输入行。
 * [enabled]=false 时整段降透明并展示 [disabledHint]（点击由调用方 VM 门槛兜底）。
 * 词表选项由调用方传入（服务端单一来源，ADR-0008：客户端禁硬编码词表）。
 */
@Composable
fun QimengSourceSection(
    title: String,
    selectedSources: List<String>,
    options: List<String>,
    enabled: Boolean,
    disabledHint: String,
    onToggle: (String) -> Unit,
    onAddCustom: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.then(
            if (enabled) {
                Modifier
            } else {
                Modifier.alpha(DISABLED_SECTION_ALPHA)
            },
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitleText(title)
        if (!enabled) {
            Text(
                text = disabledHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selectedSources.isNotEmpty()) {
            QimengWordPillFlow(
                pills = selectedSources.map { name -> QimengPill(text = name, selected = true) },
                onPillClick = { index -> onToggle(selectedSources[index]) },
            )
        }
        if (options.isNotEmpty()) {
            // 快捷词表横滚胶囊（点击 toggle；选中态由调用方状态驱动，组件本身受控）
            QimengChipRow(
                pills = options.map { name -> QimengPill(text = name, selected = name in selectedSources) },
                onPillClick = { index -> onToggle(options[index]) },
            )
        }
        var customInput by rememberSaveable { mutableStateOf("") }
        QimengCapsuleTextField(
            value = customInput,
            onValueChange = { customInput = it },
            placeholder = "输入新站点或 URL，回车加入",
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    onAddCustom(customInput)
                    customInput = ""
                },
            ),
        )
    }
}

/** 分节标题（titleMedium；原 UploadScreen SectionTitle 同款） */
@Composable
private fun SectionTitleText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** 来源段未启用时的降透明系数（整段禁用的视觉表达） */
private const val DISABLED_SECTION_ALPHA = 0.5f

/** 空输入种子列表限高（dp）：全量常规作者无条数上限，限高内滚防撑爆外层长表单 */
private const val AUTHOR_SEED_LIST_MAX_HEIGHT_DP = 200

/**
 * 拦截/错误横幅（点击关闭；原 UploadScreen MessageCard 同款抽出，上传页与编辑页共用）：
 * 错误用 error 容器色、提示/拦截用 tertiary 容器色，由调用方传色。
 * [onDismiss] 收尾位：支持调用点 trailing-lambda 写法。
 */
@Composable
fun QimengMessageCard(
    text: String,
    container: Color,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onDismiss() },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}
