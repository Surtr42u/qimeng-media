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
)

/** 上传目标库（GET /libraries 的展示子集） */
data class LibraryChoice(
    val id: String,
    val name: String,
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
    /** 失败原因（4xx 服务端文案透传 / 重试耗尽说明） */
    val errorMessage: String?,
)

enum class UploadStatus { QUEUED, UPLOADING, SUCCEEDED, FAILED }

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
}
