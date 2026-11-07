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
import media.qimeng.app.core.ui.component.QimengCapsuleTextField
import media.qimeng.app.core.ui.component.QimengSettingsCard
import media.qimeng.app.core.ui.icon.BackupRestoreIcon
import media.qimeng.app.core.ui.theme.QimengDimens

/**
 * 设备结果包设置/手动触发页（task-17，嵌入「资料维护」第四个分段）。
 *
 * 只做最小闭环：设备标签、专用设备 AI 令牌（口令式）、后台领取开关、立即应用。
 * 结果包列表/历史仍以 PC 端服务端账本为准；本页不展示段内容，也不接受会话 token 顶替。
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
                    "在服务器本机执行 qimeng ai-client create -label <名称> -scopes device.apply " +
                        "-device-ids <设备标签>，把只显示一次的令牌填入下方。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text("设备标签", style = MaterialTheme.typography.labelMedium)
                QimengCapsuleTextField(
                    value = state.targetDeviceId,
                    onValueChange = viewModel::onTargetDeviceIdChange,
                    placeholder = "如 phone-main",
                    singleLine = true,
                    enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "1–100 位字母、数字或 ._:-；必须与令牌的 device-ids 完全一致。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text("设备 AI 令牌", style = MaterialTheme.typography.labelMedium)
                QimengCapsuleTextField(
                    value = state.deviceToken,
                    onValueChange = viewModel::onDeviceTokenChange,
                    placeholder = "只显示一次的专用令牌",
                    singleLine = true,
                    enabled = !state.saving,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "只保存在本机；登录会话 token 不能替代设备级授权。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

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
                        enabled = !state.saving && state.deviceToken.isNotBlank(),
                    )
                }

                if (!state.configured) {
                    Text(
                        DeviceApplyAuthorization.SESSION_TOKEN_NOT_DEVICE_AUTH_MESSAGE,
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
                        enabled = !state.saving && state.configured,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (state.saving) "处理中…" else "立即应用")
                    }
                    OutlinedButton(
                        onClick = viewModel::save,
                        enabled = !state.saving,
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
