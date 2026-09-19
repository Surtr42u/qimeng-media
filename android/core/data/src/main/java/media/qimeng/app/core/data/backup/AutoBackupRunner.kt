package media.qimeng.app.core.data.backup

import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import media.qimeng.app.core.data.repository.BackupAutoPrefsRepository
import media.qimeng.app.core.data.repository.BackupRepository
import media.qimeng.sdk.infrastructure.Serializer
import media.qimeng.sdk.models.LegacyBackupFile
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自动备份执行器（2026-09-15 批）：旧版「备份目录 + 自动写入全量备份」（GUIDE_BACKUP）
 * 的移植。触发口径 = App 冷启动登录态就绪后跑一次判定：开启且距上次成功 ≥24h 才写；
 * 写入目录 = 用户以 SAF 授权的树 URI（授权经 takePersistableUriPermission 持久化）。
 * 为什么不做 WorkManager 后台：导出走服务端 GET /export，App 不在前台/服务端未起时
 * 后台任务必然失败——「打开应用即自动备份」才是本形态的有效口径（旧版同为应用内写）。
 *
 * 任务S 2026-09-19 两卡收敛：本执行器同时是备份页「导入导出」卡的读写执行体——导出/
 * 导入直接读写备份目录的固定名文件（不弹 SAF 选择器），与自动判定共用同一条写链路
 * 与同一份授权；SAF 读写细节在 [BackupDirAccess] 端口单源收口（Context 依赖随之移出）。
 */
@Singleton
class AutoBackupRunner @Inject constructor(
    private val backupRepository: BackupRepository,
    private val prefs: BackupAutoPrefsRepository,
    private val dirAccess: BackupDirAccess,
) {

    /** 判定 + 执行；返回是否实际写入了备份（调用方仅记日志，不打扰用户） */
    suspend fun runIfDue(now: Long = System.currentTimeMillis()): Boolean {
        val st = prefs.state.first()
        if (!st.enabled) return false
        val dirUri = st.dirUri ?: return false
        if (st.lastRunMillis > 0 && now - st.lastRunMillis < MIN_INTERVAL.toMillis()) return false
        val ok = writeToDir(dirUri) != null
        if (ok) prefs.setLastRunMillis(now)
        return ok
    }

    /**
     * 直接写入备份目录并翻新上次运行时间（备份页「导出」与自动判定共用同一条写链路，
     * 任务S 2026-09-19：固定文件名覆盖式，永远最新数据；「上次备份时间」=上次写目录时间）。
     * 返回实写字节数（成功反馈的 KB 口径源）；未设备份目录或写入失败返回 null。
     */
    suspend fun writeNow(): Long? {
        val dirUri = prefs.state.first().dirUri ?: return null
        val written = writeToDir(dirUri) ?: return null
        prefs.setLastRunMillis(System.currentTimeMillis())
        return written
    }

    /** 读备份目录内固定名备份文件全量字节（备份页「导入」数据源）；未设目录/无文件/读失败返回 null */
    suspend fun readBackupBytes(): ByteArray? {
        val dirUri = prefs.state.first().dirUri ?: return null
        return dirAccess.readBytes(dirUri)
    }

    /** 读备份目录内固定名备份文件状态（备份页状态行数据源）；未设目录/无文件/查询失败返回 null */
    suspend fun readFileStatus(): BackupFileStatus? {
        val dirUri = prefs.state.first().dirUri ?: return null
        return dirAccess.fileStatus(dirUri)
    }

    /**
     * 导出信封 → JSON 文本 → 目录写入。为什么序列化挂 Default 池：数十 MB 备份的 Moshi
     * 序列化 + UTF-8 全量拷贝是纯 CPU 重活（任务R reviewer P2 纪律，原 BackupViewModel
     * buildExportJson 同口径随写链路迁移至此）；磁盘段在 [BackupDirAccess] 实现的 IO 池。
     */
    private suspend fun writeToDir(dirUri: String): Long? {
        val json = withContext(Dispatchers.Default) {
            val file = backupRepository.export()
            Serializer.moshi.adapter(LegacyBackupFile::class.java).toJson(file)
        }
        return dirAccess.writeBytes(dirUri, json)
    }

    companion object {
        /** 自动备份最小间隔：24h（每日一次口径，旧版「自动写入全量备份」节奏） */
        val MIN_INTERVAL: Duration = Duration.ofHours(24)

        const val BACKUP_FILE_NAME = "qimeng_backup.json"

        /** SAF 树 URI 授权持久化标志（目录选择后由调用方 takePersistableUriPermission） */
        const val TREE_PERMISSION_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
