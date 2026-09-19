package media.qimeng.app.core.data.scan

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import media.qimeng.app.core.data.di.ClientPrefsDataStore
import media.qimeng.app.core.data.repository.LibraryRepository
import media.qimeng.app.core.model.ScanGate
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey

/** 「重扫」请求的处置结果（UI 反馈文案的唯一分型依据） */
enum class RescanDecision {
    /** 已立即触发扫描（POST /scan 已发出或直扫通道） */
    STARTED,

    /** 未充电且设置开（本机模式）：已记待扫标记，接通电源后自动补扫 */
    DEFERRED,

    /** 扫描触发失败（网络/服务端错误） */
    FAILED,
}

/**
 * 扫描充电联动数据端口（批C 任务Q C-3，PROJECT_PLAN M6 性能项）：
 * - 「仅充电时扫描」设置项持久化（默认开，仅本机模式 UI 可见可改）；
 * - 重扫请求的推迟判定（[ScanGate] 纯函数 + 充电态查询 + 本机模式地址判定）；
 * - 待扫标记持久化与补扫（ACTION_POWER_CONNECTED 广播 → [onPowerConnected]）。
 *
 * 本机模式语义（冻结）：ServerConfigDataSource 当前地址为 core:network
 * ServerAddress.LOCAL_MODE_PRESET（127.0.0.1/localhost + 18430 端口单源）。
 */
interface ScanChargeController {

    /** 「仅充电时扫描」设置项流（默认 true；DataStore 持久化） */
    val chargeOnlyScanEnabled: Flow<Boolean>

    /** 修改设置项（ServerSettingsScreen 的 Switch 直写） */
    suspend fun setChargeOnlyScanEnabled(enabled: Boolean)

    /**
     * 库管理页「重扫」按钮入口：按充电门判定直接扫或记待扫标记。
     * 返回 [RescanDecision] 供 UI 反馈；判定为推迟时立即返回（不发起网络请求）。
     */
    suspend fun requestRescan(libraryId: String): RescanDecision

    /** ACTION_POWER_CONNECTED 补扫：有待扫标记且当前充电中 → 逐个扫描并清标记 */
    suspend fun onPowerConnected()

    /** 启动兜底：App 冷启动时若存在待扫标记且充电中 → 补扫（进程回收后 receiver 丢失的补链） */
    suspend fun resumeDeferredIfCharging()
}

@Singleton
class DataStoreScanChargeController @Inject constructor(
    @ApplicationContext private val context: Context,
    @ClientPrefsDataStore private val dataStore: DataStore<Preferences>,
    private val serverConfigDataSource: media.qimeng.app.core.network.ServerConfigDataSource,
    private val libraryRepository: LibraryRepository,
) : ScanChargeController {

    override val chargeOnlyScanEnabled: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[KEY_CHARGE_ONLY] ?: DEFAULT_CHARGE_ONLY }

    override suspend fun setChargeOnlyScanEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_CHARGE_ONLY] = enabled }
    }

    override suspend fun requestRescan(libraryId: String): RescanDecision {
        val settingOn = chargeOnlyScanEnabled.first()
        val localMode = serverConfigDataSource.currentServerUrl()
            ?.let { media.qimeng.app.core.network.ServerAddress.isLocalModePreset(it) } == true
        // 非本机模式/设置关/充电中 → 直接扫；其余（本机+设置开+未充电）→ 记待扫标记
        if (!ScanGate.shouldDeferScan(isCharging = isCharging(), settingOn = settingOn, isLocalMode = localMode)) {
            return try {
                libraryRepository.scan(libraryId)
                RescanDecision.STARTED
            } catch (e: Exception) {
                RescanDecision.FAILED
            }
        }
        dataStore.edit { prefs ->
            prefs[KEY_DEFERRED_IDS] = (prefs[KEY_DEFERRED_IDS] ?: emptySet()) + libraryId
        }
        Log.i(LOG_TAG, "rescan deferred (not charging, local mode) libraryId=$libraryId")
        return RescanDecision.DEFERRED
    }

    override suspend fun onPowerConnected() {
        // 广播语义本身即「已接通电源」：不二次查询 status——真机上 status 更新可能
        // 滞后于 ACTION_POWER_CONNECTED（模拟器 dumpsys set ac 1 干脆不更新 status，
        // 批C 冒烟实证），二次查询会把补扫误判回「未充电」而错过
        flushDeferredNow()
    }

    override suspend fun resumeDeferredIfCharging() {
        if (!isCharging()) {
            val deferred = dataStore.data.first()[KEY_DEFERRED_IDS].orEmpty()
            if (deferred.isNotEmpty()) {
                Log.i(LOG_TAG, "deferred scan pending (not charging) count=${deferred.size}")
            }
            return
        }
        flushDeferredNow()
    }

    /** 消费待扫标记并逐个补扫；失败项留在标记里等下一次触发重试，成功项即时清除 */
    private suspend fun flushDeferredNow() {
        val deferred = dataStore.data.first()[KEY_DEFERRED_IDS].orEmpty()
        if (deferred.isEmpty()) return
        // 先拷贝再逐个 edit，避免遍历中并发修改
        deferred.forEach { libraryId ->
            try {
                libraryRepository.scan(libraryId)
                dataStore.edit { prefs ->
                    prefs[KEY_DEFERRED_IDS] = (prefs[KEY_DEFERRED_IDS] ?: emptySet()) - libraryId
                }
                Log.i(LOG_TAG, "deferred scan flushed libraryId=$libraryId")
            } catch (e: Exception) {
                Log.w(LOG_TAG, "deferred scan failed, kept pending libraryId=$libraryId", e)
            }
        }
    }

    /**
     * 当前是否在充电（官方姿势：ACTION_BATTERY_CHANGED 是 sticky 广播，
     * registerReceiver(null) 直读无需注册 receiver；CHARGING 与 FULL 都算充电态——
     * 满电插着电源时也应允许扫描）。查不到（极端 ROM）按未充电处理：宁可推迟不误烧电。
     */
    private fun isCharging(): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return false
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    companion object {
        /** logcat 证据标签（冒烟剧本 2 的时间线 grep 锚点） */
        const val LOG_TAG = "QimengScanCharge"

        /**
         * 设置项默认值：仅充电时扫描默认开（本机模式省电语义是出厂期望，PROJECT_PLAN M6）。
         */
        const val DEFAULT_CHARGE_ONLY = true
    }
}

/** DataStore 键位（client_prefs 文件；C-3 专用键段） */
private val KEY_CHARGE_ONLY = booleanPreferencesKey("charge_only_scan_enabled")

/** 待扫标记：库 id 集合（充电后自动补扫；补扫成功逐个移除） */
private val KEY_DEFERRED_IDS = stringSetPreferencesKey("deferred_scan_library_ids")
