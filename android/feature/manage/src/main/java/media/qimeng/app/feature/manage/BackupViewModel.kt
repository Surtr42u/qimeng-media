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
import media.qimeng.app.core.data.backup.AutoBackupRunner
import media.qimeng.app.core.data.events.ViewEventQueue
import media.qimeng.app.core.data.repository.BackupAutoPrefsRepository
import media.qimeng.app.core.data.repository.BackupRepository
import media.qimeng.app.core.data.repository.StagedBackupMeta
import media.qimeng.app.core.data.repository.SyncStagingRepository
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
    /** 导入请求进行中（导入行禁点防重，文案「导入中…」） */
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
    // ---------- 浏览数据同步（2026-09-15 批自我的页迁入：原 SettingsViewModel 同款字段） ----------
    /** 待上传事件条数（进页读一次；同步后随结果刷新；null=读失败） */
    val pendingEvents: Int? = null,
    /** 上传进行中（立即同步按钮禁用防重） */
    val eventSyncing: Boolean = false,
    /** 同步结果的一次性提示（点按消除；「导出未上传」按钮 2026-09-16 用户反馈退役） */
    val eventSyncNote: String? = null,
    // ---------- 自动备份（2026-09-16 用户反馈：BackupAutoPrefs 三键回流 + 立即备份防重） ----------
    /** 自动备份开关（跟随 prefs DataStore 流） */
    val autoBackupEnabled: Boolean = false,
    /** 已授权的备份目录树 URI（null=未选择；授权持久化在屏幕层，VM 只存值） */
    val autoBackupDirUri: String? = null,
    /** 上次成功备份时间戳（0=从未运行，UI 显「未运行」） */
    val autoBackupLastRunMillis: Long = 0,
    /** 立即备份进行中（按钮禁用防重，文案「备份中…」） */
    val autoBackupBusy: Boolean = false,
    // ---------- 跨端同步暂存（2026-09-18 用户拍板：本机⇄服务器同步免来回导文件；
    // 2026-09-19 任务R 四入口重做：暂存/导入暂存收敛进「跨端同步」卡与顶部暂存卡） ----------
    /** 当前暂存元数据（null=无暂存；进页读一次，暂存成功后随结果更新） */
    val staged: StagedBackupMeta? = null,
    /** 暂存信封的媒体文件数（读暂存过 BackupValidator 所得；null=暂存内容未过校验，卡上隐藏该项） */
    val stagedMediaFiles: Int? = null,
    /** 暂存进行中（按钮禁用防重，文案「暂存中…」） */
    val stagingBusy: Boolean = false,
)

