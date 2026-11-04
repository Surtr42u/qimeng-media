package media.qimeng.app.feature.author

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import media.qimeng.app.core.data.repository.CreatorProfileRepository
import media.qimeng.app.core.model.CreatorProfile
import media.qimeng.app.core.model.CreatorProfileCandidate
import media.qimeng.app.core.model.CreatorProfileDraft
import media.qimeng.app.core.testing.FakeAuthorRepository
import media.qimeng.app.core.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CreatorProfileViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()

    private class Profiles : CreatorProfileRepository {
        var saved = CreatorProfile("cos_example", 1, "原昵称", "人工简介", "", "https://example.com", emptyList(), "verified", false, "", false, "", "", "",
            CreatorProfileCandidate("候选昵称", "候选简介", "", "", listOf("https://example.com/about")), emptyList())
        var writes = 0
        var failure = false
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun profile(authorId: String) = saved
        override suspend fun save(authorId: String, draft: CreatorProfileDraft): CreatorProfile {
            writes++
            gate?.await()
            if (failure) error("409 conflict")
            return saved.copy(nickname = draft.nickname, revision = saved.revision + 1)
        }
        override suspend fun refresh(authorId: String) = saved
        override suspend fun avatar(authorId: String) = byteArrayOf()
    }

    private fun vm(repo: Profiles) = CreatorProfileViewModel(repo, FakeAuthorRepository(), SavedStateHandle(mapOf(AuthorCollectionRoutes.KEY_AUTHOR_ID to "cos_example")))

    @Test fun `候选先进入草稿不写库且保持人工原值`() = runTest {
        val repo = Profiles()
        val vm = vm(repo)
        advanceUntilIdle()
        vm.useCandidate()
        assertEquals("候选简介", vm.state.value.draft?.biography)
        assertEquals("人工简介", vm.state.value.profile?.biography)
        assertEquals(0, repo.writes)
        vm.cancelEdit()
        assertFalse(vm.state.value.editing)
    }

    @Test fun `冲突失败保留草稿与提交版本`() = runTest {
        val repo = Profiles().apply { failure = true }
        val vm = vm(repo)
        advanceUntilIdle()
        vm.edit()
        vm.changeDraft { it.copy(nickname = "新昵称") }
        vm.save()
        advanceUntilIdle()
        assertTrue(vm.state.value.editing)
        assertEquals("新昵称", vm.state.value.draft?.nickname)
        assertEquals(1L, vm.state.value.draft?.revision)
        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.busy)
    }

    @Test fun `保存中防重复且成功才发布新档案`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = Profiles().apply { this.gate = gate }
        val vm = vm(repo)
        advanceUntilIdle()
        vm.edit()
        vm.changeDraft { it.copy(nickname = "已核实昵称") }
        vm.save()
        runCurrent()
        vm.save()
        assertEquals(1, repo.writes)
        assertEquals("原昵称", vm.state.value.profile?.nickname)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("已核实昵称", vm.state.value.profile?.nickname)
        assertFalse(vm.state.value.editing)
    }
}
