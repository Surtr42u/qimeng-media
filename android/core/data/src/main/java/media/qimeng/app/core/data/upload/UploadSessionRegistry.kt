package media.qimeng.app.core.data.upload

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import media.qimeng.app.core.data.di.ClientPrefsDataStore
import media.qimeng.app.core.network.ServerRequestBinding

/**
 * 分片会话把手持久化，进程重建后先探测服务端权威 offset，不存本地断点或 token。
 * localId/目标地址同入队载荷绑定；同址换实例仍依靠服务端 404 重建，无法证明实例身份。
 * 只在会话创建成功后、发送字节前提交把手；写失败不发送新会话字节。
 */
@Singleton
class UploadSessionRegistry @Inject constructor(
    @ClientPrefsDataStore private val dataStore: DataStore<Preferences>,
) {
    suspend fun lookup(localId: String, target: String): String? {
        val prefs = dataStore.data.first()
        val storedTarget = prefs[targetKey(localId)] ?: return null
        if (!ServerRequestBinding(target).matches(storedTarget)) return null
        return prefs[sessionKey(localId)]
    }

    suspend fun remember(localId: String, target: String, sessionId: String) {
        dataStore.edit { prefs ->
            prefs[targetKey(localId)] = target
            prefs[sessionKey(localId)] = sessionId
        }
    }

    suspend fun forget(localId: String) {
        dataStore.edit { prefs ->
            prefs.remove(targetKey(localId))
            prefs.remove(sessionKey(localId))
        }
    }

    private fun sessionKey(id: String) = stringPreferencesKey("upload_session_$id")
    private fun targetKey(id: String) = stringPreferencesKey("upload_session_target_$id")
}
