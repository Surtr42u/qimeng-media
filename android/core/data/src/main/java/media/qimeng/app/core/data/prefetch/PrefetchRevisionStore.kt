package media.qimeng.app.core.data.prefetch

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 预取修订号持久仓端口（2026-09-30 revision 跳过批）：记录「上一轮**完成**的全量
 * 预取当时读到的库修订号」，供下一轮 [PrefetchRevisionGate] 比对整轮跳过——库未变时
 * 连全量分页拉列表都省掉（大库下原流程唯一的固定大头）。
 *
 * **null 语义**：无记录 = 本机从未完成过一轮全量（首次，或换服务端后尚未跑过），
 * 门判定为 FULL。「换服务端不区分」是有意取舍：多跑一轮全量只耗带宽无正确性风险，
 * 为分服务端存键引入的复杂度不划算。
 *
 * 接口化（P1 返工批 2026-09-30）：缩略图缓存页清空缓存池后要失效记录
 * （ThumbnailCacheViewModel），VM 依赖只读/可替身接口（NIA 范式，铁律 7 可测性同款），
 * 实现收口 DataModule。
 */
interface PrefetchRevisionStore {

    /** 上一轮完成时的库修订号；无记录返回 null（首次/未持久化/已失效） */
    suspend fun lastDoneRevision(): Long?

    /** 记录本轮完成时的修订号（覆盖写；调用方保证 revision 非 null 才调） */
    suspend fun setLastDoneRevision(revision: Long)

    /**
     * 失效记录（清空磁盘缓存池后调用）：池内缩略图已删，`last_prefetch_revision`
     * 若保留，库静态时下轮门判定会误 SKIP、已清空的缓存不再补拉。修订号是全局
     * 单计数器不分池，NAS/本地任一池清空都走这里作废（读回 null → 下轮 FULL 全量补拉）。
     */
    suspend fun clear()
}

/** [PrefetchRevisionStore] DataStore 实现：与搜索历史/网格列数/缓存档位同一个
 *  client_prefs 文件（客户端本地偏好域）；纯键值读写不做容错包装——失败语义
 * （读失败按无记录降级 FULL、写失败保旧值）由调用方 [ThumbnailPrefetcher] 收口，
 * 本实现保持可直测的透明存取面。 */
@Singleton
class DataStorePrefetchRevisionStore @Inject constructor(
    @media.qimeng.app.core.data.di.ClientPrefsDataStore private val dataStore: DataStore<Preferences>,
) : PrefetchRevisionStore {

    override suspend fun lastDoneRevision(): Long? =
        dataStore.data.first()[KEY_LAST_PREFETCH_REVISION]

    override suspend fun setLastDoneRevision(revision: Long) {
        dataStore.edit { it[KEY_LAST_PREFETCH_REVISION] = revision }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(KEY_LAST_PREFETCH_REVISION) }
    }

    private companion object {
        /** DataStore 键：上一轮完成时的库修订号（Long；缺键 = 无记录 = null） */
        val KEY_LAST_PREFETCH_REVISION = longPreferencesKey("last_prefetch_revision")
    }
}
