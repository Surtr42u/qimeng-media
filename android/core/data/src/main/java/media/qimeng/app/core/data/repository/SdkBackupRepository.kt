package media.qimeng.app.core.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import media.qimeng.app.core.network.di.BackupClient
import media.qimeng.sdk.models.LegacyBackupFile
import media.qimeng.sdk.models.LegacyBackupImport
import media.qimeng.sdk.models.LegacyImportResult
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [BackupRepository] 生成 SDK 实现（U10-6b）。IO 搬移与 logcat 打点协议同
 * [SdkMediaRepository]（HANDOVER_APP §4.7 文本证据）。
 *
 * 出网走 @BackupClient 长超时客户端（2026-09-19 任务R）：导出/导入是同步长处理
 * （导入在服务端逐条幂等合并、大库分钟级），主客户端 10s 读超时必掐断——
 * 与上传通道超时同款根因，客户端派生共享 AuthInterceptor（鉴权不丢）。
 */
@Singleton
class SdkBackupRepository @Inject constructor(
    private val apiFactory: BusinessApiFactory,
    @BackupClient private val backupClient: OkHttpClient,
) : BackupRepository {

    override suspend fun export(): LegacyBackupFile {
        Log.d(SdkMediaRepository.LOG_TAG, "GET /export/qimeng-backup")
        return withContext(Dispatchers.IO) {
            apiFactory.createWith(backupClient).apiV1ExportQimengBackupGet()
        }
    }

    override suspend fun import(payload: LegacyBackupImport): LegacyImportResult {
        // 载荷可达数十 MB，logcat 只打口径键（格式/媒体文件数），不打全量
        Log.d(
            SdkMediaRepository.LOG_TAG,
            "POST /import/qimeng-backup format=${payload.format} mediaFiles=${payload.data.mediaFiles?.size ?: 0}",
        )
        return withContext(Dispatchers.IO) {
            apiFactory.createWith(backupClient).apiV1ImportQimengBackupPost(payload)
        }
    }
}
