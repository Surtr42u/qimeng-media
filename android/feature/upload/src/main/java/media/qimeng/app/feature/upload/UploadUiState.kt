package media.qimeng.app.feature.upload

import media.qimeng.app.core.model.AuthorSuggestion
import media.qimeng.app.core.model.DirNode
import media.qimeng.app.core.model.LibraryChoice
import media.qimeng.app.core.model.StagedUpload
import media.qimeng.app.core.model.UploadLimits
import media.qimeng.app.core.model.UploadQueueEntry
import media.qimeng.app.core.model.UploadStatus

/** 目标库必选提示（2026-09-25 流程重排：开始上传门禁，按钮提示与 enqueue 兜底同源） */
internal const val LIBRARY_REQUIRED_MESSAGE = "先选择目标库"

// ---------- 上传页反馈文案与防抖常量（自 UploadViewModel companion 迁出，控制 600 行红线；
// internal 供同模块 VM/测试可见） ----------

/** 配置不可达时的文案兜底值（正常不出现；仅展示层） */
internal const val UNKNOWN_LIMIT_MB = 2048L

/** 作者联想防抖（ms）：与编辑页/Web 端同值口径 */
internal const val AUTHOR_SUGGEST_DEBOUNCE_MS = 200L

/** 作品名联想防抖（ms）：与作者联想同档（暂存区重做） */
internal const val NAME_SUGGEST_DEBOUNCE_MS = 200L

internal const val LOAD_FAILED_MESSAGE = "加载库列表失败：请检查登录与服务端连接"
internal const val DIR_LOAD_FAILED_MESSAGE = "目录树加载失败，请重试"
internal const val DIR_CREATE_FAILED_MESSAGE = "新建目录失败，请重试"
internal const val INVALID_DIR_NAME_MESSAGE = "目录名不合法：不能为空、点段或包含路径分隔符"
internal const val DESCRIBE_FAILED_MESSAGE = "读取所选文件信息失败，请重试"
internal const val ENQUEUE_FAILED_MESSAGE = "上传任务创建失败，请重试"
internal const val ONLY_EXISTING_AUTHORS = "未找到该作者：仅能选择已有作者，请从联想中选择"
internal const val INBOX_NOT_SET_MESSAGE = "尚未设置下载收件箱：请到 数据管理 → 上传收件箱与归档 选择文件夹"
internal const val INBOX_NO_NEW_MESSAGE = "收件箱里没有新文件"
internal const val INBOX_SCAN_FAILED_MESSAGE = "读取收件箱失败：请检查文件夹是否仍在"

/** 浏览文件弹层确认后零新进（所选全部已在暂存区；2026-09-28「浏览文件」入口） */
internal const val BROWSE_NO_NEW_MESSAGE = "所选文件都已在暂存区"

/** 超限拦截文案（中文；列明上限与被拦文件；VM 与状态同文件单源） */
internal fun blockText(limits: UploadLimits?, blocked: List<StagedUpload>): String? {
    if (blocked.isEmpty()) return null
    val limitMb = limits?.maxBytesMb ?: UNKNOWN_LIMIT_MB
    val names = blocked.joinToString("、") { it.effectiveUploadName }
    return "以下文件超过服务端上限 $limitMb MB，已停止上传：$names"
}

/**
 * 上传流 UI 状态（2026-09-25 暂存区重做）：暂存条目/批次默认/收件箱路径来自
 * StagingRepository 持久流（combine 注入，杀进程重启不丢），其余为页面会话态。
 * 自 UI 状态文件拆出（UploadViewModel 600 行红线）：只放状态与派生口径，逻辑在 VM。
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
    /** 暂存条目（持久层真源；收件箱导入 + 相册多选/分享接收共用） */
    val pendingItems: List<StagedUpload> = emptyList(),
    /** 批次作者联想输入草稿 */
    val batchAuthorQuery: String = "",
    /** 批次作者联想结果（防抖回填） */
    val batchAuthorSuggestions: List<AuthorSuggestion> = emptyList(),
    /** 全站来源词表（GET /authors/source-vocabulary，仅单独词；失败静默 = 纯自由输入） */
    val sourceOptions: List<String> = emptyList(),
    /** 当前展开编辑的暂存条目 source（null = 无展开项；同时至多一项展开） */
    val editingSource: String? = null,
    /** 展开项的作者联想输入草稿 */
    val itemAuthorQuery: String = "",
    /** 展开项的作者联想结果（防抖回填） */
    val itemAuthorSuggestions: List<AuthorSuggestion> = emptyList(),
    /** 展开项的作品名联想建议基名（GET /assets/name-suggestions；最多 3 条） */
    val itemNameSuggestions: List<String> = emptyList(),
    /** 失效条目（收件箱路径类源已不存在；渲染「文件已不存在」+ 清除，不阻塞其他项） */
    val missingSources: Set<String> = emptySet(),
    /** 下载收件箱路径（null = 未设置；设置页选定后持久化） */
    val inboxPath: String? = null,
    /** 收件箱扫描进行中 */
    val scanningInbox: Boolean = false,
    /** 服务端配置 upload 组（超限本地拦截口径） */
    val limits: UploadLimits? = null,
    /** 超限/门禁拦截文案（入队时生成：未选库兜底、超限本地拦截；不阻断其余未超限项） */
    val blockMessage: String? = null,
    /** 非拦截类错误（加载失败/新建目录失败等） */
    val errorMessage: String? = null,
    /** 提示文案（联想未命中/收件箱无新文件等非失败反馈） */
    val noticeMessage: String? = null,
    /** 新建目录请求进行中 */
    val creatingDir: Boolean = false,
    /** 入队请求进行中 */
    val enqueueing: Boolean = false,
    /** 队列实时状态（WorkManager WorkInfo 映射） */
    val queue: List<UploadQueueEntry> = emptyList(),
) {
    /** 批次库对象（batchLibraryId 在库列表中的解析结果；库被删 = null，门禁兜底） */
    val selectedLibrary: LibraryChoice?
        get() = batchLibraryId?.let { id -> libraries.firstOrNull { it.id == id } }

    /** 队列里仍有活跃任务（排队/上传中） */
    val hasActiveWork: Boolean
        get() = queue.any { it.status == UploadStatus.QUEUED || it.status == UploadStatus.UPLOADING }

    /**
     * 开始上传门禁（2026-09-25 流程重排 + 暂存区重做）：有待传项且非入队中，
     * 且批次库已选——逐项全部带库覆盖时批次库可缺省（覆盖项按各自库入队）。
     */
    val canEnqueue: Boolean
        get() = pendingItems.isNotEmpty() && !enqueueing &&
            (batchLibraryId != null || pendingItems.all { it.libraryIdOverride != null })

    /** 未选库时的门禁提示（有待传项且存在未覆盖项才提示；按钮提示与 enqueue 兜底文案同源） */
    val enqueueGateHint: String?
        get() = if (
            pendingItems.isNotEmpty() && batchLibraryId == null &&
            pendingItems.any { it.libraryIdOverride == null }
        ) {
            LIBRARY_REQUIRED_MESSAGE
        } else {
            null
        }

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
}
