package media.qimeng.app.core.data.events

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 行为上报离线队列的 Hilt 装配（M4-4）：库/DAO 提供与出网端口绑定。
 * 队列本体 [ViewEventQueue] 是带 @Inject 构造的具体类，无需绑定。
 */
@Module
@InstallIn(SingletonComponent::class)
object EventQueueModule {

    @Provides
    @Singleton
    fun provideEventDatabase(@ApplicationContext context: Context): QimengEventDatabase =
        Room.databaseBuilder(context, QimengEventDatabase::class.java, QimengEventDatabase.NAME)
            .build()

    @Provides
    @Singleton
    fun providePendingViewEventDao(database: QimengEventDatabase): PendingViewEventDao =
        database.pendingViewEventDao()
}

/** 出网端口绑定：drain 走生成 SDK（单测注入 fake sender，不触网） */
@Module
@InstallIn(SingletonComponent::class)
interface EventSyncBindings {

    @Binds
    @Singleton
    fun bindViewEventSender(impl: SdkViewEventSender): ViewEventSender
}
