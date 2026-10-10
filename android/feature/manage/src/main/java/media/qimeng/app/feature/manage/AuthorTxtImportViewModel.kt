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
import media.qimeng.app.core.data.repository.AuthorRepository
import media.qimeng.app.core.model.AuthorMirrorConfig

/** 作者 TXT 导入页 UI 状态（信息架构基准 = Web 文件管理页 TxtAuthorImportCard） */
data class AuthorTxtImportUiState(
    /** 首屏加载（已有列表后的后台刷新不清 loading，防整页闪进度件） */
    val loading: Boolean = false,
    /** 已导入片段文件名列表（空串=匿名导入，显示映射在屏幕层「（匿名导入）」） */
    val files: List<String> = emptyList(),
    /** 导入进行中（导入区禁点防重，文案「导入中…」） */
    val importing: Boolean = false,
    /** 移除进行中（全部行移除钮禁用，对齐 Web deleteTxt.isPending 全局禁用口径） */
    val removing: Boolean = false,
    /** 重放进行中（重新匹配钮禁用，文案「重放中…」） */
    val rebuilding: Boolean = false,
    /** 错误横幅（点击关闭） */
    val errorMessage: String? = null,
    /** 操作结果提示（点击关闭；Web 同位用 toast） */
    val noticeMessage: String? = null,
    /** 镜像文件绝对路径（留空=关闭） */
    val mirrorPath: String = "",
    /** 镜像目标片段（留空=最近导入的片段） */
    val mirrorFragmentFilename: String = "",
    /** 镜像保存进行中 */
    val mirrorSaving: Boolean = false,
) {
    /** 重新匹配可用性（Web L145：disabled = rebuildTxt.isPending || files.length === 0） */
    val canRebuild: Boolean get() = !rebuilding && files.isNotEmpty()
}

/**
 * 作者 TXT 导入 ViewModel（U10-6b）：片段列表 + 选 TXT 导入 + 移除片段重建 + 幂等重放 + 作者总表镜像。
 * 全走 [AuthorRepository]，UI 零直调（铁律 7）；成功反馈文案逐字对齐 Web
 * LibraryManagePage.tsx TxtAuthorImportCard 各 mutate onSuccess toast。
 */
