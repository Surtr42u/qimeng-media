package media.qimeng.app.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import media.qimeng.app.core.data.repository.CreatorProfileRepository

data class DetailAuthorAvatars(val scope: String = "", val server: String = "", val ids: Set<String> = emptySet(), val loaded: Map<String, ByteArray?> = emptyMap())

/** Only the currently opened sheet is retained. Cancellation and scope fence late IO. */
@HiltViewModel
class DetailAuthorAvatarViewModel @Inject constructor(private val profiles: CreatorProfileRepository) : ViewModel() {
    private val session = UUID.randomUUID().toString()
    private val _state = MutableStateFlow(DetailAuthorAvatars())
    val state = _state.asStateFlow()
    private var request: Job? = null
    private var generation = 0L
    private val permits = Semaphore(2)

    fun currentServerScope(): String = profiles.avatarScope()

    fun load(ids: List<String>) {
        request?.cancel()
        val token = ++generation
        val server = profiles.avatarScope()
        val scope = "$session|$server|$token"
        _state.value = DetailAuthorAvatars(scope, server, ids.toSet())
        request = viewModelScope.launch {
            ids.distinct().map { id -> async {
                permits.withPermit {
                    val bytes = try { profiles.avatar(id) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                    if (token == generation && profiles.avatarScope() == server) {
                        _state.value = _state.value.copy(loaded = _state.value.loaded + (id to bytes))
                    }
                }
            } }.awaitAll()
        }
    }
}
