package media.qimeng.app.feature.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.qimeng.app.core.data.repository.AuthRepository
import media.qimeng.app.core.data.repository.LoginError
import media.qimeng.app.core.data.repository.LoginResult
import media.qimeng.app.core.network.DefaultEndpoint
import media.qimeng.app.core.network.ServerAddress

/**
 * 登录页 UI 状态（第三百六十三笔：登录页改为「服务器 / 本机」两个选项）。
 * 错误只存 [LoginError] 分类、不存文案——中文文案在 UI 层经资源表映射
 * （「地址不通」与「密码错」分开提示，M4-1 冻结口径）。
 */
data class LoginUiState(
    /** 当前选中的登录端（两张选项卡点选）；null = 尚未选中（未选就点登录报 InvalidAddress） */
    val selected: DefaultEndpoint? = null,
    /** 「服务器」选项的地址（进页解析一次，可编辑；解析优先级见 [LoginViewModel] init 注释） */
    val serverUrl: String = "",
    /** 「本机」选项的地址（记忆槽或预设常量，只读展示；端口定制在设置页本机模式卡） */
    val localUrl: String = "",
    val password: String = "",
    val isSubmitting: Boolean = false,
    val error: LoginError? = null,
)

/**
 * 登录页 ViewModel（第三百六十三笔，用户拍板：登录页改「服务器 / 本机」两选项）：
 * 持有选项与表单状态，提交时把选中端的地址原样透传给 [AuthRepository]（探活→登录→
 * 持久化的编排在 core，本层零业务规则）。登录成功不需要显式导航——token 持久化后
 * 壳层的登录态流自动进主壳。
 *
 * 进页预选与地址解析（一次性，`first()` 取快照）：
 * - 预选 = 「默认登录」选项（设置页单选；登录成功后仓库层会把它改写为实际登录端）；
 *   未设置时按当前记忆地址的端型推定；全空则不预选。
 * - 服务器地址 = 当前记忆地址优先（含换址流程刚预置的新地址——「app 内改地址同步
 *   记录」的落点），非本机型才可用；否则回退 NAS 记忆槽。
 * - 本机地址 = 本机记忆槽，无则回退 [ServerAddress.LOCAL_MODE_PRESET]（与旧快捷
 *   填入口径一致）。
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val current = authRepository.serverUrl.first()
            val rememberedNas = authRepository.rememberedNasUrl.first()
            val default = authRepository.defaultEndpoint.first()
            val currentIsNas = current.isNotEmpty() && !ServerAddress.isLocalModePreset(current)
            val selected = default
                ?: if (current.isNotEmpty()) {
                    if (currentIsNas) DefaultEndpoint.NAS else DefaultEndpoint.LOCAL
                } else {
                    null
                }
            val nasAddress = if (currentIsNas) current else rememberedNas
            val localAddress = authRepository.rememberedLocalUrl.first().ifEmpty { ServerAddress.LOCAL_MODE_PRESET }
            _uiState.update {
                it.copy(selected = selected, serverUrl = nasAddress, localUrl = localAddress)
            }
        }
    }

    /** 选项卡点选（选中即高亮；登录成功后仓库层把默认端同步为本端） */
    fun onEndpointSelected(endpoint: DefaultEndpoint) {
        _uiState.update { it.copy(selected = endpoint, error = null) }
    }

    fun onServerUrlChange(value: String) {
        _uiState.update { it.copy(serverUrl = value, error = null) }
    }

    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value, error = null) }
    }

    /** 提交登录：取选中端解析出的地址透传。提交中禁重复触发（UI 禁用之外的状态机兜底）。 */
    fun submit() {
        val current = _uiState.value
        if (current.isSubmitting) return
        val address = when (current.selected) {
            DefaultEndpoint.NAS -> current.serverUrl
            DefaultEndpoint.LOCAL -> current.localUrl
            null -> ""
        }
        if (address.isEmpty()) {
            // 未选端或服务器地址为空：走「地址格式不正确」分类（UI 文案已有），不发仓库调用
            _uiState.update { it.copy(error = LoginError.InvalidAddress) }
            return
        }
        _uiState.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            val result = authRepository.login(address, current.password)
            _uiState.update { state ->
                when (result) {
                    is LoginResult.Success -> state.copy(isSubmitting = false, error = null)
                    is LoginResult.Failure -> state.copy(isSubmitting = false, error = result.error)
                }
            }
        }
    }
}
