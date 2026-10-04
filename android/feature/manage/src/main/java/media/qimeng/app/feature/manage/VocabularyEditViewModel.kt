package media.qimeng.app.feature.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.qimeng.app.core.data.repository.VocabularyEditRepository
import media.qimeng.app.core.data.repository.VocabularySyncError
import media.qimeng.app.core.data.repository.VocabularySyncException
import media.qimeng.sdk.models.CustomSourceCharacter
import media.qimeng.sdk.models.CustomSourceGroup
import media.qimeng.sdk.models.CustomSourceGroups

/**
 * 词表维护页 UI 状态（BackupUiState 三件套同款 + 编辑域）。编辑模型直接用 SDK 生成物
 * （协议即编辑形态，无映射层；可省列表以 null 归一为空、保存时空表回 null）。
 */
data class VocabularyEditUiState(
    /** 进页加载中（防重复加载） */
    val loading: Boolean = false,
    /** 保存中（防重复提交，保存按钮禁用） */
    val saving: Boolean = false,
    /** 自定义出处组（内存编辑副本；保存前不代表服务端生效形态） */
    val groups: List<CustomSourceGroup> = emptyList(),
    /** 停用词追加层（内存编辑副本；恒非 null，空数组=清空回内置基线） */
    val stopWords: List<String> = emptyList(),
    /** 有未保存修改（保存按钮/返回拦截的门） */
    val dirty: Boolean = false,
    /** 错误横幅（点击关闭） */
    val errorMessage: String? = null,
    /** 保存结果提示（点击关闭） */
    val noticeMessage: String? = null,
)

/**
 * 词表维护 ViewModel（ADR-0035，2026-10-04）：进页 [load] 拉全量词表进内存编辑，
 * 全部增删改是纯内存列表操作（[dirty] 置位），[save] 把编辑结果整体 PUT（协议整体
 * 替换语义——用户看到什么就保存什么），成功后静默回读抹平服务端规范化差异
 * （trim/去空/去重/canonical 自并入）。业务全在 core:data VocabularyEditRepository
 * （铁律 7）：本 VM 不持有任何地址/鉴权/协议知识。
 */
