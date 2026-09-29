package media.qimeng.app.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import media.qimeng.app.core.data.di.ClientPrefsDataStore
import media.qimeng.app.core.model.StagingBatchConfig

/**
 * [StagingRepository] 的 DataStore Preferences 实现（client_prefs 文件，搜索历史/网格列数
 * 同仓不同键）。批次配置以 moshi 反射 JSON 落键（DataStoreSearchHistoryRepository 同款
 * 「DataStore 单键 JSON」模式；moshi 为 core:data 既有依赖，零新增）——跨进程重启、
 * 隔任意天数读取一致。File 操作（授权判定/目录浏览）委托 [InboxFileStore]。
 */
@Singleton
class DataStoreStagingRepository @Inject constructor(
    @ClientPrefsDataStore private val dataStore: DataStore<Preferences>,
    private val fileStore: InboxFileStore,
) : StagingRepository {

    override val batchConfig: Flow<StagingBatchConfig> =
        dataStore.data.map { StagingJson.batchFromJson(it[KEY_STAGING_BATCH]) }.distinctUntilChanged()

    override val inboxPath: Flow<String?> =
        dataStore.data.map { it[KEY_INBOX_PATH]?.takeIf { path -> path.isNotEmpty() } }.distinctUntilChanged()

    override val archivePath: Flow<String?> =
        dataStore.data.map { it[KEY_ARCHIVE_PATH]?.takeIf { path -> path.isNotEmpty() } }.distinctUntilChanged()

    /** 原子读-改-写（批次配置）：edit 块内读当前键的最新解码值 -> transform -> 写回
     *  （DataStore edit 对同文件写天然串行，连续两次变换叠加而非互相覆盖），键恒写回 */
    override suspend fun editBatchConfig(transform: (StagingBatchConfig) -> StagingBatchConfig) {
        withContext(Dispatchers.IO) {
            dataStore.edit { prefs ->
                prefs[KEY_STAGING_BATCH] =
                    StagingJson.batchToJson(transform(StagingJson.batchFromJson(prefs[KEY_STAGING_BATCH])))
            }
        }
    }

    override suspend fun setArchivePath(path: String?) {
        // 键卫生口径：清除/空串不占键，读侧缺键与空串等价 null
        withContext(Dispatchers.IO) {
            dataStore.edit {
                if (path.isNullOrBlank()) it.remove(KEY_ARCHIVE_PATH) else it[KEY_ARCHIVE_PATH] = path
            }
        }
    }

    override suspend fun hasAllFilesAccess(): Boolean =
        withContext(Dispatchers.IO) { fileStore.hasAllFilesAccess() }

    override suspend fun storageRoot(): String =
        withContext(Dispatchers.IO) { fileStore.storageRoot() }

    override suspend fun listDirectories(path: String): List<InboxDirEntry> =
        withContext(Dispatchers.IO) { fileStore.listDirectories(path) }

    private companion object {
        /** 批次默认配置键（moshi JSON 对象：库/作者/来源） */
        val KEY_STAGING_BATCH = stringPreferencesKey("upload_staging_batch")

        /** 下载收件箱路径键（历史遗留持久值；读取面仅剩 worker 的收件箱来源归档判定） */
        val KEY_INBOX_PATH = stringPreferencesKey("upload_inbox_path")

        /** 上传归档文件夹路径键（空串不存，读侧归一 null；键名风格与 upload_inbox_path 一致） */
        val KEY_ARCHIVE_PATH = stringPreferencesKey("upload_archive_path")
    }
}

/**
 * 批次配置 JSON 编解码（internal：JVM 单测锁定持久化往返与损坏数据容错）。
 * moshi 反射（KotlinJsonAdapterFactory，SDK 生成物/UploadApiBodies 同款单版本原则）。
 * 2026-09-29 直传化收窄：暂存条目面随暂存区退役删除，只余批次配置。
 */
internal object StagingJson {

    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private val batchAdapter = moshi.adapter<StagingBatchConfig>(StagingBatchConfig::class.java)

    fun batchToJson(config: StagingBatchConfig): String = batchAdapter.toJson(config)

    /** JSON -> 批次配置；缺键/损坏回退全默认（未选库/未挂作者） */
    fun batchFromJson(raw: String?): StagingBatchConfig {
        if (raw.isNullOrBlank()) return StagingBatchConfig()
        return runCatching { batchAdapter.fromJson(raw) }.getOrNull() ?: StagingBatchConfig()
    }
}
