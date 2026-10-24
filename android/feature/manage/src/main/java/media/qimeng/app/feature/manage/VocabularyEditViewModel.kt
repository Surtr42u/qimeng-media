package media.qimeng.app.feature.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.qimeng.app.core.data.repository.VocabularyEditRepository
import media.qimeng.app.core.data.repository.VocabularySyncError
import media.qimeng.app.core.data.repository.VocabularySyncException
import media.qimeng.app.core.model.EditableVocabulary
import media.qimeng.app.core.model.VocabularyCharacter
import media.qimeng.app.core.model.VocabularyGroup

/**
 * 词表维护页 UI 状态（BackupUiState 三件套同款 + 编辑域）。编辑模型用端内域模型
 * [EditableVocabulary]（2026-10-24 SDK 隔离批）：可省子列表端内恒非 null（空表语义
 * 由 core:data mapper 在保存边缘归一回协议可省），本 VM 零 SDK 引用。
 *
 * [draftRevision] 是本地草稿元数据（防旧回读覆盖新草稿的正确性条件，2026-10-24
 * 回读覆盖修复批），不是服务端词表版本：每次实际内容变更 +1，纯无效/越界/no-op
 * 操作不变更。与请求代际（readGeneration，见 [save]）共同构成「旧响应不得回填」
 * 的双重校验——取消旧 Job 只是优化，代际校验才是防线。
 */
data class VocabularyEditUiState(
    /** 进页加载中（防重复加载） */
    val loading: Boolean = false,
    /** 保存中（防重复提交，保存按钮禁用） */
    val saving: Boolean = false,
    /** 自定义出处组（内存编辑副本；保存前不代表服务端生效形态） */
    val groups: List<VocabularyGroup> = emptyList(),
    /** 停用词追加层（内存编辑副本；恒非 null，空数组=清空回内置基线） */
    val stopWords: List<String> = emptyList(),
    /** 有未保存修改（保存按钮/返回拦截的门） */
    val dirty: Boolean = false,
    /** 本地草稿修订号：实际内容变更递增；保存快照与回读回填的匹配凭据 */
    val draftRevision: Long = 0L,
    /** 错误横幅（点击关闭） */
    val errorMessage: String? = null,
    /** 保存结果提示（点击关闭；计数取保存启动时的快照） */
    val noticeMessage: String? = null,
)

/**
 * 词表维护 ViewModel（ADR-0035，2026-10-04）：进页 [load] 拉全量词表进内存编辑，
 * 全部增删改是纯内存列表操作（[dirty] 置位，经 [updateDraft] 统一递增草稿修订号），
 * [save] 把编辑结果整体 PUT（协议整体替换语义——用户看到什么就保存什么），成功后
 * 静默回读抹平服务端规范化差异（trim/去空/去重/canonical 自并入）。业务全在
 * core:data VocabularyEditRepository（铁律 7）：本 VM 不持有任何地址/鉴权/协议知识。
 *
 * 保存后回读的防覆盖纪律（2026-10-24 修复批，取代「只检查 saving」的旧防线）：
 * - 保存启动时立即固定快照（payload/期望修订号）并推进请求代际（旧回读 Job 取消
 *   且其结果必失效）；回读仅在代际与草稿修订号都匹配、且无 dirty/loading/saving 时
 *   回填——慢回读不得覆盖用户在保存成功后继续输入的新草稿。
 * - 所有 load/save/回读路径传播取消（[request] 包装）：取消不显示「加载/保存失败」，
 *   不发起成功后回读；busy 状态在所属操作的 finally 按代际 guard 收尾，旧任务不清
 *   新任务的 busy。
 * - 正常保存后的服务端规范化仍可回显；回读失败静默保留内存态，不把已提交保存改成
 *   失败。
 */
