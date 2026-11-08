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
import media.qimeng.app.core.data.device.DeviceApplyLocalTokenRepository
import media.qimeng.app.core.data.device.DeviceApplyPolicy
import media.qimeng.app.core.data.device.DeviceApplyRecentTasksResult
import media.qimeng.app.core.data.device.DeviceApplyScheduler
import media.qimeng.app.core.data.device.DeviceApplySettingsRepository
import media.qimeng.app.core.data.device.DeviceApplySummary
import media.qimeng.app.core.data.device.DeviceApplyTaskHistoryRepository
import media.qimeng.app.core.data.device.LocalDeviceTokenResult
import media.qimeng.app.core.data.util.runSuspendCatching
import media.qimeng.app.core.network.ServerAddress
import media.qimeng.app.core.network.ServerConfigDataSource

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
    /** 仅设备端点使用的远端地址；空=当前登录服务端（本机模式可填 PC/NAS）。 */
    val deviceServiceUrl: String = "",
    /** 应用 v2 备份时是否以备份整份替换本机作者档案（replace）；默认保留本机（keep）。 */
    val replaceAuthorProfilesOnApply: Boolean = false,
    /** 是否已保存过设备令牌（只显示状态，不显示令牌本体）。 */
    val tokenConfigured: Boolean = false,
    val backgroundEnabled: Boolean = false,
    val saving: Boolean = false,
    /** 本机单机模式（127.0.0.1:18430）：决定「生成本机设备令牌」入口是否可见。 */
    val localMode: Boolean = false,
    /** 正在用当前会话创建/轮换本机设备令牌。 */
    val generatingToken: Boolean = false,
    /** 最近设备任务（只读；服务端新建倒序，最多 20 条）。 */
    val recentTasks: List<DeviceApplySummary> = emptyList(),
    val recentLoading: Boolean = false,
    val recentLoaded: Boolean = false,
    val recentError: String? = null,
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
 * 设备结果包设置页 ViewModel（task-17；2026-11-08 task-21 改令牌展示口径，
 * 2026-11-08 增加本机令牌自助生成与最近任务只读区）。
 *
 * 只负责表单、校验、持久化与手动触发；领取/下载/应用/回传全部在 core:data 的 Worker/Runner。
 * 「领取服务端地址」只影响设备端点（空=当前登录服务端），保存前按
 * [DeviceApplyPolicy.isValidDeviceServiceUrl] 校验；段应用仍走当前登录会话（本机库）。
 * 令牌只写 [DeviceApplySettingsRepository]（client_prefs），不进入日志、结果包、导航参数
 * 或 UI state 回填；本类仅保留内存态 [persistedToken] 以便「只改标签/开关时保留原令牌」。
 * 本机模式生成令牌同样只把「已配置」布尔状态带回 UI，明文不进入 state。
 */
