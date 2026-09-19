package media.qimeng.app.core.data.repository

import kotlinx.coroutines.flow.Flow

/** 登录失败分类：驱动登录页分文案（「地址不通」与「密码错」必须分开给中文提示，M4-1 冻结口径）。 */
sealed interface LoginError {
    /** 地址格式非法（无法解析为 http(s) base URL）。 */
    data object InvalidAddress : LoginError

    /** 地址不通：探活/登录请求未到达服务端（网络不可达、连接拒绝、响应的不是绮梦服务端）。 */
    data object ServerUnreachable : LoginError

    /** 服务端明确拒绝：密码错误（POST /auth/login 401）。 */
    data object WrongPassword : LoginError

    /** 免密不可用：空密码走 dev-login 但服务端未开启开发模式（404），提示改用密码登录（2026-09-06）。 */
    data object DevLoginUnavailable : LoginError

    /** 其他失败（服务端 5xx 等），[detail] 用于日志排查不直接展示给用户。 */
    data class Other(val detail: String) : LoginError
}

/** 登录结果：成功仅表达「token 已持久化」，跳壳由 isLoggedIn 状态流驱动。 */
sealed interface LoginResult {
    data object Success : LoginResult
    data class Failure(val error: LoginError) : LoginResult
}

/**
 * 认证仓库（:core:data）：地址/token 的读写与清除对外只经本接口（HANDOVER_APP M4-1 冻结架构）。
 * 服务端定位的持久化真相在 [media.qimeng.app.core.network.ServerConfigDataSource]（全 App 唯一定位点，
 * ADR-0015 单机形态只改那一处指向 localhost）。
 */
interface AuthRepository {

    /** 当前服务端 base URL（登录页「记忆上次」的数据源；退出登录后仍有值）。 */
    val serverUrl: Flow<String>

    /** 最近一次成功登录的 NAS 地址记忆（批S3 服务器地址固化；透传自 ServerConfigDataSource，本机模式不覆盖）。 */
    val rememberedNasUrl: Flow<String>

    /** 最近一次成功登录的本机模式地址记忆（M6 单机形态口；与 NAS 记忆各归各槽、切换互换回填）。 */
    val rememberedLocalUrl: Flow<String>

    /** 登录态：token 非空且已持久化。壳层据此决定起始页（杀进程重启仍登录=直进壳）。 */
    val isLoggedIn: Flow<Boolean>

    /** 鉴权失效事件（透传自网络层 SessionEventBus；401 跳登录由壳层收集本流实现）。 */
    val unauthorizedEvents: Flow<Unit>

    /**
     * 登录：规范化地址 → （本机模式预设地址先拉起内嵌服务端并等端口就绪，批S8 用户实测
     * 死锁修复，超时不造新错误继续走原链）→ `GET /api/v1/healthz` 探活 → 登录 → 持久化地址+token。
     * 探活在登录前：先确认「这是可达的绮梦服务端」，密码错误才不会被误报成地址不通。
     * 密码非空走 `POST /auth/login`；**空密码走 `POST /auth/dev-login` 免密通道**（2026-09-06
     * 用户拍板：测试环境免输密码；仅服务端开启 auth_dev_mode 时可用，未开启报
     * [LoginError.DevLoginUnavailable]，生产/远程部署不受影响）。
     */
    suspend fun login(rawAddress: String, password: String): LoginResult

    /**
     * 退出登录：先尽力吊销当前服务端会话（ADR-0021 多设备并发会话——不吊销则这条
     * auth_sessions 会话会活到过期；「尽力」= 失败/超时忽略，登出不被旧地址停机阻塞，
     * 见实现），再清 token、保留地址（下次登录自动回填）。
     */
    suspend fun logout()

    /**
     * 登出并预置下次登录的服务器地址（任务T T3 本机模式快捷入口专用；地址仍只经
     * ServerConfigDataSource 单点流转，ADR-0015）。预置值是「登录页带出的未提交输入」，
     * 用户在登录页确认（走 [login] 既有探活→登录→持久化）才真正切换。
     * 吊销时机：切地址前先对当前旧地址尽力吊销旧会话（此刻 token/地址都还是旧值）。
     * 为什么先写地址再清 token：token 流翻 false 壳层立即切登录页并回填「记忆上次」，
     * 若先清 token，回填可能抢在地址写入前读到旧值（顺序换确定性）。窗口如实记档：
     * updateServerUrl 与 clearToken 两个连续挂起调用之间确有短暂窗口（业务 API 构造
     * 动态读 currentServerUrl），但窗口极短且目标为本机回环——服务端未启动时连接拒绝、
     * 已启动自家服务端时 401 走既有 clearToken 幂等路径，无实害。
     */
    suspend fun logoutWithStagedUrl(url: String)
}
