package media.qimeng.app.feature.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import media.qimeng.app.core.data.backup.AutoBackupRunner
import media.qimeng.app.core.data.backup.BackupFileStatus
import media.qimeng.app.core.data.events.ViewEventQueue
import media.qimeng.app.core.data.repository.BackupAutoPrefsRepository
import media.qimeng.app.core.data.repository.BackupRepository
import media.qimeng.sdk.models.LegacyBackupImport

/** 待确认的导入载荷（非 null 即打开确认弹窗；Web BackupCard pending 同构） */
data class PendingBackupImport(
    val payload: LegacyBackupImport,
    val summary: BackupValidator.BackupSummary,
)

/** 备份页 UI 状态（2026-09-19 任务S 两卡收敛：卡1 导入导出 + 卡2 自动备份 + 浏览数据同步区） */
data class BackupUiState(
    /** 导出链路进行中（按钮禁用防重，文案「导出中…」） */
    val exporting: Boolean = false,
    /** 导入请求进行中（导入按钮禁用防重，文案「导入中…」） */
    val importing: Boolean = false,
    /** 二次确认弹窗的载荷（Web ConfirmDialog 同位） */
    val pendingImport: PendingBackupImport? = null,
    /** 错误横幅（点击关闭） */
    val errorMessage: String? = null,
    /** 操作结果提示（点击关闭；Web 同位用 toast） */
    val noticeMessage: String? = null,
    /** 迁移提示（warnings>0 逐条展示；Web toast.info description 同语义） */
    val warnings: List<String> = emptyList(),
    /** 备份目录内备份文件状态（卡1 状态行数据源；null=目录未设/无文件/读取失败，显空态） */
    val backupFileStatus: BackupFileStatus? = null,
    // ---------- 浏览数据同步（2026-09-15 批自我的页迁入：原 SettingsViewModel 同款字段） ----------
    /** 待上传事件条数（进页读一次；同步后随结果刷新；null=读失败） */
    val pendingEvents: Int? = null,
    /** 上传进行中（立即同步按钮禁用防重） */
    val eventSyncing: Boolean = false,
    /** 同步结果的一次性提示（点按消除；「导出未上传」按钮 2026-09-16 用户反馈退役） */
    val eventSyncNote: String? = null,
    // ---------- 自动备份（2026-09-16 用户反馈：BackupAutoPrefs 三键回流） ----------
    /** 自动备份开关（跟随 prefs DataStore 流） */
    val autoBackupEnabled: Boolean = false,
    /** 已授权的备份目录树 URI（null=未选择；授权持久化在屏幕层，VM 只存值） */
    val autoBackupDirUri: String? = null,
    /** 上次成功写入备份目录的时间戳（0=从未运行，UI 显「未运行」；手动导出成功同刷） */
    val autoBackupLastRunMillis: Long = 0,
)

