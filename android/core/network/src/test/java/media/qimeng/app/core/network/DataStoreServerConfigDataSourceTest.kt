package media.qimeng.app.core.network

import androidx.datastore.core.DataStore
import androidx.datastore.core.InterProcessCoordinator
import androidx.datastore.core.ReadScope
import androidx.datastore.core.Serializer
import androidx.datastore.core.Storage
import androidx.datastore.core.StorageConnection
import androidx.datastore.core.WriteScope
import androidx.datastore.core.createSingleProcessCoordinator
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * token 存取单测（DataStore 真实读写，临时目录文件）：
 * 锁定「写入→流发射→清除→null」与「清 token 保留地址（登录页记忆上次）」两条语义。
 *
 * ## 为什么不用 `PreferenceDataStoreFactory.create(scope, produceFile)` 而注入 [TestFileStorage]
 * （2026-09-15 Windows 预存红根治，行级结论见 [TestFileStorage] 注释）
 *
 * datastore 1.2.1 的真 `FileStorage` 在 Windows JVM 上，同文件**第二次及以后**的写入
 * （DataStoreImpl 的 read-modify-write：读旧值→写 `.tmp`→rename 覆盖已存在目标）会**确定性**
 * 抛 "Unable to rename … multiple instances of DataStore" IOException。本机诊断（临时探针，
 * 已清理）证据链：
 * - 失败可持续 ≥500ms（10 次 × 50ms 重试全败）→ 持久性冲突，不是调度瞬态；
 * - 换 StandardTestDispatcher / Dispatchers.IO / runBlocking / 绑定 testScheduler 全部复现
 *   → 与测试调度器无关，不是 Unconfined 交错问题；
 * - 真存储直连（不经 DataStoreImpl）、以及按 1.2.1 源码逐字复刻的存储走完整 DataStoreImpl
 *   链路，均稳定全绿 → 缺陷仅在「真 artifact 存储 × DataStoreImpl」组合；
 * - 生产 DI 已核实 `server_config` 文件 `@Singleton` 单实例（NetworkModule
 *   .provideServerConfigDataStore），Android/ART 运行时不表现该 JVM 侧行为 → 生产无真缺陷，
 *   属上游已知 Windows JVM 缺陷族（issuetracker 203087070 / 194301881 / 185414033）。
 *
 * [TestFileStorage] 按 1.2.1 源码逐字复刻（文件格式走公开的 [PreferencesSerializer]，与
 * 生产同一 protobuf 格式），仅替换实现载体以规避真 artifact 的缺陷路径；持久化语义由
 * 「冷启动预热」用例实际验证（第二个 dataSource 从同一文件恢复 token）。
 *
 * ## scope 生命周期
 * 各用例的 DataStore scope 统一登记并在 @After cancel：datastore 官方约定「scope 存活期 =
 * DataStore 活跃期」，不 cancel 会滞留文件句柄并占住存储层的活跃文件注册表。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreServerConfigDataSourceTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    /** 登记每个用例创建的 DataStore scope，@After 统一 cancel（见类注释「scope 生命周期」）。 */
    private val scopes = mutableListOf<CoroutineScope>()

    private fun newDataStore(testDir: File): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            storage = TestFileStorage(PreferencesJavaIoSerializer) { File(testDir, "server_config.preferences_pb") },
            scope = CoroutineScope(UnconfinedTestDispatcher() + Job()).also { scopes += it },
        )

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        scopes.clear()
    }

    @Test
    fun `token 写入后流发射且内存缓存同步`() = runTest {
        val dataSource = DataStoreServerConfigDataSource(
            dataStore = newDataStore(tmpFolder.newFolder()),
            appScope = CoroutineScope(UnconfinedTestDispatcher()),
        )

        dataSource.updateToken("token-abc")

        assertEquals("token-abc", dataSource.token.first())
        assertEquals("token-abc", dataSource.currentToken())
    }

    @Test
    fun `清除 token 后流为 null 且地址保留`() = runTest {
        val dataSource = DataStoreServerConfigDataSource(
            dataStore = newDataStore(tmpFolder.newFolder()),
            appScope = CoroutineScope(UnconfinedTestDispatcher()),
        )
        dataSource.updateServerUrl("http://10.0.2.2:8420")
        dataSource.updateToken("token-abc")

        dataSource.clearToken()

        assertNull(dataSource.token.first())
        assertNull(dataSource.currentToken())
        // 「记忆上次」语义：退出登录只清 token，地址留给登录页回填
        assertEquals("http://10.0.2.2:8420", dataSource.serverUrl.first())
    }

    @Test
    fun `冷启动预热从持久化文件恢复 token`() = runTest {
        val dir = tmpFolder.newFolder()
        val dataStore = newDataStore(dir)
        val first = DataStoreServerConfigDataSource(dataStore, CoroutineScope(UnconfinedTestDispatcher()))
        first.updateToken("persisted-token")
        first.updateServerUrl("http://192.0.2.10:8420")

        // 同一文件新实例 = 模拟进程重启；Unconfined 调度让 init 预热立即执行
        val rebooted = DataStoreServerConfigDataSource(dataStore, CoroutineScope(UnconfinedTestDispatcher()))

        assertEquals("persisted-token", rebooted.currentToken())
        assertEquals("http://192.0.2.10:8420", rebooted.serverUrl.first())
    }

    // ---------- 登录记忆槽（任务S 批S3 服务器地址固化） ----------

    @Test
    fun `登录记忆按端型分流_本机模式不覆盖NAS记忆槽`() = runTest {
        val dataSource = DataStoreServerConfigDataSource(
            dataStore = newDataStore(tmpFolder.newFolder()),
            appScope = CoroutineScope(UnconfinedTestDispatcher()),
        )

        // 先后两次「成功登录」：NAS 地址 → 本机模式地址（真实顺序=先 NAS 后切本机）
        dataSource.rememberLoginAddress("http://192.0.2.10:8420")
        dataSource.rememberLoginAddress(ServerAddress.LOCAL_MODE_PRESET)

        // 各归各槽：NAS 槽不被本机模式登录覆盖（切回 NAS 免重输的核心保证）
        assertEquals("http://192.0.2.10:8420", dataSource.rememberedNasUrl.first())
        assertEquals(ServerAddress.LOCAL_MODE_PRESET, dataSource.rememberedLocalUrl.first())
        // 记忆写入不碰主地址键——「当前连着谁」与「记得哪些地址」是两份独立状态
        assertEquals("", dataSource.serverUrl.first())
    }

    @Test
    fun `登录记忆槽随持久化文件冷启动恢复`() = runTest {
        val dir = tmpFolder.newFolder()
        val dataStore = newDataStore(dir)
        val first = DataStoreServerConfigDataSource(dataStore, CoroutineScope(UnconfinedTestDispatcher()))
        first.rememberLoginAddress("http://192.0.2.10:8420")
        first.rememberLoginAddress(ServerAddress.LOCAL_MODE_PRESET)

        // 同一文件新实例 = 模拟进程重启：两个记忆槽都要活过重启
        val rebooted = DataStoreServerConfigDataSource(dataStore, CoroutineScope(UnconfinedTestDispatcher()))

        assertEquals("http://192.0.2.10:8420", rebooted.rememberedNasUrl.first())
        assertEquals(ServerAddress.LOCAL_MODE_PRESET, rebooted.rememberedLocalUrl.first())
    }
}

