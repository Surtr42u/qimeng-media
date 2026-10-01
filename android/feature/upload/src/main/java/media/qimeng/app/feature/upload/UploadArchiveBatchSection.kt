package media.qimeng.app.feature.upload

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 上传页「从归档文件夹一键上传」区（2026-10-01 归档一键上传；自 UploadScreen 拆出，
 * 控制 600 行红线）：归档根未配置 → 引导文案（不强跳，设置入口在数据管理/归档文件夹页）；
 * 已配置 → 摘要行 +「一键上传」按钮。确认弹窗列匹配概况与未匹配文件夹清单。
 * 本文件只做状态渲染与弹窗编排：扫描/匹配在 core:data（scanArchiveForUpload 纯函数）、
 * 入队规则在 UploadViewModel（ADR-0008 铁律 7），文案常量收文件尾（代码卫生约束）。
 */

/**
 * 归档一键上传入口区：未配置只引导、扫描中提示、扫描完出摘要与按钮。
 * 按钮仅在扫描结果有待传条目且非提交中时可点（空结果置灰，门禁在 VM 同口径兜底）。
 */
@Composable
internal fun ArchiveBatchSection(
    state: UploadUiState,
    onUploadClick: () -> Unit,
) {
    SectionTitle(ARCHIVE_BATCH_SECTION_TITLE)
    val scan = state.archiveBatch
    when {
        // 扫描中（含归档根已配置但首扫未完成的窗口）
        state.archiveBatchLoading ->
            Text(
                text = ARCHIVE_BATCH_SCANNING_TEXT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

        // 归档根未配置：只指路不强跳
        scan == null ->
            Text(
                text = ARCHIVE_BATCH_NOT_CONFIGURED_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

        else -> {
            Text(
                text = state.archiveBatchSummary.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onUploadClick,
                // 置门口径：无待传条目 / 提交中 / 本会话已成功入队过一轮且扫描未变化
                // （防整批重入队——源文件上传成功前仍在归档根，重复确认 = 服务端同名副本）
                enabled = scan.items.isNotEmpty() && !state.submitting && !state.archiveBatchEnqueued,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(ARCHIVE_BATCH_BUTTON_TEXT)
            }
            Text(
                text = ARCHIVE_BATCH_REUPLOAD_WARNING,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = ARCHIVE_BATCH_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 一键上传确认弹窗（对齐页面既有 AlertDialog 样式）：正文列匹配概况与未匹配文件夹
 * 清单（文件夹名 + 原因，超长可滚动）；确认走 [UploadViewModel.onArchiveBatchUpload]，
 * 入队成功提示沿用页面既有 QimengMessageCard 横幅（noticeMessage，VM 侧落字）。
 */
@Composable
internal fun ArchiveBatchConfirmDialog(
    state: UploadUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scan = state.archiveBatch ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ARCHIVE_BATCH_DIALOG_TITLE) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = ARCHIVE_BATCH_DIALOG_MAX_HEIGHT_DP.dp),
            ) {
                Text(
                    text = state.archiveBatchSummary.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.padding(top = 8.dp))
                Text(
                    text = ARCHIVE_BATCH_DIALOG_SKIP_ARCHIVE_NOTE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.padding(top = 4.dp))
                Text(
                    text = ARCHIVE_BATCH_REUPLOAD_WARNING,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                if (state.archiveBatchOverLimitCount > 0) {
                    Spacer(modifier = Modifier.padding(top = 4.dp))
                    Text(
                        text = archiveBatchOverLimitNote(state.archiveBatchOverLimitCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (scan.unmatchedFolders.isNotEmpty()) {
                    Spacer(modifier = Modifier.padding(top = 8.dp))
                    Text(
                        text = ARCHIVE_BATCH_DIALOG_UNMATCHED_TITLE,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    scan.unmatchedFolders.forEach { (folder, reason) ->
                        Text(
                            text = "· $folder：$reason",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                // 与入口区按钮同口径：条目为空 / 提交中 / 防重入队门禁置位时禁用
                enabled = scan.items.isNotEmpty() && !state.submitting && !state.archiveBatchEnqueued,
            ) {
                Text(ARCHIVE_BATCH_DIALOG_CONFIRM_TEXT)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(ARCHIVE_BATCH_DIALOG_CANCEL_TEXT) }
        },
    )
}

// ---------- 文案常量（单文件单源；修改须知确认弹窗与入口区共用同一套措辞） ----------

/** 入口区标题 */
private const val ARCHIVE_BATCH_SECTION_TITLE = "从归档文件夹一键上传"

/** 一键上传按钮文案 */
private const val ARCHIVE_BATCH_BUTTON_TEXT = "一键上传"

/** 归档根未配置引导文案（指到数据管理/归档文件夹设置页，不强跳） */
private const val ARCHIVE_BATCH_NOT_CONFIGURED_HINT =
    "尚未设置归档文件夹：到 数据管理 → 归档文件夹 设置后，可把归档区里已按库名分好类的存量文件一键重传到当前服务器"

/** 扫描进行中文案 */
private const val ARCHIVE_BATCH_SCANNING_TEXT = "正在扫描归档文件夹…"

/** 按钮下说明（行为口径：逐条按文件夹匹配库；成功后不重复归档） */
private const val ARCHIVE_BATCH_HINT =
    "按归档文件夹的库名子文件夹逐条匹配目标库并上传；上传完成后文件保留原位，不再重复归档"

/** 确认弹窗标题 */
private const val ARCHIVE_BATCH_DIALOG_TITLE = "确认一键上传"

/** 确认弹窗正文：不重复归档口径说明 */
private const val ARCHIVE_BATCH_DIALOG_SKIP_ARCHIVE_NOTE =
    "上传完成后文件保留在归档文件夹内（不再重复归档）"

/**
 * 防重复上传警示（区块与确认弹窗共用同一措辞；审查修整项 2026-10-01）：一键入队成功后
 * 源文件在上传成功前仍留在归档根，防重入队门禁只挡「扫描未变化」的整批重复，用户手动
 * 增删文件后的重新入队/并行上传仍可能产生同名副本，须常驻提示。
 */
private const val ARCHIVE_BATCH_REUPLOAD_WARNING =
    "归档文件在上传成功前仍保留原位，重复确认会重复上传（同名会在服务端生成副本）"

/** 确认弹窗超限提示（count = 本地判定的超限文件数；超限条目一键路径不入队，判定见 VM 单源） */
private fun archiveBatchOverLimitNote(count: Int): String = "$count 个超限文件将被跳过"

/** 确认弹窗未匹配清单标题 */
private const val ARCHIVE_BATCH_DIALOG_UNMATCHED_TITLE = "未匹配文件夹（不上传）"

/** 确认弹窗确认按钮文案 */
private const val ARCHIVE_BATCH_DIALOG_CONFIRM_TEXT = "确认上传"

/** 确认弹窗取消按钮文案 */
private const val ARCHIVE_BATCH_DIALOG_CANCEL_TEXT = "取消"

/** 确认弹窗正文滚动区最大高度（dp）：超长未匹配清单在此内滚动，弹窗不撑破屏幕 */
private const val ARCHIVE_BATCH_DIALOG_MAX_HEIGHT_DP = 360
