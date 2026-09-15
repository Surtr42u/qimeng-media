package media.qimeng.app.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import media.qimeng.app.core.data.embedded.EmbeddedServerController
import media.qimeng.app.core.data.repository.AuthRepository

/** 壳层会话状态：Loading = 持久化登录态尚未首读完成（防误闪登录页）。 */
enum class SessionState {
    Loading,
    LoggedIn,
    LoggedOut,
}

/**
 * 壳层登录态分支的唯一来源（M4-1）：token 流驱动 已登录/未登录，401 事件驱动即时退登录页。
 *
 * 401 双通道设计（为什么不是单看 token 流）：AuthInterceptor 收 401 先广播事件、再异步清 token
 * （DataStore 写盘），事件通道保证「服务端已踢」在写盘落地前就翻到登录页；token 流通道负责
 * 常规分支（启动恢复/主动登出/重新登录）。sessionExpired 在重新登录成功时复位，保证下一次
 * 401 仍能触发跳转。
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val embeddedServerController: EmbeddedServerController,
) : ViewModel() {

    private val sessionExpired = MutableStateFlow(false)

    /** 待消费的系统分享 URI（M4-5 上传入口①）；壳层进上传流后消费清空 */
    private val _pendingShareUris = MutableStateFlow<List<String>>(emptyList())
    val pendingShareUris: StateFlow<List<String>> = _pendingShareUris.asStateFlow()

    val sessionState: StateFlow<SessionState> = combine(
        authRepository.isLoggedIn,
        sessionExpired,
    ) { loggedIn, expired ->
        when {
            loggedIn && !expired -> SessionState.LoggedIn
            else -> SessionState.LoggedOut
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SessionState.Loading)

    init {
        viewModelScope.launch {
            authRepository.unauthorizedEvents.collect { sessionExpired.value = true }
        }
        viewModelScope.launch {
            authRepository.isLoggedIn.collect { loggedIn ->
                if (loggedIn) sessionExpired.value = false
            }
        }
        // U11 批次D（ADR-0015 形态 B）：地址流驱动内嵌服务端随需启停——配置为内嵌
        // 预设（127.0.0.1:18430）时冷启动自拉起（用户上次用本机模式，这次打开 App
        // 即可用，不必先去设置页点一次）；切回 NAS 地址则停服回收。runCatching 吞
        // 后台态 FGS 启动限制（进程后台恢复场景——此时登录页交互后设置页路径仍可拉起）。
        viewModelScope.launch {
            authRepository.serverUrl.collect { url ->
                runCatching {
                    if (!embeddedServerController.ensureStartedIfLocalMode(url)) {
                        embeddedServerController.stop()
                    }
                }
            }
        }
    }

    /** 系统分享到达（MainActivity 解包 SEND/SEND_MULTIPLE 后调用；覆盖上一次未消费的分享） */
    fun receiveSharedUris(uris: List<String>) {
        _pendingShareUris.value = uris
    }

    /** 上传流已接手分享内容（壳层导航进上传页后调用，避免重复触发） */
    fun consumeSharedUris() {
        _pendingShareUris.value = emptyList()
    }
}
