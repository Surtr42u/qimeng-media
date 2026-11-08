package media.qimeng.app.core.data.device

import android.util.Log
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import media.qimeng.app.core.data.di.IoDispatcher
import media.qimeng.app.core.data.util.runSuspendCatching
import media.qimeng.app.core.network.ServerConfigDataSource
import media.qimeng.sdk.infrastructure.ClientException
import media.qimeng.sdk.infrastructure.ServerException

/**
 * 设置页「最近设备任务」只读读取（2026-11-08）。
 *
 * 必须使用**专用设备令牌**客户端（[DeviceApplyRemoteFactory]），不能用登录会话：
 * 设备端点按 device.apply + device_ids 做设备级隔离，会话 token 只是管理员身份，
 * 无法证明「这台手机就是目标设备」。本类不领取、不下载、不回传，只读列表；
 * 领取/应用仍由 [DeviceApplyRunner] 在后台完成。
 */
@Singleton
class DeviceApplyTaskHistoryRepository @Inject constructor(
    private val settingsRepository: DeviceApplySettingsRepository,
    private val serverConfig: ServerConfigDataSource,
    private val remoteFactory: DeviceApplyRemoteFactory,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun recent(limit: Int = RECENT_LIMIT): DeviceApplyRecentTasksResult {
        val settings = runSuspendCatching { settingsRepository.settings.first() }.getOrElse {
            return DeviceApplyRecentTasksResult.Failed("读取本机设备设置失败，请重试")
        }
        val authorization = DeviceApplyAuthorization.check(settings.targetDeviceId, settings.deviceToken)
        if (authorization !is DeviceApplyAuthorization.AuthorizationState.Ready) {
            return DeviceApplyRecentTasksResult.Failed(
                (authorization as DeviceApplyAuthorization.AuthorizationState.Missing).message,
            )
        }
        val serverUrl = serverConfig.currentServerUrl()
        if (serverUrl.isNullOrBlank()) {
            return DeviceApplyRecentTasksResult.Failed("尚未登录服务器，无法读取设备任务")
        }
        return try {
            val remote = remoteFactory.create(serverUrl, settings.deviceToken)
            val tasks = withContext(ioDispatcher) {
                remote.listRecent(settings.targetDeviceId, limit = limit)
            }
            DeviceApplyRecentTasksResult.Loaded(tasks)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: ClientException) {
            when (e.statusCode) {
                HTTP_UNAUTHORIZED, HTTP_FORBIDDEN ->
                    DeviceApplyRecentTasksResult.Failed(
                        "设备 AI 令牌无效、已撤销或无权访问该设备；请在设置页更新专用设备令牌",
                    )
                else ->
                    DeviceApplyRecentTasksResult.Failed("读取最近设备任务被服务端拒绝（HTTP ${e.statusCode}）")
            }
        } catch (e: ServerException) {
            DeviceApplyRecentTasksResult.Failed("读取最近设备任务失败：服务端错误（HTTP ${e.statusCode}）")
        } catch (e: IOException) {
            DeviceApplyRecentTasksResult.Failed("读取最近设备任务失败：网络不可用或本机服务端未启动")
        } catch (e: Exception) {
            Log.w(TAG, "读取最近设备任务失败", e)
            DeviceApplyRecentTasksResult.Failed("读取最近设备任务失败，请重试")
        }
    }

    private companion object {
        /** 与服务端 limit 上限 100 内的固定只读条数。 */
        const val RECENT_LIMIT = 20
        const val TAG = "DeviceApplyHistory"
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
    }
}

/** 最近任务读取结果；[Loaded.tasks] 已按服务端「新建倒序」排列。 */
sealed interface DeviceApplyRecentTasksResult {
    data class Loaded(val tasks: List<DeviceApplySummary>) : DeviceApplyRecentTasksResult
    data class Failed(val message: String) : DeviceApplyRecentTasksResult
}
