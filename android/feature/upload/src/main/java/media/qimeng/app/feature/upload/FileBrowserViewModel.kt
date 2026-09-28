package media.qimeng.app.feature.upload

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import media.qimeng.app.core.data.repository.BrowserFileEntry
import media.qimeng.app.core.data.repository.InboxDirEntry
import media.qimeng.app.core.data.repository.InboxFileStore

/**
 * 浏览文件弹层 UI 状态（2026-09-28「浏览文件」入口）：目录导航 + 当前目录文件多选。
 * 状态口径对照两个既有先例——目录栈形态照收件箱设置页（InboxSettingsViewModel：跨目录
 * 导航是业务状态，进 VM、UI 零逻辑）；已选集合照相册选择器（MediaPickerViewModel：
 * LinkedHashMap 语义 path → 条目，跨目录保留且保持用户选择序——允许先在一个目录勾几个
 * 再去别的目录继续勾）。
 */
data class FileBrowserUiState(
    /** 目录/文件装载进行中 */
    val loading: Boolean = true,
    /** 浏览起点（主存储根，/storage/emulated/0） */
    val storageRoot: String = "",
    /** 浏览中的目录 */
    val browsingPath: String = "",
    /** 浏览目录下的一级子目录（含点前缀隐藏目录，名称升序） */
    val dirEntries: List<InboxDirEntry> = emptyList(),
    /** 浏览目录下的一级媒体文件（扩展名白名单已过滤，名称升序） */
    val files: List<BrowserFileEntry> = emptyList(),
    /** 已选文件（path → 条目；跨目录保留、保持选择序） */
    val selected: Map<String, BrowserFileEntry> = emptyMap(),
    /** 目录读取失败文案（弹层内重试入口） */
    val errorMessage: String? = null,
)

/**
 * 浏览文件弹层 ViewModel（2026-09-28）：纯 File API 浏览主存储（MANAGE_EXTERNAL_STORAGE
 * 前置闸门在上传页入口校验，弹层只在已授权时打开，故无授权分支、也不做授权页往返的
 * ON_RESUME 重查）。目录/文件列举收口 InboxFileStore（与收件箱设置页同一数据面）；
 * File IO 统一 Dispatchers.IO——与 DataStoreStagingRepository 包装 InboxFileStore 的
 * 调度口径一致，主线程只收状态。
 */
@HiltViewModel
class FileBrowserViewModel @Inject constructor(
    private val inboxFileStore: InboxFileStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FileBrowserUiState())
    val uiState: StateFlow<FileBrowserUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    /** 装载/重试：根目录起步；已在浏览中途时保留当前路径重载（弹层错误态重试的常见路径） */
    fun refresh() {
        viewModelScope.launch {
            val root = withContext(Dispatchers.IO) { inboxFileStore.storageRoot() }
            // 根路径必须回写进状态再装载：enter/goUp 都以 state.storageRoot 作为后续装载的
            // root 入参，漏写会让下钻拿空根、被 loadDirectory 的空根保护静默吞掉（目录行
            // 点击无响应且零错误提示的缺陷根因；对照 InboxSettingsViewModel.refresh 同款回写）
            _uiState.update { it.copy(storageRoot = root) }
            loadDirectory(root, _uiState.value.browsingPath.ifEmpty { root })
        }
    }

    /** 点选子目录：下钻一层（目录行点击） */
    fun enter(path: String) {
        viewModelScope.launch { loadDirectory(_uiState.value.storageRoot, path) }
    }

    /** 返回上一级（到根为止；父不在主存储内回根——与收件箱设置页 goUp 同款口径） */
    fun goUp() {
        val current = _uiState.value
        val parent = current.browsingPath.substringBeforeLast('/')
        val target = if (parent.startsWith(current.storageRoot) && parent != current.browsingPath) {
            parent
        } else {
            current.storageRoot
        }
        if (target.isNotEmpty()) {
            viewModelScope.launch { loadDirectory(current.storageRoot, target) }
        }
    }

    /** 文件行点选 toggle（已选即取消；未选即加入，保持选择序；跨目录保留） */
    fun toggle(file: BrowserFileEntry) {
        _uiState.update { current ->
            val selected = LinkedHashMap(current.selected)
            if (file.path in selected) {
                selected.remove(file.path)
            } else {
                selected[file.path] = file
            }
            current.copy(selected = selected)
        }
    }

    /** 装载目录（子目录与媒体文件一次拉齐；失败保留原列表并给可重试错误） */
    private suspend fun loadDirectory(root: String, path: String) {
        if (root.isEmpty()) return
        _uiState.update { it.copy(loading = true, errorMessage = null) }
        try {
            val dirs = withContext(Dispatchers.IO) { inboxFileStore.listDirectories(path) }
            val files = withContext(Dispatchers.IO) { inboxFileStore.listMediaFiles(path) }
            _uiState.update {
                it.copy(loading = false, browsingPath = path, dirEntries = dirs, files = files)
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(loading = false, errorMessage = LOAD_FAILED_MESSAGE) }
        }
    }

    private companion object {
        /** 目录读取失败文案（点按重试；授权缺失不会走到本弹层） */
        const val LOAD_FAILED_MESSAGE = "读取目录失败：请重试"
    }
}
