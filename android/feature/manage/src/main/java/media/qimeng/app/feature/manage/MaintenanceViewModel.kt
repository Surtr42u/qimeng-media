package media.qimeng.app.feature.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import media.qimeng.app.core.data.authorimport.AuthorTxtDocument
import media.qimeng.app.core.data.backup.MaintenanceFolderRepository
import media.qimeng.app.core.data.repository.InboxDirEntry
import media.qimeng.app.core.data.repository.StagingRepository

data class MaintenanceUiState(
    val root: String? = null,
    val archivePath: String? = null,
    val storageRoot: String = "",
    val browsingPath: String = "",
    val entries: List<InboxDirEntry> = emptyList(),
    val browsing: Boolean = false,
    val busy: Boolean = false,
    val granted: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val documents: List<String> = emptyList(),
    val pendingDocument: AuthorTxtDocument? = null,
)

@HiltViewModel
class MaintenanceViewModel @Inject constructor(
    private val folders: MaintenanceFolderRepository,
    private val staging: StagingRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(MaintenanceUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch { folders.root.collect { path -> _state.update { it.copy(root = path) } } }
        viewModelScope.launch { staging.archivePath.collect { path -> _state.update { it.copy(archivePath = path) } } }
        refresh()
    }

    fun refresh() = operation {
        val selected = folders.root.first()
        _state.update { it.copy(root = selected) }
        val granted = staging.hasAllFilesAccess()
        val storage = staging.storageRoot()
        _state.update { it.copy(granted = granted, storageRoot = storage) }
        if (granted && _state.value.browsing) loadDirectory(_state.value.browsingPath.ifBlank { storage })
        if (granted && _state.value.root != null) loadDocuments()
    }

    fun browse() = operation {
        check(staging.hasAllFilesAccess()) { "请先授予所有文件访问权限" }
        val storage = staging.storageRoot()
        _state.update { it.copy(browsing = true, storageRoot = storage) }
        loadDirectory(_state.value.browsingPath.ifBlank { _state.value.root ?: storage })
    }

    fun enter(path: String) = operation { loadDirectory(path) }
    fun goUp() {
        val current = _state.value
        val parent = current.browsingPath.substringBeforeLast('/')
        enter(if (parent == current.storageRoot || parent.startsWith(current.storageRoot + "/")) parent else current.storageRoot)
    }
    fun closeBrowser() { if (!_state.value.busy) _state.update { it.copy(browsing = false) } }

    fun selectCurrent() = operation {
        folders.select(_state.value.browsingPath)
        _state.update { it.copy(browsing = false) }
        // An externally replaced pending import is protected by its checksum.
        folders.export(automatic = true)
        loadDocuments()
        _state.update { it.copy(message = "维护目录已设置，已生成备份和作者资料，最多保留三代") }
    }

    fun export() = operation {
        folders.export()
        loadDocuments()
        _state.update { it.copy(message = "已更新最新备份和作者资料，最多保留三代") }
    }

    fun prepareDocument(filename: String) = operation {
        val document = folders.authorDocuments().firstOrNull { it.filename == filename }
            ?: error("作者TXT已变更或被移除，请刷新目录")
        _state.update { it.copy(pendingDocument = document) }
    }

    fun dismissDocument() { _state.update { it.copy(pendingDocument = null) } }

    private suspend fun loadDocuments() {
        val documents = folders.authorDocuments()
        _state.update { it.copy(documents = documents.map { document -> document.filename }) }
    }

    private suspend fun loadDirectory(path: String) {
        val storage = _state.value.storageRoot
        require(path == storage || path.startsWith(storage + "/")) { "目录超出共享存储" }
        val entries = staging.listDirectories(path)
        _state.update { it.copy(browsingPath = path, entries = entries) }
    }

    private fun operation(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { _state.update { it.copy(error = failure.message ?: "维护操作失败（${failure.javaClass.simpleName}），请重试") } }
            finally { _state.update { it.copy(busy = false) } }
        }
    }
}