@HiltViewModel
class AuthorTxtImportViewModel @Inject constructor(
    private val authorRepository: AuthorRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthorTxtImportUiState())
    val uiState: StateFlow<AuthorTxtImportUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    /**
     * 重查片段列表与镜像配置。已有数据时静默刷新（不置 loading）；[clearError]=false 供
     * 「写操作失败后的还原刷新」用——失败横幅先于刷新协程入队，默认清错误会吞掉
     * 刚设置的横幅（LibraryManageViewModel.refresh 同款时序口径）。
     */
    fun refresh(clearError: Boolean = true) {
        if (_uiState.value.loading) return
        val silent = _uiState.value.files.isNotEmpty()
        viewModelScope.launch {
            _uiState.update {
                it.copy(loading = !silent, errorMessage = if (clearError) null else it.errorMessage)
            }
            try {
                val names = authorRepository.importedTxtFileNames()
                val mirror = runCatching { authorRepository.authorMirror() }.getOrDefault(AuthorMirrorConfig())
                _uiState.update {
                    it.copy(
                        loading = false,
                        files = names,
                        mirrorPath = mirror.path,
                        mirrorFragmentFilename = mirror.fragmentFilename,
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, errorMessage = ERROR_LOAD) }
            }
        }
    }

    /**
     * 屏幕层 SAF 选完文件后回调（读文件是平台胶水，屏幕层 UTF-8 解码后进这里）。
     * 前置拦截两道：非 .txt 扩展名（Web L49 口径）与空内容（拍板：空 content 不出网，
     * Web 的 FileReader 无此闸、App 侧读空文件常为选错的占位文件，静默忽略防脏写）。
     * 导入无确认弹窗（Web 同口径：选择即导入）。
     */
    fun onFilePicked(filename: String, content: String) {
        if (_uiState.value.importing) return
        if (!TXT_EXTENSION.containsMatchIn(filename)) {
            _uiState.update { it.copy(errorMessage = ERROR_NOT_TXT) }
            return
        }
        if (content.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(importing = true, errorMessage = null, noticeMessage = null) }
            try {
                val result = authorRepository.importTxt(filename, content)
                _uiState.update {
                    it.copy(
                        importing = false,
                        noticeMessage = NOTICE_IMPORT.format(filename, result.authorsImported ?: 0, result.filesMatched ?: 0),
                    )
                }
                refresh()
            } catch (e: Exception) {
                _uiState.update { it.copy(importing = false, errorMessage = ERROR_IMPORT) }
            }
        }
    }

    /** 移除一个片段并从剩余片段重建（成功反馈 Web L70 逐字；失败后刷新还原列表） */
    fun remove(filename: String) {
        if (_uiState.value.removing) return
        viewModelScope.launch {
            _uiState.update { it.copy(removing = true, errorMessage = null, noticeMessage = null) }
            try {
                authorRepository.removeImportedTxt(filename)
                _uiState.update {
                    it.copy(removing = false, noticeMessage = NOTICE_REMOVE.format(filename))
                }
                refresh()
            } catch (e: Exception) {
                _uiState.update { it.copy(removing = false, errorMessage = ERROR_REMOVE) }
                refresh(clearError = false)
            }
        }
    }

    /** 幂等重放已存片段重建作者-文件关联（成功反馈 Web L78 逐字；片段不变故不刷新列表） */
    fun rebuild() {
        val current = _uiState.value
        if (!current.canRebuild) return
        viewModelScope.launch {
            _uiState.update { it.copy(rebuilding = true, errorMessage = null, noticeMessage = null) }
            try {
                val result = authorRepository.rebuildTxt()
                _uiState.update {
                    it.copy(
                        rebuilding = false,
                        noticeMessage = NOTICE_REBUILD.format(result.authorsImported ?: 0, result.filesMatched ?: 0),
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(rebuilding = false, errorMessage = ERROR_REBUILD) }
            }
        }
    }

    /** 镜像文件路径输入变更 */
    fun onMirrorPathChange(path: String) {
        _uiState.update { it.copy(mirrorPath = path) }
    }

    /** 镜像目标片段输入变更 */
    fun onMirrorFragmentChange(fragment: String) {
        _uiState.update { it.copy(mirrorFragmentFilename = fragment) }
    }

    /** 保存作者总表镜像配置（PUT /authors/mirror） */
    fun saveMirror() {
        if (_uiState.value.mirrorSaving) return
        viewModelScope.launch {
            _uiState.update { it.copy(mirrorSaving = true, errorMessage = null, noticeMessage = null) }
            try {
                val saved = authorRepository.saveAuthorMirror(
                    AuthorMirrorConfig(
                        path = _uiState.value.mirrorPath.trim(),
                        fragmentFilename = _uiState.value.mirrorFragmentFilename.trim(),
                    )
                )
                _uiState.update {
                    it.copy(
                        mirrorSaving = false,
                        mirrorPath = saved.path,
                        mirrorFragmentFilename = saved.fragmentFilename,
                        noticeMessage = NOTICE_MIRROR_SAVED,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        mirrorSaving = false,
                        errorMessage = "${ERROR_MIRROR_SAVE}：${e.message ?: e.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    /** 屏幕层 SAF 读文件失败的入口（Web reader.onerror 文案逐字；无出网） */
    fun onReadFailed() {
        _uiState.update { it.copy(errorMessage = ERROR_READ_FILE) }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun dismissNotice() {
        _uiState.update { it.copy(noticeMessage = null) }
    }

    private companion object {
        /** 作者镜像操作结果文案 */
        const val NOTICE_MIRROR_SAVED = "作者总表镜像配置已保存"
        const val ERROR_MIRROR_SAVE = "保存作者总表镜像失败"
        /** .txt 扩展名（大小写不敏感，Web L49 /\.txt$/i 同口径；containsMatchIn=子串尾锚测试，
         *  不能用 Regex.matches——那是全串匹配，中文名永远不命中） */
        val TXT_EXTENSION = Regex("""\.txt$""", RegexOption.IGNORE_CASE)

        /** 非 txt 拦截文案（Web L50 toast 逐字） */
        const val ERROR_NOT_TXT = "仅支持 .txt 作者清单文件"

        /** SAF 读文件失败（Web reader.onerror toast 逐字） */
        const val ERROR_READ_FILE = "读取文件失败，请重试"

        /** 加载/操作失败文案（同模块 LibraryManageViewModel 固定文案口径；Web 为 err.message 拼接，
         *  App 侧异常消息是英文技术噪声，不逐字搬） */
        const val ERROR_LOAD = "加载片段列表失败：请检查登录与服务端连接"
        const val ERROR_IMPORT = "导入失败，请重试"
        const val ERROR_REMOVE = "移除失败，请重试"
        const val ERROR_REBUILD = "重放失败，请重试"

        /** 操作结果文案（Web TxtAuthorImportCard 各 mutate onSuccess toast 逐字对齐） */
        const val NOTICE_IMPORT = "「%s」导入完成：作者 %d，匹配文件 %d"
        const val NOTICE_REMOVE = "已移除「%s」，并从剩余片段重建作者关联"
        const val NOTICE_REBUILD = "重放完成：%d 位作者 · 关联 %d 个文件"
    }
}
