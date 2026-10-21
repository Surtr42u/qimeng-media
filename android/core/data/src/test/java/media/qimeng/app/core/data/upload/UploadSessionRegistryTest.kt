package media.qimeng.app.core.data.upload

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UploadSessionRegistryTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `restart reads committed handle only for bound target and terminal cleanup persists`() = runBlocking {
        val file = File(tmp.root, "sessions.preferences_pb")
        fun create(job: Job) = UploadSessionRegistry(PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file },
        ))
        val firstJob = Job()
        try {
            create(firstJob).remember("task-1", "http://example.test:8421", "session-1")
        } finally { firstJob.cancelAndJoin() }
        val secondJob = Job()
        try {
            val registry = create(secondJob)
            assertEquals("session-1", registry.lookup("task-1", "http://example.test:8421"))
            assertEquals(null, registry.lookup("task-1", "http://other.test:8421"))
            assertEquals(null, registry.lookup("task-unknown", "http://example.test:8421"))
            registry.forget("task-1")
        } finally { secondJob.cancelAndJoin() }
        val thirdJob = Job()
        try { assertEquals(null, create(thirdJob).lookup("task-1", "http://example.test:8421")) }
        finally { thirdJob.cancelAndJoin() }
    }
}
