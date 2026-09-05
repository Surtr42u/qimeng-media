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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import media.qimeng.app.core.data.repository.AuthorRepository
import media.qimeng.app.core.data.repository.DataStoreGridPrefsRepository
import media.qimeng.app.core.data.repository.DataStoreSearchHistoryRepository
import media.qimeng.app.core.data.repository.GridPrefsRepository
import media.qimeng.app.core.data.repository.HistoryRepository
import media.qimeng.app.core.data.repository.MediaRepository
import media.qimeng.app.core.data.repository.SdkAuthorRepository
import media.qimeng.app.core.data.repository.SdkHistoryRepository
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
    fun bindAssetOrigUrlResolver(
        impl: media.qimeng.app.core.data.repository.CachingAssetOrigUrlResolver,
    ): AssetOrigUrlResolver

    @Binds
    @Singleton
    fun bindSearchHistoryRepository(impl: DataStoreSearchHistoryRepository): SearchHistoryRepository

    @Binds
    @Singleton
    fun bindGridPrefsRepository(impl: DataStoreGridPrefsRepository): GridPrefsRepository
}

/** 客户端本地偏好 DataStore 限定符（与 :core:network 的 server_config DataStore 区分绑定） */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ClientPrefsDataStore

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

    /** 客户端偏好持久化文件名（client_prefs.preferences_pb） */
    private const val CLIENT_PREFS_FILE_NAME = "client_prefs"
}
