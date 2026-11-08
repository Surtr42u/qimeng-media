package media.qimeng.app.core.data.device

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeviceApplySettingsRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var scope: CoroutineScope? = null

    @After
    fun tearDown() {
        scope?.cancel()
        scope = null
    }

    private fun newRepository(): DataStoreDeviceApplySettingsRepository {
        val coroutineScope = CoroutineScope(Dispatchers.IO + Job())
        scope = coroutineScope
        val produceFile: () -> File = { File(tmp.root, "device-apply.preferences_pb") }
        val dataStore = PreferenceDataStoreFactory.create(scope = coroutineScope, produceFile = produceFile)
        return DataStoreDeviceApplySettingsRepository(dataStore)
    }

    @Test
    fun `保存trim首尾空白并在无令牌时强制关闭后台领取`() = runBlocking {
        val repository = newRepository()
        repository.save("  phone-main  ", "   ", backgroundEnabled = true)
        val saved = repository.settings.first()
        assertEquals("phone-main", saved.targetDeviceId)
        assertEquals("", saved.deviceToken)
        assertFalse(saved.replaceAuthorProfilesOnApply)
        assertFalse(saved.backgroundEnabled)
        assertFalse(saved.isConfigured)
    }

    @Test
    fun `完整配置时后台开关保持并记录最近运行`() = runBlocking {
        val repository = newRepository()
        repository.save(
            targetDeviceId = "phone-main",
            deviceToken = " device-token ",
            backgroundEnabled = true,
            deviceServiceUrl = " 192.0.2.10:8420/ ",
            replaceAuthorProfilesOnApply = true,
        )
        var saved = repository.settings.first()
        assertEquals("device-token", saved.deviceToken)
        assertEquals("http://192.0.2.10:8420", saved.deviceServiceUrl)
        assertTrue(saved.replaceAuthorProfilesOnApply)
        assertTrue(saved.backgroundEnabled)
        assertTrue(saved.isConfigured)

        repository.recordLastRun("已处理 1 个结果包", atMillis = 1234L)
        saved = repository.settings.first()
        assertEquals("已处理 1 个结果包", saved.lastMessage)
        assertEquals(1234L, saved.lastRunAtMillis)
    }

    @Test
    fun `领取服务端地址规范化且非法值保存为空`() = runBlocking {
        val repository = newRepository()
        repository.save(
            targetDeviceId = "phone-main",
            deviceToken = "device-token",
            backgroundEnabled = false,
            deviceServiceUrl = "https://nas.example:8443/",
        )
        assertEquals("https://nas.example:8443", repository.settings.first().deviceServiceUrl)

        repository.save(
            targetDeviceId = "phone-main",
            deviceToken = "device-token",
            backgroundEnabled = false,
            deviceServiceUrl = "ftp://192.0.2.10:8420",
        )
        assertEquals("", repository.settings.first().deviceServiceUrl)
    }

    @Test
    fun `非法标签不能开启后台领取`() = runBlocking {
        val repository = newRepository()
        repository.save("手机", "device-token", backgroundEnabled = true)
        val saved = repository.settings.first()
        assertEquals("手机", saved.targetDeviceId)
        assertFalse(saved.backgroundEnabled)
        assertFalse(saved.isConfigured)
    }
}
