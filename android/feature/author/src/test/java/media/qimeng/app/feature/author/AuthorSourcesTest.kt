package media.qimeng.app.feature.author

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import media.qimeng.app.core.data.repository.AssetOrigUrlResolver
import media.qimeng.app.core.data.repository.AuthorRepository
import media.qimeng.app.core.model.AuthorSummary
import media.qimeng.app.core.testing.FakeAuthorRepository
import media.qimeng.app.core.testing.MainDispatcherRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AuthorSourcesTest {
    @get:Rule val dispatcher = MainDispatcherRule()
    private val author = FakeAuthorRepository.summary("one", "作者甲")
    private val resolver = object : AssetOrigUrlResolver {
        override suspend fun origUrl(assetId: String): String? = null
    }

    @Test fun `读取失败禁止保存空来源`() = runTest {
        var writes = 0
        val repo = object : AuthorRepository {
            override suspend fun authors(): List<AuthorSummary> = emptyList()
            override suspend fun setFollowed(authorId: String, followed: Boolean) = Unit
            override suspend fun authorSourcesById(authorId: String): List<String> = error("offline")
            override suspend fun replaceAuthorSources(authorId: String, sources: List<String>) { writes++ }
        }
        val vm = AuthorViewModel(repo, resolver)
        vm.openSourcesEditor(author)
        advanceUntilIdle()
        vm.saveSources()
        assertEquals(0, writes)
        assertFalse(vm.uiState.value.sourcesEditor!!.loaded)
    }

    @Test fun `主动清空发送空数组失败保留草稿重试成功`() = runTest {
        var fail = true
        val writes = mutableListOf<List<String>>()
        val repo = object : AuthorRepository {
            override suspend fun authors(): List<AuthorSummary> = emptyList()
            override suspend fun setFollowed(authorId: String, followed: Boolean) = Unit
            override suspend fun authorSourcesById(authorId: String) = listOf("site-a")
            override suspend fun replaceAuthorSources(authorId: String, sources: List<String>) {
                if (fail) error("offline")
                writes.add(sources)
            }
        }
        val vm = AuthorViewModel(repo, resolver)
        vm.openSourcesEditor(author)
        advanceUntilIdle()
        vm.changeSources(emptyList())
        vm.saveSources()
        advanceUntilIdle()
        assertEquals(emptyList<String>(), vm.uiState.value.sourcesEditor!!.sources)
        assertNotNull(vm.uiState.value.sourcesEditor!!.error)
        fail = false
        vm.saveSources()
        advanceUntilIdle()
        assertEquals(listOf(emptyList<String>()), writes)
        assertNull(vm.uiState.value.sourcesEditor)
    }

    @Test fun `关闭读取中的作者再打开另一作者旧响应不能覆盖`() = runTest {
        val pending = CompletableDeferred<List<String>>()
        val repo = object : AuthorRepository {
            override suspend fun authors(): List<AuthorSummary> = emptyList()
            override suspend fun setFollowed(authorId: String, followed: Boolean) = Unit
            override suspend fun authorSourcesById(authorId: String) = if (authorId == "one") pending.await() else listOf("site-b")
        }
        val vm = AuthorViewModel(repo, resolver)
        vm.openSourcesEditor(author)
        runCurrent()
        vm.dismissSourcesEditor()
        vm.openSourcesEditor(FakeAuthorRepository.summary("two", "作者乙"))
        pending.complete(listOf("site-a"))
        advanceUntilIdle()
        assertEquals("two", vm.uiState.value.sourcesEditor!!.author.id)
        assertEquals(listOf("site-b"), vm.uiState.value.sourcesEditor!!.sources)
    }
}
