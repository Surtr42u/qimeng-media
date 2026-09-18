package media.qimeng.app.core.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import media.qimeng.app.core.network.ServerConfigDataSource

/** 暂存元数据（暂存时间/来源服务端/体积；暂存内容本体是旧版信封 JSON 文本） */
data class StagedBackupMeta(
    val stagedAtMillis: Long,
    val sourceUrl: String,
    val sizeBytes: Long,
)

/** 已暂存的完整数据（元数据 + 信封 JSON 文本） */
data class StagedBackup(
    val meta: StagedBackupMeta,
    val json: String,
)

/**
 * 跨端同步暂存端口（2026-09-18 用户拍板：本机⇄服务器同步免来回导文件）：
 * 连着 A 端导出全量信封 → 暂存到 App 内部存储 → 切换登录 B 端 → 读暂存走既有
 * 导入链路（幂等合并）。暂存语义 = **只保留最新一份**（重复暂存覆盖写，不做多份
 * 管理——同步目标恒是"另一端"，多份只会增加误导入旧数据的面）。
 *
 * 存储位置：App 内部存储（filesDir）而非 SAF——暂存是短生命周期中转，不该让用户
 * 挑目录；卸载即清，符合中转定位。来源服务端地址由实现方经 ServerConfigDataSource
 * 捕获（core:data 已依赖 core:network，调用方不感知地址单点）。
 */
interface SyncStagingRepository {

    /** 覆盖写暂存（JSON 文本，来源地址实现方自取）；返回落盘后的元数据 */
    suspend fun stage(json: String): StagedBackupMeta

    /** 读暂存；无暂存/元数据损坏返回 null（半截写入按空表自愈，与 clientlogs 同口径） */
    suspend fun loadStaged(): StagedBackup?
}

/** 内部存储文件实现（filesDir/sync-staging/ 下 backup.json + meta.properties 两个文件） */
@Singleton
class InternalFileSyncStagingRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serverConfig: ServerConfigDataSource,
) : SyncStagingRepository {

    override suspend fun stage(json: String): StagedBackupMeta =
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, STAGING_DIR_NAME)
            dir.mkdirs()
            val meta = StagedBackupMeta(
                stagedAtMillis = System.currentTimeMillis(),
                // 来源地址记进元数据：导入侧展示「来自哪端」防导错方向；防御性兜底不让
                // 地址读取失败炸掉暂存（备份页只在登录后可达，正常路径必非空）
                sourceUrl = runCatching { serverConfig.currentServerUrl() }.getOrNull() ?: SOURCE_URL_UNKNOWN,
                sizeBytes = json.toByteArray(Charsets.UTF_8).size.toLong(),
            )
            // 先写内容后写元数据：元数据存在 = 暂存完整（中断只留孤儿内容文件，读侧判 null 自愈）
            File(dir, BACKUP_FILE_NAME).writeText(json)
            Properties().apply {
                setProperty(KEY_STAGED_AT, meta.stagedAtMillis.toString())
                setProperty(KEY_SOURCE_URL, meta.sourceUrl)
                setProperty(KEY_SIZE_BYTES, meta.sizeBytes.toString())
            }.store(File(dir, META_FILE_NAME).writer(), "qimeng sync staging meta")
            meta
        }

    override suspend fun loadStaged(): StagedBackup? = withContext(Dispatchers.IO) {
        val backupFile = File(context.filesDir, "$STAGING_DIR_NAME/$BACKUP_FILE_NAME")
        val metaFile = File(context.filesDir, "$STAGING_DIR_NAME/$META_FILE_NAME")
        if (!backupFile.isFile || !metaFile.isFile) return@withContext null
        val meta = runCatching {
            Properties().apply { metaFile.reader().use { load(it) } }.let {
                StagedBackupMeta(
                    stagedAtMillis = it.getProperty(KEY_STAGED_AT)?.toLongOrNull() ?: return@withContext null,
                    sourceUrl = it.getProperty(KEY_SOURCE_URL) ?: return@withContext null,
                    sizeBytes = it.getProperty(KEY_SIZE_BYTES)?.toLongOrNull() ?: 0L,
                )
            }
        }.getOrNull() ?: return@withContext null
        StagedBackup(meta = meta, json = backupFile.readText())
    }

    private companion object {
        /** 暂存目录名（filesDir 下；单一职责，自动备份/常规导出不共享此目录） */
        const val STAGING_DIR_NAME = "sync-staging"
        const val BACKUP_FILE_NAME = "qimeng_backup.json"
        const val META_FILE_NAME = "meta.properties"
        const val KEY_STAGED_AT = "stagedAtMillis"
        const val KEY_SOURCE_URL = "sourceUrl"
        const val KEY_SIZE_BYTES = "sizeBytes"

        /** 地址读取失败时的占位（正常路径备份页只在登录后可达，必非空） */
        const val SOURCE_URL_UNKNOWN = "未知来源"
    }
}
