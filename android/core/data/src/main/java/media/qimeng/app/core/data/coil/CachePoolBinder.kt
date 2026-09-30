package media.qimeng.app.core.data.coil

import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import media.qimeng.app.core.data.repository.AuthRepository
import media.qimeng.app.core.network.ServerAddress
import media.qimeng.app.core.network.di.ApplicationScope

/**
 * 分池路由来源接线（第四百一十一笔 反复缓存根治配套）：观察登录 [AuthRepository.serverUrl]，
 * 把「当前连接来源」写入 [SplitDiskCache.updateActivePool]——本地端预设地址（回环 + 端口
 * 口径见 [ServerAddress.isLocalModePreset]）→ LOCAL 池，其余（NAS/登出态）→ NAS 池。
 *
 * **为什么需要它**：签名直链稳定键剥掉 host 后（第三百六十五笔）不再含来源信息，
 * SplitDiskCache 无法从键自身判定写入/读取归属池；由连接来源驱动路由是键外唯一的
 * 单源事实（批S5 分池拍板语义=「按连接来源分两池」，来源即当时的 serverUrl）。
 *
 * 接线模式与 EventSyncBootstrapper/DiagnosticsBootstrapper 同款：进程启动 onAppCreate
 * 挂 StateFlow 收集，冷启动立即回放当前地址；运行中切换连接（登出/换端登录）实时跟随。
 * collect 跑在 [ApplicationScope]（Dispatchers.Default），[Lazy.get] 首次触发的
 * DataStore 档位读/存量迁移不在主线程（与 CoilModule 惰性装配拍板兼容）。
 *
 * 登出态：serverUrl 回放为最后保存的地址（登出不记档），路由维持不变——登出后预取停、
 * 无浏览写请求，路由值无实际消费方，不另设登出复位分支。
 */
@Singleton
class CachePoolBinder @Inject constructor(
    private val authRepository: AuthRepository,
    private val splitDiskCache: Lazy<SplitDiskCache>,
    @ApplicationScope private val appScope: CoroutineScope,
) {

    /** App 启动接线（QimengApplication.onCreate 调一次）：serverUrl 变化即更新路由来源 */
    fun onAppCreate() {
        appScope.launch {
            authRepository.serverUrl.collect { url ->
                splitDiskCache.get().updateActivePool(
                    if (ServerAddress.isLocalModePreset(url)) CachePool.LOCAL else CachePool.NAS,
                )
            }
        }
    }
}
