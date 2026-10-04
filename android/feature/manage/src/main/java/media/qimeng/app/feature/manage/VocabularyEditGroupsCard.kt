package media.qimeng.app.feature.manage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import media.qimeng.sdk.models.CustomSourceGroup
import media.qimeng.sdk.models.CustomSourceCharacter

/** 卡与小节文案（新增/改名对话框标题由 Screen 层构建 VocabularyPrompt 时提供） */
private const val CARD_TITLE = "自定义出处组"
private const val GROUPS_EMPTY = "没有自定义出处组（内置 133 组基线始终生效，这里只维护追加词层）"
private const val GROUP_ADD = "添加组"
private const val GROUP_RENAME = "改名"
private const val GROUP_REMOVE = "移除"
private const val SECTION_VARIANTS = "变体写法"
private const val SECTION_CHARACTERS = "角色检索表"
private const val SECTION_ALIASES = "别名"
private const val SUBTITLE_NONE = "无词条"

/** 12dp：卡四向内边距（VocabularyEditScreen.CardInnerPadding 同值，本文件私有单源） */
private val CardInnerPadding = 12.dp

/** 8dp：卡内元素纵向节奏（同上档） */
private val CardRowSpacing = 8.dp

/** 20dp：展开区左缩进（组/角色两级展开共用一档） */
private val ExpandedIndent = 20.dp

/**
 * 自定义出处组卡（词表维护页主体，ADR-0035）：组行（规范名 + 词条计数，点按展开/
 * 收起，展开态互斥）→ 变体写法小节 + 角色小节（行内改名/移除，点按展开别名小节）。
 * 全部操作经回调转 ViewModel（铁律 7：本组件零业务规则，只做列表渲染与转发）；
 * 新增一律走 VocabularyPromptDialog（Screen 层统一持有 pendingPrompt）。
 */
@Composable
internal fun VocabularyGroupsCard(
    groups: List<CustomSourceGroup>,
    busy: Boolean,
    onAddGroup: () -> Unit,
    onRemoveGroup: (Int) -> Unit,
    onRenameGroup: (index: Int, current: String) -> Unit,
    onAddVariant: (index: Int) -> Unit,
    onRemoveVariant: (groupIndex: Int, variantIndex: Int) -> Unit,
    onAddCharacter: (index: Int) -> Unit,
    onRemoveCharacter: (groupIndex: Int, characterIndex: Int) -> Unit,
    onRenameCharacter: (groupIndex: Int, characterIndex: Int, current: String) -> Unit,
    onAddAlias: (groupIndex: Int, characterIndex: Int) -> Unit,
    onRemoveAlias: (groupIndex: Int, characterIndex: Int, aliasIndex: Int) -> Unit,
) {
    // 展开态是纯 UI 状态（不进 VM/不进 dirty）：单组互斥展开 + 组内单角色互斥展开
    var expandedGroup by rememberSaveable { mutableIntStateOf(-1) }
    var expandedCharacter by rememberSaveable { mutableIntStateOf(-1) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(CardInnerPadding),
            verticalArrangement = Arrangement.spacedBy(CardRowSpacing),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = CARD_TITLE, style = MaterialTheme.typography.titleSmall)
                TextButton(
                    onClick = onAddGroup,
                    enabled = !busy && groups.size < VocabularyLimits.GROUPS_MAX,
                ) {
                    Text(GROUP_ADD)
                }
            }
            if (groups.isEmpty()) {
                Text(
                    text = GROUPS_EMPTY,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                groups.forEachIndexed { index, group ->
                    GroupRow(
                        group = group,
                        expanded = expandedGroup == index,
                        busy = busy,
                        onToggle = {
                            expandedCharacter = -1
                            expandedGroup = if (expandedGroup == index) -1 else index
                        },
                        onRename = { onRenameGroup(index, group.canonical) },
                        onRemove = { onRemoveGroup(index) },
                    )
                    if (expandedGroup == index) {
                        GroupDetail(
                            group = group,
                            groupIndex = index,
                            busy = busy,
                            expandedCharacter = expandedCharacter,
                            onToggleCharacter = { ci ->
                                expandedCharacter = if (expandedCharacter == ci) -1 else ci
                            },
                            onAddVariant = { onAddVariant(index) },
                            onRemoveVariant = { vi -> onRemoveVariant(index, vi) },
                            onAddCharacter = { onAddCharacter(index) },
                            onRemoveCharacter = { ci -> onRemoveCharacter(index, ci) },
                            onRenameCharacter = { ci, current -> onRenameCharacter(index, ci, current) },
                            onAddAlias = { ci -> onAddAlias(index, ci) },
                            onRemoveAlias = { ci, ai -> onRemoveAlias(index, ci, ai) },
                        )
                    }
                    if (index < groups.lastIndex) {
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/** 组行：规范名 + 词条计数（点按展开）；改名/移除常驻行尾 */
@Composable
private fun GroupRow(
    group: CustomSourceGroup,
    expanded: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = group.canonical,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = groupSubtitle(group),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRename, enabled = !busy) {
            Text(GROUP_RENAME)
        }
        TextButton(onClick = onRemove, enabled = !busy) {
            Text(GROUP_REMOVE)
        }
    }
    if (expanded) {
        // 展开提示随行内小字下沉一行（无箭头图标依赖，展开/收起以计数行文案提示）
        Text(
            text = "已展开，点组名行收起",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = ExpandedIndent),
        )
    }
}

/** 组词条计数副行（变体/角色数；空组给「无词条」占位） */
private fun groupSubtitle(group: CustomSourceGroup): String {
    val variantCount = group.variants.orEmpty().size
    val characterCount = group.characters.orEmpty().size
    if (variantCount == 0 && characterCount == 0) {
        return SUBTITLE_NONE
    }
    return "$SECTION_VARIANTS $variantCount · $SECTION_CHARACTERS $characterCount"
}

/** 组展开区：变体写法小节 + 角色小节（角色行可再展开别名小节） */
@Composable
private fun GroupDetail(
    group: CustomSourceGroup,
    groupIndex: Int,
    busy: Boolean,
    expandedCharacter: Int,
    onToggleCharacter: (Int) -> Unit,
    onAddVariant: () -> Unit,
    onRemoveVariant: (Int) -> Unit,
    onAddCharacter: () -> Unit,
    onRemoveCharacter: (Int) -> Unit,
    onRenameCharacter: (characterIndex: Int, current: String) -> Unit,
    onAddAlias: (characterIndex: Int) -> Unit,
    onRemoveAlias: (characterIndex: Int, aliasIndex: Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ExpandedIndent),
        verticalArrangement = Arrangement.spacedBy(CardRowSpacing),
    ) {
        SectionHeader(
            title = SECTION_VARIANTS,
            addEnabled = !busy && group.variants.orEmpty().size < VocabularyLimits.VARIANTS_MAX,
            onAdd = onAddVariant,
        )
        val variants = group.variants.orEmpty()
        if (variants.isEmpty()) {
            SmallNoteInline("暂无变体（规范名自身恒参与前缀匹配与剥离）")
        } else {
            variants.forEachIndexed { variantIndex, variant ->
                KeyValueRow(text = variant, busy = busy) { onRemoveVariant(variantIndex) }
            }
        }

        SectionHeader(
            title = SECTION_CHARACTERS,
            addEnabled = !busy && group.characters.orEmpty().size < VocabularyLimits.CHARACTERS_MAX,
            onAdd = onAddCharacter,
        )
        val characters = group.characters.orEmpty()
        if (characters.isEmpty()) {
            SmallNoteInline("暂无角色（兜底提取可按命名规约自动出角色）")
        } else {
            characters.forEachIndexed { characterIndex, character ->
                CharacterRow(
                    character = character,
                    expanded = expandedCharacter == characterIndex,
                    busy = busy,
                    onToggle = { onToggleCharacter(characterIndex) },
                    onRename = { onRenameCharacter(characterIndex, character.canonical) },
                    onRemove = { onRemoveCharacter(characterIndex) },
                )
                if (expandedCharacter == characterIndex) {
                    AliasSection(
                        character = character,
                        busy = busy,
                        onAdd = { onAddAlias(characterIndex) },
                        onRemove = { aliasIndex -> onRemoveAlias(characterIndex, aliasIndex) },
                    )
                }
            }
        }
    }
}

