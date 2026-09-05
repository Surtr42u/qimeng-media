package media.qimeng.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import media.qimeng.app.core.data.repository.AuthRepository
import javax.inject.Inject

/**
 * 设置页 ViewModel（M4-1 最小版）：目前只有登出动作。
 * 登出后壳层由登录态流自动回登录页，本层不做导航。
 * M4-6 完整设置页（地址展示/缓存档位/版本信息）落地时再扩充，此处禁止提前堆料。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    /** 退出登录：清 token（服务器地址保留，下次登录自动回填）。 */
    fun logout() {
        viewModelScope.launch { authRepository.logout() }
    }
}
