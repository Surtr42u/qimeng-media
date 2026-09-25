package media.qimeng.app.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.lang.reflect.Type
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import media.qimeng.app.core.data.di.ClientPrefsDataStore
import media.qimeng.app.core.model.StagedUpload
import media.qimeng.app.core.model.StagingBatchConfig

/**
 * [StagingRepository] 的 DataStore Preferences 实现（client_prefs 文件，搜索历史/网格列数
 * 同仓不同键）。暂存条目/批次配置以 moshi 反射 JSON 落键（DataStoreSearchHistoryRepository
 * 同款「DataStore 单键 JSON」模式；moshi 为 core:data 既有依赖，零新增）——
 * 跨进程重启、隔任意天数读取一致（本次重做的核心目标）。
 * File 操作（扫描/探测/归档）委托 [InboxFileStore]。
 */
@Singleton
class DataStoreStagingRepository @Inject constructor(
    @ClientPrefsDataStore private val dataStore: DataStore<Preferences>,
    private val fileStore: InboxFileStore,
) : StagingRepository {

    // distinctUntilChanged：DataStore 任一键变更都重发射整快照（同值也算新发射），
    // 不去重会让暂存区 UI 订阅方空转（DataStoreServerConfigDataSource 同款先例）
    override val stagedItems: Flow<List<StagedUpload>> =
        dataStore.data.map { StagingJson.itemsFromJson(it[KEY_STAGING_ITEMS]) }.distinctUntilChanged()

    override val batchConfig: Flow<StagingBatchConfig> =
        dataStore.data.map { StagingJson.batchFromJson(it[KEY_STAGING_BATCH]) }.distinctUntilChanged()

    override val inboxPath: Flow<String?> =
        dataStore.data.map { it[KEY_INBOX_PATH]?.takeIf { path -> path.isNotEmpty() } }.distinctUntilChanged()

    override suspend fun addItems(items: List<StagedUpload>) {
        if (items.isEmpty()) return
        withContext(Dispatchers.IO) {
            dataStore.edit { prefs ->
                val existing = StagingJson.itemsFromJson(prefs[KEY_STAGING_ITEMS])
                val known = existing.mapTo(mutableSetOf()) { it.source }
                prefs[KEY_STAGING_ITEMS] = StagingJson.itemsToJson(
                    existing + items.filterNot { it.source in known },
                )
            }
        }
    }

    override suspend fun updateItems(items: List<StagedUpload>) {
        if (items.isEmpty()) return
        withContext(Dispatchers.IO) {
            dataStore.edit { prefs ->
                val updates = items.associateBy { it.source }
                val next = StagingJson.itemsFromJson(prefs[KEY_STAGING_ITEMS]).map { updates[it.source] ?: it }
                prefs[KEY_STAGING_ITEMS] = StagingJson.itemsToJson(next)
            }
        }
    }

    /**
     * 原子读-改-写：edit 块内读当前键的最新解码值 -> transform -> 写回（DataStore edit
     * 对同文件写天然串行，连续两次变换叠加而非互相覆盖）。变换后为空表删键（removeItems
     * 同款键卫生；空表与缺键读侧等价，均为空暂存区）。
     */
    override suspend fun editItems(transform: (List<StagedUpload>) -> List<StagedUpload>) {
        withContext(Dispatchers.IO) {
            dataStore.edit { prefs ->
                val next = transform(StagingJson.itemsFromJson(prefs[KEY_STAGING_ITEMS]))
                if (next.isEmpty()) prefs.remove(KEY_STAGING_ITEMS)
                else prefs[KEY_STAGING_ITEMS] = StagingJson.itemsToJson(next)
            }
        }
    }

    /** 原子读-改-写（批次配置）：同 [editItems] 口径，键恒写回（配置无空态语义） */
    override suspend fun editBatchConfig(transform: (StagingBatchConfig) -> StagingBatchConfig) {
        withContext(Dispatchers.IO) {
            dataStore.edit { prefs ->
                prefs[KEY_STAGING_BATCH] =
                    StagingJson.batchToJson(transform(StagingJson.batchFromJson(prefs[KEY_STAGING_BATCH])))
            }
        }
    }

    override suspend fun removeItems(sources: Collection<String>) {
        if (sources.isEmpty()) return
        withContext(Dispatchers.IO) {
            dataStore.edit { prefs ->
                val next = StagingJson.itemsFromJson(prefs[KEY_STAGING_ITEMS])
                    .filterNot { it.source in sources }
                if (next.isEmpty()) prefs.remove(KEY_STAGING_ITEMS)
                else prefs[KEY_STAGING_ITEMS] = StagingJson.itemsToJson(next)
            }
        }
    }

    override suspend fun setBatchConfig(config: StagingBatchConfig) {
        withContext(Dispatchers.IO) {
            dataStore.edit { it[KEY_STAGING_BATCH] = StagingJson.batchToJson(config) }
        }
    }

    override suspend fun setInboxPath(path: String?) {
        withContext(Dispatchers.IO) {
            dataStore.edit {
                if (path.isNullOrBlank()) it.remove(KEY_INBOX_PATH) else it[KEY_INBOX_PATH] = path
            }
        }
    }

    override suspend fun hasAllFilesAccess(): Boolean =
        withContext(Dispatchers.IO) { fileStore.hasAllFilesAccess() }

    override suspend fun storageRoot(): String =
        withContext(Dispatchers.IO) { fileStore.storageRoot() }

    override suspend fun listDirectories(path: String): List<InboxDirEntry> =
        withContext(Dispatchers.IO) { fileStore.listDirectories(path) }

    override suspend fun scanInbox(): List<StagedUpload> =
        withContext(Dispatchers.IO) { inboxPath.first()?.let { fileStore.scanInbox(it) }.orEmpty() }

    override suspend fun sourceExists(item: StagedUpload): Boolean =
        withContext(Dispatchers.IO) { fileStore.sourceExists(item) }

    override suspend fun archiveToUploaded(sourcePath: String): Boolean =
        withContext(Dispatchers.IO) { fileStore.archiveToUploaded(sourcePath) }

    private companion object {
        /** 暂存条目键（moshi JSON 数组；缺键/损坏 = 空暂存区，解码容错见 [StagingJson]） */
        val KEY_STAGING_ITEMS = stringPreferencesKey("upload_staging_items")

        /** 批次默认配置键（moshi JSON 对象：库/作者/来源） */
        val KEY_STAGING_BATCH = stringPreferencesKey("upload_staging_batch")

        /** 下载收件箱路径键（空串不存，读侧归一 null） */
        val KEY_INBOX_PATH = stringPreferencesKey("upload_inbox_path")
    }
}

