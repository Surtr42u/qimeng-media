package media.qimeng.app.core.data.prefetch

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 预取修订号持久仓纯 JVM 行为锁定（2026-09-30 revision 跳过批；同日三元组修订扩展）：
 * 真 DataStore + 临时目录文件仓（DataStoreStagingRepositoryTest 同款基座）。覆盖
 * 三键同写原子面（记录+样本同 edit）、完整记录读回、样本回读、覆盖写取最新、
 * 清空失效三键全删（P1：清缓存池后下轮必须回 FULL）、存量迁移（只有旧版 Long 键
 * → lastDone 为 null）；跨进程持久化属 DataStore 自身保证，不在此测。
 */
class PrefetchRevisionStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var scope: CoroutineScope? = null

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + Job())
    }

    @After
    fun tearDown() {
        scope?.cancel()
        scope = null
    }

    /** 每例独立 DataStore 文件（TemporaryFolder 每测新开；不预先创建：不存在 = 空数据） */
    private fun newStore(): PrefetchRevisionStore {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope!!,
            produceFile = { File(tmp.root, "client_prefs.preferences_pb") },
        )
        return DataStorePrefetchRevisionStore(dataStore)
    }

    /** 测试合成值：NAS 形态地址 + 样本 URL（原串带签名参数，模拟真实直链形态） */
    private val recordA = PrefetchRevisionRecord(serverKey = "http://nas.local:8080", revision = 42L)

    private val sampleA = setOf(
        "http://nas.local:8080/media/thumb/aaa?size=md&exp=1&sig=x",
        "http://nas.local:8080/media/thumb/bbb?size=md&exp=1&sig=y",
    )

    @Test
    fun `无记录读取为null`() = runBlocking {
        assertEquals(null, newStore().lastDone())
        Unit
    }

    @Test
    fun `三键同写后读取完整记录与样本`() = runBlocking {
        val store = newStore()
        store.setLastDone(recordA, sampleA)
        assertEquals(recordA, store.lastDone())
        assertEquals(sampleA, store.storedSample())
        Unit
    }

    @Test
    fun `覆盖写入取最新值`() = runBlocking {
        val store = newStore()
        store.setLastDone(recordA, sampleA)
        val recordB = PrefetchRevisionRecord(serverKey = "http://127.0.0.1:18430", revision = 43L)
        store.setLastDone(recordB, emptySet())
        assertEquals(recordB, store.lastDone())
        assertEquals(emptySet<String>(), store.storedSample())
        Unit
    }

    @Test
    fun `只有旧版修订号键的存量迁移为null`() = runBlocking {
        // 向后兼容迁移：历史版本只写 Long 修订号键（无服务器键）——lastDone 视同无记录
        // （下轮 FULL 一次后写入完整三元组）；残留修订号键不得被读成可用记录
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope!!,
            produceFile = { File(tmp.root, "client_prefs.preferences_pb") },
        )
        dataStore.edit { it[longPreferencesKey("last_prefetch_revision")] = 42L }
        assertEquals(null, DataStorePrefetchRevisionStore(dataStore).lastDone())
        Unit
    }

    @Test
    fun `无样本键或空样本storedSample返回空集合`() = runBlocking {
        val store = newStore()
        // 从未写过：缺键返回空集合
        assertEquals(emptySet<String>(), store.storedSample())
        // 写过但样本为空：同样空集合
        store.setLastDone(recordA, emptySet())
        assertEquals(emptySet<String>(), store.storedSample())
        Unit
    }

    @Test
    fun `清空后三键全空下次走全量`() = runBlocking {
        // P1（清缓存池失效接线）：clear 语义 = 三键同删，lastDone 读回 null（门判 FULL 补拉）
        val store = newStore()
        store.setLastDone(recordA, sampleA)
        store.clear()
        assertEquals(null, store.lastDone())
        assertEquals(emptySet<String>(), store.storedSample())
        Unit
    }
}
