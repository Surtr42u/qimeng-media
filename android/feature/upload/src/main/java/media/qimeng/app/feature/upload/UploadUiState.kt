package media.qimeng.app.feature.upload

import media.qimeng.app.core.data.upload.ArchiveBatchScanResult
import media.qimeng.app.core.model.AuthorSuggestion
import media.qimeng.app.core.model.DirNode
import media.qimeng.app.core.model.LibraryChoice
import media.qimeng.app.core.model.UploadItem
import media.qimeng.app.core.model.UploadLimits
import media.qimeng.app.core.model.UploadQueueEntry
import media.qimeng.app.core.model.UploadStatus

/** 目标库必选提示（2026-09-29 直传化：submitUris 门禁，横幅提示） */
internal const val LIBRARY_REQUIRED_MESSAGE = "先选择目标库"

// ---------- 上传页反馈文案与防抖常量（自 UploadViewModel companion 迁出，控制 600 行红线；
// internal 供同模块 VM/测试可见） ----------

/** 配置不可达时的文案兜底值（正常不出现；仅展示层） */
internal const val UNKNOWN_LIMIT_MB = 2048L

/** 作者联想防抖（ms）：与编辑页/Web 端同值口径 */
internal const val AUTHOR_SUGGEST_DEBOUNCE_MS = 200L

internal const val LOAD_FAILED_MESSAGE = "加载库列表失败：请检查登录与服务端连接"
internal const val DIR_LOAD_FAILED_MESSAGE = "目录树加载失败，请重试"
internal const val DIR_CREATE_FAILED_MESSAGE = "新建目录失败，请重试"
internal const val INVALID_DIR_NAME_MESSAGE = "目录名不合法：不能为空、点段或包含路径分隔符"
internal const val DESCRIBE_FAILED_MESSAGE = "读取所选文件信息失败，请重试"
internal const val ENQUEUE_FAILED_MESSAGE = "上传任务创建失败，请重试"
internal const val ONLY_EXISTING_AUTHORS = "未找到该作者：仅能选择已有作者，请从联想中选择"

/** 归档一键上传入队成功提示（count = 入队文件数；队列进度由既有 queueUpdates 流呈现） */
internal fun archiveBatchQueuedMessage(count: Int): String = "已加入上传队列：共 $count 个文件"

/**
 * 本地超限过滤（口径单源：手动直传 submitUris 与归档一键上传两路共用；判定走
 * [UploadLimits.overLimit] 唯一实现，调用侧禁止复制 MB 换算/边界口径）。
 * limits 为 null（GET /config 不可得）时不拦——交服务端 413 兜底（冻结口径）。
 * 返回（超限项, 放行项）。
 */
internal fun <T> partitionOverLimit(
    entries: List<T>,
    limits: UploadLimits?,
    sizeOf: (T) -> Long,
): Pair<List<T>, List<T>> =
    limits?.let { rule -> entries.partition { rule.overLimit(sizeOf(it)) } }
        ?: (emptyList<T>() to entries)

/**
 * 超限拦截文案（中文；列明上限与被拦文件名；VM 与状态同文件单源，直传与归档一键两路
 * 共用，文件名由 [nameOf] 适配各自条目形态——直传取 effectiveUploadName、归档取 file.name）。
 */
internal fun <T> blockText(limits: UploadLimits?, blocked: List<T>, nameOf: (T) -> String): String? {
    if (blocked.isEmpty()) return null
    val limitMb = limits?.maxBytesMb ?: UNKNOWN_LIMIT_MB
    val names = blocked.joinToString("、") { nameOf(it) }
    return "以下文件超过服务端上限 $limitMb MB，已停止上传：$names"
}

/**
 * 上传流 UI 状态（2026-09-29 直传化：暂存区退役，无待传条目/逐项编辑/入队门禁字段）。
 * 批次默认来自 StagingRepository.batchConfig 持久流（combine 注入，杀进程重启不丢），
 * 其余为页面会话态。自 UI 状态文件拆出（UploadViewModel 600 行红线）：只放状态与派生口径，
 * 逻辑在 VM。
 */
