package media.qimeng.app.core.network

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import media.qimeng.app.core.network.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ServerConfigDataSource] 的 DataStore Preferences 实现（`server_config.preferences_pb`，随 App 数据持久）。
 *
 * token 内存缓存设计：`updateToken`/`clearToken` 写穿（写 DataStore 成功后立即刷新 @Volatile 缓存），
 * 启动时再从 DataStore 预热一次——保证 [currentToken] 在进程重启后的首批请求前已就绪，
 * 而 [AuthInterceptor] 全程零阻塞。
 *
 * **token 明文落盘的取舍（2026-09-06 审查补记，风险接受决策）**：DataStore Preferences 不提供加密，
 * 本文件有意不引入加密层——①威胁模型按 SECURITY.md 是纯内网单用户，防御目标是局域网误访问与
 * 横向渗透，不覆盖「物理拿到已解锁设备」的攻击者；该场景下明文 token 才构成实质风险；②官方加密
 * 方案 androidx.security-crypto（EncryptedSharedPreferences）已整体弃用，官方指向 Android Keystore
 * 自行封装——自造加密存储引入的出错面大于收益；③泄露可收敛：本设备登出走 POST /auth/logout
 * 吊销自己的会话，管理端「重置 token」一键吊销全部会话（ADR-0021 多会话模型，
 * SECURITY.md 鉴权设计）。远期若威胁模型升级（多用户/公网面），此处是
 * 改造点：换 Keystore 加密包装的存储实现，接口不变。
 */
@Singleton
class DataStoreServerConfigDataSource @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @ApplicationScope appScope: CoroutineScope, // 只为启动预热，构造完不留引用
) : ServerConfigDataSource {

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var cachedServerUrl: String? = null

    /**
     * 内嵌 dev 共享密钥纯内存槽（2026-09-30 批A）。@Volatile 写线程=Service 拉起协程
     * （Dispatchers.Default）、读线程=devLogin 的 OkHttp IO 线程，必须保证可见。
     * 为什么不进 DataStore：密钥生命周期=本次拉起的子进程（Service 每次拉起重新生成
     * 覆写、App 重启必然重拉），持久化只会留过期值；且「密钥不落盘」是本卡红线——
     * 内存槽让该约束成为结构保证（DataStore 路径上根本不存在这个键）。
     */
    @Volatile
    private var cachedEmbeddedDevSecret: String? = null

    init {
        // 进程冷启动预热：把持久化的 token/地址灌进内存缓存（只取一次，后续靠写穿维持一致）
        appScope.launch {
            val snapshot = dataStore.data.first()
            cachedToken = snapshot[KEY_TOKEN]
            cachedServerUrl = snapshot[KEY_SERVER_URL]
        }
    }

    // distinctUntilChanged：DataStore 的 data Flow 在任一键变更时都会重发射整个 preferences 快照
    // （同值也算新发射）——不去重的话，token 每次写入都会让订阅方空转（MainViewModel 的内嵌
    // 服务启停判定跟着重跑一遍无意义的判定）。
    override val serverUrl: Flow<String> =
        dataStore.data.map { it[KEY_SERVER_URL].orEmpty() }.distinctUntilChanged()

    override val rememberedNasUrl: Flow<String> =
        dataStore.data.map { it[KEY_REMEMBERED_NAS_URL].orEmpty() }.distinctUntilChanged()

    override val rememberedLocalUrl: Flow<String> =
        dataStore.data.map { it[KEY_REMEMBERED_LOCAL_URL].orEmpty() }.distinctUntilChanged()

    override val token: Flow<String?> = dataStore.data.map { it[KEY_TOKEN] }.distinctUntilChanged()

    override val defaultEndpoint: Flow<DefaultEndpoint?> =
        dataStore.data.map { it[KEY_DEFAULT_ENDPOINT]?.let(DefaultEndpoint::fromStorage) }.distinctUntilChanged()

    override suspend fun setDefaultEndpoint(endpoint: DefaultEndpoint?) {
        dataStore.edit {
            if (endpoint == null) it.remove(KEY_DEFAULT_ENDPOINT) else it[KEY_DEFAULT_ENDPOINT] = endpoint.storageValue
        }
    }

    override fun currentToken(): String? = cachedToken

    override fun currentServerUrl(): String? = cachedServerUrl

    override fun currentEmbeddedDevSecret(): String? = cachedEmbeddedDevSecret

    override fun updateEmbeddedDevSecret(value: String?) {
        cachedEmbeddedDevSecret = value
    }

    override suspend fun updateServerUrl(url: String) {
        dataStore.edit { it[KEY_SERVER_URL] = url }
        cachedServerUrl = url
    }

    // 批S3 服务器地址固化：按端型分流记忆（本机模式=回环+18430 进本地槽，其余进 NAS 槽）。
    // 这是「切到本机模式后 NAS 地址不丢」的唯一保证点——主地址键（KEY_SERVER_URL）会被
    // 本机模式切换/登录照常覆盖（那是「当前连着谁」的真相），NAS 记忆只在独立的槽里。
    override suspend fun rememberLoginAddress(url: String) {
        if (url.isEmpty()) return
        if (ServerAddress.isLocalModePreset(url)) {
            dataStore.edit { it[KEY_REMEMBERED_LOCAL_URL] = url }
        } else {
            dataStore.edit { it[KEY_REMEMBERED_NAS_URL] = url }
        }
    }

    override suspend fun updateToken(token: String) {
        dataStore.edit { it[KEY_TOKEN] = token }
        cachedToken = token
    }

    override suspend fun clearToken() {
        dataStore.edit { it.remove(KEY_TOKEN) }
        cachedToken = null
    }

    private companion object {
        /** 服务端地址键（ServerAddress.normalize 的产物，规范化 base URL）。 */
        val KEY_SERVER_URL = stringPreferencesKey("server_url")

        /** 登录 token 键（POST /auth/login 返回；ADR-0021 多设备并发会话——本设备持有一条独立会话 token，登录新设备不再重铸旧 token，吊销走 POST /auth/logout）。 */
        val KEY_TOKEN = stringPreferencesKey("token")

        /**
         * 最近一次成功登录的 NAS 地址记忆槽（批S3）：本机模式切换/登录不覆盖，
         * 从本机切回 NAS 时设置页地址卡/登录页据此免重输回填。
         */
        val KEY_REMEMBERED_NAS_URL = stringPreferencesKey("remembered_nas_url")

        /** 最近一次成功登录的本机模式地址记忆槽（M6 单机形态口；与 NAS 槽各归各、切换互换回填）。 */
        val KEY_REMEMBERED_LOCAL_URL = stringPreferencesKey("remembered_local_url")

        /** 默认登录端选项键（存 [DefaultEndpoint.storageValue] 字面量；缺键 = 未设置）。 */
        val KEY_DEFAULT_ENDPOINT = stringPreferencesKey("default_endpoint")
    }
}
