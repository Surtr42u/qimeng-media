package media.qimeng.app.core.network

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
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
 * 自行封装——自造加密存储引入的出错面大于收益；③泄露可收敛：服务端登录即重铸 token，且管理端
 * 「重置 token」一键吊销（SECURITY.md 鉴权设计）。远期若威胁模型升级（多用户/公网面），此处是
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

    init {
        // 进程冷启动预热：把持久化的 token/地址灌进内存缓存（只取一次，后续靠写穿维持一致）
        appScope.launch {
            val snapshot = dataStore.data.first()
            cachedToken = snapshot[KEY_TOKEN]
            cachedServerUrl = snapshot[KEY_SERVER_URL]
        }
    }

    override val serverUrl: Flow<String> = dataStore.data.map { it[KEY_SERVER_URL].orEmpty() }

    override val token: Flow<String?> = dataStore.data.map { it[KEY_TOKEN] }

    override fun currentToken(): String? = cachedToken

    override fun currentServerUrl(): String? = cachedServerUrl

    override suspend fun updateServerUrl(url: String) {
        dataStore.edit { it[KEY_SERVER_URL] = url }
        cachedServerUrl = url
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

        /** 登录 token 键（POST /auth/login 返回；单用户单 token，服务端登录即重铸）。 */
        val KEY_TOKEN = stringPreferencesKey("token")
    }
}
