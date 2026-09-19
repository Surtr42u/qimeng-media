package media.qimeng.app.feature.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.qimeng.app.core.data.repository.LibraryRepository
import media.qimeng.app.core.model.LibraryKind
import media.qimeng.app.core.model.LibrarySummary

/** 库管理页 UI 状态（信息架构基准 = Web 文件管理页 LibraryManagePage.tsx 的库表 + 注册表单） */
data class LibraryManageUiState(
    /** 首屏加载（已有列表后的后台刷新不清 loading，防整页闪进度件） */
    val loading: Boolean = false,
    val libraries: List<LibrarySummary> = emptyList(),
    /** 错误横幅（MessageCard error 容器，点击关闭） */
    val errorMessage: String? = null,
    /** 操作结果提示（MessageCard tertiary 容器，点击关闭；Web 同位用 toast） */
    val noticeMessage: String? = null,
    // —— 注册新库表单（POST body {name, rootPath, kind}）——
    val formName: String = "",
    val formRootPath: String = "",
    val formKind: LibraryKind = LibraryKind.NORMAL,
    /** 注册请求进行中（按钮禁用与文案「注册中…」） */
    val registering: Boolean = false,
    /** 删除二次确认目标（非 null 弹 AlertDialog，对齐 Web W-2：非 danger——索引清除，磁盘文件不动） */
    val deleteTarget: LibrarySummary? = null,
) {
    /** 注册按钮可用性（对齐 Web submitRegister 校验：名称与路径均必填） */
    val canSubmitRegister: Boolean
        get() = !registering && formName.isNotBlank() && formRootPath.isNotBlank()
}

/**
 * 库管理 ViewModel（U10-6）：库表五操作（注册/重扫/删除/启停/列表）+ 注册表单编排。
 * 全走 [LibraryRepository] 与 [ScanChargeController]，UI 零直调（铁律 7）；
 * scanState 无实时推送（SSE 未接，实时进度走 SSE 列入后续），页面 ON_START 与每个
 * 写操作成功后重查拉平。
 */
