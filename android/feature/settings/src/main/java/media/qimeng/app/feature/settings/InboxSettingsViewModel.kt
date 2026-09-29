package media.qimeng.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.qimeng.app.core.data.repository.InboxDirEntry
import media.qimeng.app.core.data.repository.StagingRepository

/**
 * 上传归档文件夹设置页 UI 状态（2026-09-29 直传化收窄：收件箱目标随上传页「从收件箱
 * 导入」入口退役删除，本页只余归档文件夹单目标）：授权态 + 目录浏览器 + 当前选定。
 * 归档文件夹 = 用户指定的真实文件夹（上传成功后源文件移入 该文件夹/库名/ 供手动同步），
 * 路径持久化到 StagingRepository；浏览器纯 File API 列目录（已持 MANAGE_EXTERNAL_STORAGE），
 * 不用系统弹窗。
 */
data class InboxSettingsUiState(
    /** 授权判定/目录加载进行中 */
    val loading: Boolean = true,
    /** 「所有文件访问」是否已授权（未授权时整页给引导态） */
    val allFilesGranted: Boolean = false,
    /** 浏览器起点（主存储根，/storage/emulated/0） */
    val storageRoot: String = "",
    /** 浏览中的目录（空串 = 未进入） */
    val browsingPath: String = "",
    /** 浏览中的目录下的一级子目录（含点前缀隐藏目录，名称升序） */
    val entries: List<InboxDirEntry> = emptyList(),
    /** 当前选定的上传归档文件夹路径（持久化；null = 未设置 → 维持 uploaded/ 归档） */
    val selectedArchivePath: String? = null,
    /**
     * 目录浏览器是否展示。初始 false（见 init 回显决策：UiState 先收起，回放后归档文件夹
     * 未设置才展开，已设置用户进页不闪现浏览器）；选定成功即收起；清空重开——此时
     * 「重新选择」按钮不再渲染，浏览器是唯一再选入口。已展开时「重新选择」再展开
     * 且保留浏览位置（[InboxSettingsViewModel.reopenBrowser]）。
     */
    val browserVisible: Boolean = false,
    /** 目录读取失败等非致命错误（点按重试） */
    val errorMessage: String? = null,
)

/**
 * 上传归档文件夹设置页 ViewModel（2026-09-25 暂存区重做；2026-09-29 直传化收窄为归档
 * 单目标）：授权判定 + 目录浏览器导航 + 归档文件夹选定/清除（写 StagingRepository
 * 持久化）。目录浏览只列一级子目录——归档文件夹是用户挑一个文件夹，文件列表不进本页。
 * 回显决策（2026-09-29 修复，语义保留）：浏览器初始展开态随持久化选定值走——归档文件夹
 * 未设置才展开（首用直达选择），已有选定则收起（页面显示当前值卡 + 「重新选择」），
 * 已设置用户再次进页不再回显「选择文件夹」界面。
 */
@HiltViewModel
class InboxSettingsViewModel @Inject constructor(
    private val stagingRepository: StagingRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(InboxSettingsUiState())
    val uiState: StateFlow<InboxSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // 回显决策（2026-09-29 修复，语义保留）：初始展开态随回放值定——归档文件夹
            // 未设置才展开浏览器（首用直达选择），已有选定则收起。UiState 初始
            // browserVisible=false：已设置用户进页不会先展开再收起地闪一下浏览器。
            val archive = stagingRepository.archivePath.first()
            _uiState.update {
                it.copy(
                    selectedArchivePath = archive,
                    browserVisible = archive == null,
                )
            }
        }
        refresh()
    }

    /**
     * 刷新（进入页面/从系统授权页返回）：授权判定 → 根目录起步。
     * 已在浏览中途时保留当前路径重新加载（返回键回到本页的常见路径）。
     */
    fun refresh() {
        viewModelScope.launch {
            val granted = stagingRepository.hasAllFilesAccess()
            _uiState.update { it.copy(loading = true, allFilesGranted = granted, errorMessage = null) }
            if (!granted) {
                _uiState.update { it.copy(loading = false) }
                return@launch
            }
            val root = stagingRepository.storageRoot()
            _uiState.update { it.copy(storageRoot = root) }
            val target = _uiState.value.browsingPath.ifEmpty { root }
            loadDirectory(root, target)
        }
    }

    /** 点选子目录：下钻一层（目录行点击） */
    fun enter(path: String) {
        viewModelScope.launch { loadDirectory(_uiState.value.storageRoot, path) }
    }

    /** 返回上一级（根目录时不动） */
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

    /**
     * 选用当前浏览中的文件夹为上传归档文件夹（持久化；2026-09-28 归档文件夹功能）。
     */
    fun selectCurrentAsArchive() {
        val path = _uiState.value.browsingPath
        if (path.isEmpty()) return
        viewModelScope.launch {
            stagingRepository.setArchivePath(path)
            // 选定成功即收起浏览器（选完即消失的用户预期）；再开走 reopenBrowser
            _uiState.update { it.copy(selectedArchivePath = path, browserVisible = false, errorMessage = null) }
        }
    }

    /** 清除上传归档文件夹（worker 归档回退源文件同目录 uploaded/ 既有行为） */
    fun clearArchive() {
        viewModelScope.launch {
            stagingRepository.setArchivePath(null)
            _uiState.update {
                it.copy(
                    selectedArchivePath = null,
                    // 清空 = 回到首用态：当前值卡不再渲染「重新选择」，浏览器是唯一再选
                    // 入口，必须重开（与 init 展开决策同一口径）
                    browserVisible = true,
                )
            }
        }
    }

    /**
     * 重新展开目录浏览器（选定成功即收起后的再入口）：只翻可见位，浏览位置不清零——
     * 用户常是「选定后发现不对换一个」，保留位置免去重新逐层下钻。
     */
    fun reopenBrowser() {
        _uiState.update { it.copy(browserVisible = true) }
    }

    /** 消除错误提示 */
    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /** 加载目录（root 兜底非法路径；失败保留原位并给可重试错误） */
    private suspend fun loadDirectory(root: String, path: String) {
        if (root.isEmpty()) return
        _uiState.update { it.copy(loading = true, errorMessage = null) }
        try {
            val entries = stagingRepository.listDirectories(path)
            _uiState.update { it.copy(loading = false, browsingPath = path, entries = entries) }
        } catch (e: Exception) {
            _uiState.update { it.copy(loading = false, errorMessage = LOAD_FAILED_MESSAGE) }
        }
    }

    private companion object {
        /** 目录读取失败文案（点按重试；授权缺失走引导态不走本错误） */
        const val LOAD_FAILED_MESSAGE = "读取目录失败：请重试"
    }
}