/**
 * 备份导入/导出 ViewModel（U10-6b，DOMAIN_RULES §10；2026-09-19 任务R 四入口重做）：
 * 备份页收敛为导出备份/导入备份/跨端同步/自动备份四入口，导入/导出对象恒=当前连接的
 * 服务端。导出全量备份（序列化在 VM，SAF 落盘在屏幕层）+ 选 JSON → 前置校验
 * （BackupValidator）→ 二次确认 → 幂等导入。成功反馈文案逐字对齐 Web
 * LibraryManagePage.tsx BackupCard 各 mutate onSuccess toast。
 * 2026-09-16 用户反馈：浏览数据「导出未上传」入口退役（exportPending/onExported 删除，
 * 立即同步保留）；新增自动备份状态编排（开关/目录/上次运行持久化态回流 + 立即备份，
 * 执行体在 core:data AutoBackupRunner）。
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupRepository: BackupRepository,
    private val viewEventQueue: ViewEventQueue,
    private val autoBackupPrefs: BackupAutoPrefsRepository,
    private val autoBackupRunner: AutoBackupRunner,
    private val syncStaging: SyncStagingRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    init {
        loadPendingEvents()
        observeAutoBackupPrefs()
        loadStagedMeta()
    }

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
            val export = buildExportJson()
            _uiState.update { it.copy(exporting = false, pendingExport = export) }
            export
        } catch (e: Exception) {
            _uiState.update { it.copy(exporting = false, errorMessage = ERROR_EXPORT) }
            null
        }
    }

    /**
     * 导出信封 → JSON 文本（导出落盘与跨端暂存共用的同一段序列化；单源纪律）。
     * 数十 MB 备份的 Moshi 序列化 + UTF-8 全量拷贝是纯 CPU 重活，必须离开主线程
     * （reviewer P2：Main 线程跑会整页冻结甚至 ANR；repository 出网已收口 IO，
     * 这里把序列化段一并推到 Default 池）。
     */
    private suspend fun buildExportJson(): BackupExportFile = withContext(Dispatchers.Default) {
        val file = backupRepository.export()
        val json = Serializer.moshi.adapter(LegacyBackupFile::class.java).toJson(file)
        BackupExportFile(json = json, sizeBytes = json.toByteArray(Charsets.UTF_8).size)
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

    // ---------- 自动备份（2026-09-16 用户反馈：持久化态回流 + 手动触发，执行体 AutoBackupRunner） ----------

    /**
     * 自动备份持久化态回流（开关/目录/上次运行三键）：持续跟随 DataStore 流——含
     * 冷启动 runIfDue 成功后 setLastRunMillis 的回填（「上次备份」时间自动翻新）。
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
            }
        }
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

    /**
     * 立即备份：与自动判定同一写路径（AutoBackupRunner.writeNow，写所选目录的
     * qimeng_backup.json 并翻新上次运行时间）。busy 置位防重（按钮显「备份中…」）；
     * 结果经常规错误横幅/操作结果提示反馈——未选目录、写入失败一律可见可重试。
     */
    fun writeAutoBackupNow() {
        if (_uiState.value.autoBackupBusy) return
        _uiState.update { it.copy(autoBackupBusy = true, errorMessage = null, noticeMessage = null) }
        viewModelScope.launch {
            val ok = runCatching { autoBackupRunner.writeNow() }.getOrDefault(false)
            _uiState.update {
                if (ok) {
                    it.copy(autoBackupBusy = false, noticeMessage = NOTICE_AUTO_BACKUP)
                } else {
                    it.copy(autoBackupBusy = false, errorMessage = ERROR_AUTO_BACKUP)
                }
            }
        }
    }

    // ---------- 跨端同步（2026-09-18 用户拍板：本机⇄服务器同步免来回导文件；2026-09-19
    // 任务R 四入口重做：源端一键「同步到另一端」=导出+暂存→提示切换；目标端 staged 非 null
    // 时顶部暂存卡+「导入并合并到当前端」走既有校验→确认→幂等导入链路。暂存只保留最新
    // 一份覆盖写的语义不变） ----------

    /** 进页回流暂存元数据+摘要（读失败按无暂存处理，不横幅——首次使用是常态） */
    private fun loadStagedMeta() {
        viewModelScope.launch {
            val staged = runCatching { syncStaging.loadStaged() }.getOrNull()
            // 元数据先进状态（卡先现），摘要过 Validator 后单独回填——摘要解析是 MB 级重活
            // （reviewer P1：真库量级信封双遍 Moshi 全量解析+字节拷贝跑 Main 会整页冻结），
            // 已由 [stagedSummaryMediaFiles] 收进 Default 池
            _uiState.update { it.copy(staged = staged?.meta) }
            if (staged != null) {
                val mediaFiles = stagedSummaryMediaFiles(staged.json)
                _uiState.update { it.copy(stagedMediaFiles = mediaFiles) }
            }
        }
    }

    /**
     * 暂存内容过 BackupValidator 取媒体文件数（任务R：暂存卡 N 文件项）。校验未过返回
     * null（卡上隐藏该项）；导入侧 confirm 前仍会完整重校验，此处不拦截不横幅——
     * 摘要展示与导入校验职责分离，单源都在 BackupValidator。
     * 为什么挂起+Default 池：validate 内是双遍 Moshi 全量解析+UTF-8 全量拷贝，真库
     * （6341 资产）量级信封几 MB～几十 MB，跑 Main 是进页冻结级重活——与
     * [buildExportJson] 序列化同款约束（reviewer P1 返工）。
     */
    private suspend fun stagedSummaryMediaFiles(json: String): Int? = withContext(Dispatchers.Default) {
        (BackupValidator.validate(STAGED_FILE_NAME, json.toByteArray(Charsets.UTF_8)) as? BackupValidator.Result.Ok)
            ?.summary?.mediaFiles
    }

    /**
     * 源端一键「同步到另一端」：导出全量信封（与「导出备份」同一段序列化）写进 App 内部
     * 暂存；来源服务端地址由暂存实现方捕获（导入侧展示「来自哪端」，防导错方向）。
     */
    fun stageForSync() {
        if (_uiState.value.stagingBusy) return
        _uiState.update { it.copy(stagingBusy = true, errorMessage = null, noticeMessage = null) }
        viewModelScope.launch {
            try {
                val export = buildExportJson()
                val meta = syncStaging.stage(export.json)
                // 摘要预先在 Default 池算好再进状态（update lambda 内禁止重活，reviewer P1 同款）
                val mediaFiles = stagedSummaryMediaFiles(export.json)
                _uiState.update {
                    it.copy(
                        stagingBusy = false,
                        staged = meta,
                        stagedMediaFiles = mediaFiles,
                        noticeMessage = NOTICE_STAGED.format((meta.sizeBytes / BYTES_PER_KB).roundToInt()),
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(stagingBusy = false, errorMessage = ERROR_STAGE) }
            }
        }
    }

    /**
     * 导入暂存：读暂存信封后走与「选择文件」完全相同的校验→确认弹窗→幂等导入链路
     * （onFilePicked 单源口径）。成功后暂存保留（同一份重复导入不翻倍，可作重试兜底）。
     * JSON→字节拷贝同样是 MB 级重活，先切 Default 池再进校验链（reviewer P1 同款）。
     */
    fun importStaged() {
        if (_uiState.value.importing) return
        viewModelScope.launch {
            val staged = runCatching { syncStaging.loadStaged() }.getOrNull()
            if (staged == null) {
                _uiState.update { it.copy(errorMessage = ERROR_STAGED_MISSING) }
                return@launch
            }
            val bytes = withContext(Dispatchers.Default) { staged.json.toByteArray(Charsets.UTF_8) }
            onFilePicked(STAGED_FILE_NAME, bytes)
        }
    }

    private companion object {
        /** KB 换算分母（Web (sizeBytes/1024).toFixed(0) 同口径；四舍五入由 roundToInt 承担） */
        const val BYTES_PER_KB = 1024.0

        /** 失败文案（同模块固定文案口径；Web 为 err.message 拼接，App 侧异常消息不逐字搬） */
        const val ERROR_EXPORT = "导出失败，请重试"
        const val ERROR_EXPORT_WRITE = "写入备份文件失败，请重试"
        const val ERROR_IMPORT = "导入失败，请重试"
        const val ERROR_READ_FILE = "读取文件失败，请重试"
        const val ERROR_SAVE_PREFS = "保存失败，请重试"
        const val ERROR_AUTO_BACKUP = "自动备份写入失败，请重试（请先选择备份目录）"

        /** 操作结果文案（Web BackupCard 各 mutate onSuccess toast 逐字对齐） */
        const val NOTICE_EXPORT = "已导出 qimeng_backup.json（%d KB）"
        const val NOTICE_IMPORT = "「%s」导入完成：匹配文件 %d/%d，作者 %d，标签 %d，事件回放 %d 条"

        /** 自动备份成功文案（文件名单源 = AutoBackupRunner.BACKUP_FILE_NAME） */
        const val NOTICE_AUTO_BACKUP = "已写入自动备份 " + AutoBackupRunner.BACKUP_FILE_NAME

        /** 浏览数据同步失败文案（SettingsViewModel 原款随迁） */
        const val EVENT_SYNC_FAILED_MESSAGE = "同步失败，请重试"

        /** 跨端暂存文案（任务R 2026-09-19 拍板措辞：提示切换到目标端） */
        const val ERROR_STAGE = "暂存失败，请重试"
        const val ERROR_STAGED_MISSING = "暂存数据不存在或已损坏，请重新暂存"
        const val NOTICE_STAGED = "已暂存（%d KB），请切换到目标端登录后回来导入"
        const val STAGED_FILE_NAME = "qimeng_backup.json"
    }
}
