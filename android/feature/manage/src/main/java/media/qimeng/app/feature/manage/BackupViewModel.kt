package media.qimeng.app.feature.manage

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
import media.qimeng.app.core.data.repository.BackupRepository
import media.qimeng.sdk.infrastructure.Serializer
import media.qimeng.sdk.models.LegacyBackupFile
import media.qimeng.sdk.models.LegacyBackupImport
import kotlin.math.roundToInt

/** 待写入的导出文件（VM 序列化，屏幕层只负责 SAF 落盘——SettingsScreen 平台胶水口径） */
data class BackupExportFile(
    val json: String,
    /** 序列化后 UTF-8 字节数（=SAF 实写字节数，成功提示的 KB 口径源） */
    val sizeBytes: Int,
)

/** 待确认的导入载荷（非 null 即打开确认弹窗；Web BackupCard pending 同构） */
data class PendingBackupImport(
    val payload: LegacyBackupImport,
    val summary: BackupValidator.BackupSummary,
)

/** 备份导入/导出页 UI 状态（信息架构基准 = Web 文件管理页 BackupCard） */
data class BackupUiState(
    /** 导出链路进行中（按钮禁用防重，文案「导出中…」） */
    val exporting: Boolean = false,
    /** 导入请求进行中（导入区禁点防重，文案「导入中…」） */
    val importing: Boolean = false,
    /** 已备好待写的导出内容（非 null 屏幕层落盘；写完回调 onExportWritten 清位） */
    val pendingExport: BackupExportFile? = null,
    /** 二次确认弹窗的载荷（Web ConfirmDialog 同位） */
    val pendingImport: PendingBackupImport? = null,
    /** 错误横幅（点击关闭） */
    val errorMessage: String? = null,
    /** 操作结果提示（点击关闭；Web 同位用 toast） */
    val noticeMessage: String? = null,
    /** 迁移提示（warnings>0 逐条展示；Web toast.info description 同语义） */
    val warnings: List<String> = emptyList(),
)