/** 角色行：规范名（点按展开别名）；改名/移除常驻行尾 */
@Composable
private fun CharacterRow(
    character: CustomSourceCharacter,
    expanded: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = character.canonical + if (expanded) "（含别名）" else "",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f, fill = false),
        )
        TextButton(onClick = onRename, enabled = !busy) {
            Text(GROUP_RENAME)
        }
        TextButton(onClick = onRemove, enabled = !busy) {
            Text(GROUP_REMOVE)
        }
    }
}

/** 别名小节（角色展开区）：别名清单逐行移除 + 新增入口 */
@Composable
private fun AliasSection(
    character: CustomSourceCharacter,
    busy: Boolean,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ExpandedIndent),
        verticalArrangement = Arrangement.spacedBy(CardRowSpacing),
    ) {
        SectionHeader(
            title = SECTION_ALIASES,
            addEnabled = !busy && character.aliases.orEmpty().size < VocabularyLimits.ALIASES_MAX,
            onAdd = onAdd,
        )
        val aliases = character.aliases.orEmpty()
        if (aliases.isEmpty()) {
            SmallNoteInline("暂无别名（规范名自身恒参与子串匹配）")
        } else {
            aliases.forEachIndexed { aliasIndex, alias ->
                KeyValueRow(text = alias, busy = busy) { onRemove(aliasIndex) }
            }
        }
    }
}

/** 小节头：小节名 + 添加钮（容量封顶时禁用） */
@Composable
private fun SectionHeader(
    title: String,
    addEnabled: Boolean,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
        )
        TextButton(onClick = onAdd, enabled = addEnabled) {
            Text("添加")
        }
    }
}

/** 词条行：文本 + 移除（变体/别名共用） */
@Composable
private fun KeyValueRow(text: String, busy: Boolean, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f, fill = false),
        )
        TextButton(onClick = onRemove, enabled = !busy) {
            Text(GROUP_REMOVE)
        }
    }
}

/** 行内小字（空小节占位；与屏级 SmallNote 语义同、文件内私有单源） */
@Composable
private fun SmallNoteInline(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
