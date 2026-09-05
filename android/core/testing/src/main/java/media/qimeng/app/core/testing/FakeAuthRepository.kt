package media.qimeng.app.core.testing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import media.qimeng.app.core.data.repository.AuthRepository
import media.qimeng.app.core.data.repository.LoginResult

/**
 * [AuthRepository] 内存替身（AuthRepositoryImpl 的全链路行为测试在 :core:data 自带；
 * 本替身服务于 ViewModel/壳层测试——只须可编程地回放登录结果与登录态翻转）。
 *
 * 语义与真实实现对齐：登录成功 = token 持久化 = isLoggedIn 翻 true；
 * 退出登录 = isLoggedIn 翻 false 且地址保留。
 */
class FakeAuthRepository(
    initialServerUrl: String = "",
    initialLoggedIn: Boolean = false,
) : AuthRepository {

    /** 一次登录调用的参数记录（断言「ViewModel 原样透传用户输入」用）。 */
    data class LoginCall(val rawAddress: String, val password: String)

    val loginCalls = mutableListOf<LoginCall>()

    /** logout 调用次数（断言退出登录入口确实触达仓库层）。 */
    var logoutCount = 0
        private set

    /** 可编程：下一次 login() 的返回结果（默认成功）。 */
    var nextLoginResult: LoginResult = LoginResult.Success

    /** 可编程：非空时 login() 挂起直到测试放行（测「提交中防重复提交」）。 */
    var loginGate: CompletableDeferred<Unit>? = null

    private val serverUrlState = MutableStateFlow(initialServerUrl)
    private val loggedInState = MutableStateFlow(initialLoggedIn)
    private val unauthorizedFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override val serverUrl: Flow<String> = serverUrlState

    override val isLoggedIn: Flow<Boolean> = loggedInState

    override val unauthorizedEvents: Flow<Unit> = unauthorizedFlow

    override suspend fun login(rawAddress: String, password: String): LoginResult {
        loginCalls += LoginCall(rawAddress, password)
        loginGate?.await()
        return nextLoginResult
    }

    override suspend fun logout() {
        logoutCount++
        loggedInState.value = false
    }

    /** 测试驱动：模拟登录成功（token 已持久化）。 */
    fun setLoggedIn(value: Boolean) {
        loggedInState.value = value
    }

    /** 测试驱动：模拟 AuthInterceptor 收到 401 后广播的鉴权失效事件。 */
    fun emitUnauthorized() {
        unauthorizedFlow.tryEmit(Unit)
    }
}