/**
 * 备份导入/导出 ViewModel（U10-6b，DOMAIN_RULES §10）：导出全量备份（序列化在 VM，
 * SAF 落盘在屏幕层）+ 选 JSON → 前置校验（BackupValidator）→ 二次确认 → 幂等导入。
 * 成功反馈文案逐字对齐 Web LibraryManagePage.tsx BackupCard 各 mutate onSuccess toast。
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupRepository: BackupRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    /**
     * 出导出内容（屏幕层 SAF uri 就绪后调用）。导出中防重：进行中直接返回 null
     * （按钮已禁用，双保险）；失败置错误横幅。写文件由屏幕层拿到 [BackupUiState.pendingExport]
     * 后执行，写完必须回调 [onExportWritten] 清位。
     */
    suspend fun prepareExport(): BackupExportFile? {
        val current = _uiState.value
        if (current.exporting || current.pendingExport != null) return null
        _uiState.update { it.copy(exporting = true, errorMessage = null, noticeMessage = null) }
        return try {
            // 数十 MB 备份的 Moshi 序列化 + UTF-8 全量拷贝是纯 CPU 重活，必须离开主线程
            // （reviewer P2：Main 线程跑会整页冻结甚至 ANR；repository 出网已收口 IO，
            // 这里把序列化段一并推到 Default 池）
            val export = withContext(Dispatchers.Default) {
                val file = backupRepository.export()
                val json = Serializer.moshi.adapter(LegacyBackupFile::class.java).toJson(file)
                BackupExportFile(json = json, sizeBytes = json.toByteArray(Charsets.UTF_8).size)
            }
            _uiState.update { it.copy(exporting = false, pendingExport = export) }
            export
        } catch (e: Exception) {
            _uiState.update { it.copy(exporting = false, errorMessage = ERROR_EXPORT) }
            null
        }
    }

    /**
     * 屏幕层落盘回执：[sizeBytes]=实写字节数（null=写入失败）。成功反馈 Web L210 逐字
     * （(sizeBytes/1024).toFixed(0) KB → 四舍五入整数 KB）；两种结果都清 pendingExport
     * ——内容已消费，重试须重新走 prepareExport。
     */
    fun onExportWritten(sizeBytes: Int?) {
        _uiState.update {
            if (sizeBytes == null) {
                it.copy(pendingExport = null, errorMessage = ERROR_EXPORT_WRITE)
            } else {
                it.copy(
                    pendingExport = null,
                    noticeMessage = NOTICE_EXPORT.format((sizeBytes / BYTES_PER_KB).roundToInt()),
                )
            }
        }
    }

    /**
     * 屏幕层 SAF 选完导入文件后回调（读字节是平台胶水）。前置校验失败（超限/坏 JSON/
     * 格式不符）置错误横幅不出网；通过则进二次确认弹窗（旧版「检测到备份数据…」语义）。
     */
    fun onFilePicked(fileName: String, bytes: ByteArray) {
        if (_uiState.value.importing) return
        when (val result = BackupValidator.validate(fileName, bytes)) {
            is BackupValidator.Result.Invalid -> _uiState.update { it.copy(errorMessage = result.message) }
            is BackupValidator.Result.Ok -> _uiState.update {
                it.copy(pendingImport = PendingBackupImport(result.payload, result.summary), errorMessage = null)
            }
        }
    }

    /** 取消确认（Web onOpenChange(false) → setPending(null) 同构；无出网无提示） */
    fun dismissImport() {
        _uiState.update { it.copy(pendingImport = null) }
    }

    /**
     * 确认导入（幂等合并）。成功反馈 Web L233-234 逐字；warnings>0 逐条进状态由
     * 屏幕层展示（Web toast.info「另有 N 条迁移提示」+ description 的 App 同语义件）。
     */
    fun confirmImport() {
        val pending = _uiState.value.pendingImport ?: return
        _uiState.update { it.copy(pendingImport = null, importing = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val result = backupRepository.import(pending.payload)
                val warnings = result.warnings.orEmpty()
                _uiState.update {
                    it.copy(
                        importing = false,
                        noticeMessage = NOTICE_IMPORT.format(
                            pending.summary.fileName,
                            result.assetsMatched ?: 0,
                            result.mediaFilesTotal ?: 0,
                            result.authorsImported ?: 0,
                            result.tagsImported ?: 0,
                            result.eventsReplayed ?: 0,
                        ),
                        warnings = warnings,
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(importing = false, errorMessage = ERROR_IMPORT) }
            }
        }
    }

    /** 屏幕层 SAF 读文件失败的入口（无出网；文案同作者 TXT 页固定口径） */
    fun onReadFailed() {
        _uiState.update { it.copy(errorMessage = ERROR_READ_FILE) }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun dismissNotice() {
        _uiState.update { it.copy(noticeMessage = null) }
    }

    fun dismissWarnings() {
        _uiState.update { it.copy(warnings = emptyList()) }
    }

    private companion object {
        /** KB 换算分母（Web (sizeBytes/1024).toFixed(0) 同口径；四舍五入由 roundToInt 承担） */
        const val BYTES_PER_KB = 1024.0

        /** 失败文案（同模块固定文案口径；Web 为 err.message 拼接，App 侧异常消息不逐字搬） */
        const val ERROR_EXPORT = "导出失败，请重试"
        const val ERROR_EXPORT_WRITE = "写入备份文件失败，请重试"
        const val ERROR_IMPORT = "导入失败，请重试"
        const val ERROR_READ_FILE = "读取文件失败，请重试"

        /** 操作结果文案（Web BackupCard 各 mutate onSuccess toast 逐字对齐） */
        const val NOTICE_EXPORT = "已导出 qimeng_backup.json（%d KB）"
        const val NOTICE_IMPORT = "「%s」导入完成：匹配文件 %d/%d，作者 %d，标签 %d，事件回放 %d 条"
    }
}
