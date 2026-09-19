package media.qimeng.app.core.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import media.qimeng.app.core.data.repository.QueuedUpload
import media.qimeng.app.core.data.repository.UploadRepository
import media.qimeng.app.core.model.DirNode
import media.qimeng.app.core.model.LibraryChoice
import media.qimeng.app.core.model.UploadItem
import media.qimeng.app.core.model.UploadLimits
import media.qimeng.app.core.model.UploadQueueEntry

/** [UploadRepository] 测试替身：记录入队调用、目录树/配置可编程（UploadViewModel 单测用）。 */
class FakeUploadRepository : UploadRepository {

    /** GET /libraries 返回值（可编程） */
    var librariesResult: List<LibraryChoice> = emptyList()

    /** GET /dirs 返回值（可编程） */
    var dirTreeResult: DirNode = DirNode(path = "", fileCount = 0, children = emptyList())

    /** GET /config 返回值（可编程） */
    var limitsResult: UploadLimits = UploadLimits(maxBytesMb = 2048, autoAccept = true)

    /** describe 阶段对每个 uri 字符串给出的元数据（可编程） */
    var describedItem: (String) -> UploadItem = { uri ->
        UploadItem(uri = uri, displayName = "file-${uri.hashCode()}", sizeBytes = 100L)
    }

    /** enqueue 抛错模拟（入队失败场景） */
    var enqueueError: Exception? = null

    /** createDir 抛错模拟（新建目录失败场景） */
    var createDirError: Exception? = null

    /** 入队调用记录（断言入队参数与顺序） */
    data class EnqueueCall(val items: List<UploadItem>, val libraryId: String, val dir: String)

    val enqueueCalls = mutableListOf<EnqueueCall>()

    val createDirCalls = mutableListOf<Pair<String, String>>() // libraryId to path

    /** 队列状态流（测试直接推状态驱动 UI 断言） */
    private val _queue = MutableStateFlow<List<UploadQueueEntry>>(emptyList())
    val queue: StateFlow<List<UploadQueueEntry>> = _queue.asStateFlow()

    fun pushQueue(entries: List<UploadQueueEntry>) {
        _queue.value = entries
    }

    override suspend fun libraries(): List<LibraryChoice> = librariesResult

    override suspend fun dirTree(libraryId: String): DirNode = dirTreeResult

    override suspend fun createDir(libraryId: String, path: String) {
        createDirError?.let { throw it }
        createDirCalls.add(libraryId to path)
    }

    override suspend fun uploadLimits(): UploadLimits = limitsResult

    override suspend fun describe(uris: List<String>): List<UploadItem> = uris.map(describedItem)

    override fun enqueue(
        items: List<UploadItem>,
        libraryId: String,
        dir: String,
    ): List<QueuedUpload> {
        enqueueError?.let { throw it }
        enqueueCalls.add(EnqueueCall(items, libraryId, dir))
        return items.map { QueuedUpload(localId = "local-${it.hashCode()}", displayName = it.displayName) }
    }

    /** 取消调用记录（断言取消透传，批C 任务Q C-2） */
    val cancelCalls = mutableListOf<String>()

    override fun cancel(localId: String) {
        cancelCalls.add(localId)
    }

    override fun queueUpdates(): Flow<List<UploadQueueEntry>> = _queue.asStateFlow()
}
