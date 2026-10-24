package media.qimeng.app.feature.manage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import media.qimeng.app.core.data.repository.VocabularyEditRepository
import media.qimeng.app.core.data.repository.VocabularySyncError
import media.qimeng.app.core.data.repository.VocabularySyncException
import media.qimeng.app.core.model.EditableVocabulary
import media.qimeng.app.core.model.VocabularyCharacter
import media.qimeng.app.core.model.VocabularyGroup
import media.qimeng.app.core.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 词表维护 ViewModel 状态机锁定（ADR-0035）：进页加载回填 / 全部编辑操作置 dirty 且
 * 纯内存变更（组/变体/角色/别名/停用词五层）/ 端内空表语义（空=清空；「空子列表→协议
 * 可省」的映射归一收口在 core:data mapper 单测，VM 层只见非 null 空表）/ 整体保存携带
 * 编辑结果并静默回读规范化形态 / 错误分类→文案映射 / 草稿代际与取消纪律（2026-10-24
 * 回读覆盖修复批）。
 *
 * 代际场景（2026-10-24 修复批核心回归）用 [GatedVocabularyEditRepository] 把回读
 * 拦在 gate 上、在任意时点放行，逐事件推进虚拟时钟——不 advanceUntilIdle 一步到位：
 * 「保存成功→回读未完成→继续编辑→旧回读返回」每一步的中间态都必须断言。
 * 关于「fake 吞取消」：挂起中的回读 Job 被取消时，取消会在恢复点抛出、旧结果根本
 * 进不了 onSuccess；真正需要代际守卫兜底的是「旧结果不经取消检查点返回」（场景
 * `保存后慢回读不得覆盖继续编辑的新草稿`：全程无取消介入，纯代际/修订号拒绝）——
 * 两条防线各自被独立锁定。
 */
class VocabularyEditViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun driveIdle() = mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

    private fun samplePayload() = EditableVocabulary(
        groups = listOf(
            VocabularyGroup(
                canonical = "怪物猎人",
                variants = listOf("MHW"),
                characters = listOf(
                    VocabularyCharacter(canonical = "杰玛", aliases = listOf("Gemma")),
                ),
            ),
        ),
        stopWords = listOf("触手"),
    )

    private class FakeVocabularyEditRepository : VocabularyEditRepository {
        val loadQueue = ArrayDeque<Result<EditableVocabulary>>()
        val savePayloads = mutableListOf<EditableVocabulary>()
        var saveResult: Result<Unit> = Result.success(Unit)

        override suspend fun load(): Result<EditableVocabulary> =
            if (loadQueue.isEmpty()) {
                Result.success(EditableVocabulary())
            } else {
                loadQueue.removeFirst()
            }

        override suspend fun save(payload: EditableVocabulary): Result<Unit> {
            savePayloads.add(payload)
            return saveResult
        }
    }

    /**
     * 可控延迟 fake：每次 load 弹出一个 gate 并挂起等待放行，用于把回读/加载钉在
     * 任意时点（慢回读、反序到达）。gate 完成即返回其携带的结果。
     */
    private class GatedVocabularyEditRepository : VocabularyEditRepository {
        val loadGates = ArrayDeque<CompletableDeferred<Result<EditableVocabulary>>>()
        val savePayloads = mutableListOf<EditableVocabulary>()

        override suspend fun load(): Result<EditableVocabulary> =
            loadGates.removeFirst().await()

        override suspend fun save(payload: EditableVocabulary): Result<Unit> {
            savePayloads.add(payload)
            return Result.success(Unit)
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
    fun `变体增删端内保持非空表`() = runTest {
        val (_, vm) = newViewModel()
        vm.addGroup("怪物猎人")
        vm.addVariant(0, "MHW")
        assertEquals(listOf("MHW"), vm.uiState.value.groups.single().variants)
        vm.removeVariant(0, 0)
        // 端内删空=emptyList（空=清空语义）；「空→协议可省」归一在 core:data mapper
        assertTrue(vm.uiState.value.groups.single().variants.isEmpty())
    }

    @Test
    fun `角色与别名增删改`() = runTest {
        val (_, vm) = newViewModel()
        vm.addGroup("守望先锋")
        vm.addCharacter(0, "D.Mon")
        vm.addAlias(0, 0, "Dmon")
        vm.addAlias(0, 0, "D.MON")
        val character = vm.uiState.value.groups.single().characters.single()
        assertEquals("D.Mon", character.canonical)
        assertEquals(listOf("Dmon", "D.MON"), character.aliases)
        vm.renameCharacter(0, 0, "D.Mon（重装）")
        vm.removeAlias(0, 0, 1)
        val renamed = vm.uiState.value.groups.single().characters.single()
        assertEquals("D.Mon（重装）", renamed.canonical)
        assertEquals(listOf("Dmon"), renamed.aliases)
        vm.removeAlias(0, 0, 0)
        assertTrue(vm.uiState.value.groups.single().characters.single().aliases.isEmpty())
        vm.removeCharacter(0, 0)
        assertTrue(vm.uiState.value.groups.single().characters.isEmpty())
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
        assertTrue(vm.uiState.value.groups.single().variants.isEmpty())
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
                EditableVocabulary(
                    groups = listOf(VocabularyGroup(canonical = "怪物猎人")),
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

    // ---- 草稿代际与取消纪律（2026-10-24 修复批核心回归） ----

    @Test
    fun `立即完成的回读仍能回填保存后的规范化结果`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(mainDispatcherRule.testDispatcher.scheduler))
        try {
            val repo = FakeVocabularyEditRepository()
            val vm = VocabularyEditViewModel(repo)
            vm.addGroup("原组")
            repo.loadQueue.add(
                Result.success(EditableVocabulary(groups = listOf(VocabularyGroup("规范化组")))),
            )

            vm.save()

            assertEquals("规范化组", vm.uiState.value.groups.single().canonical)
            assertFalse(vm.uiState.value.saving)
            assertFalse(vm.uiState.value.dirty)
            assertNotNull(vm.uiState.value.noticeMessage)
        } finally {
            Dispatchers.setMain(mainDispatcherRule.testDispatcher)
        }
    }

    @Test
    fun `加载期间的新编辑不被初始响应覆盖`() = runTest {
        val repo = GatedVocabularyEditRepository()
        val vm = VocabularyEditViewModel(repo)
        val gate = CompletableDeferred<Result<EditableVocabulary>>()
        repo.loadGates.add(gate)
        vm.load()
        driveIdle()
        assertTrue(vm.uiState.value.loading)

        vm.addGroup("新草稿")
        gate.complete(Result.success(samplePayload()))
        driveIdle()

        assertEquals("新草稿", vm.uiState.value.groups.single().canonical)
        assertTrue(vm.uiState.value.dirty)
        assertFalse(vm.uiState.value.loading)
    }

    @Test
    fun `保存后慢回读不得覆盖继续编辑的新草稿`() = runTest {
        val repo = GatedVocabularyEditRepository()
        val vm = VocabularyEditViewModel(repo)
        vm.addGroup("怪物猎人")
        val staleGate = CompletableDeferred<Result<EditableVocabulary>>()
        repo.loadGates.add(staleGate)
        vm.save()
        driveIdle()
        // 保存已成功收口，回读仍挂在 gate 上未返回
        assertFalse(vm.uiState.value.saving)
        assertNotNull(vm.uiState.value.noticeMessage)
        // 回读未完成时用户继续编辑：新草稿 + 修订号推进（全程无取消介入）
        vm.addGroup("新组")
        assertTrue(vm.uiState.value.dirty)
        // 旧回读此刻才返回：携带的是不含「新组」的旧形态
        staleGate.complete(Result.success(samplePayload()))
        driveIdle()
        val state = vm.uiState.value
        assertEquals(listOf("怪物猎人", "新组"), state.groups.map { it.canonical })
        assertTrue(state.dirty)
        assertNotNull(state.noticeMessage)
        assertNull(state.errorMessage)
    }

    @Test
    fun `连续两轮保存的回读反序不覆盖新规范化结果`() = runTest {
        val repo = GatedVocabularyEditRepository()
        val vm = VocabularyEditViewModel(repo)
        vm.addGroup("旧组")
        val gateA = CompletableDeferred<Result<EditableVocabulary>>()
        repo.loadGates.add(gateA)
        vm.save()
        driveIdle() // 保存1成功；回读 A 挂在 gateA
        val gateB = CompletableDeferred<Result<EditableVocabulary>>()
        repo.loadGates.add(gateB)
        vm.addGroup("新组")
        vm.save()
        driveIdle() // 保存2成功（代际推进，回读 A 的 Job 被取消）；回读 B 挂在 gateB
        // 放行 B：携带第二轮保存的服务端规范化形态（trim/去重后的「新组 」）
        gateB.complete(
            Result.success(
                EditableVocabulary(groups = listOf(VocabularyGroup(canonical = "规范化组"))),
            ),
        )
        driveIdle()
        assertEquals("规范化组", vm.uiState.value.groups.single().canonical)
        assertFalse(vm.uiState.value.dirty)
        // 再放行 A（旧形态）：无论其协程是否已被取消吞掉，旧结果都不得回填
        gateA.complete(Result.success(samplePayload()))
        driveIdle()
        assertEquals("规范化组", vm.uiState.value.groups.single().canonical)
        assertTrue(vm.uiState.value.groups.none { it.canonical == "怪物猎人" })
        assertNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun `加载与保存取消不显示失败且收口busy`() = runTest {
        val loadRepo = FakeVocabularyEditRepository()
        val loadVm = VocabularyEditViewModel(loadRepo)
        loadRepo.loadQueue.add(Result.failure(CancellationException("测试取消")))
        loadVm.load()
        driveIdle()
        assertNull(loadVm.uiState.value.errorMessage)
        assertFalse(loadVm.uiState.value.loading)

        val saveRepo = FakeVocabularyEditRepository()
        val saveVm = VocabularyEditViewModel(saveRepo)
        saveVm.addGroup("组")
        saveRepo.saveResult = Result.failure(CancellationException("测试取消"))
        saveVm.save()
        driveIdle()
        // 取消不是业务失败：无横幅、无成功提示、busy 收口、草稿保持脏态等下一轮保存
        assertNull(saveVm.uiState.value.errorMessage)
        assertNull(saveVm.uiState.value.noticeMessage)
        assertFalse(saveVm.uiState.value.saving)
        assertTrue(saveVm.uiState.value.dirty)
        assertEquals("组", saveVm.uiState.value.groups.single().canonical)
    }

    @Test
    fun `保存后回读取消不影响已提交保存`() = runTest {
        val repo = GatedVocabularyEditRepository()
        val vm = VocabularyEditViewModel(repo)
        vm.addGroup("怪物猎人")
        val gate = CompletableDeferred<Result<EditableVocabulary>>()
        repo.loadGates.add(gate)
        vm.save()
        driveIdle() // 保存成功；回读挂在 gate
        // 回读以取消收场（模拟取消传播到回读链路）：已提交的保存不得被改成失败
        gate.complete(Result.failure(CancellationException("测试取消")))
        driveIdle()
        val state = vm.uiState.value
        assertNotNull(state.noticeMessage)
        assertNull(state.errorMessage)
        assertEquals("怪物猎人", state.groups.single().canonical)
        assertFalse(state.saving)
        assertFalse(state.loading)
    }
}
