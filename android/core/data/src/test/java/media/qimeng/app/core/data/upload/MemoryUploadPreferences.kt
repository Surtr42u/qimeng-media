package media.qimeng.app.core.data.upload

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 纯队列投影测试的存储端口；重启/磁盘提交另用真实 DataStore 验证。 */
internal class MemoryUploadPreferences : DataStore<Preferences> {
    private val mutex = Mutex()
    override val data = MutableStateFlow(emptyPreferences())
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
        transform(data.value).also { data.value = it }
    }
}
