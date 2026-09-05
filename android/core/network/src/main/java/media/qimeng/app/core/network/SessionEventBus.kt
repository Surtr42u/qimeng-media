package media.qimeng.app.core.network

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 会话事件总线（进程内）：目前唯一事件 = 鉴权失效（HTTP 401）。
 *
 * 为什么用事件而不是只靠 token 状态流：401 的语义是「服务端已判定 token 失效」——
 * 即使本地 token 流尚未翻转，壳层也应立即退登录页；事件同时是「服务端主动踢下线」
 * 的扩展点（服务端登录即重铸 token，旧 token 的下一个请求必然 401）。
 *
 * 拦截器只发事件不做导航（HANDOVER_APP M4-1 冻结口径）；导航由 :app 壳层收集本流后切换。
 * extraBufferCapacity=1 + DROP_OLDEST：事件只表达「发生了」，不排队，多次 401 合并为一次跳转。
 */
@Singleton
class SessionEventBus @Inject constructor() {

    private val _unauthorized = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 鉴权失效事件（AuthInterceptor 在收到 401 时发射）。 */
    val unauthorized: SharedFlow<Unit> = _unauthorized

    /** 发射鉴权失效事件（非挂起，永不丢调用方线程——溢出即丢弃旧事件）。 */
    fun notifyUnauthorized() {
        _unauthorized.tryEmit(Unit)
    }
}
