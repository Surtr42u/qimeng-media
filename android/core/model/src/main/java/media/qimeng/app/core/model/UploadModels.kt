package media.qimeng.app.core.model

/**
 * 上传主通道领域模型（M4-5）。uri 用 String 承载：本模块零 Android 依赖，
 * content:// 形态由 :core:data 与 Android 层互转。
 */

/** 一个待上传的本地文件（SAF 多选结果或系统分享接收结果） */
data class UploadItem(
    /** content:// URI 字符串（SAF/分享授予的读权限到设备重启前有效，覆盖队列重试窗口） */
    val uri: String,
    /** 展示名（OpenableColumns.DISPLAY_NAME，同时作为上传 filename 查询参数） */
    val displayName: String,
    /** 字节数；-1 = 未知（此时超限本地拦截跳过、由服务端 413 兜底） */
    val sizeBytes: Long,
    /**
     * 库内相对子目录（'/' 分隔、不含首尾斜杠）。空串 = 文件级上传（相对页面已选目标目录
     * 本身）；选文件夹上传时为「所选文件夹名/子路径」（所选文件夹名作为首段，U10-6c）。
     * 入队时与页面已选目录由 UploadRules.joinUploadDirPath 拼成每个任务自己的 dir。
     */
    val relativeDir: String = "",
    /**
     * 编辑后的落库文件名（null/blank = 未编辑，回退 [displayName]）。
     * 这是上传 filename 查询参数的实际值（作者匹配与展示依据），实际取值统一走
     * [effectiveUploadName]（单一口径，UI/入队/worker 均不自行回退）。
     */
    val uploadFileName: String? = null,
    /**
     * 编辑后的落库基名（不含扩展名；null/blank = 未编辑，回退 [displayName] 的基名）。
     * 扩展名锁定口径：编辑只改基名，扩展名锁定保持不变。
     */
    val uploadBaseName: String? = null,
    /** 上传成功后自动挂靠的作者 id（null = 该项不带挂靠；来源挂靠以其存在为前提） */
    val attachAuthorId: String? = null,
    /** 挂靠作者的展示名（纯 UI 展示；不进 WorkManager 载荷，服务端只认 id） */
    val attachAuthorName: String? = null,
    /** 上传成功后并入作者来源区的来源词（append 语义永不覆盖；null/空 = 不挂来源） */
    val attachSources: List<String>? = null,
    /**
     * 目标库展示名（2026-09-28 上传归档文件夹功能：worker 上传成功后把路径类源文件归档到
     * <归档文件夹>/<库名>/，库名经此随载荷入队）。空串 = 入队时未解析到库名（旧调用/
     * 解析失败），worker 侧回退既有 uploaded/ 归档。
     */
    val libraryName: String = "",
    /**
     * 源文件已在归档根标志（2026-10-01 归档一键上传）：true = 条目来自归档文件夹一键上传，
     * worker 上传成功后跳过归档移动——源文件本就在 <归档根>/<库名>/ 内，重复归档会把
     * 同一路径走「删旧放新」冲突路径（先删目标位再落新），源与目标同路径时等于删源，必须整体跳过。
     * 默认 false = 既有归档行为不变。
     */
    val alreadyArchived: Boolean = false,
) {
    /** 展示名锁定的扩展名（含点；无扩展名/点前缀隐藏文件 = 空串） */
    val extension: String
        get() = UploadNaming.extensionOf(displayName)

    /** 展示名去掉扩展名后的基名（输入框未编辑时的展示值） */
    val defaultBaseName: String
        get() = UploadNaming.baseNameOf(displayName)

    /** 当前编辑落库名的基名（若未编辑则为 defaultBaseName） */
    val currentBaseName: String
        get() = uploadBaseName ?: uploadFileName?.let { UploadNaming.baseNameOf(it) } ?: defaultBaseName

    /** 上传 filename 参数实际取值（编辑优先、trim 后非空才生效，否则回退展示名；经 UploadRules.sanitizeFileName 规范化） */
    val effectiveUploadName: String
        get() {
            val candidate = when {
                uploadBaseName != null -> {
                    val base = uploadBaseName.trim()
                    if (base.isEmpty()) displayName
                    else UploadNaming.composeUploadName(base, extension).ifEmpty { displayName }
                }
                uploadFileName != null -> uploadFileName.trim().ifEmpty { displayName }
                else -> displayName
            }
            return UploadRules.sanitizeFileName(candidate)
        }
}

/**
 * 上传文件名纯规则（无 IO，单测锁定）：基名/扩展名拆装。
 * 扩展名锁定口径的单一实现——UI 只允许编辑基名，完整落库名一律经 [composeUploadName] 拼装，
 * 禁止散拼（对齐 Web 端 upload-naming.ts，三端同一口径）。
 */
object UploadNaming {

    /** 文件名去掉扩展名后的基名（".jpg" -> ""；".hidden" -> ".hidden" 整体为基名） */
    fun baseNameOf(fileName: String): String {
        val idx = fileName.lastIndexOf('.')
        return if (idx <= 0) fileName else fileName.substring(0, idx)
    }

