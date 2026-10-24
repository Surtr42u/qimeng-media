package media.qimeng.app.core.data.upload

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import media.qimeng.app.core.data.di.ClientPrefsDataStore

/**
 * 持久协作式取消：提交 DataStore 后才发布已取消，Worker 发送前 load。
 * 不使用 WorkManager.cancelWorkById，避免级联取消串行链上的后续任务。
 * Worker 返回 success+取消标记；仅观察到 WorkManager 已持久终态后才能清标记，
 * 否则在返回 Result 与引擎提交之间进程死亡，仍会恢复已取消的任务。
 */
@Singleton
class UploadCancelRegistry @Inject constructor(
    @ClientPrefsDataStore private val dataStore: DataStore<Preferences>,
) {
    private val mutex = Mutex()
    @Volatile private var cancelled: Set<String> = emptySet()
    private var loaded = false
    private val _updates = MutableStateFlow(0)
    val updates: StateFlow<Int> = _updates.asStateFlow()

    suspend fun load() = mutex.withLock {
        if (!loaded) publish(dataStore.data.first()[CANCELLED].orEmpty())
    }

    suspend fun cancel(localId: String) = update { it + localId }

    fun isCancelled(localId: String): Boolean = localId in cancelled

    /** 仅由已经观察到 WorkInfo.isFinished 的队列投影调用。 */
    suspend fun forgetCompleted(localIds: Set<String>) {
        if (localIds.any { it in cancelled }) update { it - localIds }
    }

    private suspend fun update(transform: (Set<String>) -> Set<String>) = mutex.withLock {
        val committed = dataStore.edit { prefs -> prefs[CANCELLED] = transform(prefs[CANCELLED].orEmpty()) }
        publish(committed[CANCELLED].orEmpty())
    }

    private fun publish(value: Set<String>) {
        if (!loaded || cancelled != value) {
            cancelled = value.toSet()
            loaded = true
            _updates.value += 1
        }
    }

    private companion object {
        val CANCELLED = stringSetPreferencesKey("cancelled_upload_tasks")
    }
}
