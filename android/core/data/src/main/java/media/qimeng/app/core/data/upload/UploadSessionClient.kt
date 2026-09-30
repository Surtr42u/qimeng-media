package media.qimeng.app.core.data.upload

import java.io.InputStream

/**
 * 会话通道单次调用的收敛结果。错误不抛异常（唯一例外 = 取消信号异常）：传输层把
 * 4xx/5xx/网络异常/响应体不可解析全收敛进本类型，编排器（[ChunkedUploadSession]）
 * 保持纯 JVM 可单测（UploadAttacherTest 替身同款思路）。
 */
internal sealed interface SessionCall<out T> {

    /** 2xx：value = 协议载荷（会话 id / 权威 offset / 资产详情子集） */
    data class Ok<T>(val value: T) : SessionCall<T>

    /**
     * 服务端 4xx（终局候选，重试与否由编排器按 code 细分）。
     * [authorityOffset] 仅 PATCH 409 携带：响应体权威 UploadSession 的 offset（客户端
     * 立即重同步的依据）；其余场景恒 null。
     */
    data class ClientError(
        val code: Int,
        val message: String,
        val authorityOffset: Long? = null,
    ) : SessionCall<Nothing>

    /** 5xx / 网络异常 / 响应体不可解析：WorkManager 退避重试语义 */
    data class Transient(val reason: String) : SessionCall<Nothing>
}

/**
 * 断点续传会话通道传输面（ADR-0028 五操作：POST /api/v1/uploads、GET/PATCH/DELETE
 * /api/v1/uploads/{id}、POST /api/v1/uploads/{id}/complete）。
 * 生产实现 = [OkHttpUploadSessionClient]；测试替身就地定义（路径/时序断言用）。
 */
internal interface UploadSessionClient {

    /** 创建会话 → 会话 id。4xx（扩展名白名单/size 超限/上传已关闭等）= [SessionCall.ClientError]。 */
    suspend fun create(libraryId: String, fileName: String, dir: String, sizeBytes: Long): SessionCall<String>

    /** 断点探测 → 服务端权威 offset；会话未知/已过期 = [SessionCall.ClientError](404)。 */
    suspend fun probe(sessionId: String): SessionCall<Long>

    /**
     * 追加分片：从 [source]（已定位到 [offset]）精确写 [length] 字节。
     * offset 与权威值不符 = [SessionCall.ClientError](409, authorityOffset=服务端权威)。
     * 用户取消以 [UploadCancelledException] 抛出——这是唯一穿透的异常（写流中断是
     * 唯一需要编排器立即放弃会话的路径），其余错误一律收敛进返回值。
     */
    suspend fun patch(
        sessionId: String,
        offset: Long,
        source: InputStream,
        length: Long,
        isCancelled: () -> Boolean,
    ): SessionCall<Long>

    /** 终结会话 → 资产详情子集（id + 冲突自动重命名后的最终名；与直传 201 响应同构）。 */
    suspend fun complete(sessionId: String): SessionCall<UploadApiBodies.UploadResult>

    /** 放弃会话（DELETE）：尽力语义，任何失败静默（取消路径不许被网络问题阻断）。 */
    suspend fun abandon(sessionId: String)
}