@HiltViewModel
class VocabularyEditViewModel @Inject constructor(
    private val repository: VocabularyEditRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VocabularyEditUiState())
    val uiState: StateFlow<VocabularyEditUiState> = _uiState.asStateFlow()

    /**
     * 请求代际（读侧序号）：每次 load/save 启动时 +1，使先前在途的回读/读响应全部
     * 过期。单线程 Main 约束下只在 load/save 入口（busy 门之后）递增，不进
     * StateFlow.update 的可重试 lambda。
     */
    private var readGeneration = 0L

    /** 在途静默回读 Job：新 load/save 启动时取消（取消是优化，代际校验才是防线） */
    private var readbackJob: Job? = null

    /**
     * 进页加载（LaunchedEffect 触发；保存后静默回读不走此口）。推进读代际——新 load
     * 使旧回读与旧 load 响应一并失效。
     */
    fun load() {
        if (_uiState.value.loading || _uiState.value.saving) return
        val generation = invalidateReadbacks()
        _uiState.update { it.copy(loading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                request { repository.load() }.fold(
                    onSuccess = { payload ->
                        _uiState.update { current ->
                            if (readGeneration != generation || current.saving) {
                                // 代际已被新 load/save 接管或另有保存：旧响应不回填
                                current
                            } else {
                                current.copy(
                                    loading = false,
                                    groups = payload.groups,
                                    stopWords = payload.stopWords,
                                    dirty = false,
                                )
                            }
                        }
                    },
                    onFailure = { e ->
                        _uiState.update { current ->
                            if (readGeneration != generation) current else current.copy(errorMessage = errorText(e))
                        }
                    },
                )
            } finally {
                // busy 按操作 ownership 收尾：仅当代际未被接管时清本 load 的 loading
                _uiState.update { current ->
                    if (readGeneration == generation && current.loading) current.copy(loading = false) else current
                }
            }
        }
    }

    /**
     * 整体保存（恒显式携带 groups+stopWords）。成功即按快照提示（服务端已自动后台
     * 重算）并仅在草稿修订号未再变更时清 dirty；随后静默回读生效形态抹平规范化差异
     * （回读失败不影响保存结果，维持内存态即可——差异只是 trim/去重的展示层细节）。
     */
    fun save() {
        if (_uiState.value.saving || _uiState.value.loading) return
        // 穿过 busy 门后立即固定快照：payload 与期望修订号取此刻草稿，不进协程再读
        // （保存期间的新编辑不得混入已保存计数），随后推进代际作废旧回读
        val snapshot = _uiState.value
        val payload = EditableVocabulary(groups = snapshot.groups, stopWords = snapshot.stopWords)
        val expectedRevision = snapshot.draftRevision
        val expectedGeneration = invalidateReadbacks()
        _uiState.update { it.copy(saving = true, errorMessage = null, noticeMessage = null) }
        viewModelScope.launch {
            try {
                request { repository.save(payload) }.fold(
                    onSuccess = {
                        _uiState.update { current ->
                            if (readGeneration != expectedGeneration) {
                                // 已被新 load/save 接管：状态与 busy 归新操作收尾
                                current
                            } else {
                                current.copy(
                                    // 仅当草稿修订号仍等于保存快照时清 dirty；保存期间的
                                    // 新编辑保持脏态等下一轮保存。提示计数用快照
                                    dirty = current.dirty && current.draftRevision != expectedRevision,
                                    noticeMessage = NOTICE_SAVED.format(payload.groups.size, payload.stopWords.size),
                                )
                            }
                        }
                        reloadAfterSave(expectedRevision, expectedGeneration)
                    },
                    onFailure = { e ->
                        _uiState.update { current ->
                            if (readGeneration != expectedGeneration) {
                                current
                            } else {
                                current.copy(errorMessage = errorText(e))
                            }
                        }
                    },
                )
            } finally {
                // busy 按操作 ownership 收尾：代际 guard 防旧任务清掉新任务的 saving
                _uiState.update { current ->
                    if (readGeneration == expectedGeneration) current.copy(saving = false) else current
                }
            }
        }
    }

    /**
     * 保存成功后的静默回读：只在请求代际、草稿修订号都匹配且无 dirty/loading/saving
     * 时回填（慢回读不得覆盖保存成功后的新草稿）；失败静默保留内存态，不动
     * loading/saving/notice。
     */
    private fun reloadAfterSave(
        expectedRevision: Long,
        expectedGeneration: Long,
    ) {
        readbackJob = viewModelScope.launch {
            request { repository.load() }.onSuccess { payload ->
                _uiState.update { current ->
                    if (
                        readGeneration != expectedGeneration ||
                        current.draftRevision != expectedRevision ||
                        current.dirty || current.saving || current.loading
                    ) {
                        current
                    } else {
                        current.copy(groups = payload.groups, stopWords = payload.stopWords)
                    }
                }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun dismissNotice() {
        _uiState.update { it.copy(noticeMessage = null) }
    }

    // ---- 编辑操作：全部纯内存列表操作 + dirty 置位（保存时整体替换生效） ----
    // 新增/改名先 trim 并拒绝空白（trim 后为空 = no-op 不置脏）；去重交给服务端
    // PUT 规范化（保存后静默回读抹平展示差异）

    fun addGroup(canonical: String) = addMeaningful(canonical) { trimmed ->
        editGroups { groups -> groups + VocabularyGroup(canonical = trimmed) }
    }

    fun removeGroup(index: Int) = editGroups { list ->
        list.removeAtOrNull(index)
    }

    fun renameGroup(index: Int, canonical: String) = addMeaningful(canonical) { trimmed ->
        editGroupAt(index) { group -> group.copy(canonical = trimmed) }
    }

    fun addVariant(groupIndex: Int, variant: String) = addMeaningful(variant) { trimmed ->
        editGroupAt(groupIndex) { group -> group.copy(variants = group.variants + trimmed) }
    }

    fun removeVariant(groupIndex: Int, variantIndex: Int) = editGroupAt(groupIndex) {
        it.copy(variants = it.variants.removeAtOrNull(variantIndex))
    }

    fun addCharacter(groupIndex: Int, canonical: String) = addMeaningful(canonical) { trimmed ->
        editGroupAt(groupIndex) { group ->
            group.copy(characters = group.characters + VocabularyCharacter(canonical = trimmed))
        }
    }

    fun removeCharacter(groupIndex: Int, characterIndex: Int) = editGroupAt(groupIndex) {
        it.copy(characters = it.characters.removeAtOrNull(characterIndex))
    }

    fun renameCharacter(groupIndex: Int, characterIndex: Int, canonical: String) =
        addMeaningful(canonical) { trimmed ->
            editCharacterAt(groupIndex, characterIndex) { character ->
                character.copy(canonical = trimmed)
            }
        }

    fun addAlias(groupIndex: Int, characterIndex: Int, alias: String) = addMeaningful(alias) { trimmed ->
        editCharacterAt(groupIndex, characterIndex) { character ->
            character.copy(aliases = character.aliases + trimmed)
        }
    }

    fun removeAlias(groupIndex: Int, characterIndex: Int, aliasIndex: Int) =
        editCharacterAt(groupIndex, characterIndex) {
            it.copy(aliases = it.aliases.removeAtOrNull(aliasIndex))
        }

    fun addStopWord(word: String) = addMeaningful(word) { trimmed ->
        editStopWords { words -> words + trimmed }
    }

    fun removeStopWord(index: Int) = editStopWords { list ->
        list.removeAtOrNull(index)
    }

    // ---- 内部工具 ----

    /** trim 后非空才执行（回调收 trim 后值）；空白输入静默 no-op（改名同口径） */
    private inline fun addMeaningful(value: String, action: (String) -> Unit) {
        val trimmed = value.trim()
        if (trimmed.isNotEmpty()) action(trimmed)
    }

    /**
     * 所有实际编辑的唯一更新口：内容确有变更才置脏并递增草稿修订号。比较放
     * StateFlow.update 的可重试 lambda 外层结果上（不在其中递增外部计数器）；
     * 越界/无效/no-op 变换内容不变 → 原样返回，不误置脏。
     */
    private fun updateDraft(
        transform: (VocabularyEditUiState) -> VocabularyEditUiState,
    ) {
        _uiState.update { before ->
            val after = transform(before)
            if (after.groups == before.groups && after.stopWords == before.stopWords) {
                before
            } else {
                after.copy(dirty = true, draftRevision = before.draftRevision + 1)
            }
        }
    }

    private fun editGroups(transform: (List<VocabularyGroup>) -> List<VocabularyGroup>) {
        updateDraft { it.copy(groups = transform(it.groups)) }
    }

    private fun editGroupAt(index: Int, transform: (VocabularyGroup) -> VocabularyGroup) {
        updateDraft { st ->
            if (index !in st.groups.indices) {
                st
            } else {
                st.copy(groups = st.groups.toMutableList().also { it[index] = transform(it[index]) })
            }
        }
    }

    private fun editCharacterAt(
        groupIndex: Int,
        characterIndex: Int,
        transform: (VocabularyCharacter) -> VocabularyCharacter,
    ) = editGroupAt(groupIndex) { group ->
        if (characterIndex !in group.characters.indices) {
            group
        } else {
            group.copy(
                characters = group.characters.toMutableList().also {
                    it[characterIndex] = transform(it[characterIndex])
                },
            )
        }
    }

    private fun editStopWords(transform: (List<String>) -> List<String>) {
        updateDraft { it.copy(stopWords = transform(it.stopWords)) }
    }

    /**
     * 按下标移除：越界=原样不动（经 [updateDraft] 内容比对不置脏）；删除后为空在端内
     * 仍是 emptyList，「空子列表→协议可省」的归一由 core:data mapper 在保存边缘决定
     */
    private fun <T> List<T>.removeAtOrNull(index: Int): List<T> =
        if (index !in indices) {
            this
        } else {
            toMutableList().apply { removeAt(index) }
        }

    /**
     * 请求统一包装：取消异常（直接抛出或藏在 Result.failure 里）一律重新上抛，不收作
     * 业务失败；其余异常兜底为 Result.failure。调用侧 try/finally 中传播取消且 busy
     * 按代际 guard 收尾。
     */
    private suspend fun <T> request(
        block: suspend () -> Result<T>,
    ): Result<T> = try {
        block().also { result ->
            val failure = result.exceptionOrNull()
            if (failure is CancellationException) throw failure
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        Result.failure(error)
    }

    /** 推进请求代际并取消在途回读；返回新代际供本次操作的响应校验 */
    private fun invalidateReadbacks(): Long {
        readGeneration += 1
        readbackJob?.cancel()
        readbackJob = null
        return readGeneration
    }

    /** 领域分类 → 中文文案（复用 VocabularySyncError 本地两支，映射只此一处） */
    private fun errorText(e: Throwable?): String = when {
        e is VocabularySyncException -> when (e.error) {
            VocabularySyncError.LocalServerUnavailable -> ERROR_LOAD
            VocabularySyncError.LocalApplyFailed -> ERROR_SAVE
            else -> ERROR_GENERIC
        }
        else -> ERROR_GENERIC
    }

    private companion object {
        const val ERROR_LOAD = "本机词表加载失败，请稍后重试"
        const val ERROR_SAVE = "保存失败，请重试"
        const val ERROR_GENERIC = "操作失败，请重试"

        /** 结果提示（%d=组数、停用词数；「后台重算」与协议 PUT 语义同源） */
        const val NOTICE_SAVED = "已保存：%d 组出处、%d 个停用词，本机将后台重算"
    }
}