/**
 * 备份导入/导出 ViewModel（U10-6b，DOMAIN_RULES §10；2026-09-19 任务S 两卡收敛）：
 * 备份页收敛为「导入导出」「自动备份」两张功能卡 + 浏览数据同步区。导出/导入对象恒=当前
 * 连接的服务端，且**直接读写备份目录**（AutoBackupRunner 授权目录的固定名文件，不弹 SAF
 * 选择器）：导出=全量信封覆盖写 qimeng_backup.json（未设目录不出网）；导入=直读该文件 →
 * BackupValidator 前置校验（64MB 闸保留）→ 二次确认 → 幂等合并。成功反馈文案逐字对齐
 * Web LibraryManagePage.tsx BackupCard 各 mutate onSuccess toast。
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupRepository: BackupRepository,
    private val viewEventQueue: ViewEventQueue,
    private val autoBackupPrefs: BackupAutoPrefsRepository,
    private val autoBackupRunner: AutoBackupRunner,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    init {
        loadPendingEvents()
        observeAutoBackupPrefs()
    }

    /**
     * 导出：直接覆盖写备份目录的 qimeng_backup.json（执行体 AutoBackupRunner.writeNow，
     * 与自动备份同一条写链路；成功翻新「上次备份时间」）。未设备份目录→错误横幅并不出网
     * （导出对象在备份目录里，目录未设时连 GET /export 都不发）；写入失败→错误横幅。
     */
    fun exportToBackupDir() {
        if (_uiState.value.exporting) return
        if (_uiState.value.autoBackupDirUri == null) {
            _uiState.update { it.copy(errorMessage = ERROR_NO_BACKUP_DIR) }
            return
        }
        _uiState.update { it.copy(exporting = true, errorMessage = null, noticeMessage = null) }
        viewModelScope.launch {
            val written = runCatching { autoBackupRunner.writeNow() }.getOrNull()
            _uiState.update {
                if (written == null) {
                    it.copy(exporting = false, errorMessage = ERROR_EXPORT_WRITE)
                } else {
                    it.copy(
                        exporting = false,
                        noticeMessage = NOTICE_EXPORT.format((written / BYTES_PER_KB).roundToInt()),
                    )
                }
            }
        }
    }

    /**
     * 导入：直读备份目录的 qimeng_backup.json → 与文件导入完全相同的前置校验→确认弹窗→
     * 幂等导入链路（[onFilePicked] 单源口径）。目录未设/无文件→可读横幅不出网。
     */
    fun importFromBackupDir() {
        if (_uiState.value.importing) return
        if (_uiState.value.autoBackupDirUri == null) {
            _uiState.update { it.copy(errorMessage = ERROR_NO_BACKUP_DIR) }
            return
        }
        viewModelScope.launch {
            // 先查状态再读字节：状态 null=目录里没有备份文件（文案口径），有状态但读
            // 失败=授权被回收/IO 异常（区分文案，避免「没有文件」误导）
            val status = runCatching { autoBackupRunner.readFileStatus() }.getOrNull()
            if (status == null) {
                _uiState.update { it.copy(errorMessage = ERROR_NO_BACKUP_FILE) }
                return@launch
            }
            val bytes = runCatching { autoBackupRunner.readBackupBytes() }.getOrNull()
            if (bytes == null) {
                _uiState.update { it.copy(errorMessage = ERROR_READ_FILE) }
                return@launch
            }
            // 校验下 Default 线程（2026-09-20 全库审查 P1）：64MB 上限的字节→字符串→
            // 双次 Moshi 解析是页内最重的 CPU 活，Main 线程执行是 ANR 风险——R2 已修
            // 导出侧（AutoBackupRunner 序列化段同口径），导入/校验侧此处补齐。
            // onFilePicked 的同步入口仅供小体量直调/测试，生产大文件路径恒走本链。
            val result = withContext(Dispatchers.Default) {
                BackupValidator.validate(AutoBackupRunner.BACKUP_FILE_NAME, bytes)
            }
            stageValidation(result)
        }
    }

    /**
     * 导入前置校验入口（文件名+字节 → BackupValidator → 确认弹窗）。同步执行——
     * 生产大文件路径恒走 [importFromBackupDir] 的 Default 线程链，本入口留给
     * 测试/小体量直调；调用方自行保证不在主线程塞大文件。前置校验失败
     * （超限/坏 JSON/格式不符）置错误横幅不出网；通过则进二次确认弹窗（旧版
     * 「检测到备份数据…」语义）。
     */
    fun onFilePicked(fileName: String, bytes: ByteArray) {
        if (_uiState.value.importing) return
        stageValidation(BackupValidator.validate(fileName, bytes))
    }

    /** 校验结果落地（两条入口共用的单一出口：失败→错误横幅，通过→确认弹窗载荷） */
    private fun stageValidation(result: BackupValidator.Result) {
        when (result) {
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
     * 确认导入（幂等合并，网络层走 @BackupClient 长超时通道）。成功反馈 Web L233-234
     * 逐字；warnings>0 逐条进状态由屏幕层展示（Web toast.info「另有 N 条迁移提示」+
     * description 的 App 同语义件）。
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

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun dismissNotice() {
        _uiState.update { it.copy(noticeMessage = null) }
    }

    fun dismissWarnings() {
        _uiState.update { it.copy(warnings = emptyList()) }
    }

    // ---------- 浏览数据同步（2026-09-15 批自我的页迁入：SettingsViewModel 原款逻辑搬运，
    // 执行体同在 ViewEventQueue 本地优先队列，与三通道自动补传同一 Mutex 串行） ----------

    /** 待上传条数（进页读一次；同步后随结果刷新） */
    private fun loadPendingEvents() {
        viewModelScope.launch {
            _uiState.update { it.copy(pendingEvents = runCatching { viewEventQueue.pendingCount() }.getOrNull()) }
        }
    }

    /**
     * 立即同步：手动触发一轮 drain（与三通道自动补传同一执行体、同一 Mutex 串行）。
     * 退避中的行不到期不会被本次 drain 取走（防「连点立即同步狂打故障端点」），
     * 结果提示按摘要语义给出。
     */
    fun syncEventsNow() {
        if (_uiState.value.eventSyncing) return
        _uiState.update { it.copy(eventSyncing = true, eventSyncNote = null) }
        viewModelScope.launch {
            val summary = runCatching { viewEventQueue.drain() }.getOrNull()
            val pending = runCatching { viewEventQueue.pendingCount() }.getOrNull()
            _uiState.update {
                it.copy(
                    eventSyncing = false,
                    pendingEvents = pending,
                    eventSyncNote = when {
                        summary == null -> EVENT_SYNC_FAILED_MESSAGE
                        summary.keptForRetry > 0 -> "网络不通，${summary.keptForRetry} 条稍后自动重试"
                        summary.dropped > 0 -> "同步完成；${summary.dropped} 条发送失败已保留"
                        else -> "同步完成"
                    },
                )
            }
        }
    }

    /** 同步结果提示点按消除 */
    fun dismissEventSyncNote() {
        _uiState.update { it.copy(eventSyncNote = null) }
    }

    // ---------- 自动备份（2026-09-16 用户反馈：持久化态回流；任务S：手动触发并入卡1「导出」） ----------

    /**
     * 自动备份持久化态回流（开关/目录/上次运行三键）+ 卡1 状态行跟随刷新：持续跟随
     * DataStore 流——含导出成功/冷启动 runIfDue 成功后 setLastRunMillis 的回填
     * （「上次备份」时间与备份文件状态行自动翻新）。
     */
    private fun observeAutoBackupPrefs() {
        viewModelScope.launch {
            autoBackupPrefs.state.collect { prefs ->
                _uiState.update {
                    it.copy(
                        autoBackupEnabled = prefs.enabled,
                        autoBackupDirUri = prefs.dirUri,
                        autoBackupLastRunMillis = prefs.lastRunMillis,
                    )
                }
                refreshBackupFileStatus(prefs.dirUri)
            }
        }
    }

    /** 卡1 状态行回流：目录未设→null（空态）；已设→读目录内备份文件元数据（SAF 查询轻量） */
    private suspend fun refreshBackupFileStatus(dirUri: String?) {
        val status = if (dirUri == null) {
            null
        } else {
            runCatching { autoBackupRunner.readFileStatus() }.getOrNull()
        }
        _uiState.update { it.copy(backupFileStatus = status) }
    }

    /** 开关写回（UI 跟随 prefs 流原值；写失败流不发射=回滚并给反馈） */
    fun setAutoBackupEnabled(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { autoBackupPrefs.setEnabled(enabled) }
                .onFailure { _uiState.update { it.copy(errorMessage = ERROR_SAVE_PREFS) } }
        }
    }

    /** SAF 目录选择回填（授权持久化在屏幕层，VM 只存 URI；UI 跟随 prefs 流） */
    fun onAutoBackupDirPicked(uri: String) {
        viewModelScope.launch {
            runCatching { autoBackupPrefs.setDirUri(uri) }
                .onFailure { _uiState.update { it.copy(errorMessage = ERROR_SAVE_PREFS) } }
        }
    }

    private companion object {
        /** KB 换算分母（Web (sizeBytes/1024).toFixed(0) 同口径；四舍五入由 roundToInt 承担） */
        const val BYTES_PER_KB = 1024.0

        /** 失败文案（同模块固定文案口径；Web 为 err.message 拼接，App 侧异常消息不逐字搬） */
        const val ERROR_EXPORT_WRITE = "写入备份文件失败，请重试"
        const val ERROR_IMPORT = "导入失败，请重试"
        const val ERROR_READ_FILE = "读取文件失败，请重试"
        const val ERROR_SAVE_PREFS = "保存失败，请重试"

        /** 任务S 2026-09-19 冻结文案：未设备份目录（导出/导入共用；此态不出网） */
        const val ERROR_NO_BACKUP_DIR = "请先在自动备份中设置备份目录"

        /** 任务S 2026-09-19 冻结文案：目录里没有备份文件（导入方向，提示先在源端导出） */
        const val ERROR_NO_BACKUP_FILE = "备份目录还没有备份文件，请先在源端导出"

        /** 操作结果文案（Web BackupCard 各 mutate onSuccess toast 逐字对齐） */
        const val NOTICE_EXPORT = "已导出 qimeng_backup.json（%d KB）"
        const val NOTICE_IMPORT = "「%s」导入完成：匹配文件 %d/%d，作者 %d，标签 %d，事件回放 %d 条"

        /** 浏览数据同步失败文案（SettingsViewModel 原款随迁） */
        const val EVENT_SYNC_FAILED_MESSAGE = "同步失败，请重试"
    }
}
