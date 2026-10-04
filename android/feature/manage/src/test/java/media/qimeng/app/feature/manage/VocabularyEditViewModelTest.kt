package media.qimeng.app.feature.manage

import media.qimeng.app.core.data.repository.VocabularyEditRepository
import media.qimeng.app.core.data.repository.VocabularySyncError
import media.qimeng.app.core.data.repository.VocabularySyncException
import media.qimeng.app.core.testing.MainDispatcherRule
import media.qimeng.sdk.models.CustomSourceCharacter
import media.qimeng.sdk.models.CustomSourceGroup
import media.qimeng.sdk.models.CustomSourceGroups
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 词表维护 ViewModel 状态机锁定（ADR-0035）：进页加载回填 / 全部编辑操作置 dirty 且
 * 纯内存变更（组/变体/角色/别名/停用词五层）/ 空表回 null（协议可省归一）/ 整体保存
 * 携带编辑结果并静默回读规范化形态 / 错误分类→文案映射。fake 只在测试源集内
 * （BackupViewModelTest 同款模式）。
 */
class VocabularyEditViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun driveIdle() = mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

    private fun samplePayload() = CustomSourceGroups(
        groups = listOf(
            CustomSourceGroup(
                canonical = "怪物猎人",
                variants = listOf("MHW"),
                characters = listOf(
                    CustomSourceCharacter(canonical = "杰玛", aliases = listOf("Gemma")),
                ),
            ),
        ),
        stopWords = listOf("触手"),
    )

    private class FakeVocabularyEditRepository : VocabularyEditRepository {
        val loadQueue = ArrayDeque<Result<CustomSourceGroups>>()
        val savePayloads = mutableListOf<CustomSourceGroups>()
        var saveResult: Result<Unit> = Result.success(Unit)

        override suspend fun load(): Result<CustomSourceGroups> =
            if (loadQueue.isEmpty()) {
                Result.success(CustomSourceGroups(groups = emptyList()))
            } else {
                loadQueue.removeFirst()
            }

        override suspend fun save(payload: CustomSourceGroups): Result<Unit> {
            savePayloads.add(payload)
            return saveResult
        }
    }

    private fun newViewModel(
        repository: FakeVocabularyEditRepository = FakeVocabularyEditRepository(),
    ) = Pair(repository, VocabularyEditViewModel(repository).also { driveIdle() })

    @Test
    fun `进页加载回填且非脏`() = runTest {
        val (repo, vm) = newViewModel()
        repo.loadQueue.add(Result.success(samplePayload()))
        vm.load()
        driveIdle()
        val state = vm.uiState.value
        assertFalse(state.loading)
        assertFalse(state.dirty)
        assertEquals("怪物猎人", state.groups.single().canonical)
        assertEquals(listOf("触手"), state.stopWords)
        assertNull(state.errorMessage)
    }

    @Test
    fun `加载失败映射专属文案`() = runTest {
        val (repo, vm) = newViewModel()
        repo.loadQueue.add(
            Result.failure(VocabularySyncException(VocabularySyncError.LocalServerUnavailable)),
        )
        vm.load()
        driveIdle()
        assertTrue(vm.uiState.value.errorMessage!!.contains("加载失败"))
        assertFalse(vm.uiState.value.loading)
    }

    @Test
    fun `组级编辑置脏且变更内存副本`() = runTest {
        val (_, vm) = newViewModel()
        vm.addGroup("战锤40k")
        assertEquals("战锤40k", vm.uiState.value.groups.single().canonical)
        assertTrue(vm.uiState.value.dirty)
        vm.renameGroup(0, "战锤40K")
        assertEquals("战锤40K", vm.uiState.value.groups.single().canonical)
        vm.removeGroup(0)
        assertTrue(vm.uiState.value.groups.isEmpty())
        assertTrue(vm.uiState.value.dirty)
    }

    @Test
    fun `变体增删走可省归一`() = runTest {
        val (_, vm) = newViewModel()
        vm.addGroup("怪物猎人")
        vm.addVariant(0, "MHW")
        assertEquals(listOf("MHW"), vm.uiState.value.groups.single().variants)
        vm.removeVariant(0, 0)
        assertNull(vm.uiState.value.groups.single().variants)
    }

    @Test
    fun `角色与别名增删改`() = runTest {
        val (_, vm) = newViewModel()
        vm.addGroup("守望先锋")
        vm.addCharacter(0, "D.Mon")
        vm.addAlias(0, 0, "Dmon")
        vm.addAlias(0, 0, "D.MON")
        val character = vm.uiState.value.groups.single().characters!!.single()
        assertEquals("D.Mon", character.canonical)
        assertEquals(listOf("Dmon", "D.MON"), character.aliases)
        vm.renameCharacter(0, 0, "D.Mon（重装）")
        vm.removeAlias(0, 0, 1)
        val renamed = vm.uiState.value.groups.single().characters!!.single()
        assertEquals("D.Mon（重装）", renamed.canonical)
        assertEquals(listOf("Dmon"), renamed.aliases)
        vm.removeAlias(0, 0, 0)
        assertNull(vm.uiState.value.groups.single().characters!!.single().aliases)
        vm.removeCharacter(0, 0)
        assertNull(vm.uiState.value.groups.single().characters)
    }

    @Test
    fun `越界编辑静默不动`() = runTest {
        val (_, vm) = newViewModel()
        vm.addGroup("组")
        vm.renameGroup(5, "越界")
        vm.removeGroup(5)
        vm.addVariant(5, "越界")
        vm.removeVariant(0, 3)
        assertEquals("组", vm.uiState.value.groups.single().canonical)
        assertNull(vm.uiState.value.groups.single().variants)
    }

    @Test
    fun `保存携带编辑结果并静默回读规范化形态`() = runTest {
        val (repo, vm) = newViewModel()
        vm.addGroup("怪物猎人 ")
        vm.addStopWord(" 白丝 ")
        vm.addStopWord("触手")
        // 模拟服务端规范化后的生效形态（trim + 去重）
        repo.loadQueue.add(
            Result.success(
                CustomSourceGroups(
                    groups = listOf(CustomSourceGroup(canonical = "怪物猎人")),
                    stopWords = listOf("白丝", "触手"),
                ),
            ),
        )
        vm.save()
        driveIdle()
        val payload = repo.savePayloads.single()
        assertEquals(listOf("白丝", "触手"), payload.stopWords)
        assertTrue(vm.uiState.value.noticeMessage!!.contains("已保存"))
        assertFalse(vm.uiState.value.dirty)
        // 静默回读：内存态与服务端生效形态抹平，loading/saving 均已收口
        assertEquals("怪物猎人", vm.uiState.value.groups.single().canonical)
        assertEquals(listOf("白丝", "触手"), vm.uiState.value.stopWords)
        assertFalse(vm.uiState.value.saving)
        assertFalse(vm.uiState.value.loading)
    }

    @Test
    fun `保存失败置错误横幅且保持脏态`() = runTest {
        val (repo, vm) = newViewModel()
        vm.addGroup("组")
        repo.saveResult = Result.failure(
            VocabularySyncException(VocabularySyncError.LocalApplyFailed),
        )
        vm.save()
        driveIdle()
        assertTrue(vm.uiState.value.errorMessage!!.contains("保存失败"))
        assertFalse(vm.uiState.value.saving)
        assertTrue(vm.uiState.value.dirty)
    }

    @Test
    fun `停用词增删置脏`() = runTest {
        val (_, vm) = newViewModel()
        vm.addStopWord("触手")
        assertEquals(listOf("触手"), vm.uiState.value.stopWords)
        vm.removeStopWord(0)
        assertTrue(vm.uiState.value.stopWords.isEmpty())
        assertTrue(vm.uiState.value.dirty)
    }
}
