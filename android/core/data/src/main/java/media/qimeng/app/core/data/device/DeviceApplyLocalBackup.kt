package media.qimeng.app.core.data.device

import java.io.File

/**
 * 本机两代备份的 SAF/共享存储门禁（纯路径推导，JVM 单测锁定）。
 *
 * 与 [media.qimeng.app.core.data.backup.MaintenanceFolderRepository] 的目录约定同源：
 * 维护根下「当前 / 上次」两代各有 作者资料/作者总表.txt 与 备份/影库备份.json。
 * Worker 只通过已持久化的维护根写这两代（export(automatic=true)），不接受任意路径；
 * 目录未设置或缺少「所有文件访问」时返回 failed，让 UI 引导用户授权。
 *
 * 路径字符串只用于日志/门禁与测试；实际写入仍由 MaintenanceFolderRepository 做
 * canonical 校验、原子替换与摘要核对，本对象不直接落盘。
 */
object DeviceApplyLocalBackup {

    const val CURRENT_DIR = "当前"
    const val PREVIOUS_DIR = "上次"
    const val AUTHORS_DIR = "作者资料"
    const val BACKUP_DIR = "备份"
    const val AUTHOR_MASTER_FILE = "作者总表.txt"
    const val BACKUP_FILE = "影库备份.json"

    /** 设置页/维护页共用的提示：没有维护目录时不能安全应用结果包。 */
    const val MISSING_ROOT_MESSAGE =
        "未设置维护目录：应用前无法保存本机两代备份。请打开 数据管理 → 资料备份与归档 选择并授权目录后重试。"

    /** 权限被撤回/未授予时的提示（与目录未设置分开，便于用户定位系统设置）。 */
    const val MISSING_PERMISSION_MESSAGE =
        "缺少所有文件访问权限：无法写入维护目录保存两代备份。请在系统设置中授权后重试。"

    /** 应用前的本机写入门禁。 */
    fun gate(root: String?, hasAllFilesAccess: Boolean): LocalWriteGate {
        if (root.isNullOrBlank()) return LocalWriteGate.Blocked(MISSING_ROOT_MESSAGE)
        if (!hasAllFilesAccess) return LocalWriteGate.Blocked(MISSING_PERMISSION_MESSAGE)
        return LocalWriteGate.Ready
    }

    /** 两代四个固定文件（目录约定；Worker 只检查，不做路径拼接写入）。 */
    fun generationFiles(root: String): List<String> = listOf(
        "$CURRENT_DIR/$AUTHORS_DIR/$AUTHOR_MASTER_FILE",
        "$CURRENT_DIR/$BACKUP_DIR/$BACKUP_FILE",
        "$PREVIOUS_DIR/$AUTHORS_DIR/$AUTHOR_MASTER_FILE",
        "$PREVIOUS_DIR/$BACKUP_DIR/$BACKUP_FILE",
    ).map { relative -> File(root, relative).path }

    /** 两代目录（当前/上次）；export 后应存在，否则视为布局写入失败。 */
    fun missingGenerationDirectories(root: File): List<String> = listOf(CURRENT_DIR, PREVIOUS_DIR)
        .filterNot { File(root, it).isDirectory }

    fun currentBackupFile(root: File): File = File(File(File(root, CURRENT_DIR), BACKUP_DIR), BACKUP_FILE)
}

sealed interface LocalWriteGate {
    data object Ready : LocalWriteGate
    data class Blocked(val message: String) : LocalWriteGate
}