/**
 * datastore 1.2.1 `FileStorage`/`FileStorageConnection` 的测试内逐字复刻（为什么需要见
 * [DataStoreServerConfigDataSourceTest] 类注释）。语义与真实现一致：
 * - createConnection 时 canonical 化并绑定单进程 coordinator（同进程内互斥）；
 * - 写事务 = 写 `<file>.tmp`（fsync）→ `Files.move(REPLACE_EXISTING)` 换名覆盖；
 * - 读 = FileInputStream（文件不存在回退默认值）；
 * - transactionMutex 串行化写事务、读事务尽力互斥（与真实现同构）。
 */
private class TestFileStorage<T>(
    private val serializer: Serializer<T>,
    private val produceFile: () -> File,
) : Storage<T> {

    override fun createConnection(): StorageConnection<T> {
        val file = produceFile().canonicalFile
        return TestFileStorageConnection(file, serializer, createSingleProcessCoordinator(file)) { }
    }
}

private class TestFileStorageConnection<T>(
    private val file: File,
    private val serializer: Serializer<T>,
    override val coordinator: InterProcessCoordinator,
    private val onClose: () -> Unit,
) : StorageConnection<T> {

    private val closed = AtomicBoolean(false)

    // TODO(b/233402915) 同真实现：只支持单读者
    private val transactionMutex = Mutex()

    override suspend fun <R> readScope(block: suspend ReadScope<T>.(locked: Boolean) -> R): R {
        checkNotClosed()
        val lock = transactionMutex.tryLock()
        try {
            return TestFileScope(file, serializer).useScoped { block(it, lock) }
        } finally {
            if (lock) transactionMutex.unlock()
        }
    }

    override suspend fun writeScope(block: suspend WriteScope<T>.() -> Unit) {
        checkNotClosed()
        file.parentFile?.mkdirs()
        transactionMutex.withLock {
            val scratchFile = File(file.absolutePath + ".tmp")
            try {
                TestFileWriteScope(scratchFile, serializer).useScoped { block(it) }
                if (scratchFile.exists() && !scratchFile.atomicMoveTo(file)) {
                    throw IOException("Unable to rename $scratchFile to $file (test storage)")
                }
            } catch (ex: IOException) {
                if (scratchFile.exists()) {
                    scratchFile.delete() // 同真实现：吞掉清理失败
                }
                throw ex
            }
        }
    }

    override fun close() {
        closed.set(true)
        onClose()
    }

    private fun checkNotClosed() {
        check(!closed.get()) { "StorageConnection has already been disposed." }
    }
}

