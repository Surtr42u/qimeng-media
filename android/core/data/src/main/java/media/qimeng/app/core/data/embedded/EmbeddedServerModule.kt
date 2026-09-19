package media.qimeng.app.core.data.embedded

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 内嵌服务端控制点绑定（U11 批次D）：接口出消费面、Impl 持 ApplicationContext。
 * 批S8 增补登录前预热两件（[LocalServerWarmup]/[LocalPortProber]）：接口出消费面
 * 供 AuthRepositoryImpl 与 JVM 单测桩替换，真实探针为 socket connect。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EmbeddedServerModule {

    @Binds
    abstract fun bindEmbeddedServerController(
        impl: EmbeddedServerControllerImpl,
    ): EmbeddedServerController

    @Binds
    abstract fun bindLocalServerWarmup(
        impl: LocalServerWarmupImpl,
    ): LocalServerWarmup

    @Binds
    abstract fun bindLocalPortProber(
        impl: SocketLocalPortProber,
    ): LocalPortProber
}
