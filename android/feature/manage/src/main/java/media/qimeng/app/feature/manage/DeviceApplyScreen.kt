package media.qimeng.app.feature.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import media.qimeng.app.core.data.device.DeviceApplyAuthorization
import media.qimeng.app.core.data.device.DeviceApplyPolicy
import media.qimeng.app.core.data.device.DeviceTaskStatus
import media.qimeng.app.core.ui.component.QimengCapsuleTextField
import media.qimeng.app.core.ui.component.QimengSettingsCard
import media.qimeng.app.core.ui.icon.BackupRestoreIcon
import media.qimeng.app.core.ui.theme.QimengDimens

/**
 * 设备结果包设置/手动触发页（task-17，嵌入「资料维护」第四个分段）。
 *
 * 只做最小闭环：设备标签、专用设备 AI 令牌（口令式）、后台领取开关、立即应用。
 * 2026-11-08 增加：本机单机模式「生成本机设备令牌」（会话签发 device.apply，明文只落盘
 * 不回显）与「最近设备任务」只读区（设备令牌读取最近 20 条，不领取/不应用）。
 * 结果包历史仍以 PC 端服务端账本为准；本页不展示段内容，也不接受会话 token 顶替。
 */
@Composable
fun DeviceApplyScreen(
    modifier: Modifier = Modifier,
    viewModel: DeviceApplyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = QimengDimens.ScreenPaddingHorizontal)
            .padding(bottom = QimengDimens.EmptyStateVerticalPadding),
        verticalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM),
    ) {
        QimengSettingsCard {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(BackupRestoreIcon, contentDescription = null)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("设备结果包", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "PC 登记、手机后台领取；应用作者主表与 v2 备份",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    "远端模式：在服务器本机执行 qimeng ai-client create -label <名称> -scopes device.apply " +
                        "-device-ids <设备标签>，把只显示一次的令牌填入下方。" +
                        "本机单机模式可直接点下方「生成本机设备令牌」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text("设备标签", style = MaterialTheme.typography.labelMedium)
                QimengCapsuleTextField(
                    value = state.targetDeviceId,
                    onValueChange = viewModel::onTargetDeviceIdChange,
                    placeholder = "如 phone-main",
                    singleLine = true,
                    enabled = !state.saving && !state.generatingToken,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "1–100 位字母、数字或 ._:-；必须与令牌的 device-ids 完全一致。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text("设备 AI 令牌", style = MaterialTheme.typography.labelMedium)
                QimengCapsuleTextField(
                    value = state.deviceTokenDraft,
                    onValueChange = viewModel::onDeviceTokenChange,
                    placeholder = if (state.tokenConfigured) "已配置；输入新令牌可替换" else "只显示一次的专用令牌",
                    singleLine = true,
                    enabled = !state.saving && !state.generatingToken,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "只保存在本机且不回显明文：保存后仅显示「已配置」，输入新令牌保存即替换。登录会话 token 不能替代设备级授权。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.localMode) {
                    // 本机单机模式：用应用自己的本机管理员会话签发 device.apply 令牌
                    // （POST /ai-clients；同名同设备已存在时走 rotate）。明文只落盘不回显。
                    Text(
                        "本机单机模式可用当前本机管理员会话生成本机设备令牌（device.apply，仅绑定上方设备标签）；" +
                            "令牌只保存不回显。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = viewModel::generateLocalToken,
                        enabled = !state.saving && !state.generatingToken &&
                            DeviceApplyPolicy.isValidTargetDeviceId(state.targetDeviceId.trim()),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.generatingToken) "生成中…" else "生成本机设备令牌")
                    }
                }
                if (state.tokenConfigured) {
                    OutlinedButton(
                        onClick = viewModel::clearToken,
                        enabled = !state.saving && !state.generatingToken,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("清除设备令牌")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("后台领取", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "每 15 分钟尝试一次；系统省电可能延迟。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.backgroundEnabled,
                        onCheckedChange = viewModel::onBackgroundEnabledChange,
                        enabled = !state.saving && !state.generatingToken && state.deviceTokenProvided,
                    )
                }

                if (!state.configured) {
                    Text(
                        if (state.deviceTokenProvided) {
                            DeviceApplyAuthorization.MISSING_TARGET_DEVICE_MESSAGE
                        } else {
                            DeviceApplyAuthorization.SESSION_TOKEN_NOT_DEVICE_AUTH_MESSAGE
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = viewModel::applyNow,
                        enabled = !state.saving && !state.generatingToken && state.configured,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (state.saving) "处理中…" else "立即应用")
                    }
                    OutlinedButton(
                        onClick = viewModel::save,
                        enabled = !state.saving && !state.generatingToken,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("保存设置")
                    }
                }
                state.error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                state.notice?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                state.lastMessage?.let {
                    Text(
                        "最近一次：${it}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                state.lastRunAtMillis?.let {
                    Text(
                        "运行时间：${formatRunTime(it)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        QimengSettingsCard {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("最近设备任务", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "只读：最近 20 条，使用专用设备令牌读取；领取/应用仍由后台执行。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = viewModel::refreshRecentTasks,
                        enabled = !state.recentLoading && state.tokenConfigured,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(if (state.recentLoading) "读取中…" else "刷新")
                    }
                }
                when {
                    !state.tokenConfigured -> Text(
                        "配置设备令牌后可查看本机最近任务。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    state.recentError != null -> Text(
                        state.recentError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    state.recentTasks.isEmpty() && state.recentLoaded -> Text(
                        "暂无设备任务。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    state.recentTasks.isEmpty() && !state.recentLoading -> Text(
                        "点「刷新」读取最近任务。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> state.recentTasks.forEach { task ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    taskStatusLabel(task.status),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    "${formatRunTime(task.createdAtMillis)} · ${task.segmentCount} 段",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        QimengSettingsCard {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("应用前提", style = MaterialTheme.typography.titleSmall)
                Text(
                    "• 维护目录已授权：应用前会先导出本机「当前/上次」两代备份；" +
                        "缺授权或写失败会回传 failed，并提示到资料备份与归档处理。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "• 作者 TXT 与 v2 备份导入使用当前登录会话；设备令牌只用于设备级领取与回传。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "• 结果以服务端账本为准；后台任务受系统调度影响，可用「立即应用」手动触发。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatRunTime(millis: Long): String = runCatching {
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}.getOrDefault("")

/** 服务端账本状态 → 中文只读标签（不改变状态机语义）。 */
private fun taskStatusLabel(status: DeviceTaskStatus): String = when (status) {
    DeviceTaskStatus.PENDING -> "待领取"
    DeviceTaskStatus.CLAIMED -> "已领取"
    DeviceTaskStatus.APPLIED -> "已应用"
    DeviceTaskStatus.PARTIAL -> "部分成功"
    DeviceTaskStatus.FAILED -> "失败"
    DeviceTaskStatus.CANCELLED -> "已取消"
    DeviceTaskStatus.EXPIRED -> "已过期"
}
