package media.qimeng.app.core.data.repository

import media.qimeng.sdk.models.LegacyBackupFile
import media.qimeng.sdk.models.LegacyBackupImport
import media.qimeng.sdk.models.LegacyImportResult

/**
 * 旧版格式备份端口（U10-6b，Web 文件管理页 BackupCard 对等物，DOMAIN_RULES §10）：
 * 导出 = GET /export/qimeng-backup 全量信封；导入 = POST /import/qimeng-backup
 * 幂等合并（按唯一键合并不删除现有数据，重复导入不翻倍）。
 * 接口直用生成传输模型（拍板签名）：信封/载荷/结果三型皆协议 DTO，
 * 序列化与前置校验的分工见 feature:manage BackupValidator/BackupViewModel。
 */
interface BackupRepository {

    /** 导出当前库全量备份信封（旧版单文件格式，恒 qimeng_backup） */
    suspend fun export(): LegacyBackupFile

    /** 导入备份载荷（幂等合并）；结果含匹配计数与迁移 warnings（旧版独有段的丢弃清单） */
    suspend fun import(payload: LegacyBackupImport): LegacyImportResult
}