    /** 文件名的扩展名（含点；无扩展名/点前缀隐藏文件返回空串） */
    fun extensionOf(fileName: String): String {
        val idx = fileName.lastIndexOf('.')
        return if (idx <= 0) "" else fileName.substring(idx)
    }

    /**
     * 基名 + 锁定扩展名 -> 完整落库名。基名 trim 后为空返回空串（调用方回退展示名）；
     * 扩展名为空则只取基名。
     */
    fun composeUploadName(base: String, extension: String): String {
        val trimmed = base.trim()
        if (trimmed.isEmpty()) return ""
        val ext = extension.trim()
        return if (ext.isEmpty()) trimmed else trimmed + ext
    }
}

/** 上传目标库（GET /libraries 的展示子集） */
data class LibraryChoice(
    val id: String,
    val name: String,
)

/**
 * 作者联想结果条目（GET /authors/suggest 映射；REQ §3.1① 上传挂靠输入框数据源）。
 * displayName 已含 ` / ` 连接的全部别名，别名片段可命中同一作者（协议口径）。
 */
data class AuthorSuggestion(
    val id: String,
    val displayName: String,
    /** 该作者名下文件数（联想行副文案） */
    val fileCount: Int,
)

/**
 * 目录树节点（GET /dirs 的 DirTree 映射）。path = 库内相对路径、'/' 分隔、
 * 根为空串（协议口径）；fileCount 只计直接子文件。
 */
data class DirNode(
    val path: String,
    val fileCount: Int,
    val children: List<DirNode>,
)

/** 客户端配置 upload 组（GET /api/v1/config；超限本地拦截的唯一口径来源） */
data class UploadLimits(
    /** 单文件上限 MB（协议 64–8192，缺省 2048） */
    val maxBytesMb: Long,
    /** 服务端自动接收开关；false 时上传 403 UPLOAD_DISABLED（文案透传展示） */
    val autoAccept: Boolean,
) {
    /** 超限判定（sizeBytes 未知 = -1 时不拦，交给服务端 413 兜底） */
    fun overLimit(sizeBytes: Long): Boolean =
        sizeBytes >= 0 && sizeBytes > maxBytesMb * BYTES_PER_MB

    private companion object {
        /** MB -> 字节换算（协议 maxBytesMb 单位为 MB） */
        const val BYTES_PER_MB = 1024L * 1024L
    }
}

/** 队列单条状态（WorkManager WorkInfo 映射，UI 直接渲染） */
data class UploadQueueEntry(
    /** 客户端生成的任务标识（入队时分配，WorkInfo tag 关联） */
    val localId: String,
    val displayName: String,
    val status: UploadStatus,
    /** 0..100；null = 未开始/未知 */
    val progressPercent: Int?,
    /** 服务端冲突自动重命名后的最终文件名（成功时携带） */
    val finalFileName: String?,
    /** 失败原因（4xx 服务端文案透传 / 重试耗尽说明）；ATTACH_FAILED 时为补挂指引文案 */
    val errorMessage: String?,
)

/**
 * 队列任务状态。CANCELLED（批C 任务Q）：用户主动取消的终态——与 FAILED 分列，聚合行
 * 「失败 N」不把取消计入失败数（用户取消不是失败，UI 文案也分列「已取消」）。
 * ATTACH_FAILED（挂靠批）：文件已入库但自动挂靠失败——文件本身成功（finalFileName 在），
 * 聚合行既不计成功也不计失败、单独「挂靠失败 Z」计数。
 */
enum class UploadStatus { QUEUED, UPLOADING, SUCCEEDED, FAILED, CANCELLED, ATTACH_FAILED }

/**
 * 上传纯规则（无 IO，单测锁定）：目录路径拼装与本地校验。
 * 客户端校验只是输入卫生；服务端 NormalizeRelPath 仍是唯一权威（越界由 400 兜底）。
 */
object UploadRules {

    /**
     * 校验"新建子目录"名单段合法性：非空、不含路径分隔符、非点段。
     * 为什么客户端先拦：拼 path 前的基本卫生，避免拼出 a//b 或 a/../b 形态的明显垃圾。
     */
    fun isValidDirName(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") return false
        return !trimmed.contains('/') && !trimmed.contains('\\')
    }

    /**
     * 把新目录名拼到当前选中目录下，返回库内相对路径（'/' 分隔、无首尾斜杠）；
     * 名字非法返回 null。[selectedDir] 允许带首尾斜杠（容错），空串 = 库根。
     */
    fun joinDirPath(selectedDir: String, newName: String): String? {
        if (!isValidDirName(newName)) return null
        val base = selectedDir.trim('/')
        return if (base.isEmpty()) newName.trim() else "$base/${newName.trim()}"
    }

