package media.qimeng.app.feature.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.qimeng.app.core.data.repository.AuthRepository
import media.qimeng.app.core.data.repository.LoginError
import media.qimeng.app.core.data.repository.LoginResult
import media.qimeng.app.core.network.ServerAddress
import javax.inject.Inject

/**
 * 登录页 UI 状态。错误只存 [LoginError] 分类、不存文案——中文文案在 UI 层经资源表映射
 * （「地址不通」与「密码错」分开提示，M4-1 冻结口径）。
 */
data class LoginUiState(
    val serverUrl: String = "",
    val password: String = "",
    val isSubmitting: Boolean = false,
    val error: LoginError? = null,
)

/**
 * 登录页 ViewModel：持有表单状态，提交时原样透传给 [AuthRepository]（探活→登录→持久化的
 * 编排在 core，本层零业务规则）。登录成功不需要显式导航——token 持久化后壳层的登录态流自动进主壳。
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        // 「记忆上次输入」：回填上次登录成功的服务器地址（退出登录不清地址，故退出后仍能带出）
        viewModelScope.launch {
            val lastUrl = authRepository.serverUrl.first()
            if (lastUrl.isNotEmpty()) {
                _uiState.update { it.copy(serverUrl = lastUrl) }
            }
        }
    }

    fun onServerUrlChange(value: String) {
        _uiState.update { it.copy(serverUrl = value, error = null) }
    }

    /**
     * 本机模式快捷填入（任务T T3，ADR-0015 单点预留兑现）：把预设地址一键填入地址输入框。
     * 只是未提交的输入框赋值——用户看到/确认后仍走既有 [submit]（探活→登录→持久化），
     * 不在此处直接保存（UI 快捷入口禁内嵌保存行为，地址单点流转红线不动）。
     */
    fun fillLocalMode() {
        _uiState.update { it.copy(serverUrl = ServerAddress.LOCAL_MODE_PRESET, error = null) }
    }

    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value, error = null) }
    }

    /** 提交登录。提交中禁重复触发（探活+登录两个往返期间按钮禁用是 UI 表现，此处兜底状态机）。 */
    fun submit() {
        val current = _uiState.value
        if (current.isSubmitting) return
        _uiState.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            val result = authRepository.login(current.serverUrl, current.password)
            _uiState.update { state ->
                when (result) {
                    is LoginResult.Success -> state.copy(isSubmitting = false, error = null)
                    is LoginResult.Failure -> state.copy(isSubmitting = false, error = result.error)
                }
            }
        }
    }
}
