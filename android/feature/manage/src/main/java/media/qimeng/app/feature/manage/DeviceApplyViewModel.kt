package media.qimeng.app.feature.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.qimeng.app.core.data.device.DeviceApplyAuthorization
import media.qimeng.app.core.data.device.DeviceApplyPolicy
import media.qimeng.app.core.data.device.DeviceApplyScheduler
import media.qimeng.app.core.data.device.DeviceApplySettingsRepository

/** 设备领取设置页 UI 状态；lastMessage 只显示本机最近一次运行摘要，不代表服务端账本。 */
data class DeviceApplyUiState(
    val targetDeviceId: String = "",
    val deviceToken: String = "",
    val backgroundEnabled: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val lastMessage: String? = null,
    val lastRunAtMillis: Long? = null,
) {
    val configured: Boolean
        get() = DeviceApplyAuthorization.check(targetDeviceId, deviceToken) is
            DeviceApplyAuthorization.AuthorizationState.Ready
}

/**
 * 设备结果包设置页 ViewModel（task-17）。
 *
 * 只负责表单、校验、持久化与手动触发；领取/下载/应用/回传全部在 core:data 的 Worker/Runner。
 * 令牌只写 [DeviceApplySettingsRepository]（client_prefs），不进入日志、结果包或导航参数。
 */
@HiltViewModel
class DeviceApplyViewModel @Inject constructor(
    private val settingsRepository: DeviceApplySettingsRepository,
    private val scheduler: DeviceApplyScheduler,
) : ViewModel() {

    private val _state = MutableStateFlow(DeviceApplyUiState())
    val state: StateFlow<DeviceApplyUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = settingsRepository.settings.first()
            _state.update {
                it.copy(
                    targetDeviceId = saved.targetDeviceId,
                    deviceToken = saved.deviceToken,
                    backgroundEnabled = saved.backgroundEnabled,
                    lastMessage = saved.lastMessage,
                    lastRunAtMillis = saved.lastRunAtMillis,
                )
            }
        }
        // 只订阅 Worker 写入的状态行；不把整份 settings 回灌表单，避免覆盖未保存草稿。
        viewModelScope.launch {
            settingsRepository.settings
                .map { it.lastMessage to it.lastRunAtMillis }
                .distinctUntilChanged()
                .collect { (message, at) ->
                    _state.update { it.copy(lastMessage = message, lastRunAtMillis = at) }
                }
        }
    }

    fun onTargetDeviceIdChange(value: String) {
        _state.update { it.copy(targetDeviceId = value, error = null) }
    }

    fun onDeviceTokenChange(value: String) {
        _state.update { it.copy(deviceToken = value, error = null) }
    }

    fun onBackgroundEnabledChange(enabled: Boolean) {
        if (enabled && !_state.value.configured) {
            _state.update {
                it.copy(error = DeviceApplyAuthorization.SESSION_TOKEN_NOT_DEVICE_AUTH_MESSAGE)
            }
            return
        }
        _state.update { it.copy(backgroundEnabled = enabled, error = null) }
    }

    /** 保存表单；即使未配置令牌也允许保存（用于清空/稍后补令牌），但后台开关会被强制关闭。 */
    fun save() {
        viewModelScope.launch { persistDraft() }
    }

    /**
     * 手动立即应用：先落盘当前草稿（失败不触发），再入队一次性 Worker。
     * 后台开关关闭时仍允许手动执行；无设备令牌时明确拒绝，不回退会话 token。
     */
    fun applyNow() {
        viewModelScope.launch {
            if (persistDraft() != PersistOutcome.Ready) return@launch
            scheduler.requestNow()
            _state.update {
                it.copy(notice = "已请求立即应用；WorkManager 会在满足网络条件时执行")
            }
        }
    }

    private suspend fun persistDraft(): PersistOutcome {
        if (_state.value.saving) return PersistOutcome.Busy
        _state.update { it.copy(saving = true, error = null, notice = null) }
        val target = _state.value.targetDeviceId.trim()
        val token = _state.value.deviceToken.trim()
        val authorization = DeviceApplyAuthorization.check(target, token)
        // 允许「标签与令牌都为空」保存为清空；只填了令牌却没有标签是不完整配置。
        if (target.isEmpty() && token.isNotEmpty()) {
            _state.update {
                it.copy(
                    saving = false,
                    error = DeviceApplyAuthorization.MISSING_TARGET_DEVICE_MESSAGE,
                )
            }
            return PersistOutcome.Invalid
        }
        if (target.isNotEmpty() && !DeviceApplyPolicy.isValidTargetDeviceId(target)) {
            _state.update {
                it.copy(
                    saving = false,
                    error = "设备标签只允许 1–100 位字母、数字或 ._:-，且不能包含空格或其他字符。",
                )
            }
            return PersistOutcome.Invalid
        }
        val enabled = _state.value.backgroundEnabled && authorization is DeviceApplyAuthorization.AuthorizationState.Ready
        return try {
            settingsRepository.save(target, token, enabled)
            val notice = when {
                authorization is DeviceApplyAuthorization.AuthorizationState.Missing -> authorization.message
                enabled -> "已保存；后台领取已开启（系统省电可能延迟）"
                else -> "已保存；后台领取未开启"
            }
            _state.update {
                it.copy(
                    targetDeviceId = target,
                    deviceToken = token,
                    backgroundEnabled = enabled,
                    saving = false,
                    notice = notice,
                    error = null,
                )
            }
            if (authorization is DeviceApplyAuthorization.AuthorizationState.Ready) {
                PersistOutcome.Ready
            } else {
                PersistOutcome.NotConfigured
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            _state.update {
                it.copy(saving = false, error = "保存设备设置失败，请重试")
            }
            PersistOutcome.Invalid
        }
    }

    private enum class PersistOutcome { Ready, NotConfigured, Invalid, Busy }
}
