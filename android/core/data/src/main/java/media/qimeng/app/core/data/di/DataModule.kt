package media.qimeng.app.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import media.qimeng.app.core.data.repository.AuthorRepository
import media.qimeng.app.core.data.repository.DataStoreGridPrefsRepository
import media.qimeng.app.core.data.repository.DataStoreSearchHistoryRepository
import media.qimeng.app.core.data.repository.DetailRepository
import media.qimeng.app.core.data.repository.GridPrefsRepository
import media.qimeng.app.core.data.repository.HistoryRepository
import media.qimeng.app.core.data.repository.LibraryRepository
import media.qimeng.app.core.data.repository.MediaRepository
import media.qimeng.app.core.data.repository.SdkAuthorRepository
import media.qimeng.app.core.data.repository.SdkDetailRepository
import media.qimeng.app.core.data.repository.SdkHistoryRepository
import media.qimeng.app.core.data.repository.SdkLibraryRepository
import media.qimeng.app.core.data.repository.SdkMediaRepository
import media.qimeng.app.core.data.repository.SearchHistoryRepository
import media.qimeng.app.core.data.repository.AssetOrigUrlResolver
import javax.inject.Qualifier
import javax.inject.Singleton

/** :core:data 接口绑定（Now in Android 范式：调用方依赖接口，实现在此收口）。 */
@Module
@InstallIn(SingletonComponent::class)
interface DataModule {

    @Binds
    @Singleton
    fun bindAuthRepository(impl: media.qimeng.app.core.data.repository.AuthRepositoryImpl): media.qimeng.app.core.data.repository.AuthRepository

    @Binds
    @Singleton
    fun bindMediaRepository(impl: SdkMediaRepository): MediaRepository

    @Binds
    @Singleton
    fun bindHistoryRepository(impl: SdkHistoryRepository): HistoryRepository

    @Binds
    @Singleton
    fun bindAuthorRepository(impl: SdkAuthorRepository): AuthorRepository

    @Binds
    @Singleton
    fun bindDetailRepository(impl: SdkDetailRepository): DetailRepository

    @Binds
    @Singleton
    fun bindAssetOrigUrlResolver(
        impl: media.qimeng.app.core.data.repository.CachingAssetOrigUrlResolver,
    ): AssetOrigUrlResolver

    @Binds
    @Singleton
    fun bindSearchHistoryRepository(impl: DataStoreSearchHistoryRepository): SearchHistoryRepository

    @Binds
    @Singleton
    fun bindGridPrefsRepository(impl: DataStoreGridPrefsRepository): GridPrefsRepository

    @Binds
    @Singleton
    fun bindUploadRepository(impl: media.qimeng.app.core.data.repository.SdkUploadRepository): media.qimeng.app.core.data.repository.UploadRepository

    // U10-6：媒体库管理（库列表/注册/删除/重扫/启停），我的页「数据管理」合并入口的数据面
    @Binds
    @Singleton
    fun bindLibraryRepository(impl: SdkLibraryRepository): LibraryRepository

    // U10-6b：旧版备份导入/导出（数据管理 hub「备份导入导出」子页的数据面）
    @Binds
    @Singleton
    fun bindBackupRepository(impl: media.qimeng.app.core.data.repository.SdkBackupRepository): media.qimeng.app.core.data.repository.BackupRepository

    @Binds
    @Singleton
    fun bindStatsRepository(impl: media.qimeng.app.core.data.repository.SdkStatsRepository): media.qimeng.app.core.data.repository.StatsRepository

    @Binds
    @Singleton
    fun bindRecommendPrefsRepository(impl: media.qimeng.app.core.data.repository.SdkRecommendPrefsRepository): media.qimeng.app.core.data.repository.RecommendPrefsRepository

    // 备份目录 SAF 访问（任务S 2026-09-19 两卡收敛：备份页导入导出与自动备份共用同目录读写，
    // SAF 细节单源收口；2026-09-18 的跨端暂存仓退役——备份目录直读直写替代）
    @Binds
    @Singleton
    fun bindBackupDirAccess(impl: media.qimeng.app.core.data.backup.SafBackupDirAccess): media.qimeng.app.core.data.backup.BackupDirAccess

    @Binds
    @Singleton
    fun bindSystemInfoRepository(impl: media.qimeng.app.core.data.repository.SdkSystemInfoRepository): media.qimeng.app.core.data.repository.SystemInfoRepository

    @Binds
    @Singleton
    fun bindDiskCachePrefsRepository(impl: media.qimeng.app.core.data.repository.DataStoreDiskCachePrefsRepository): media.qimeng.app.core.data.repository.DiskCachePrefsRepository

    @Binds
    @Singleton
    fun bindCoilCacheManager(impl: media.qimeng.app.core.data.di.RealCoilCacheManager): media.qimeng.app.core.data.repository.CoilCacheManager

    @Binds
    @Singleton
    fun bindThumbnailProgressRepository(impl: media.qimeng.app.core.data.repository.SdkThumbnailProgressRepository): media.qimeng.app.core.data.repository.ThumbnailProgressRepository

    @Binds
    @Singleton
    fun bindBackupAutoPrefsRepository(impl: media.qimeng.app.core.data.repository.DataStoreBackupAutoPrefsRepository): media.qimeng.app.core.data.repository.BackupAutoPrefsRepository

    // 扫描充电联动（批C 任务Q C-3，PROJECT_PLAN M6 性能项）：设置项+待扫标记+补扫编排
    @Binds
    @Singleton
    fun bindScanChargeController(impl: media.qimeng.app.core.data.scan.DataStoreScanChargeController): media.qimeng.app.core.data.scan.ScanChargeController
}

/** 客户端本地偏好 DataStore 限定符（与 :core:network 的 server_config DataStore 区分绑定） */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ClientPrefsDataStore

/** IO 调度器限定符（ViewModel 注入用——可测性：测试传测试调度器，不在产品代码硬引用 Dispatchers.IO） */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class IoDispatcher

/** 客户端本地偏好 DataStore（搜索历史/网格列数；与 server_config 分文件——语义域不同） */
@Module
@InstallIn(SingletonComponent::class)
object ClientPrefsModule {

    @Provides
    @Singleton
    @ClientPrefsDataStore
    fun provideClientPrefsDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    ) {
        context.preferencesDataStoreFile(CLIENT_PREFS_FILE_NAME)
    }

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /** 客户端偏好持久化文件名（client_prefs.preferences_pb） */
    private const val CLIENT_PREFS_FILE_NAME = "client_prefs"
}