data class UploadUiState(
    /** 首屏库列表加载中 */
    val loading: Boolean = false,
    /** 可选目标库（GET /libraries） */
    val libraries: List<LibraryChoice> = emptyList(),
    /** 批次默认库 id（持久化配置；null = 未选） */
    val batchLibraryId: String? = null,
    /** 批次默认作者 id（持久化配置；null = 未选） */
    val batchAuthorId: String? = null,
    /** 批次默认作者展示名（持久化配置） */
    val batchAuthorName: String? = null,
    /** 批次默认来源（持久化配置；与批次作者同进退） */
    val batchSources: List<String> = emptyList(),
    /** 目标库目录树（GET /dirs；null = 未加载） */
    val dirTree: DirNode? = null,
    /** 已选目标目录（库内相对路径，空串 = 库根；会话态不持久） */
    val selectedDirPath: String = "",
    /** 批次作者联想输入草稿 */
    val batchAuthorQuery: String = "",
    /** 批次作者联想结果（防抖回填） */
    val batchAuthorSuggestions: List<AuthorSuggestion> = emptyList(),
    /** 全站来源词表（GET /authors/source-vocabulary，仅单独词；失败静默 = 纯自由输入） */
    val sourceOptions: List<String> = emptyList(),
    /** 服务端配置 upload 组（直传超限本地拦截口径） */
    val limits: UploadLimits? = null,
    /** 超限/门禁拦截文案（提交时生成：未选库兜底、超限本地拦截；不阻断其余未超限项） */
    val blockMessage: String? = null,
    /** 非拦截类错误（加载失败/新建目录失败/describe 失败等） */
    val errorMessage: String? = null,
    /** 提示文案（联想未命中等非失败反馈） */
    val noticeMessage: String? = null,
    /** 新建目录请求进行中 */
    val creatingDir: Boolean = false,
    /** 直传提交进行中（describe + 入队窗口；防重复触发） */
    val submitting: Boolean = false,
    /**
     * 归档一键重传扫描结果（2026-10-01 归档一键上传；null = 归档根未配置/尚未扫到/
     * 入队成功后待重扫）。扫描在库列表就绪后 IO 协程执行（scanArchiveForUpload 纯函数），
     * 本状态只承载结果。
     */
    val archiveBatch: ArchiveBatchScanResult? = null,
    /** 归档扫描进行中（进页面/库列表就绪后的一次性扫描窗口） */
    val archiveBatchLoading: Boolean = false,
    /**
     * 归档一键上传防重入队门禁（2026-10-01 审查修整项）：本会话已成功入队过一轮且重扫
     * 文件数未变化时为 true——一键按钮与确认键置灰，防止「源文件上传成功前仍在归档根」
     * 时整批重复入队（同名文件会在服务端生成副本）。重扫文件数与入队时不同（归档区
     * 内容已变化）由 VM 复位；全被超限拦截未入队时不置位（归档区未变，可调整后重试）。
     */
    val archiveBatchEnqueued: Boolean = false,
    /** 队列实时状态（WorkManager WorkInfo 映射） */
    val queue: List<UploadQueueEntry> = emptyList(),
) {
    /** 批次库对象（batchLibraryId 在库列表中的解析结果；库被删 = null，提交门禁兜底） */
    val selectedLibrary: LibraryChoice?
        get() = batchLibraryId?.let { id -> libraries.firstOrNull { it.id == id } }

    /** 队列里仍有活跃任务（排队/上传中） */
    val hasActiveWork: Boolean
        get() = queue.any { it.status == UploadStatus.QUEUED || it.status == UploadStatus.UPLOADING }

    /** 队列聚合行「共 N 个 · 成功 X · 失败 Y · 挂靠失败 Z」（空队列 null；从 queue 派生）。
     *  取消（CANCELLED）不计失败数——用户取消不是失败（批C 任务Q C-2）；
     *  挂靠失败（ATTACH_FAILED，挂靠批）文件已入库，成功/失败都不计、单独分列。 */
    val queueSummary: String?
        get() = queue.takeIf { it.isNotEmpty() }?.let { entries ->
            val base = "共 ${entries.size} 个 · 成功 ${entries.count { it.status == UploadStatus.SUCCEEDED }}" +
                " · 失败 ${entries.count { it.status == UploadStatus.FAILED }}"
            val attachFailed = entries.count { it.status == UploadStatus.ATTACH_FAILED }
            if (attachFailed > 0) "$base · 挂靠失败 $attachFailed" else base
        }

    /**
     * 归档一键重传摘要行（null = 未配置/未扫描）：「待传 N 个文件 · 命中 M 个库 ·
     * 未匹配 K 个文件夹」。命中库数按条目目标库去重（同库多文件夹只计一次）。
     */
    val archiveBatchSummary: String?
        get() = archiveBatch?.let { scan ->
            val matchedLibraries = scan.items.map { it.libraryId }.distinct().size
            "待传 ${scan.items.size} 个文件 · 命中 $matchedLibraries 个库 · " +
                "未匹配 ${scan.unmatchedFolders.size} 个文件夹"
        }

    /**
     * 归档扫描中超出服务端上限的文件数（确认弹窗「N 个超限文件将被跳过」的依据）。
     * 判定走 [partitionOverLimit] 单源（与两条入队管道同一口径）；limits 缺失时 0
     * （本地不判定，交服务端 413 兜底）。
     */
    val archiveBatchOverLimitCount: Int
        get() {
            val scan = archiveBatch ?: return 0
            return partitionOverLimit(scan.items, limits) { it.file.length() }.first.size
        }
}
