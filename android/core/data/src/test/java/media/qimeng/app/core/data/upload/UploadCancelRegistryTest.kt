package media.qimeng.app.core.data.upload

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UploadCancelRegistryTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `取消跨存储重启恢复且只在已完成后清理`() = runBlocking {
        val file = File(tmp.root, "cancel.preferences_pb")
        fun create(job: Job) = UploadCancelRegistry(PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file },
        ))
        val firstJob = Job()
        try { create(firstJob).cancel("task") } finally { firstJob.cancelAndJoin() }
        val secondJob = Job()
        try {
            val registry = create(secondJob)
            registry.load()
            assertTrue(registry.isCancelled("task"))
            registry.forgetCompleted(setOf("other"))
            assertTrue(registry.isCancelled("task"))
            registry.forgetCompleted(setOf("task"))
            assertFalse(registry.isCancelled("task"))
        } finally { secondJob.cancelAndJoin() }
        val thirdJob = Job()
        try {
            val registry = create(thirdJob)
            registry.load()
            assertFalse(registry.isCancelled("task"))
        } finally { thirdJob.cancelAndJoin() }
    }

    @Test fun `磁盘提交失败不发布取消成功`() = runBlocking {
        val memory = MemoryUploadPreferences()
        val failing = object : DataStore<Preferences> {
            override val data = memory.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                throw IOException("synthetic disk failure")
        }
        val registry = UploadCancelRegistry(failing)
        registry.load()
        try {
            registry.cancel("task")
            fail("持久化失败应向调用方报错")
        } catch (_: IOException) {
            assertFalse(registry.isCancelled("task"))
        }
    }
}
