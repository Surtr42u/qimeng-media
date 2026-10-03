package media.qimeng.app.core.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import media.qimeng.app.core.data.backup.ValidatedBackupPayload
import media.qimeng.app.core.model.LegacyImportSummary
import media.qimeng.app.core.network.di.BackupClient
import media.qimeng.sdk.infrastructure.Serializer
import media.qimeng.sdk.models.LegacyBackupFile
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
 *
 * 2026-10-03 撤 :sdk 直依赖批：签名收口域类型——SDK 模型的序列化（导出）与映射
 * （导入结果 → LegacyImportSummary 消费投影）下沉本实现，不再外泄。
 */
@Singleton
class SdkBackupRepository @Inject constructor(
    private val apiFactory: BusinessApiFactory,
    @BackupClient private val backupClient: OkHttpClient,
) : BackupRepository {

    override suspend fun exportJson(): String {
        Log.d(SdkMediaRepository.LOG_TAG, "GET /export/qimeng-backup")
        // 出网与序列化都在 IO/Default 池（调用方 AutoBackupRunner 不再持有序列化段——
        // 数十 MB 备份的 Moshi 序列化 + UTF-8 拷贝是纯 CPU 重活，随搬实现内收口）
        return withContext(Dispatchers.IO) {
            val file: LegacyBackupFile = apiFactory.createWith(backupClient).apiV1ExportQimengBackupGet()
            withContext(Dispatchers.Default) {
                Serializer.moshi.adapter(LegacyBackupFile::class.java).toJson(file)
            }
        }
    }

    override suspend fun importBackup(payload: ValidatedBackupPayload): LegacyImportSummary {
        // 载荷可达数十 MB，logcat 只打口径键（格式/媒体文件数），不打全量
        Log.d(
            SdkMediaRepository.LOG_TAG,
            "POST /import/qimeng-backup format=${payload.sdkPayload.format} " +
                "mediaFiles=${payload.sdkPayload.data.mediaFiles?.size ?: 0}",
        )
        val result = withContext(Dispatchers.IO) {
            apiFactory.createWith(backupClient).apiV1ImportQimengBackupPost(payload.sdkPayload)
        }
        return LegacyImportSummary(
            mediaFilesTotal = result.mediaFilesTotal,
            assetsMatched = result.assetsMatched,
            authorsImported = result.authorsImported,
            tagsImported = result.tagsImported,
            eventsReplayed = result.eventsReplayed,
            warnings = result.warnings,
        )
    }
}
