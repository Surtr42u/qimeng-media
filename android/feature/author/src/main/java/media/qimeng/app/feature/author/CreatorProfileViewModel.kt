package media.qimeng.app.feature.author

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.qimeng.app.core.data.repository.AuthorRepository
import media.qimeng.app.core.data.repository.CreatorProfileRepository
import media.qimeng.app.core.model.AuthorSummary
import media.qimeng.app.core.model.CreatorProfile
import media.qimeng.app.core.model.CreatorProfileDraft
import media.qimeng.app.core.model.toDraft

enum class CreatorProfileSection(val title: String) {
    X("X资料"), BASIC("基础资料"), SOURCES("账号来源"), SUBSCRIPTION("作品订阅"), REVIEW("候选核对"),
}

data class CreatorProfileUiState(
    val profile: CreatorProfile? = null,
    val author: AuthorSummary? = null,
    val avatar: ByteArray? = null,
    val draft: CreatorProfileDraft? = null,
    val editing: Boolean = false,
    val editingSection: CreatorProfileSection = CreatorProfileSection.BASIC,
    val busy: Boolean = false,
    val followBusy: Boolean = false,
    val error: String? = null,
    val avatarError: Boolean = false,
)

/** 附属档案的请求与草稿独立于作品分页，错误不遮住已有作品。 */
@HiltViewModel
class CreatorProfileViewModel @Inject constructor(
    private val repository: CreatorProfileRepository,
    private val authors: AuthorRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val authorId: String = savedStateHandle[AuthorCollectionRoutes.KEY_AUTHOR_ID] ?: ""
    private val _state = MutableStateFlow(CreatorProfileUiState())
    val state = _state.asStateFlow()
    private var request: Job? = null
    private var generation = 0L
    private var authorGeneration = 0L
    private var authorRequest: Job? = null

    init { reload() }

    fun reload() {
        if (authorId.isBlank() || _state.value.editing || _state.value.busy || _state.value.followBusy) return
        runRequest { repository.profile(authorId) }
        authorRequest?.cancel()
        val current = ++authorGeneration
        authorRequest = viewModelScope.launch {
            try {
                val author = authors.authors().find { row -> row.id == authorId }
                if (current == authorGeneration) _state.update { it.copy(author = author) }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (current == authorGeneration) _state.update { it.copy(error = "作者状态读取失败，可重试") } }
        }
    }

    fun edit(section: CreatorProfileSection = CreatorProfileSection.BASIC) {
        if (_state.value.busy || _state.value.editing) return
        _state.value.profile?.let { profile ->
            _state.update { it.copy(editing = true, editingSection = section, draft = profile.toDraft(), error = null) }
        }
    }
    fun cancelEdit() { if (!_state.value.busy) _state.update { it.copy(editing = false, draft = null, error = null) } }
    fun changeDraft(transform: (CreatorProfileDraft) -> CreatorProfileDraft) {
        if (!_state.value.busy) _state.value.draft?.let { draft -> _state.update { it.copy(draft = transform(draft)) } }
    }
    fun useCandidate() {
        if (_state.value.busy || _state.value.editing) return
        val profile = _state.value.profile ?: return
        val candidate = profile.candidate
        _state.update { it.copy(editing = true, editingSection = CreatorProfileSection.REVIEW, error = null, draft = profile.toDraft().copy(
            nickname = candidate.nickname.ifBlank { profile.nickname },
            biography = candidate.biography.ifBlank { profile.biography },
            avatarUrl = candidate.avatarUrl.ifBlank { profile.avatarUrl },
            homepageUrl = candidate.homepageUrl.ifBlank { profile.homepageUrl },
            evidenceUrls = (profile.evidenceUrls + candidate.evidenceUrls).distinct(),
        )) }
    }
    fun save() {
        val draft = _state.value.draft ?: return
        if (!_state.value.busy) runRequest { repository.save(authorId, draft) }
    }
    fun refresh() {
        if (!_state.value.busy && !_state.value.editing) runRequest { repository.refresh(authorId) }
    }
    fun toggleFollow() {
        val author = _state.value.author ?: return
        if (_state.value.followBusy) return
        authorRequest?.cancel()
        val current = ++authorGeneration
        _state.update { it.copy(followBusy = true, error = null) }
        viewModelScope.launch {
            try {
                authors.setFollowed(authorId, !author.followed)
                if (current == authorGeneration) _state.update { it.copy(author = author.copy(followed = !author.followed)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (current == authorGeneration) _state.update { it.copy(error = "关注修改失败，请重试") } }
            finally { if (current == authorGeneration) _state.update { it.copy(followBusy = false) } }
        }
    }
    private fun runRequest(block: suspend () -> CreatorProfile) {
        request?.cancel()
        val current = ++generation
        _state.update { it.copy(busy = true, error = null) }
        request = viewModelScope.launch {
            try {
                val profile = block()
                if (current != generation) return@launch
                _state.update { it.copy(profile = profile, busy = false, editing = false, draft = null, avatar = null, avatarError = false) }
                if (profile.avatarUrl.isNotBlank()) {
                    try {
                        val avatar = repository.avatar(authorId)
                        if (current == generation) _state.update { it.copy(avatar = avatar) }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { if (current == generation) _state.update { it.copy(avatarError = true) } }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (current == generation) _state.update { it.copy(busy = false, error = "资料操作失败。请检查连接与服务端版本；保存冲突时保留草稿，取消编辑后重新读取。") }
            }
            finally { if (current == generation) _state.update { it.copy(busy = false) } }
        }
    }
}
