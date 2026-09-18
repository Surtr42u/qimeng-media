package media.qimeng.app.core.data.diagnostics

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 客户端异常上报的 Hilt 装配：出网端口绑定（recorder/bootstrapper 均为
 * 带 @Inject 构造的具体类，无需 @Provides）。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DiagnosticsModule {

    @Binds
    abstract fun bindClientLogSender(impl: SdkClientLogSender): ClientLogSender
}
