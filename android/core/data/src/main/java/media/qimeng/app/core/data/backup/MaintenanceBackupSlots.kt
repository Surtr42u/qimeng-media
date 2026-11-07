package media.qimeng.app.core.data.backup

import java.io.File

/**
 * 备份槽解析（2026-11-07 task-13 修复）：两代布局把「上次」放在**独立目录**
 * `上次/备份/影库备份.json`，旧 flat3 布局则把历史份放在同一目录的旧名文件
 * `备份/影库备份（上次）.json` / `备份/影库备份（上上次）.json`。
 * 备份页「恢复上次/恢复上上次」必须先按槽解析真实文件，不能再用「当前目录 + 历史固定名」
 * 拼路径——否则两代布局下历史恢复永远找不到文件（用户报障根因）。
 *
 * 解析契约：只读、不创建目录、不移动/删除任何文件；两代布局优先，缺失时才回退旧 flat3
 * （升级后旧文件原样保留，仍可只读恢复）。SAF 旧目录不在此解析，由 [BackupDirAccess] 直读。
 */
internal fun resolveMaintenanceBackupSlot(root: File, kind: BackupFileKind): File? {
    val currentBackup = File(File(File(root, CURRENT_GENERATION_DIR), BACKUP_DIR), BackupFileKind.CURRENT.fileName)
    val previousBackup = File(File(File(root, PREVIOUS_GENERATION_DIR), BACKUP_DIR), BackupFileKind.CURRENT.fileName)
    val legacyBackup = File(root, BACKUP_DIR)
    return when (kind) {
        // 当前槽：两代当前优先；升级后尚未生成当前时回退旧布局最新一份，
        // 与「偏好只在当前份存在后才改指」的写入口径一致，避免读空。
        BackupFileKind.MANUAL, BackupFileKind.AUTOMATIC, BackupFileKind.CURRENT ->
            currentBackup.takeIf { it.isFile }
                ?: File(legacyBackup, BackupFileKind.CURRENT.fileName).takeIf { it.isFile }
        // 上次槽：两代上次目录优先；旧 flat3 的「（上次）」文件只读回退。
        BackupFileKind.PREVIOUS ->
            previousBackup.takeIf { it.isFile }
                ?: File(legacyBackup, BackupFileKind.PREVIOUS.fileName).takeIf { it.isFile }
        // 上上次：两代布局按约定只保留一代历史，只有旧 flat3 目录可能存在该文件。
        BackupFileKind.OLDER ->
            File(legacyBackup, BackupFileKind.OLDER.fileName).takeIf { it.isFile }
        // 导入前保护槽只在写入侧使用，不是恢复入口。
        BackupFileKind.PRE_IMPORT -> null
    }
}

/**
 * 读取备份槽字节并做前置摘要核对（超限/摘要不符返回 null，不把损坏或已被替换的文件
 * 交给 v2 导入校验链）；摘要文件缺失按旧 flat3 兼容口径放行（真校验在 BackupValidator）。
 * 与 [BackupDirAccess] 的文件分支同口径：摘要用于检测损坏与替换，不证明发布者身份。
 */
internal fun readMaintenanceBackupSlot(
    file: File,
    maxBytes: Long = BackupValidator.BACKUP_MAX_BYTES.toLong(),
): ByteArray? = runCatching {
    if (!file.isFile) return@runCatching null
    require(file.length() <= maxBytes) { "备份文件超过大小上限" }
    val bytes = file.readBytes()
    val checksum = File(file.parentFile, file.name + ".sha256")
    if (checksum.isFile) {
        require(checksum.length() <= 1024) { "备份摘要文件过大" }
        require(checksum.readText(Charsets.UTF_8).trim() == maintenanceSha256(bytes)) {
            "备份摘要不一致，请重新导出完整备份"
        }
    }
    bytes
}.getOrNull()
