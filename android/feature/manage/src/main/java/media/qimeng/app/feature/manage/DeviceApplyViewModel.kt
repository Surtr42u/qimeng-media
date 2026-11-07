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

/** 已持久化令牌的检查占位（只用于纯校验，不进入 UI state/日志）。 */
private const val CONFIGURED_TOKEN_MARKER = "\u00b7"

/**
 * 设备领取设置页 UI 状态；lastMessage 只显示本机最近一次运行摘要，不代表服务端账本。
 *
 * 令牌安全口径（2026-11-08 task-21 P3-2）：已保存的令牌**不回填**到 state/输入框，
 * 只暴露 [tokenConfigured] 布尔状态；输入框只承载「新令牌草稿」，保存即替换。
 */
data class DeviceApplyUiState(
    val targetDeviceId: String = "",
    /** 新令牌草稿（空=不改变已有令牌）；已保存明文不进入 UI state。 */
    val deviceTokenDraft: String = "",
    /** 是否已保存过设备令牌（只显示状态，不显示令牌本体）。 */
    val tokenConfigured: Boolean = false,
    val backgroundEnabled: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val lastMessage: String? = null,
    val lastRunAtMillis: Long? = null,
) {
    /** 草稿或已保存令牌至少一个存在。 */
    val deviceTokenProvided: Boolean
        get() = deviceTokenDraft.isNotBlank() || tokenConfigured

    /** 纯校验用有效令牌（已保存时用占位符，仅判断非空）。 */
    private val effectiveTokenForCheck: String
        get() = deviceTokenDraft.trim().ifEmpty { if (tokenConfigured) CONFIGURED_TOKEN_MARKER else "" }

    val configured: Boolean
        get() = DeviceApplyAuthorization.check(targetDeviceId, effectiveTokenForCheck) is
            DeviceApplyAuthorization.AuthorizationState.Ready
}

/**
 * 设备结果包设置页 ViewModel（task-17；2026-11-08 task-21 改令牌展示口径）。
 *
 * 只负责表单、校验、持久化与手动触发；领取/下载/应用/回传全部在 core:data 的 Worker/Runner。
 * 令牌只写 [DeviceApplySettingsRepository]（client_prefs），不进入日志、结果包、导航参数
 * 或 UI state 回填；本类仅保留内存态 [persistedToken] 以便「只改标签/开关时保留原令牌」。
 */
@HiltViewModel
class DeviceApplyViewModel @Inject constructor(
    private val settingsRepository: DeviceApplySettingsRepository,
    private val scheduler: DeviceApplyScheduler,
) : ViewModel() {

    private val _state = MutableStateFlow(DeviceApplyUiState())
    val state: StateFlow<DeviceApplyUiState> = _state.asStateFlow()

    /** 只驻留内存：保存标签/开关时保留已存令牌用；不写日志、不进 UI state。 */
    private var persistedToken: String = ""

    init {
        viewModelScope.launch {
            val saved = settingsRepository.settings.first()
            persistedToken = saved.deviceToken
            _state.update {
                it.copy(
                    targetDeviceId = saved.targetDeviceId,
                    // 不回填明文：只暴露「已配置」状态。
                    tokenConfigured = saved.deviceToken.isNotBlank(),
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
        // 输入新令牌视为替换草稿；保存后才写入持久层。
        _state.update { it.copy(deviceTokenDraft = value, error = null) }
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

    /** 清除已保存令牌并关闭后台领取（标签保留）；立即落盘，不等待点保存。 */
    fun clearToken() {
        persistedToken = ""
        _state.update {
            it.copy(
                deviceTokenDraft = "",
                tokenConfigured = false,
                backgroundEnabled = false,
                error = null,
                notice = "已清除设备令牌",
            )
        }
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
        val draftToken = _state.value.deviceTokenDraft.trim()
        // 草稿为空=保留已保存令牌；否则用新草稿替换（P3-2：不回填明文但仍可整体替换）。
        val effectiveToken = draftToken.ifEmpty { persistedToken }
        val authorization = DeviceApplyAuthorization.check(target, effectiveToken)
        // 允许「标签与令牌都为空」保存为清空；只填了令牌却没有标签是不完整配置。
        if (target.isEmpty() && effectiveToken.isNotEmpty()) {
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
            settingsRepository.save(target, effectiveToken, enabled)
            persistedToken = effectiveToken
            val notice = when {
                authorization is DeviceApplyAuthorization.AuthorizationState.Missing -> authorization.message
                draftToken.isNotEmpty() -> "已保存新设备令牌；后台领取" + (if (enabled) "已开启" else "未开启")
                enabled -> "已保存；后台领取已开启（系统省电可能延迟）"
                else -> "已保存；后台领取未开启"
            }
            _state.update {
                it.copy(
                    targetDeviceId = target,
                    deviceTokenDraft = "",
                    tokenConfigured = effectiveToken.isNotBlank(),
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
