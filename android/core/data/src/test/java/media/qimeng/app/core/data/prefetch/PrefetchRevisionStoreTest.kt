package media.qimeng.app.core.data.prefetch

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
 * 预取修订号持久仓纯 JVM 行为锁定（2026-09-30 revision 跳过批）：真 DataStore +
 * 临时目录文件仓（DataStoreStagingRepositoryTest 同款基座）。覆盖 null 语义（无记录
 * = 首次全量依据）、写读往返、覆盖写取最新、清空失效（P1：清缓存池后下轮必须
 * 回 FULL）；跨进程持久化属 DataStore 自身保证，不在此测。
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

    @Test
    fun `无记录读取为null`() = runBlocking {
        assertEquals(null, newStore().lastDoneRevision())
        Unit
    }

    @Test
    fun `写入读取往返`() = runBlocking {
        val store = newStore()
        store.setLastDoneRevision(42L)
        assertEquals(42L, store.lastDoneRevision())
        Unit
    }

    @Test
    fun `覆盖写入取最新值`() = runBlocking {
        val store = newStore()
        store.setLastDoneRevision(42L)
        store.setLastDoneRevision(43L)
        assertEquals(43L, store.lastDoneRevision())
        Unit
    }

    @Test
    fun `清空后读回null下次走全量`() = runBlocking {
        // P1（清缓存池失效接线）：clear 语义 = 删键，读回 null（门判 FULL 补拉）
        val store = newStore()
        store.setLastDoneRevision(42L)
        store.clear()
        assertEquals(null, store.lastDoneRevision())
        Unit
    }
}
