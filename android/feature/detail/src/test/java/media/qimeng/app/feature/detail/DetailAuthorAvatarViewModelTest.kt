package media.qimeng.app.feature.detail

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import media.qimeng.app.core.data.repository.CreatorProfileRepository
import media.qimeng.app.core.model.CreatorProfile
import media.qimeng.app.core.model.CreatorProfileDraft
import media.qimeng.app.core.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

class DetailAuthorAvatarViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()

    @Test fun `a late old author cannot replace the current sheet`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val profiles = object : CreatorProfileRepository {
            override fun avatarScope() = "synthetic"
            override suspend fun profile(authorId: String): CreatorProfile = error("unused")
            override suspend fun save(authorId: String, draft: CreatorProfileDraft): CreatorProfile = error("unused")
            override suspend fun refresh(authorId: String): CreatorProfile = error("unused")
            override suspend fun avatar(authorId: String): ByteArray {
                if (authorId == "old") withContext(NonCancellable) { gate.await() }
                return byteArrayOf(if (authorId == "old") 1 else 2)
            }
        }
        val vm = DetailAuthorAvatarViewModel(profiles)
        vm.load(listOf("old"))
        runCurrent()
        val firstScope = vm.state.value.scope
        vm.load(listOf("current"))
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertNotEquals(firstScope, vm.state.value.scope)
        assertEquals(2.toByte(), vm.state.value.loaded.getValue("current")!![0])
        assertFalse(vm.state.value.loaded.containsKey("old"))
    }
}