private open class TestFileScope<T>(
    protected val file: File,
    protected val serializer: Serializer<T>,
) : ReadScope<T> {

    private val closed = AtomicBoolean(false)

    override suspend fun readData(): T {
        checkNotClosed()
        return try {
            FileInputStream(file).use { stream -> serializer.readFrom(stream) }
        } catch (ex: FileNotFoundException) {
            if (file.exists()) {
                FileInputStream(file).use { stream -> serializer.readFrom(stream) }
            } else {
                serializer.defaultValue
            }
        }
    }

    override fun close() {
        closed.set(true)
    }

    protected fun checkNotClosed() {
        check(!closed.get()) { "This scope has already been closed." }
    }
}

private class TestFileWriteScope<T>(file: File, serializer: Serializer<T>) :
    TestFileScope<T>(file, serializer), WriteScope<T> {

    private val closed = AtomicBoolean(false)

    override suspend fun readData(): T {
        check(!closed.get()) { "This scope has already been closed." }
        return super.readData()
    }

    override suspend fun writeData(value: T) {
        check(!closed.get()) { "This scope has already been closed." }
        try {
            val fos = FileOutputStream(file)
            fos.use { stream ->
                serializer.writeTo(value, UncloseableOutputStream(stream))
                stream.fd.sync()
            }
        } catch (e: FileNotFoundException) {
            throw e
        }
    }

    override fun close() {
        closed.set(true)
    }
}

/** datastore 同名工具的等价物：防止序列化器误关底层流（close 交给 FileOutputStream.use）。 */
private class UncloseableOutputStream(private val stream: OutputStream) : OutputStream() {
    override fun write(b: Int) = stream.write(b)
    override fun write(b: ByteArray) = stream.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = stream.write(b, off, len)
    override fun flush() = stream.flush()
    override fun close() = stream.flush()
}

private inline fun <T : androidx.datastore.core.Closeable, R> T.useScoped(block: (T) -> R): R = try {
    block(this)
} finally {
    close()
}

/** 与真实现同构：Files.move 换名（异常吞掉返回 false，由调用方包装成明确错误）。 */
private fun File.atomicMoveTo(toFile: File): Boolean = try {
    Files.move(toPath(), toFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    true
} catch (exception: IOException) {
    false
}

/**
 * [PreferencesSerializer]（okio，公开）→ `Serializer<Preferences>`（java.io，真实现为 internal）
 * 的桥接：字节格式完全一致（同一 protobuf 序列化器），仅流类型转换。
 */
private object PreferencesJavaIoSerializer : Serializer<Preferences> {
    override val defaultValue: Preferences = PreferencesSerializer.defaultValue

    override suspend fun readFrom(input: InputStream): Preferences =
        PreferencesSerializer.readFrom(Buffer().apply { writeAll(input.source()) })

    override suspend fun writeTo(t: Preferences, output: OutputStream) {
        val buffer = Buffer()
        PreferencesSerializer.writeTo(t, buffer)
        output.write(buffer.readByteArray())
    }
}
