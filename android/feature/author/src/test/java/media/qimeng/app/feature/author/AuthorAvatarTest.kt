package media.qimeng.app.feature.author

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import media.qimeng.app.core.data.repository.AssetOrigUrlResolver
import media.qimeng.app.core.data.repository.CreatorProfileRepository
import media.qimeng.app.core.model.CreatorProfileDraft
import media.qimeng.app.core.model.CreatorProfile
import media.qimeng.app.core.testing.FakeAuthorRepository
import media.qimeng.app.core.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthorAvatarTest {
    @get:Rule val dispatcher = MainDispatcherRule()

    private class Profiles : CreatorProfileRepository {
        var scope = "synthetic-first"
        var calls = 0
        var imageSize = 8
        var missing = false
        var gate: CompletableDeferred<Unit>? = null
        override fun avatarScope() = scope
        override suspend fun profile(authorId: String): CreatorProfile = error("unused")
        override suspend fun save(authorId: String, draft: CreatorProfileDraft): CreatorProfile = error("unused")
        override suspend fun refresh(authorId: String): CreatorProfile = error("unused")
        override suspend fun avatar(authorId: String): ByteArray {
            calls++
            gate?.await()
            if (missing) error("404")
            return ByteArray(imageSize)
        }
    }

    private fun vm(profiles: Profiles) = AuthorViewModel(FakeAuthorRepository(), object : AssetOrigUrlResolver {
        override suspend fun origUrl(assetId: String): String? = null
    }, profiles)

    @Test fun `滚出再返回直接复用头像且刷新后重读`() = runTest {
        val repo = Profiles()
        val vm = vm(repo)
        advanceUntilIdle()
        val key = vm.avatarKey("one")
        assertNotEquals(key, vm(repo).avatarKey("one"))
        assertTrue(vm.cachedAvatar(key).loading)
        val first = vm.avatar("one")
        assertFalse(vm.cachedAvatar(key).loading)
        assertSame(first.bytes, vm.cachedAvatar(key).bytes)
        assertSame(first.bytes, vm.avatar("one").bytes)
        assertEquals(1, repo.calls)
        vm.refresh()
        advanceUntilIdle()
        assertNotEquals(key, vm.avatarKey("one"))
        assertTrue(vm.cachedAvatar(vm.avatarKey("one")).loading)
        vm.avatar("one")
        assertEquals(2, repo.calls)
    }

    @Test fun `无图结果有界缓存且切服隔离`() = runTest {
        val repo = Profiles().apply { missing = true }
        val vm = vm(repo)
        advanceUntilIdle()
        vm.avatar("one")
        vm.avatar("one")
        assertEquals(1, repo.calls)
        assertFalse(vm.cachedAvatar(vm.avatarKey("one")).loading)
        repo.scope = "synthetic-second"
        assertTrue(vm.cachedAvatar(vm.avatarKey("one")).loading)
        vm.avatar("one")
        assertEquals(2, repo.calls)
        val firstKey = vm.avatarKey("first")
        vm.avatar("first")
        repeat(256) { vm.avatar("other-$it") }
        assertTrue(vm.cachedAvatar(firstKey).loading)
    }

    @Test fun `字节预算淘汰最早图片`() = runTest {
        val repo = Profiles().apply { imageSize = 2 * 1024 * 1024 }
        val vm = vm(repo)
        advanceUntilIdle()
        val firstKey = vm.avatarKey("first")
        vm.avatar("first")
        repeat(8) { vm.avatar("other-$it") }
        assertTrue(vm.cachedAvatar(firstKey).loading)
        assertFalse(vm.cachedAvatar(vm.avatarKey("other-7")).loading)
    }

    @Test fun `切服后的迟到图片不进入当前缓存`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = Profiles().apply { this.gate = gate }
        val vm = vm(repo)
        advanceUntilIdle()
        val pending = async { runCatching { vm.avatar("one") } }
        runCurrent()
        repo.scope = "synthetic-second"
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(pending.await().exceptionOrNull() is CancellationException)
        assertTrue(vm.cachedAvatar(vm.avatarKey("one")).loading)
    }
}
