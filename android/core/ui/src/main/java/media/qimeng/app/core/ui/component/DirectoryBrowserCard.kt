package media.qimeng.app.core.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 目录浏览条目（浏览器卡的数据面：名称 + 导航路径）。条目类型就地定义而非复用
 *  core:data 的 InboxDirEntry——core:ui 只许依赖 core:model，调用方把自己的仓库条目
 *  映射进来，避免 core:ui 反向耦合数据层。 */
data class DirectoryBrowserEntry(
    /** 目录名（不含路径） */
    val name: String,
    /** 目录绝对路径（导航/选定的承载值） */
    val path: String,
)

/**
 * 目录浏览器卡（2026-09-28 自 feature:settings 的 InboxSettingsScreen.BrowserCard 上提）：
 * 当前路径行 + 返回上一级 + 一级子目录列表（含点前缀隐藏目录）+ 可选「选用当前目录」。
 * 上提动因：上传页「浏览文件」弹层（feature:upload）需要同一套含隐藏目录的目录导航，
 * 按「第 2 次出现即抽共享」纪律进 core:ui 单源，feature 侧禁止再复制浏览器实现。
 * 状态外置（路径栈与目录数据由宿主 ViewModel 管，本组件纯渲染）：设置页沿用
 * InboxSettingsViewModel、上传弹层走 FileBrowserViewModel——两种宿主下参数注入是
 * 改动最小且两边都能用的形态（组件自含 remember 路径栈会锁死单一宿主的导航语义）。
 *
 * @param title 卡片标题（宿主自定文案）
 * @param currentPath 当前浏览目录（展示 + 「选用当前目录」启用判断）
 * @param storageRoot 浏览起点根目录（「返回上一级」到根即禁用）
 * @param entries 当前目录下的一级子目录（含点前缀隐藏目录；宿主已排序）
 * @param onEnter 点选子目录下钻
 * @param onGoUp 返回上一级
 * @param loading 宿主目录加载中（空列表时不显示「没有子文件夹」占位）
 * @param selectedPath 已选定的目录路径（命中行加 ● 前缀；null = 宿主无「选定目录」概念不标注）
 * @param onSelectCurrent 「选用当前目录」回调（null = 隐藏该按钮——上传弹层只挑文件不选目录）
 * @param selectCurrentText 「选用当前目录」按钮文案（onSelectCurrent 非空时必传）
 */
@Composable
fun DirectoryBrowserCard(
    title: String,
    currentPath: String,
    storageRoot: String,
    entries: List<DirectoryBrowserEntry>,
    onEnter: (String) -> Unit,
    onGoUp: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    selectedPath: String? = null,
    onSelectCurrent: (() -> Unit)? = null,
    selectCurrentText: String = "",
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = currentPath.ifEmpty { LABEL_ROOT },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onGoUp, enabled = currentPath != storageRoot) {
                    Text(BUTTON_GO_UP)
                }
            }
            entries.forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEnter(entry.path) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 点前缀隐藏目录原样展示（收件箱等目标常落在系统相册扫不到的隐藏目录）
                    Text(
                        text = if (entry.path == selectedPath) "● ${entry.name}" else "○ ${entry.name}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (entries.isEmpty() && !loading) {
                Text(
                    text = EMPTY_DIRS_TEXT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onSelectCurrent != null) {
                Button(onClick = onSelectCurrent, enabled = currentPath.isNotEmpty()) {
                    Text(selectCurrentText)
                }
            }
        }
    }
}

// ---------- 组件自用文案（随组件上提自 InboxSettingsScreen 迁来，模块惯例：展示文案在代码常量） ----------
private const val BUTTON_GO_UP = "返回上一级"
private const val LABEL_ROOT = "根目录"
private const val EMPTY_DIRS_TEXT = "此目录下没有子文件夹"
