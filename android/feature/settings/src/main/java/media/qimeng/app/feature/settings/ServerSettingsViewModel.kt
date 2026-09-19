package media.qimeng.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.qimeng.app.core.data.embedded.EmbeddedServerController
import media.qimeng.app.core.data.repository.AuthRepository
import media.qimeng.app.core.network.ServerAddress

/** 服务器设置页 UI 状态（U10-4：原设置页「服务器地址」卡与「本机模式」入口行合并入本页） */
data class ServerSettingsUiState(
    /** 当前生效地址（collect 自 AuthRepository，仅展示；编辑值见 [urlInput]） */
    val currentUrl: String = "",
    /** 「服务器地址」卡输入框：进页用当前地址回填一次，此后用户输入不被仓库流覆盖 */
    val urlInput: String = "",
    /**
     * 「本机模式」卡预填输入框：初值=预设常量，允许改端口。语义仅「下次登录预填」——
     * 不持久化（真正生效仍在登录页确认后走既有登录持久化链路）、不动 core
     * ServerAddress 常量（避免出现第二地址源，见 [ServerAddress.LOCAL_MODE_PRESET] 记档）
     */
    val localUrlInput: String = ServerAddress.LOCAL_MODE_PRESET,
    /** 换址提交进行中（两卡动作共用防重位：登出链路不可重入） */
    val isSaving: Boolean = false,
    /** 服务器地址卡输入规范化失败（UI 显格式错误文案，文案在屏幕层） */
    val urlInvalid: Boolean = false,
    /** 本机模式卡输入规范化失败 */
    val localUrlInvalid: Boolean = false,
    /** 当前地址是否本机模式（批C 任务Q C-3：决定「仅充电时扫描」行可见性） */
    val isLocalMode: Boolean = false,
    /** 「仅充电时扫描」设置项（默认开；DataStore 持久化，仅本机模式 UI 可见可改） */
    val chargeOnlyScanEnabled: Boolean = true,
)

/** 一次性事件：登出完成（壳层登录态流随即切登录页；本事件是切页前的兜底退栈信号） */
sealed interface ServerSettingsEvent {
    data object LoggedOut : ServerSettingsEvent
}

/**
 * 服务器设置页 ViewModel（U10-4）：地址修改（保存并重新登录）与本机模式切换（原
 * SettingsViewModel.fillLocalModeForNextLogin 整体迁入）共用一条换址链路。
 * 业务规则一律走 :core:data 仓库与 :core:network 纯函数（规范化），本层只做状态编排。
 * 批C 任务Q C-3：注入 [ScanChargeController] 承接「仅充电时扫描」设置项（仅本机模式可见）。
 */