/**
 * 暂存区 JSON 编解码（internal：JVM 单测锁定持久化往返与损坏数据容错）。
 * moshi 反射（KotlinJsonAdapterFactory，SDK 生成物/UploadApiBodies 同款单版本原则）。
 */
internal object StagingJson {

    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    /** List<StagedUpload> 参数化类型（moshi Types 构造；泛型擦除下反序列化的类型锚点） */
    private val itemsType: Type = Types.newParameterizedType(List::class.java, StagedUpload::class.java)

    private val itemsAdapter = moshi.adapter<List<StagedUpload>>(itemsType)
    private val batchAdapter = moshi.adapter<StagingBatchConfig>(StagingBatchConfig::class.java)

    /** 条目列表 -> JSON（空表写空数组，读侧空表回退删键由写方负责） */
    fun itemsToJson(items: List<StagedUpload>): String = itemsAdapter.toJson(items)

    /** JSON -> 条目列表；缺键/损坏/形态不符一律回退空表（暂存区宁可空不可崩） */
    fun itemsFromJson(raw: String?): List<StagedUpload> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { itemsAdapter.fromJson(raw) }.getOrNull().orEmpty()
    }

    fun batchToJson(config: StagingBatchConfig): String = batchAdapter.toJson(config)

    /** JSON -> 批次配置；缺键/损坏回退全默认（未选库/未挂作者） */
    fun batchFromJson(raw: String?): StagingBatchConfig {
        if (raw.isNullOrBlank()) return StagingBatchConfig()
        return runCatching { batchAdapter.fromJson(raw) }.getOrNull() ?: StagingBatchConfig()
    }
}