@HiltViewModel
class LibraryManageViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val scanChargeController: media.qimeng.app.core.data.scan.ScanChargeController,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryManageUiState())
    val uiState: StateFlow<LibraryManageUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    /**
     * 重查库列表。scanState 无推送，页面回前台（ON_START）也走这里拉平；
     * 已有数据时静默刷新（不置 loading）。防重入：init 与首帧 ON_START 同窗口会连发两次。
     * [clearError]=false 供「写操作失败后的还原刷新」用——失败横幅先于刷新协程入队，
     * 默认的清错误会把刚设置的横幅吞掉（时序上 refresh 的首个 update 后执行）。
     */
    fun refresh(clearError: Boolean = true) {
        if (_uiState.value.loading) return
        val silent = _uiState.value.libraries.isNotEmpty()
        viewModelScope.launch {
            _uiState.update {
                it.copy(loading = !silent, errorMessage = if (clearError) null else it.errorMessage)
            }
            try {
                val libs = libraryRepository.libraries()
                _uiState.update { it.copy(loading = false, libraries = libs) }
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, errorMessage = ERROR_LOAD) }
            }
        }
    }

    fun updateFormName(value: String) {
        _uiState.update { it.copy(formName = value) }
    }

    fun updateFormRootPath(value: String) {
        _uiState.update { it.copy(formRootPath = value) }
    }

    fun updateFormKind(kind: LibraryKind) {
        _uiState.update { it.copy(formKind = kind) }
    }

    /**
     * 注册并扫描（对齐 Web submitRegister 语义：POST /libraries 本身不自动扫描，
     * 成功拿到 id 后立即补一发 POST scan 让按钮名副其实；拿不到 id 仍注册成功、只跳过续接）。
     * 扫描失败不连坐进注册失败分支——注册已成功的事实必须清表单+成功反馈，否则用户
     * 照「注册失败」提示重试会重复注册（Web 基准为注册 toast 与扫描错误 toast 分离）。
     */
    fun registerAndScan() {
        val current = _uiState.value
        if (!current.canSubmitRegister) {
            _uiState.update { it.copy(errorMessage = ERROR_FORM_VALIDATE) }
            return
        }
        val name = current.formName.trim()
        val rootPath = current.formRootPath.trim()
        viewModelScope.launch {
            _uiState.update { it.copy(registering = true, errorMessage = null) }
            try {
                val created = libraryRepository.register(name, rootPath, current.formKind)
                var scanFailed = false
                created?.id?.let {
                    try {
                        libraryRepository.scan(it)
                    } catch (e: Exception) {
                        scanFailed = true
                    }
                }
                _uiState.update {
                    it.copy(
                        registering = false,
                        formName = "",
                        formRootPath = "",
                        formKind = LibraryKind.NORMAL,
                        noticeMessage = if (scanFailed) {
                            NOTICE_REGISTER_SCAN_FAILED.format(name)
                        } else {
                            NOTICE_REGISTER_SUCCESS.format(name)
                        },
                    )
                }
                refresh()
            } catch (e: Exception) {
                _uiState.update { it.copy(registering = false, errorMessage = ERROR_REGISTER) }
            }
        }
    }

    /**
     * 行内「重新扫描」（批C 任务Q C-3 起经充电门）：本机模式+设置开+未充电 → 记待扫
     * 标记不立即扫（判定在 [ScanChargeController]，ScanGate 纯函数锁定）；否则立即扫
     * （202 受理即成功；进度由下一次刷新的 scanState 呈现）。决策三分型对应三段反馈。
     */
    fun rescan(library: LibrarySummary) {
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null, noticeMessage = null) }
            when (scanChargeController.requestRescan(library.id)) {
                media.qimeng.app.core.data.scan.RescanDecision.STARTED -> {
                    _uiState.update { it.copy(noticeMessage = NOTICE_RESCAN_SUCCESS.format(library.name)) }
                    refresh()
                }

                media.qimeng.app.core.data.scan.RescanDecision.DEFERRED ->
                    _uiState.update {
                        it.copy(noticeMessage = NOTICE_RESCAN_DEFERRED.format(library.name))
                    }

                media.qimeng.app.core.data.scan.RescanDecision.FAILED ->
                    _uiState.update { it.copy(errorMessage = ERROR_SCAN) }
            }
        }
    }

    /** 弹删除二次确认（目标快照进状态，确认框文案用其 name/fileCount） */
    fun requestDelete(library: LibrarySummary) {
        _uiState.update { it.copy(deleteTarget = library) }
    }

    fun dismissDelete() {
        _uiState.update { it.copy(deleteTarget = null) }
    }

    /** 二次确认后的真删除（DELETE /libraries/{id}；索引清除、磁盘文件不动——服务端职责） */
    fun confirmDelete() {
        val target = _uiState.value.deleteTarget ?: return
        _uiState.update { it.copy(deleteTarget = null) }
        viewModelScope.launch {
            try {
                libraryRepository.delete(target.id)
                _uiState.update { it.copy(noticeMessage = NOTICE_DELETE_SUCCESS.format(target.name)) }
                refresh()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = ERROR_DELETE) }
            }
        }
    }

    /** 启停开关（PUT enabled；停用=浏览面隐藏，记录全保留。失败后刷新还原开关原值） */
    fun setEnabled(library: LibrarySummary, enabled: Boolean) {
        viewModelScope.launch {
            try {
                libraryRepository.setEnabled(library.id, enabled)
                _uiState.update {
                    it.copy(
                        noticeMessage = if (enabled) {
                            NOTICE_ENABLED_SUCCESS.format(library.name)
                        } else {
                            NOTICE_DISABLED_SUCCESS.format(library.name)
                        },
                    )
                }
                refresh()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = ERROR_SET_ENABLED) }
                refresh(clearError = false)
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun dismissNotice() {
        _uiState.update { it.copy(noticeMessage = null) }
    }

    private companion object {
        /** 加载失败文案（与 UploadViewModel 同口径） */
        const val ERROR_LOAD = "加载库列表失败：请检查登录与服务端连接"

        /** 表单校验文案（Web submitRegister toast 逐字） */
        const val ERROR_FORM_VALIDATE = "名称与路径均必填"

        const val ERROR_REGISTER = "注册失败，请重试"
        const val ERROR_SCAN = "触发扫描失败，请重试"
        const val ERROR_DELETE = "删除失败，请重试"
        const val ERROR_SET_ENABLED = "启停失败，请重试"

        /** 操作结果文案（Web LibraryManagePage.tsx 各 mutate onSuccess toast 逐字对齐） */
        const val NOTICE_REGISTER_SUCCESS = "已注册「%s」，开始扫描"

        /** 注册成功但续接扫描失败：注册事实不回滚（与 Web 注册/扫描 toast 分离同口径） */
        const val NOTICE_REGISTER_SCAN_FAILED = "已注册「%s」，但触发扫描失败，请稍后在列表重新扫描"
        const val NOTICE_RESCAN_SUCCESS = "「%s」扫描已触发"

        /** 重扫被充电门推迟（C-3）：待扫标记已记，UI 可感知反馈（冒烟剧本 2 的断言点） */
        const val NOTICE_RESCAN_DEFERRED = "「%s」未接通电源，已记入待扫描，接入电源后自动开始"
        const val NOTICE_DELETE_SUCCESS = "「%s」已删除"
        const val NOTICE_ENABLED_SUCCESS = "「%s」已启用"
        const val NOTICE_DISABLED_SUCCESS = "「%s」已停用（浏览面隐藏，记录保留）"
    }
}
