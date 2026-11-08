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
import media.qimeng.app.core.network.ServerAddress
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
    /**
     * 仅设备端点（列表/领取/详情/段下载/回传）使用的服务端地址；空=沿用当前登录服务端。
     *
     * 本机单机模式下填 PC/NAS 地址即可从远端领取结果包；段应用与两代备份仍走当前
     * 登录会话（本机库）。保存时经 ServerAddress 规范化，非法值按空串落盘。
     */
    val deviceServiceUrl: String = "",
    /**
     * 设备结果包应用 v2 备份时，作者档案（昵称/简介/主页/证据/xProfile/头像）冲突处置：
     * false=保留本机（服务端默认 keep）；true=以备份整份替换（replace，含离线头像）。
     * 跨端从 PC 领取时打开可把 PC 的新档案带到本机。
     */
    val replaceAuthorProfilesOnApply: Boolean = false,
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
     * 保存四项设置。trim 首尾空白；未配置完成（无合法标签或空令牌）时强制关闭后台领取，
     * 避免「开关开着但 Worker 永远 no-op」的假状态。
     *
     * [deviceServiceUrl] 仅用于设备端点；空=沿用当前登录服务端。非空时经
     * [media.qimeng.app.core.network.ServerAddress.normalize] 规范化，非法值按空串保存
     * （保存前由设置页 [DeviceApplyPolicy.isValidDeviceServiceUrl] 提示格式错误）。
     * 默认值保证旧调用方源码兼容；这些调用会把该字段保存为空（不保留旧值）。
     */
    suspend fun save(
        targetDeviceId: String,
        deviceToken: String,
        backgroundEnabled: Boolean,
        deviceServiceUrl: String = "",
        replaceAuthorProfilesOnApply: Boolean = false,
    )

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
            deviceServiceUrl = prefs[KEY_DEVICE_SERVICE_URL].orEmpty(),
            replaceAuthorProfilesOnApply = prefs[KEY_REPLACE_AUTHOR_PROFILES] ?: false,
            backgroundEnabled = prefs[KEY_BACKGROUND_ENABLED] ?: false,
            lastMessage = prefs[KEY_LAST_MESSAGE],
            lastRunAtMillis = prefs[KEY_LAST_RUN_AT],
        )
    }.distinctUntilChanged()

    override suspend fun save(
        targetDeviceId: String,
        deviceToken: String,
        backgroundEnabled: Boolean,
        deviceServiceUrl: String,
        replaceAuthorProfilesOnApply: Boolean,
    ) {
        val target = targetDeviceId.trim()
        val token = deviceToken.trim()
        // 非法值不落盘（协议面永远拿不到半合法地址）；UI 保存前已给出格式提示。
        val serviceUrl = deviceServiceUrl.trim().let { raw ->
            if (raw.isEmpty()) "" else ServerAddress.normalize(raw).orEmpty()
        }
        val enabled = backgroundEnabled &&
            DeviceApplyAuthorization.check(target, token) is DeviceApplyAuthorization.AuthorizationState.Ready
        dataStore.edit { prefs ->
            prefs[KEY_TARGET_DEVICE_ID] = target
            prefs[KEY_DEVICE_TOKEN] = token
            prefs[KEY_DEVICE_SERVICE_URL] = serviceUrl
            prefs[KEY_REPLACE_AUTHOR_PROFILES] = replaceAuthorProfilesOnApply
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
        val KEY_DEVICE_SERVICE_URL = stringPreferencesKey("device_apply_service_url")
        val KEY_REPLACE_AUTHOR_PROFILES = booleanPreferencesKey("device_apply_replace_author_profiles")
        val KEY_BACKGROUND_ENABLED = booleanPreferencesKey("device_apply_background_enabled")
        val KEY_LAST_MESSAGE = stringPreferencesKey("device_apply_last_message")
        val KEY_LAST_RUN_AT = longPreferencesKey("device_apply_last_run_at")
    }
}