    /**
     * 把待上传文件的相对子目录拼到页面已选目标目录后，返回该任务自己的库内相对目录
     * （U10-6c：dir=joinUploadDirPath(selectedDirPath, item.relativeDir)）。
     * 与 [joinDirPath] 的区别：[relative] 是多段路径（文件夹扫描产物），不做名单段校验；
     * 拼出路径的最终安全性由服务端 NormalizeRelPath 兜底（400 唯一权威）。
     *
     * 口径对齐 server/internal/filing/path.go:56（NormalizeRelPath）：反斜杠一律归一为
     * '/'（Windows 形态输入不产生混合分隔符）；两端各 trim 掉 '/'（容错，同 joinDirPath）。
     * 纯函数，单测锁定（UploadModelsTest）。
     */
    fun joinUploadDirPath(base: String, relative: String): String {
        val b = base.replace('\\', '/').trim('/')
        val r = relative.replace('\\', '/').trim('/')
        return when {
            b.isEmpty() -> r
            r.isEmpty() -> b
            else -> "$b/$r"
        }
    }

    /**
     * 点击目录行时是否同时展开该层（V7 修复）：COS 等带层级的库，用户点目录行
     * 的心智是"点进去看到子文件夹"，只选中不展开会让子级看起来"不可点"
     * （唯一展开入口 ▸ 小箭头不可发现；全叶子的普通库则点行即达，行为分叉即 bug 观感）。
     * 规则：有子级且尚未展开才展开；已展开或叶子返回 false（收起仍走 ▾ 箭头，
     * 避免选中确认路径上子级忽隐忽现）。纯函数，单测锁定，UI 层只消费。
     */
    fun shouldExpandOnSelect(hasChildren: Boolean, alreadyExpanded: Boolean): Boolean =
        hasChildren && !alreadyExpanded

    // ---- 源标识判定 ----

    /**
     * 源标识是否为绝对路径类（与 content:// 类区分：路径类走 File API 读流、上传成功后
     * 可归档）。纯函数，worker/上传器共用单源。
     */
    fun isAbsoluteFilePath(source: String): Boolean = source.startsWith("/")

    // ---- 文件名安全清洗与规范化（对齐 server/internal/filing/filename.go 与 reserved.go） ----

    /** Windows 保留设备名全集（大小写不敏感，对齐 server/internal/filing/reserved.go） */
    val WINDOWS_RESERVED_NAMES: Set<String> = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        for (i in 1..9) {
            add("COM$i")
            add("LPT$i")
        }
    }

    /** 是否命中 Windows 保留设备名（以第一个点之前的部分判定，对齐 server/internal/filing/reserved.go） */
    fun isWindowsReservedName(name: String): Boolean {
        val base = name.substringBefore('.').uppercase()
        return base in WINDOWS_RESERVED_NAMES
    }

    /**
     * 清洗与规范化上传文件名（对齐服务端 docs/SECURITY.md 上传安全规范与 SanitizeFilename 规则）。
     *
     * 规则：
     * 1. 剥离路径前缀（仅取最后文件名段，防 /storage/... 或 URL 越权）；
     * 2. 剥离路径分隔符 / 与 \、控制字符 (<0x20, 0x7F) 以及 Windows 非法字符 : * ? " < > |；
     * 3. 剥离首尾空格与首尾点；
     * 4. 名字全空或全部由非法字符构成时，由 [fallbackBaseName] 兜底（默认 "upload"）；
     * 5. 若无扩展名且提供了 [fallbackExtension] 时，自动追加 .扩展名；
     * 6. 防御 Windows 保留设备名（CON, PRN, AUX, NUL, COM1-9, LPT1-9）：命中则加 "file_" 前缀，
     *    避免服务端 400 INVALID_FILENAME 拒绝。
     */
    fun sanitizeFileName(
        rawName: String?,
        fallbackExtension: String? = null,
        fallbackBaseName: String = "upload",
    ): String {
        // 1. 提取单段文件名（剥离路径前缀与反斜杠）
        val candidate = rawName.orEmpty()
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()

        val cleanExtFallback = fallbackExtension?.trim()?.trimStart('.')?.lowercase()

        // 2. 分离基名与扩展名（若有点）
        val lastDotIndex = candidate.lastIndexOf('.')
        val (rawBase, rawExt) = if (lastDotIndex >= 0) {
            candidate.substring(0, lastDotIndex) to candidate.substring(lastDotIndex + 1)
        } else {
            candidate to ""
        }

        // 3. 过滤基名与扩展名中的非法字符（路径分隔符、控制字符、Windows非法字符）
        val cleanBase = stripIllegalChars(rawBase).trim { it == ' ' || it == '.' }
        val cleanExt = stripIllegalChars(rawExt).trim { it == ' ' || it == '.' }

        // 4. 基名与扩展名兜底
        val finalBase = cleanBase.ifEmpty { fallbackBaseName.ifBlank { "upload" } }
        val finalExt = cleanExt.ifEmpty { cleanExtFallback.orEmpty() }

        // 5. 组装
        val assembled = if (finalExt.isNotEmpty()) "$finalBase.$finalExt" else finalBase

        // 6. Windows 保留名加前缀保护
        return if (isWindowsReservedName(assembled)) "file_$assembled" else assembled
    }

    private fun stripIllegalChars(str: String): String {
        val sb = StringBuilder(str.length)
        for (c in str) {
            if (c == '/' || c == '\\') continue
            if (c.code < 0x20 || c.code == 0x7F) continue
            if (c in ":*?\"<>|") continue
            sb.append(c)
        }
        return sb.toString()
    }
}
