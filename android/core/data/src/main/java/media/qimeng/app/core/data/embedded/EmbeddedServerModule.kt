package media.qimeng.app.core.data.embedded

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** 内嵌服务端控制点绑定（U11 批次D）：接口出消费面、Impl 持 ApplicationContext。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EmbeddedServerModule {

    @Binds
    abstract fun bindEmbeddedServerController(
        impl: EmbeddedServerControllerImpl,
    ): EmbeddedServerController
}
