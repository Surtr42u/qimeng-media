package media.qimeng.app.core.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import media.qimeng.app.core.model.StagingBatchConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [DataStoreStagingRepository] 原子读-改-写锁定（2026-09-25 P2 竞态修复）：
 * editBatchConfig 在 DataStore edit 内基于最新持久值变换——连续/并发两次变换
 * 叠加生效而非互相覆盖。JVM 直测真 DataStore（临时目录文件仓；File 侧行为不在此测，
 * 归 InboxFileStoreTest）。2026-09-29 直传化收窄：暂存条目面随暂存区退役删除。
 */
class DataStoreStagingRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var scope: CoroutineScope? = null

    @After
    fun tearDown() {
        scope?.cancel()
        scope = null
    }

    /** 每例独立 DataStore 文件（不预先创建文件：不存在 = 默认空数据；空文件会被判损坏） */
    private fun newRepository(): DataStoreStagingRepository {
        val s = CoroutineScope(Dispatchers.IO + Job())
        scope = s
        val produceFile: () -> File = { File(tmp.root, "staging.preferences_pb") }
        val dataStore = PreferenceDataStoreFactory.create(scope = s, produceFile = produceFile)
        return DataStoreStagingRepository(dataStore, InboxFileStore())
    }

    // ---- editBatchConfig：edit 内基于最新值变换 ----

    @Test
    fun `连续两次editBatchConfig变换基于最新值叠加`() = runBlocking {
        val repo = newRepository()
        repo.editBatchConfig { it.copy(libraryId = "lib-a") }
        repo.editBatchConfig { it.copy(authorId = "author-a", sources = it.sources + "site-a") }
        assertEquals(
            StagingBatchConfig(libraryId = "lib-a", authorId = "author-a", sources = listOf("site-a")),
            repo.batchConfig.first(),
        )
        Unit
    }

    @Test
    fun `并发两次editBatchConfig来源各加一词两词都在`() = runBlocking {
        val repo = newRepository()
        repo.editBatchConfig { it.copy(authorId = "author-a") }
        val first = async { repo.editBatchConfig { it.copy(sources = it.sources + "a") } }
        val second = async { repo.editBatchConfig { it.copy(sources = it.sources + "b") } }
        first.await()
        second.await()
        assertEquals(setOf("a", "b"), repo.batchConfig.first().sources.toSet())
        Unit
    }

    // ---- 归档文件夹路径持久化（2026-09-28 上传归档文件夹功能） ----

    @Test
    fun `归档路径设置读取与清除往返`() = runBlocking {
        val repo = newRepository()
        assertEquals(null, repo.archivePath.first())
        repo.setArchivePath("/storage/emulated/0/qimeng-archive")
        assertEquals("/storage/emulated/0/qimeng-archive", repo.archivePath.first())
        repo.setArchivePath(null)
        assertEquals(null, repo.archivePath.first())
        Unit
    }

    @Test
    fun `归档路径空串按清除处理读侧归一null`() = runBlocking {
        val repo = newRepository()
        repo.setArchivePath("/storage/emulated/0/qimeng-archive")
        repo.setArchivePath("")
        assertEquals(null, repo.archivePath.first())
        Unit
    }
}
