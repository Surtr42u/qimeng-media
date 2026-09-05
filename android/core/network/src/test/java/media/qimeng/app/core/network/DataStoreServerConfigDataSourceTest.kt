package media.qimeng.app.core.network

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * token 存取单测（DataStore 真实读写，临时目录文件）：
 * 锁定「写入→流发射→清除→null」与「清 token 保留地址（登录页记忆上次）」两条语义。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreServerConfigDataSourceTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private fun newDataStore(testDir: File): DataStore<androidx.datastore.preferences.core.Preferences> =
        PreferenceDataStoreFactory.create(scope = CoroutineScope(UnconfinedTestDispatcher() + Job())) {
            File(testDir, "server_config.preferences_pb")
        }

    @Test
    fun `token 写入后流发射且内存缓存同步`() = runTest {
        val dataSource = DataStoreServerConfigDataSource(
            dataStore = newDataStore(tmpFolder.newFolder()),
            appScope = CoroutineScope(UnconfinedTestDispatcher()),
        )

        dataSource.updateToken("token-abc")

        assertEquals("token-abc", dataSource.token.first())
        assertEquals("token-abc", dataSource.currentToken())
    }

    @Test
    fun `清除 token 后流为 null 且地址保留`() = runTest {
        val dataSource = DataStoreServerConfigDataSource(
            dataStore = newDataStore(tmpFolder.newFolder()),
            appScope = CoroutineScope(UnconfinedTestDispatcher()),
        )
        dataSource.updateServerUrl("http://10.0.2.2:8420")
        dataSource.updateToken("token-abc")

        dataSource.clearToken()

        assertNull(dataSource.token.first())
        assertNull(dataSource.currentToken())
        // 「记忆上次」语义：退出登录只清 token，地址留给登录页回填
        assertEquals("http://10.0.2.2:8420", dataSource.serverUrl.first())
    }

    @Test
    fun `冷启动预热从持久化文件恢复 token`() = runTest {
        val dir = tmpFolder.newFolder()
        val dataStore = newDataStore(dir)
        val first = DataStoreServerConfigDataSource(dataStore, CoroutineScope(UnconfinedTestDispatcher()))
        first.updateToken("persisted-token")
        first.updateServerUrl("http://192.168.1.10:8420")

        // 同一文件新实例 = 模拟进程重启；Unconfined 调度让 init 预热立即执行
        val rebooted = DataStoreServerConfigDataSource(dataStore, CoroutineScope(UnconfinedTestDispatcher()))

        assertEquals("persisted-token", rebooted.currentToken())
        assertEquals("http://192.168.1.10:8420", rebooted.serverUrl.first())
    }
}
