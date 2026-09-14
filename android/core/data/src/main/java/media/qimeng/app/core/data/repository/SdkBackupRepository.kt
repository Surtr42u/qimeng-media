package media.qimeng.app.core.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import media.qimeng.sdk.models.LegacyBackupFile
import media.qimeng.sdk.models.LegacyBackupImport
import media.qimeng.sdk.models.LegacyImportResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [BackupRepository] 生成 SDK 实现（U10-6b）。IO 搬移与 logcat 打点协议同
 * [SdkMediaRepository]（HANDOVER_APP §4.7 文本证据）。
 */
@Singleton
class SdkBackupRepository @Inject constructor(
    private val apiFactory: BusinessApiFactory,
) : BackupRepository {

    override suspend fun export(): LegacyBackupFile {
        Log.d(SdkMediaRepository.LOG_TAG, "GET /export/qimeng-backup")
        return withContext(Dispatchers.IO) {
            apiFactory.create().apiV1ExportQimengBackupGet()
        }
    }

    override suspend fun import(payload: LegacyBackupImport): LegacyImportResult {
        // 载荷可达数十 MB，logcat 只打口径键（格式/媒体文件数），不打全量
        Log.d(
            SdkMediaRepository.LOG_TAG,
            "POST /import/qimeng-backup format=${payload.format} mediaFiles=${payload.data.mediaFiles?.size ?: 0}",
        )
        return withContext(Dispatchers.IO) {
            apiFactory.create().apiV1ImportQimengBackupPost(payload)
        }
    }
}
