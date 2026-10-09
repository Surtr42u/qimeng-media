package media.qimeng.app.core.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
// getValue/setValue：`var ... by rememberSaveable` 委托的隐式取值/赋值——源码里不出现
// 字面量，静态扫「未引用 import」会误判（删掉即编译失败，2026-10-09 实测踩过一次）
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
import androidx.compose.ui.window.Popup
import media.qimeng.app.core.model.AuthorSuggestion

/**
 * 作者联想段（无状态；2026-09-25 上传挂靠退役批自 UploadScreen 抽出，上传页/资产编辑页/
 * 相册筛选面板共用）：未确定 = 胶囊输入框 + 联想胶囊流；已确定 = 选中胶囊（点按清除）。
 * 状态与业务规则（防抖/互斥/归并）全在调用方 ViewModel，本组件零逻辑（ADR-0008 铁律 7）。
 *
 * 联想形态沿革（2026-10-09 用户拍板定稿）：**输入非空时建议＝输入框正下方的自适应胶囊流**
 * （`FlowRow` + `QimengSegPill`，与文件名联想同源同观感——用户口径「对齐文件名那种视觉」）。
 * 前史：2026-09-29 曾以「浮层零布局位移」为由改自锚 Popup 大面板（夸克式中线覆盖 + 弧形
 * 补块 + 自定义定位器），三笔迭代的浮层载体、让位区、补块与定位器已随本批整体退役删除；
 * 胶囊流同样零位移（在表单内占位，不再每敲一字重排整表），但视觉与文件名联想统一为一套。
 *
 * 副文案取舍：胶囊只承载作者名（与文件名胶囊同口径——胶囊不带副文案）；文件数仍在
 * 空输入种子列表（[QimengAuthorSeedList]）里按原「displayName + N 个文件」双行口径可见。
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
            QimengCapsuleTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = "输入作者名（联想选择，回车新建）",
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onCommitInput() }),
            )
            // 联想候选＝胶囊流（2026-10-09 用户拍板「与文件名联想同一视觉」）：输入框
            // 正下方一排自适应换行的小胶囊，点选即确定——与文件名联想（UploadScreen /
            // AssetEditScreen 的 FlowRow + QimengSegPill）同源同观感，不再用自锚浮层
            // 大面板（浮层版含让位区/弧形补块/自定义定位器，已随本批整体退役）。
            // 胶囊只承载作者名（与文件名胶囊同口径：胶囊不带副文案）；文件数仍在
            // 空输入种子列表（QimengAuthorSeedList）里按原双行口径可见。
            if (query.isNotBlank() && suggestions.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    suggestions.forEach { suggestion ->
                        QimengSegPill(
                            text = suggestion.displayName,
                            selected = false,
                            onClick = { onPickSuggestion(suggestion) },
                        )
                    }
                }
            }
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

// —— 联想浮层（Popup 自锚版）尺寸常量：夸克式一体化（中线覆盖+弧形补块+外侧圆角+零阴影） ——

/** 浮层与窗口边缘的最小留白（dp）：上下方向可用空间计算时扣减 */
private const val MENU_WINDOW_MARGIN_DP = 8

/** 浮层最小可用高度（dp）：内容限高的下限 + 上翻判定阈值，防极端场景压成零高 */
private const val MENU_MIN_HEIGHT_DP = 96

/** 浮层外侧边（不贴输入框一侧）圆角（dp）：一体式列表的收尾弧度 */
private const val MENU_OUTER_CORNER_DP = 12

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
