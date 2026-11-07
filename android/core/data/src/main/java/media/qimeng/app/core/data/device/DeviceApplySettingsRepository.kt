package media.qimeng.app.core.data.device

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import media.qimeng.app.core.data.di.ClientPrefsDataStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设备结果包的本机设置（设置页与 Worker 共用单一持久源）。
 *
 * [deviceToken] 是**专用设备 AI 令牌**（服务端 ai_clients 的 device.apply scope），
 * 不是登录会话 token；无令牌时 [isConfigured]=false，Worker 不发起任何设备端点请求。
 * 令牌与登录 token 同存于 client_prefs DataStore（明文落盘的威胁模型与取舍见
 * DataStoreServerConfigDataSource 的 KDoc：单用户内网/已解锁设备不在防御范围；
 * 令牌撤销走服务端 ai-client revoke/rotate）。本类不打日志、不把令牌写进结果包。
 */
data class DeviceApplySettings(
    val targetDeviceId: String = "",
    val deviceToken: String = "",
    val backgroundEnabled: Boolean = false,
    /** 最近一次 Worker/手动触发的本机提示（UI 状态行；不代替服务端 result 账本）。 */
    val lastMessage: String? = null,
    val lastRunAtMillis: Long? = null,
) {
    /** 只有标签合法且令牌非空才算配置完成；后台开关单独保存。 */
    val isConfigured: Boolean
        get() = DeviceApplyAuthorization.check(targetDeviceId, deviceToken) is DeviceApplyAuthorization.AuthorizationState.Ready
}

interface DeviceApplySettingsRepository {

    val settings: Flow<DeviceApplySettings>

    /**
     * 保存三项设置。trim 首尾空白；未配置完成（无合法标签或空令牌）时强制关闭后台领取，
     * 避免「开关开着但 Worker 永远 no-op」的假状态。
     */
    suspend fun save(targetDeviceId: String, deviceToken: String, backgroundEnabled: Boolean)

    /** 记录最近一次运行摘要（设备标签/令牌都不写入；message 按服务端上限截断）。 */
    suspend fun recordLastRun(message: String, atMillis: Long = System.currentTimeMillis())
}

@Singleton
class DataStoreDeviceApplySettingsRepository @Inject constructor(
    @ClientPrefsDataStore private val dataStore: DataStore<Preferences>,
) : DeviceApplySettingsRepository {

    override val settings: Flow<DeviceApplySettings> = dataStore.data.map { prefs ->
        DeviceApplySettings(
            targetDeviceId = prefs[KEY_TARGET_DEVICE_ID].orEmpty(),
            deviceToken = prefs[KEY_DEVICE_TOKEN].orEmpty(),
            backgroundEnabled = prefs[KEY_BACKGROUND_ENABLED] ?: false,
            lastMessage = prefs[KEY_LAST_MESSAGE],
            lastRunAtMillis = prefs[KEY_LAST_RUN_AT],
        )
    }.distinctUntilChanged()

    override suspend fun save(targetDeviceId: String, deviceToken: String, backgroundEnabled: Boolean) {
        val target = targetDeviceId.trim()
        val token = deviceToken.trim()
        val enabled = backgroundEnabled &&
            DeviceApplyAuthorization.check(target, token) is DeviceApplyAuthorization.AuthorizationState.Ready
        dataStore.edit { prefs ->
            prefs[KEY_TARGET_DEVICE_ID] = target
            prefs[KEY_DEVICE_TOKEN] = token
            prefs[KEY_BACKGROUND_ENABLED] = enabled
        }
    }

    override suspend fun recordLastRun(message: String, atMillis: Long) {
        val bounded = DeviceApplyMessages.truncate(message)
        dataStore.edit { prefs ->
            prefs[KEY_LAST_MESSAGE] = bounded
            prefs[KEY_LAST_RUN_AT] = atMillis
        }
    }

    private companion object {
        val KEY_TARGET_DEVICE_ID = stringPreferencesKey("device_apply_target_device_id")
        val KEY_DEVICE_TOKEN = stringPreferencesKey("device_apply_token")
        val KEY_BACKGROUND_ENABLED = booleanPreferencesKey("device_apply_background_enabled")
        val KEY_LAST_MESSAGE = stringPreferencesKey("device_apply_last_message")
        val KEY_LAST_RUN_AT = longPreferencesKey("device_apply_last_run_at")
    }
}