@HiltViewModel
class DeviceApplyViewModel @Inject constructor(
    private val settingsRepository: DeviceApplySettingsRepository,
    private val scheduler: DeviceApplyScheduler,
    private val localTokenRepository: DeviceApplyLocalTokenRepository,
    private val historyRepository: DeviceApplyTaskHistoryRepository,
    private val serverConfig: ServerConfigDataSource,
) : ViewModel() {

    private val _state = MutableStateFlow(DeviceApplyUiState())
    val state: StateFlow<DeviceApplyUiState> = _state.asStateFlow()

    /** 只驻留内存：保存标签/开关时保留已存令牌用；不写日志、不进 UI state。 */
    private var persistedToken: String = ""

    init {
        // 本机模式信号与 DeviceApplyBootstrapper/调度器同源（ServerAddress 单点判定）。
        viewModelScope.launch {
            serverConfig.serverUrl
                .map { ServerAddress.isLocalModePreset(it) }
                .distinctUntilChanged()
                .collect { isLocal -> _state.update { it.copy(localMode = isLocal) } }
        }
        viewModelScope.launch {
            val saved = settingsRepository.settings.first()
            persistedToken = saved.deviceToken
            _state.update {
                it.copy(
                    targetDeviceId = saved.targetDeviceId,
                    deviceServiceUrl = saved.deviceServiceUrl,
                    replaceAuthorProfilesOnApply = saved.replaceAuthorProfilesOnApply,
                    // 不回填明文：只暴露「已配置」状态。
                    tokenConfigured = saved.deviceToken.isNotBlank(),
                    backgroundEnabled = saved.backgroundEnabled,
                    lastMessage = saved.lastMessage,
                    lastRunAtMillis = saved.lastRunAtMillis,
                )
            }
            // 已配置时进页即读一次最近任务；失败只显示错误，不阻塞表单。
            if (saved.deviceToken.isNotBlank()) refreshRecentTasks()
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

    fun onDeviceServiceUrlChange(value: String) {
        _state.update { it.copy(deviceServiceUrl = value, error = null) }
    }

    fun onReplaceAuthorProfilesChange(enabled: Boolean) {
        _state.update { it.copy(replaceAuthorProfilesOnApply = enabled, error = null) }
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
     * 本机单机模式自助生成设备令牌（2026-11-08）。
     *
     * 走当前会话鉴权的 POST /ai-clients（或复用同名客户端 rotate），只把 token 写进
     * [DeviceApplySettingsRepository]；成功后 UI 只显示「已配置」，明文不回填。
     * 401/403 明确提示需要本机管理员会话，不回退用会话 token 顶替设备令牌。
     */
    fun generateLocalToken() {
        val current = _state.value
        if (current.saving || current.generatingToken) return
        val target = current.targetDeviceId.trim()
        if (!DeviceApplyPolicy.isValidTargetDeviceId(target)) {
            _state.update { it.copy(error = DeviceApplyAuthorization.MISSING_TARGET_DEVICE_MESSAGE) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(generatingToken = true, error = null, notice = null) }
            when (val result = localTokenRepository.generate(target)) {
                is LocalDeviceTokenResult.Saved -> {
                    // 只从持久层回读「已保存」状态；明文仅停留在私有内存字段，
                    // 与手工输入令牌的既有口径一致（用于保留原令牌保存表单）。
                    persistedToken = runSuspendCatching {
                        settingsRepository.settings.first().deviceToken
                    }.getOrDefault("")
                    _state.update {
                        it.copy(
                            targetDeviceId = target,
                            deviceTokenDraft = "",
                            // Saved 已保证写盘成功；读取仅用于保留「原令牌」内存态。
                            tokenConfigured = true,
                            generatingToken = false,
                            error = null,
                            notice = if (result.rotated) {
                                "已轮换并保存本机设备令牌（不回显）；可开启后台领取"
                            } else {
                                "已生成本机设备令牌并保存（不回显）；可开启后台领取"
                            },
                        )
                    }
                    refreshRecentTasks()
                }
                is LocalDeviceTokenResult.Rejected ->
                    _state.update { it.copy(generatingToken = false, error = result.message) }
            }
        }
    }

    /**
     * 只读刷新最近设备任务（设备令牌客户端；不领取/不应用）。
     * 未保存令牌时清空列表，由 UI 显示「配置后可查看」。
     */
    fun refreshRecentTasks() {
        val current = _state.value
        if (current.recentLoading) return
        if (!current.tokenConfigured) {
            _state.update {
                it.copy(recentTasks = emptyList(), recentLoaded = false, recentError = null)
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(recentLoading = true, recentError = null) }
            when (val result = historyRepository.recent()) {
                is DeviceApplyRecentTasksResult.Loaded -> _state.update {
                    it.copy(
                        recentTasks = result.tasks,
                        recentLoading = false,
                        recentLoaded = true,
                        recentError = null,
                    )
                }
                is DeviceApplyRecentTasksResult.Failed -> _state.update {
                    it.copy(
                        recentTasks = emptyList(),
                        recentLoading = false,
                        recentLoaded = true,
                        recentError = result.message,
                    )
                }
            }
        }
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
        val deviceServiceUrl = _state.value.deviceServiceUrl.trim()
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
        // 空=沿用当前登录服务端；非空必须是可规范化的 http(s) 地址（仓库层仍兜底清空非法值）。
        if (!DeviceApplyPolicy.isValidDeviceServiceUrl(deviceServiceUrl)) {
            _state.update {
                it.copy(
                    saving = false,
                    error = "领取服务端地址格式不正确，请填 http(s)://主机[:端口] 或留空。",
                )
            }
            return PersistOutcome.Invalid
        }
        val enabled = _state.value.backgroundEnabled && authorization is DeviceApplyAuthorization.AuthorizationState.Ready
        return try {
            settingsRepository.save(
                targetDeviceId = target,
                deviceToken = effectiveToken,
                backgroundEnabled = enabled,
                deviceServiceUrl = deviceServiceUrl,
                replaceAuthorProfilesOnApply = _state.value.replaceAuthorProfilesOnApply,
            )
            persistedToken = effectiveToken
            val savedUrlNote = if (deviceServiceUrl.isNotEmpty()) "；已保存领取服务端地址" else ""
            val notice = when {
                authorization is DeviceApplyAuthorization.AuthorizationState.Missing -> authorization.message
                draftToken.isNotEmpty() ->
                    "已保存新设备令牌；后台领取" + (if (enabled) "已开启" else "未开启") + savedUrlNote
                enabled -> "已保存；后台领取已开启（系统省电可能延迟）" + savedUrlNote
                else -> "已保存；后台领取未开启" + savedUrlNote
            }
            _state.update {
                it.copy(
                    targetDeviceId = target,
                    deviceServiceUrl = deviceServiceUrl,
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
