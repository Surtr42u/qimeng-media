package media.qimeng.app.core.data.repository

import media.qimeng.app.core.data.backup.ValidatedBackupPayload
import media.qimeng.app.core.model.LegacyImportSummary

/**
 * 旧版格式备份端口（U10-6b，Web 文件管理页 BackupCard 对等物，DOMAIN_RULES §10）：
 * 导出 = GET /export/qimeng-backup 全量信封；导入 = POST /import/qimeng-backup
 * 幂等合并（按唯一键合并不删除现有数据，重复导入不翻倍）。
 *
 * 2026-10-03 feature:manage 撤 :sdk 直依赖批：签名收口域类型——导出直接出序列化后的
 * 旧版信封 JSON 文本（AutoBackupRunner 写盘专用，序列化收口实现内）；导入入参 =
 * [BackupValidator][media.qimeng.app.core.data.backup.BackupValidator] 校验产出的
 * 不透明载荷句柄，出参 = 消费投影 [LegacyImportSummary]。SDK 传输模型不再出现在
 * 签名（原「拍板接口签名直用生成模型」口径随撤依赖批退役）；前置校验同在 core:data
 * backup 包（BackupValidator，原 feature:manage 整体搬入）。
 */
interface BackupRepository {

    /** 导出当前库全量备份信封并序列化为旧版 JSON 文本（恒 qimeng_backup 信封；数十 MB 级） */
    suspend fun exportJson(): String

    /**
     * 导入备份载荷（幂等合并）；结果含匹配计数与迁移 warnings（旧版独有段的丢弃清单）。
     * 载荷必须来自 [media.qimeng.app.core.data.backup.BackupValidator.validate] 的真校验链。
     */
    suspend fun importBackup(payload: ValidatedBackupPayload): LegacyImportSummary
}
