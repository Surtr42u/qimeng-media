package media.qimeng.app.feature.upload

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import media.qimeng.app.core.model.UploadQueueEntry
import media.qimeng.app.core.model.UploadStatus

/**
 * 上传页队列区（自 UploadScreen 拆出，控制 600 行红线）：聚合行 + 逐条状态行 + 取消控件。
 * ATTACH_FAILED（挂靠批）专项态：文件已入库、挂靠未成，文案指引到作品编辑页补挂。
 */

/**
 * 队列区：聚合行「共 N 个 · 成功 X · 失败 Y（· 挂靠失败 Z）」+ 逐条状态行 + 取消控件（C-2）。
 * 取消交互对齐 Web 上传队列：点击即取消、无二次确认。
 */
@Composable
internal fun QueueSection(
    queue: List<UploadQueueEntry>,
    summary: String?,
    onCancel: (UploadQueueEntry) -> Unit,
) {
    SectionTitle("上传队列")
    summary?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    queue.forEach { entry -> QueueRow(entry, onCancel) }
}

/** 队列行：文件名 + 状态 + 取消控件（排队中/上传中可取消；C-2 冻结语义「皆可取消」） */
@Composable
private fun QueueRow(entry: UploadQueueEntry, onCancel: (UploadQueueEntry) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(entry.displayName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            when (entry.status) {
                UploadStatus.QUEUED, UploadStatus.UPLOADING -> TextButton(onClick = { onCancel(entry) }) {
                    Text(QUEUE_ACTION_CANCEL)
                }

                else -> Unit
            }
        }
        when (entry.status) {
            UploadStatus.QUEUED ->
                StatusText("排队中（串行队列）", MaterialTheme.colorScheme.onSurfaceVariant)

            UploadStatus.UPLOADING -> {
                entry.progressPercent?.let { percent ->
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                    )
                }
                StatusText(
                    "上传中${entry.progressPercent?.let { " $it%" } ?: "…"}",
                    MaterialTheme.colorScheme.primary,
                )
            }

            UploadStatus.SUCCEEDED -> StatusText(
                "上传成功：${entry.finalFileName ?: entry.displayName}",
                MaterialTheme.colorScheme.primary,
            )

            // 挂靠批专项态：文件已入库（finalFileName 在），失败的是 201 之后的挂靠调用
            UploadStatus.ATTACH_FAILED -> {
                StatusText(
                    "已入库：${entry.finalFileName ?: entry.displayName}",
                    MaterialTheme.colorScheme.tertiary,
                )
                StatusText(
                    entry.errorMessage ?: ATTACH_FAILED_FALLBACK_TEXT,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            UploadStatus.FAILED -> StatusText(
                "失败：${entry.errorMessage ?: "未知原因"}",
                MaterialTheme.colorScheme.error,
            )

            // 取消不计入失败（与 FAILED 分列；errorMessage=「已取消」仅诊断用，UI 文案定版）
            UploadStatus.CANCELLED -> StatusText(
                QUEUE_CANCELLED_TEXT,
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusText(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
    )
}

/** 队列行取消控件文案（对齐 Web 上传队列：取消无需二次确认） */
private const val QUEUE_ACTION_CANCEL = "取消"

/** 队列行取消态文案（与 FAILED 分列：用户取消不计失败） */
private const val QUEUE_CANCELLED_TEXT = "已取消"

/** 队列行挂靠失败态兜底文案（正常 errorMessage 已带完整指引；缺载体时兜底） */
private const val ATTACH_FAILED_FALLBACK_TEXT = "已入库但挂靠失败：请到 作品详情→作者→编辑 补挂"
