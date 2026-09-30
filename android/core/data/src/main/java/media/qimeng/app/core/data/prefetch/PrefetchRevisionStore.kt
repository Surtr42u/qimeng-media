package media.qimeng.app.core.data.prefetch

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 上一轮完成的全量预取记录（2026-09-30 撞号修复）：修订号 + 记录时连接的服务器标识
 * 成对存取。[serverKey] 取服务器 base URL **原串**做精确字符串比较，刻意不做规范化——
 * 同一实例换地址（IP↔域名↔回环预设）会多拉一轮全量，属「多拉无害」方向，不值得为
 * 规范化引入各端地址等价规则；反之不捆绑服务器标识会被两端独立修订号计数器撞号
 * （播种基线同为 `COUNT(assets)+1`，库规模相近时修订号可相等）——错误 SKIP 后换端
 * 永不预取，那才是真风险（详见 [PrefetchRevisionGate] 分支 3）。
 */
data class PrefetchRevisionRecord(
    val serverKey: String,
    val revision: Long,
)

/**
 * 预取修订号持久仓端口（2026-09-30 revision 跳过批）：记录「上一轮**完成**的全量
 * 预取当时读到的库修订号」，供下一轮 [PrefetchRevisionGate] 比对整轮跳过——库未变时
 * 连全量分页拉列表都省掉（大库下原流程唯一的固定大头）。
 *
 * **null 语义**：无记录 = 门判 FULL。三种来源——首次（本机从未完成过一轮全量）、
 * 已失效（清缓存池后 [clear]）、存量迁移（历史版本只写修订号键、没有服务器键：
 * 修订号键与服务器键**两者都存在**才返回记录，只有旧版 Long 键的存量视同无记录，
 * 下轮 FULL 一次后写入完整三元组——向后兼容迁移）。**服务器键不匹配同样判 FULL**
 * （门负责，「换服务端不区分」的旧论证是错的：两端修订号独立计数器会撞号，真实
 * 风险是错误 SKIP 而非多拉，见 2026-09-30 撞号修复）。
 *
 * **样本键**：[setLastDone] 同事务落一份随机抽样缩略图 URL（[PrefetchSampleGate]），
 * SKIP 判定通过后经 [storedSample] 取出做本地磁盘探测（缓存漂移兜底，缺陷 2）。
 *
 * 接口化（P1 返工批 2026-09-30）：缩略图缓存页清空缓存池后要失效记录
 * （ThumbnailCacheViewModel），VM 依赖只读/可替身接口（NIA 范式，铁律 7 可测性同款），
 * 实现收口 DataModule。
 */
interface PrefetchRevisionStore {

    /**
     * 上一轮完成记录；修订号键与服务器键缺任一即返回 null（首次/未持久化/已失效/
     * 只有旧版修订号键的存量迁移数据）。
     */
    suspend fun lastDone(): PrefetchRevisionRecord?

    /**
     * 记录本轮完成：服务器标识 + 修订号 + 抽样样本**三个键在同一次 [edit] 里写入**
     * （DataStore 单次 edit 原子，禁止分两次 edit——半写状态会被读成「无记录」或
     * 「有记录无样本」两种中间态，前者尚可自愈、后者破坏抽样核对前提）。
     * 覆盖写；调用方保证 record 非 null 才调。
     */
    suspend fun setLastDone(record: PrefetchRevisionRecord, sampleUrls: Set<String>)

    /**
     * 读记录端抽样样本（缩略图绝对 URL 原串，探测侧经 SignedMediaCacheKeys.stableKey
     * 剥 exp/sig 后比对，URL 带过期签名不影响）；缺键/空返回空集合。
     */
    suspend fun storedSample(): Set<String>

    /**
     * 失效记录（清空磁盘缓存池后调用）：池内缩略图已删，记录若保留，库静态时下轮
     * 门判定会误 SKIP、已清空的缓存不再补拉。修订号是全局单计数器不分池，NAS/本地
     * 任一池清空都走这里作废；三个键在同一次 edit 里移除（读回 null → 下轮 FULL 全量补拉）。
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

    override suspend fun lastDone(): PrefetchRevisionRecord? {
        val prefs = dataStore.data.first()
        val revision = prefs[KEY_LAST_PREFETCH_REVISION] ?: return null
        // 旧版存量只有修订号键：视同无记录（下轮 FULL 后写入完整三元组），不得只凭
        // 修订号返回——缺服务器键的记录在换端撞号判定里没有可比对象，语义上必须作废
        val serverKey = prefs[KEY_LAST_PREFETCH_SERVER] ?: return null
        return PrefetchRevisionRecord(serverKey = serverKey, revision = revision)
    }

    override suspend fun setLastDone(record: PrefetchRevisionRecord, sampleUrls: Set<String>) {
        dataStore.edit { prefs ->
            prefs[KEY_LAST_PREFETCH_REVISION] = record.revision
            prefs[KEY_LAST_PREFETCH_SERVER] = record.serverKey
            prefs[KEY_LAST_PREFETCH_SAMPLE] = sampleUrls
        }
    }

    override suspend fun storedSample(): Set<String> =
        dataStore.data.first()[KEY_LAST_PREFETCH_SAMPLE] ?: emptySet()

    override suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_LAST_PREFETCH_REVISION)
            prefs.remove(KEY_LAST_PREFETCH_SERVER)
            prefs.remove(KEY_LAST_PREFETCH_SAMPLE)
        }
    }

    private companion object {
        /** DataStore 键：上一轮完成时的库修订号（Long；缺键 = 无记录 = null） */
        val KEY_LAST_PREFETCH_REVISION = longPreferencesKey("last_prefetch_revision")

        /**
         * DataStore 键：记录时连接的服务器 base URL 原串（String；与修订号键成对存取，
         * 缺任一键 = 无记录）。原串精确比较不做规范化，取舍见 [PrefetchRevisionRecord]。
         */
        val KEY_LAST_PREFETCH_SERVER = stringPreferencesKey("last_prefetch_server")

        /**
         * DataStore 键：记录端随机抽样缩略图绝对 URL 原串（StringSet；与修订号键同事务
         * 写入，SKIP 前本地磁盘探测用——缓存漂移兜底，见 [PrefetchSampleGate]）。
         */
        val KEY_LAST_PREFETCH_SAMPLE = stringSetPreferencesKey("last_prefetch_sample")
    }
}
