package media.qimeng.app.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import media.qimeng.app.core.ui.component.QimengCapsuleTextField
import media.qimeng.app.core.ui.theme.QimengDimens

/**
 * 整理表单可提交判定（纯函数，单测锁定；Web MoveDialog submittable 同口径）：
 * trim 后名字非空 且（改名 或 移动）——无任何变化时禁用提交（服务端对同位置调用回 200
 * 幂等，但按钮该给真实反馈）。
 *
 * @param currentDir 当前目录（预填值；与目标不一致 = 移动）
 * @param currentName 当前文件名（预填值；trim 后与输入不一致且非空 = 改名）
 */
internal fun isMoveSubmittable(currentDir: String, currentName: String, targetDir: String, name: String): Boolean {
    val trimmed = name.trim()
    val trimmedDir = targetDir.trim()
    val renamed = trimmed.isNotEmpty() && trimmed != currentName
    // 目录同样 trim（复审清偿）：带尾空格的目录会被判「已移动」且原样发出，
    // 服务端 NormalizeRelPath 400 后文案退化为裸「整理失败」
    val moved = trimmedDir != currentDir
    return trimmed.isNotEmpty() && (renamed || moved)
}

/**
 * 文件整理弹窗（任务G G1b，Web MoveDialog 的 Android **最小实现**）：
 * 新名输入（预填当前文件名）+ 目标目录输入（预填当前目录，库内相对路径）。
 * 与 Web 目录树版的差异（简化取舍，交付报告注明）：Android 无目录树组件，目标目录
 * 用文本输入——用户手输库内相对路径，留空 = 库根；目录树选择器留后续批次。
 * 挂载语义对齐 Web：调用方按 open 条件挂载（Web MoveDialog 同款——条件挂载使 remember
 * 每次打开都从当前现实惰性复位，预填名/目录不冻住旧值；remember 键 = assetId 兜底同屏
 * 极端复用）。
 * 任务V V3「删除归整理」（主代理保守裁决待用户确认）：详情页删除钮已移除，破坏性出口
 * 收敛到本弹窗左侧 danger 文本钮「移入回收站」——[onDeleteClick] 交调用方先关本弹窗再
 * 开既有删除确认（回收站二次确认链原样复用，删除功能保证可达，铁律 4）。
 */
@Composable
internal fun DetailMoveDialog(
    assetId: String,
    currentDir: String,
    currentName: String,
    pending: Boolean,
    errorMessage: String?,
    onSubmit: (targetDir: String, newName: String?) -> Unit,
    onDeleteClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(assetId) { mutableStateOf(currentName) }
    var targetDir by remember(assetId) { mutableStateOf(currentDir) }

    AlertDialog(
        onDismissRequest = { if (!pending) onDismiss() },
        title = { Text(text = stringResource(R.string.detail_move_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM)) {
                Text(
                    text = stringResource(R.string.detail_move_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                QimengCapsuleTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = stringResource(R.string.detail_move_name_placeholder),
                    singleLine = true,
                    enabled = !pending,
                )
                QimengCapsuleTextField(
                    value = targetDir,
                    onValueChange = { targetDir = it },
                    placeholder = stringResource(R.string.detail_move_dir_placeholder),
                    singleLine = true,
                    enabled = !pending,
                )
                // 目录口径说明（QimengCapsuleTextField 无 supportingText 参数——提示文案随字段独立一行）
                Text(
                    text = stringResource(R.string.detail_move_dir_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 失败反馈留弹窗内（面板内独立一条——QimengFilterSheet 同范式；错误横幅在弹窗遮罩后不可见）
                errorMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !pending && isMoveSubmittable(currentDir, currentName, targetDir, name),
                onClick = {
                    val trimmed = name.trim()
                    onSubmit(targetDir.trim(), if (trimmed != currentName) trimmed else null)
                },
            ) {
                if (pending) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = QimengDimens.SpaceS)
                            .size(MOVE_PROGRESS_SIZE),
                        strokeWidth = MOVE_PROGRESS_STROKE,
                    )
                }
                Text(text = stringResource(R.string.detail_move_confirm))
            }
        },
        dismissButton = {
            // V3「删除归整理」：danger 入口钮（error 色文字与删除确认框确认钮同色系同文案）
            // 与取消并排于主按钮左侧；pending 期间与取消钮同禁用（删除确认有自己的 pending 门控）
            Row(horizontalArrangement = Arrangement.spacedBy(QimengDimens.SpaceS)) {
                TextButton(enabled = !pending, onClick = onDeleteClick) {
                    Text(
                        text = stringResource(R.string.detail_delete_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(enabled = !pending, onClick = onDismiss) {
                    Text(text = stringResource(R.string.detail_cancel))
                }
            }
        },
    )
}

/** 删除确认弹窗（任务G G1b；Web ConfirmDialog danger 同语义，文案逐字对齐 Web FileOpsDialogs） */
@Composable
internal fun DetailDeleteConfirmDialog(
    fileName: String,
    pending: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!pending) onDismiss() },
        title = { Text(text = stringResource(R.string.detail_delete_title, fileName)) },
        text = {
            // 铁律 4：DELETE 语义 = 移入回收站（非物理删除），恢复走维护页——文案必须明示
            Text(text = stringResource(R.string.detail_delete_desc))
        },
        confirmButton = {
            TextButton(enabled = !pending, onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.detail_delete_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(enabled = !pending, onClick = onDismiss) {
                Text(text = stringResource(R.string.detail_cancel))
            }
        },
    )
}

/** 保存按钮内嵌转圈直径（DetailTagSheet TAG_SAVE_PROGRESS_SIZE 同档） */
private val MOVE_PROGRESS_SIZE = 16.dp

/** 保存按钮内嵌转圈线宽（同档） */
private val MOVE_PROGRESS_STROKE = 2.dp
