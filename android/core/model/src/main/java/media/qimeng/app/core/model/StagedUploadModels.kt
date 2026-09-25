package media.qimeng.app.core.model

/**
 * 上传暂存区持久模型（2026-09-25 暂存区重做：暂存条目 + 批次配置跨进程持久化）。
 * 本模块零 Android 依赖（同 UploadItem 口径）；持久化编码（JSON）与存储在 :core:data。
 */

/**
 * 一条暂存的上传条目（持久化到 DataStore，杀进程/隔天不丢）。
 *
 * [source] 是唯一源标识与稳定 id：
 * - 收件箱导入 = 绝对路径（isPathSource=true，File API 直读、上传成功后可归档 uploaded/）；
 * - 相册多选/SAF/系统分享 = content:// uri（isPathSource=false，读权限随媒体权限存活）。
 */
data class StagedUpload(
    /** 源标识：收件箱绝对路径 或 content:// uri 字符串 */
    val source: String,
    /** true = 收件箱绝对路径类（File 存在性校验 + 上传成功后归档 uploaded/ 的对象） */
    val isPathSource: Boolean,
    /** 展示名（原文件名，含扩展名；同时是扩展名锁定的唯一依据） */
    val displayName: String,
    /** 字节数；-1 = 未知（超限本地拦截跳过、由服务端 413 兜底，UploadItem 同口径） */
    val sizeBytes: Long,
    /** true = 视频来源（缩略图角标用；收件箱扫描按扩展名判定、相册按 MediaStore 给值） */
    val isVideo: Boolean,
    /**
     * 编辑后的落库**基名**（不含扩展名；null/blank = 未编辑，回退 [displayName]）。
     * 扩展名锁定口径：仅基名可编辑，完整落库名 = 基名 + [extension]（[effectiveUploadName]）。
     */
    val uploadBaseName: String? = null,
    /** 上传成功后自动挂靠的作者 id（null = 不带挂靠；来源挂靠以其存在为前提） */
    val attachAuthorId: String? = null,
    /** 挂靠作者的展示名（纯 UI 展示；服务端只认 id） */
    val attachAuthorName: String? = null,
    /** 上传成功后并入作者来源区的来源词（append 语义；null/空 = 不挂来源） */
    val attachSources: List<String>? = null,
    /**
     * 逐项目标库覆盖（可空 = 跟随批次默认库）。逐项全有覆盖时开始上传不要求批次库
     * （门禁口径见 UploadUiState.canEnqueue）；覆盖项入队到其库的根目录（库与目录树
     * 是一一对应的批次概念，覆盖项未加载目录树，落库根由服务端 NormalizeRelPath 兜底）。
     */
    val libraryIdOverride: String? = null,
    /** 加入暂存区的时间戳（ms；收件箱扫描条目取文件修改时间，展示排序备用） */
    val addedAtMs: Long = 0L,
) {
    /** 展示名锁定的扩展名（含点；无扩展名/点前缀隐藏文件 = 空串） */
    val extension: String
        get() = UploadNaming.extensionOf(displayName)

    /** 展示名去掉扩展名后的基名（输入框未编辑时的展示值） */
    val defaultBaseName: String
        get() = UploadNaming.baseNameOf(displayName)

    /**
     * 上传 filename 参数实际取值：编辑基名（trim 非空）+ 锁定扩展名；未编辑/清空回退展示名。
     * 单一口径（UI 展示/入队载荷均走此值，不自行拼装）。
     */
    val effectiveUploadName: String
        get() {
            val base = uploadBaseName?.trim()?.takeIf { it.isNotEmpty() } ?: return displayName
            return UploadNaming.composeUploadName(base, extension).takeIf { it.isNotEmpty() } ?: displayName
        }
}

/**
 * 上传批次默认配置（持久化：目标库/作者/来源三项，暂存区新进项自动继承）。
 * 与 [StagedUpload] 同文件持久；目标目录不持久（目录树随库加载，会话态）。
 */
data class StagingBatchConfig(
    /** 批次目标库 id（null = 未选；开始上传门禁要求已选或逐项全覆盖） */
    val libraryId: String? = null,
    /** 批次作者 id（null = 未选；来源以其为前提） */
    val authorId: String? = null,
    /** 批次作者展示名（纯 UI 展示） */
    val authorName: String? = null,
    /** 批次来源词（批次作者未选时恒空） */
    val sources: List<String> = emptyList(),
)

/**
 * 上传文件名纯规则（无 IO，单测锁定）：基名/扩展名拆装。扩展名锁定口径的单一实现——
 * UI 只允许编辑基名，完整落库名一律经 [composeUploadName] 拼装，禁止散拼。
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
     * 基名 + 锁定扩展名 -> 完整落库名。基名 trim 后为空返回空串（调用方回退展示名，
     * 与 StagedUpload.effectiveUploadName 的回退口径配套）；扩展名为空则只取基名。
     */
    fun composeUploadName(base: String, extension: String): String {
        val trimmed = base.trim()
        if (trimmed.isEmpty()) return ""
        val ext = extension.trim()
        return if (ext.isEmpty()) trimmed else trimmed + ext
    }
}