@HiltViewModel
class VocabularyEditViewModel @Inject constructor(
    private val repository: VocabularyEditRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VocabularyEditUiState())
    val uiState: StateFlow<VocabularyEditUiState> = _uiState.asStateFlow()

    /** 进页加载（LaunchedEffect 触发；保存后静默回读不走此口） */
    fun load() {
        if (_uiState.value.loading || _uiState.value.saving) return
        _uiState.update { it.copy(loading = true, errorMessage = null) }
        viewModelScope.launch {
            val result = runCatching { repository.load() }.getOrElse { Result.failure(it) }
            result.fold(
                onSuccess = { payload ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            groups = payload.groups,
                            stopWords = payload.stopWords.orEmpty(),
                            dirty = false,
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(loading = false, errorMessage = errorText(e)) }
                },
            )
        }
    }

    /**
     * 整体保存（恒显式携带 groups+stopWords）。成功即置干净态并提示（服务端已自动
     * 后台重算），随后静默回读生效形态抹平规范化差异（回读失败不影响保存结果，
     * 维持内存态即可——差异只是 trim/去重的展示层细节）。
     */
    fun save() {
        if (_uiState.value.saving || _uiState.value.loading) return
        _uiState.update { it.copy(saving = true, errorMessage = null, noticeMessage = null) }
        viewModelScope.launch {
            val payload = CustomSourceGroups(
                groups = _uiState.value.groups,
                stopWords = _uiState.value.stopWords,
            )
            val result = runCatching { repository.save(payload) }.getOrElse { Result.failure(it) }
            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            saving = false,
                            dirty = false,
                            noticeMessage = NOTICE_SAVED.format(it.groups.size, it.stopWords.size),
                        )
                    }
                    reloadAfterSave()
                },
                onFailure = { e ->
                    _uiState.update { it.copy(saving = false, errorMessage = errorText(e)) }
                },
            )
        }
    }

    /** 保存成功后的静默回读：只覆盖列表，不动 loading/saving/notice（失败静默保留内存态） */
    private fun reloadAfterSave() {
        viewModelScope.launch {
            runCatching { repository.load() }.getOrNull()?.onSuccess { payload ->
                _uiState.update {
                    if (it.saving) {
                        it
                    } else {
                        it.copy(groups = payload.groups, stopWords = payload.stopWords.orEmpty())
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
        editGroups { groups -> groups + CustomSourceGroup(canonical = trimmed) }
    }

    fun removeGroup(index: Int) = editGroups { list ->
        list.toMutableList().also { if (index in it.indices) it.removeAt(index) }
    }

    fun renameGroup(index: Int, canonical: String) = addMeaningful(canonical) { trimmed ->
        editGroupAt(index) { group -> group.copy(canonical = trimmed) }
    }

    fun addVariant(groupIndex: Int, variant: String) = addMeaningful(variant) { trimmed ->
        editGroupAt(groupIndex) { group -> group.copy(variants = group.variants.orEmpty() + trimmed) }
    }

    fun removeVariant(groupIndex: Int, variantIndex: Int) = editGroupAt(groupIndex) {
        it.copy(variants = it.variants.orEmpty().removeAtOrNull(variantIndex))
    }

    fun addCharacter(groupIndex: Int, canonical: String) = addMeaningful(canonical) { trimmed ->
        editGroupAt(groupIndex) { group ->
            group.copy(characters = group.characters.orEmpty() + CustomSourceCharacter(canonical = trimmed))
        }
    }

    fun removeCharacter(groupIndex: Int, characterIndex: Int) = editGroupAt(groupIndex) {
        it.copy(characters = it.characters.orEmpty().removeAtOrNull(characterIndex))
    }

    fun renameCharacter(groupIndex: Int, characterIndex: Int, canonical: String) =
        addMeaningful(canonical) { trimmed ->
            editCharacterAt(groupIndex, characterIndex) { character ->
                character.copy(canonical = trimmed)
            }
        }

    fun addAlias(groupIndex: Int, characterIndex: Int, alias: String) = addMeaningful(alias) { trimmed ->
        editCharacterAt(groupIndex, characterIndex) { character ->
            character.copy(aliases = character.aliases.orEmpty() + trimmed)
        }
    }

    fun removeAlias(groupIndex: Int, characterIndex: Int, aliasIndex: Int) =
        editCharacterAt(groupIndex, characterIndex) {
            it.copy(aliases = it.aliases.orEmpty().removeAtOrNull(aliasIndex))
        }

    fun addStopWord(word: String) = addMeaningful(word) { trimmed ->
        editStopWords { words -> words + trimmed }
    }

    fun removeStopWord(index: Int) = editStopWords { list ->
        list.toMutableList().also { if (index in it.indices) it.removeAt(index) }
    }

    // ---- 内部工具 ----

    /** trim 后非空才执行（回调收 trim 后值）；空白输入静默 no-op（改名同口径） */
    private inline fun addMeaningful(value: String, action: (String) -> Unit) {
        val trimmed = value.trim()
        if (trimmed.isNotEmpty()) action(trimmed)
    }

    private fun editGroups(transform: (List<CustomSourceGroup>) -> List<CustomSourceGroup>) {
        _uiState.update { it.copy(groups = transform(it.groups), dirty = true) }
    }

    private fun editGroupAt(index: Int, transform: (CustomSourceGroup) -> CustomSourceGroup) {
        _uiState.update { st ->
            if (index !in st.groups.indices) {
                st
            } else {
                st.copy(
                    groups = st.groups.toMutableList().also { it[index] = transform(it[index]) },
                    dirty = true,
                )
            }
        }
    }

    private fun editCharacterAt(
        groupIndex: Int,
        characterIndex: Int,
        transform: (CustomSourceCharacter) -> CustomSourceCharacter,
    ) = editGroupAt(groupIndex) { group ->
        if (characterIndex !in group.characters.orEmpty().indices) {
            group
        } else {
            group.copy(
                characters = group.characters.orEmpty().toMutableList().also {
                    it[characterIndex] = transform(it[characterIndex])
                },
            )
        }
    }

    private fun editStopWords(transform: (List<String>) -> List<String>) {
        _uiState.update { it.copy(stopWords = transform(it.stopWords), dirty = true) }
    }

    /** 按下标移除的可选形态：越界=原样不动；结果为空表回 null（协议「可省」字段归一） */
    private fun <T> List<T>.removeAtOrNull(index: Int): List<T>? =
        if (index !in indices) {
            ifEmpty { null }
        } else {
            toMutableList().apply { removeAt(index) }.ifEmpty { null }
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
