package media.qimeng.app.core.data.repository

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [LibraryRevisionRepository] 的生成 SDK 实现（GET /library/revision 单值端点）。
 * 出网挪 IO 线程 + logcat 文本证据协议与 [SdkThumbnailProgressRepository] 同款
 * （SDK 同步 execute 不许占主线程）。
 */
@Singleton
class SdkLibraryRevisionRepository @Inject constructor(
    private val apiFactory: BusinessApiFactory,
) : LibraryRevisionRepository {

    override suspend fun revision(): Long? {
        Log.d(SdkMediaRepository.LOG_TAG, "GET /library/revision")
        return try {
            withContext(Dispatchers.IO) {
                apiFactory.create().apiV1LibraryRevisionGet().revision
            }
        } catch (e: CancellationException) {
            // 协程取消不是「端点失败」：照常上抛交预取轮的取消收口（登出复位空闲），
            // 禁止被下面的失败降级吞掉破坏协作式取消
            throw e
        } catch (e: Exception) {
            // 网络错 / 旧服务端 404 / 解析错 / 地址未就绪 → null 降级（门判 FULL 走全量），
            // 不向调用方抛出（端口约定见接口 KDoc）
            Log.w(SdkMediaRepository.LOG_TAG, "GET /library/revision 失败，降级 null（本轮走全量）", e)
            null
        }
    }
}
