package media.qimeng.app.feature.upload

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import media.qimeng.app.core.model.DirNode
import media.qimeng.app.core.model.UploadRules
import media.qimeng.app.core.ui.component.QimengSegPill
import media.qimeng.app.core.ui.theme.QimengDimens

/** 上传目标的展示与入口，目录选择仍由同一 VM 持有。 */
@Composable
internal fun UploadDestinationSection(
    state: UploadUiState,
    viewModel: UploadViewModel,
    onCreateDir: () -> Unit,
    onOpenLibraries: () -> Unit,
) {
    UploadPanel("上传位置", "选择目标媒体库；未选择目录时上传到库根。") {
        when {
            state.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            state.librariesLoadFailed -> {
                Text("媒体库列表读取失败，不能据此判断没有媒体库。",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            state.libraries.isEmpty() -> {
                Text("还没有可选的媒体库", style = MaterialTheme.typography.titleSmall)
                Text("先在库管理登记一个目录，再回来刷新列表。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onOpenLibraries) { Text("管理媒体库") }
            }
            else -> FlowRow(
                horizontalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM),
                verticalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM),
            ) {
                state.libraries.forEach { library ->
                    QimengSegPill(text = library.name, selected = state.selectedLibrary?.id == library.id,
                        onClick = { viewModel.selectLibrary(library) })
                }
            }
        }
        TextButton(onClick = viewModel::refreshLibraries, enabled = !state.loading) {
            Text(if (state.loading) "正在读取媒体库…" else "刷新列表")
        }
        state.dirTree?.takeIf { state.selectedLibrary != null }?.let { tree ->
            Text("目标目录", style = MaterialTheme.typography.titleSmall)
            DirTreePanel(tree, state.selectedDirPath, viewModel::selectDir, onCreateDir)
        }
    }
}

/** 目标目录面板：目录树卡片 + 已选目录提示 + 新建子目录入口（目录树已加载时展示） */
@Composable
private fun DirTreePanel(
    tree: DirNode,
    selectedDirPath: String,
    onSelect: (String) -> Unit,
    onCreateDir: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp)) {
            val expanded = remember(tree) { mutableStateOf(setOf(ROOT_PATH)) }
            // 根行固定展示（path = "" 即库根，协议口径）
            DirRow(
                label = DIR_ROOT_LABEL,
                detail = "${tree.fileCount} 个文件",
                selected = selectedDirPath == ROOT_PATH,
                expanded = true,
                hasChildren = tree.children.isNotEmpty(),
                onClick = { onSelect(ROOT_PATH) },
                onToggle = { },
            )
            tree.children.forEach { child ->
                DirNodeRows(
                    node = child,
                    depth = 1,
                    selectedPath = selectedDirPath,
                    expandedPaths = expanded.value,
                    onToggle = { path ->
                        expanded.value =
                            if (path in expanded.value) expanded.value - path
                            else expanded.value + path
                    },
                    onSelect = onSelect,
                )
            }
        }
    }
    Text(
        text = "已选目录：${selectedDirPath.ifEmpty { DIR_ROOT_LABEL }}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(onClick = onCreateDir) {
        Text("在当前目录下新建子目录")
    }
}

/** 目录树行（递归渲染；深度缩进 + 展开/收起 + 点选为目标目录） */
@Composable
private fun DirNodeRows(
    node: DirNode,
    depth: Int,
    selectedPath: String,
    expandedPaths: Set<String>,
    onToggle: (String) -> Unit,
    onSelect: (String) -> Unit,
) {
    val hasChildren = node.children.isNotEmpty()
    val expanded = node.path in expandedPaths
    DirRow(
        label = node.path.substringAfterLast('/', node.path),
        detail = "${node.fileCount} 个文件",
        selected = selectedPath == node.path,
        expanded = expanded,
        hasChildren = hasChildren,
        onClick = {
            // V7：点行 = 选中并进入（有子级且未展开时同时展开）——
            // COS 等层级库点行直达子文件夹，收起仍走 ▾ 箭头（规则见 UploadRules）
            onSelect(node.path)
            if (UploadRules.shouldExpandOnSelect(hasChildren, expanded)) onToggle(node.path)
        },
        onToggle = { onToggle(node.path) },
        depth = depth,
    )
    if (expanded) {
        node.children.forEach { child ->
            DirNodeRows(child, depth + 1, selectedPath, expandedPaths, onToggle, onSelect)
        }
    }
}

@Composable
private fun DirRow(
    label: String,
    detail: String,
    selected: Boolean,
    expanded: Boolean,
    hasChildren: Boolean,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    depth: Int = 0,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(start = (depth * INDENT_STEP_DP).dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasChildren) {
            // V8 #1：折叠箭头不再用 TextButton——M3 TextButton 自带约 40dp 最小高度，
            // 会把「有子级的行」撑到叶子行（Spacer 占位）的约 1.3 倍，COS 树与 normal 树
            // 切换时密度跳变。改为与叶子占位同宽、与整行约等高（20sp 行高+12dp 行距）
            // 的可点击区，行高仍与叶子行同档、由行内文字+padding 单源决定
            // （点击语义不变：仍只在此区域触发折叠）。
            Box(
                modifier = Modifier
                    .size(width = DIR_TOGGLE_AREA_WIDTH_DP.dp, height = DIR_TOGGLE_AREA_HEIGHT_DP.dp)
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (expanded) "▾" else "▸", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Spacer(modifier = Modifier.width(DIR_TOGGLE_AREA_WIDTH_DP.dp))
        }
        Text(
            text = if (selected) "● $label" else "○ $label",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val ROOT_PATH = ""
private const val DIR_ROOT_LABEL = "（库根）"
// 复用原目录树尺寸与点击语义，未更改路径或展开规则。
private const val INDENT_STEP_DP = 20
private const val DIR_TOGGLE_AREA_WIDTH_DP = 48
private const val DIR_TOGGLE_AREA_HEIGHT_DP = 32
