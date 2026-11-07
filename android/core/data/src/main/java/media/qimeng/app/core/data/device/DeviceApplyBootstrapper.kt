package media.qimeng.app.core.data.device

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import media.qimeng.app.core.network.ServerAddress
import media.qimeng.app.core.network.ServerConfigDataSource
import media.qimeng.app.core.network.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设置/登录地址 → 调度器的常驻接线（App onCreate 调一次）。
 *
 * 只把「是否已配置且开启后台领取」与「是否本机回环地址」作为调度输入：
 * lastMessage/lastRunAt 变化不会重新注册周期任务（否则 Worker 每次写状态都会
 * UPDATE 周期链，形成自触发循环）。App 冷启动、开关变化、本机/NAS 切换都会同步一次。
 */
@Singleton
class DeviceApplyBootstrapper @Inject constructor(
    private val settings: DeviceApplySettingsRepository,
    private val serverConfig: ServerConfigDataSource,
    private val scheduler: DeviceApplyScheduler,
    @ApplicationScope private val appScope: CoroutineScope,
) {

    fun onAppCreate() {
        appScope.launch {
            combine(settings.settings, serverConfig.serverUrl, serverConfig.token) { current, serverUrl, sessionToken ->
                ScheduleState(
                    // 设备令牌只负责设备端点；导入/两代备份导出仍要登录会话，未登录时不排周期。
                    enabled = current.backgroundEnabled && current.isConfigured && !sessionToken.isNullOrBlank(),
                    localMode = ServerAddress.isLocalModePreset(serverUrl),
                )
            }
                .distinctUntilChanged()
                .collect { state -> scheduler.sync(state.enabled, state.localMode) }
        }
    }

    private data class ScheduleState(val enabled: Boolean, val localMode: Boolean)
}