@HiltViewModel
class ServerSettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val embeddedServerController: EmbeddedServerController,
    private val scanChargeController: media.qimeng.app.core.data.scan.ScanChargeController,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServerSettingsUiState())
    val uiState: StateFlow<ServerSettingsUiState> = _uiState.asStateFlow()

    private val _events = Channel<ServerSettingsEvent>(Channel.BUFFERED)
    val events: Flow<ServerSettingsEvent> = _events.receiveAsFlow()

    /** 输入框只回填一次（首个非空地址）；置位后仓库流再变只刷展示位，不动用户输入 */
    private var inputSeeded = false

    /** 本机模式卡预填是否已被用户编辑（批S3：记忆流晚到不覆盖用户输入） */
    private var localInputEdited = false

    init {
        viewModelScope.launch {
            // 批S3 服务器地址固化：地址卡回填按当前端型取记忆——当前端=本机模式时回填
            // 记忆的 NAS 地址（切回 NAS 免重输，无记忆回退当前地址=既有行为）；当前端=NAS
            // 时回填当前地址（此时当前地址即 NAS 记忆，等价）
            combine(authRepository.serverUrl, authRepository.rememberedNasUrl) { url, nasMemory ->
                url to nasMemory
            }.collect { (url, nasMemory) ->
                // C-3：本机模式判定随地址流实时刷新（连 localhost:18430 = isLocalModePreset）
                val localMode = ServerAddress.isLocalModePreset(url)
                if (!inputSeeded && url.isNotEmpty()) {
                    inputSeeded = true
                    val seed = if (localMode) nasMemory.ifEmpty { url } else url
                    _uiState.update {
                        it.copy(currentUrl = url, urlInput = seed, isLocalMode = localMode)
                    }
                } else {
                    _uiState.update { it.copy(currentUrl = url, isLocalMode = localMode) }
                }
            }
        }
        // 批S3：本机模式卡预填=记忆的本机模式地址（M6 单机形态口，自定义端口也能带出），
        // 无记忆保持预设常量初值；用户已编辑则晚到的记忆不覆盖
        viewModelScope.launch {
            val rememberedLocal = authRepository.rememberedLocalUrl.first()
            if (rememberedLocal.isNotEmpty() && !localInputEdited) {
                _uiState.update { it.copy(localUrlInput = rememberedLocal) }
            }
        }
        viewModelScope.launch {
            scanChargeController.chargeOnlyScanEnabled.collect { enabled ->
                _uiState.update { it.copy(chargeOnlyScanEnabled = enabled) }
            }
        }
    }

    fun onUrlChange(value: String) {
        _uiState.update { it.copy(urlInput = value, urlInvalid = false) }
    }

    fun onLocalUrlChange(value: String) {
        localInputEdited = true
        _uiState.update { it.copy(localUrlInput = value, localUrlInvalid = false) }
    }

    /** 「仅充电时扫描」Switch 直写持久化（C-3；仅本机模式 UI 暴露） */
    fun onChargeOnlyScanChange(enabled: Boolean) {
        viewModelScope.launch {
            scanChargeController.setChargeOnlyScanEnabled(enabled)
        }
    }

    /** 保存并重新登录：规范化新地址 → 登出并预置为下次登录带出值 */
    fun saveAndRelogin() {
        stageAndLogout(_uiState.value.urlInput) { _uiState.update { it.copy(urlInvalid = true) } }
    }

    /**
     * 一键切换本机模式（原 SettingsViewModel.fillLocalModeForNextLogin 迁入，语义不变）：
     * 预填值默认=预设常量，编辑过则=编辑值（都经同一规范化）。
     */
    fun switchToLocalMode() {
        stageAndLogout(_uiState.value.localUrlInput) { _uiState.update { it.copy(localUrlInvalid = true) } }
    }

    /**
     * 换址公共链路（两卡动作共用）。为什么经登出写地址：登录态下直写地址会让在途业务
     * 请求立即改指新地址；经 [AuthRepository.logoutWithStagedUrl] 只影响登录页回填，
     * 地址仍经 ServerConfigDataSource 单点流转（与旧设置页快捷入口同语义）。
     * 规范化失败只置错误态不发仓库调用（中文文案在屏幕层）；提交后不回滚 isSaving——
     * 登出成功即整页离树（壳层切登录页），重入位随页面销毁消失。
     */
    private fun stageAndLogout(rawInput: String, onInvalid: () -> Unit) {
        if (_uiState.value.isSaving) return
        val normalized = ServerAddress.normalize(rawInput)
        if (normalized == null) {
            onInvalid()
            return
        }
        // U11 批次D：切到内嵌预设地址时先拉起本机服务端（ensure 幂等）——登录页
        // 回填 18430 后用户点登录时服务端已在启动中；非预设地址（自定义端口=
        // 自带 Termux 服务端场景）不触发内嵌拉起
        embeddedServerController.ensureStartedIfLocalMode(normalized)
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            authRepository.logoutWithStagedUrl(normalized)
            _events.send(ServerSettingsEvent.LoggedOut)
        }
    }
}
