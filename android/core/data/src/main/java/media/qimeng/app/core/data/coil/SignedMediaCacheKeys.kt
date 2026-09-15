package media.qimeng.app.core.data.coil

import media.qimeng.app.core.network.ServerAddress

import coil3.Uri
import coil3.intercept.Interceptor
import coil3.key.Keyer
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.Options

/**
 * 签名媒体直链的稳定缓存键推导（任务U10-5 缓存键漂移根治，2026-09-15 批次2）。
 *
 * **为什么剥 query**：媒体直链 = HMAC 签名 URL，服务端对每次列表/详情响应
 * 重算 `exp`/`sig`（`DefaultTokenTTL=6h`，`server/internal/httpapi/server.go`），
 * 同一张缩略图/海报帧的 URL 字符串随响应不断变化；Coil 默认以完整 URL 为
 * 缓存键 → 键 miss → 同图反复下载——「浏览越多越吃网速」的根因（任务卷
 * U10-5 §6 头号嫌疑，用户拍板口径：纯客户端剥签名 query 做键，不动协议、
 * 不改服务端；服务端仍逐请求验签，安全语义不变）。
 *
 * 键稳定性依据：直链 path 含资产 id（`/media/thumb/<uuid>`、`/media/orig/<uuid>`），
 * 资产内容不变则 path 不变——剥掉随时间轮换的 `exp`/`sig` 后即为稳定键。
 * **`size` 参数必须保留在键里**：服务端缩略图 size 档（sm/md/lg）是普通
 * query 参数（`assets_media_url.go thumbURL`），不在 path 里——全部剥光会让
 * 同资产不同尺寸缩略图键碰撞（命中错尺寸的缓存条目）。
 */
object SignedMediaCacheKeys {

    /**
     * 签名直链家族总前缀：仅对该家族的 URL 改写缓存键，其余 URL 一律回 null
     * 交回 Coil 默认键逻辑（行为与改写前完全一致）。
     * 与服务端 `server.go` 的 `mediaPathPrefix` 协议联动：服务侧改动须同步此处，反之亦然。
     */
    const val MEDIA_PATH_PREFIX = "/media/"

    /** 签名 query 参数名（与 `assets_media_url.go signedMediaURL` 的生成、`server.go` 的验签读取三方联动；协议侧改动须同步此处）。 */
    const val PARAM_EXP = "exp"
    const val PARAM_SIG = "sig"

    private val ROTATING_PARAMS = setOf(PARAM_EXP, PARAM_SIG)

    // 协议前缀单源=core:network ServerAddress（U11 小债清偿批收敛，防三处手抄漂移）
    private val HTTP_PREFIX = ServerAddress.SCHEME_HTTP
    private val HTTPS_PREFIX = ServerAddress.SCHEME_HTTPS
    private const val QUERY_SEPARATOR = "?"
    private const val PARAM_SEPARATOR = "&"

    /**
     * 由签名直链推导稳定缓存键（纯函数，行为由单元测试锁定）：
     * - 剥掉 query 中随响应轮换的 `exp`/`sig`，其余参数（`size` 等）原样保留、顺序不变；
     * - 无 query 的 http(s) URL 原样返回；
     * - 非签名家族（path 不含 [MEDIA_PATH_PREFIX]）、非 http(s) 协议、空/blank
     *   输入 → null（调用方交回 Coil 默认键逻辑，异常输入安全兜底）。
     */
    fun stableKey(url: String): String? {
        if (url.isBlank()) return null
        if (!url.startsWith(HTTP_PREFIX) && !url.startsWith(HTTPS_PREFIX)) return null
        val queryStart = url.indexOf(QUERY_SEPARATOR)
        val path = if (queryStart < 0) url else url.substring(0, queryStart)
        // 家族判定先于 query 处理：非签名家族一律回 null（无 query 交回默认键 = 原串，语义等价）
        if (!path.contains(MEDIA_PATH_PREFIX)) return null
        // 无 query：URL 本身已是稳定键（签名漂移只发生在 query 里），原样返回
        if (queryStart < 0) return url
        val keptParams = url.substring(queryStart + 1)
            .split(PARAM_SEPARATOR)
            .filter { it.substringBefore('=') !in ROTATING_PARAMS }
        return if (keptParams.isEmpty()) path
        else path + QUERY_SEPARATOR + keptParams.joinToString(PARAM_SEPARATOR)
    }
}

/**
 * 内存缓存键器（Coil Keyer）：签名直链按「剥 exp/sig 后的 URL」做内存缓存键。
 *
 * 键的是 [Uri] 而非 String——Coil 3 的键器链收到的是**映射后**的数据：
 * String 模型先经 StringMapper 映射为 `coil3.Uri` 再进键器（对 3.6.2 产物
 * EngineInterceptor 字节码核实，非凭记忆），且返回 null 时按序回退默认
 * UriKeyer（= Uri.toString，即现状行为）。装配顺序在本模块 components 里
 * 先于内置组件注册，保证优先命中。
 */
class SignedMediaUriKeyer : Keyer<Uri> {
    override fun key(data: Uri, options: Options): String? =
        SignedMediaCacheKeys.stableKey(data.toString())
}

/**
 * 磁盘缓存键改写器（Coil Interceptor）：签名直链请求覆写请求级 `diskCacheKey`。
 *
 * **为什么只有 Keyer 不够**：coil3 的磁盘缓存不走键器链——NetworkFetcher 按
 * `options.diskCacheKey ?: 原始URL` 读写磁盘缓存（3.6.2 源码 NetworkFetcher
 * `diskCacheKey` getter 核实），而 `options.diskCacheKey` 只来自请求级显式
 * 覆写。不在请求上覆写的话，杀进程重进后的二次浏览仍会因 URL 轮换 miss
 * 磁盘缓存（验收口径「杀进程重进亦可零网络下载」）。
 *
 * 用户拦截器先于 EngineInterceptor 执行，改写后的 request 会进入
 * Options 计算（RealImageLoader 组件装配顺序核实）；只覆写 diskCacheKey、
 * 不动 data 与内存键（内存键归 [SignedMediaUriKeyer] 管），非签名家族
 * 请求原样放行。
 */
class SignedMediaDiskKeyInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val url = when (val data = request.data) {
            is String -> data
            is Uri -> data.toString()
            else -> null
        }
        val stableKey = url?.let(SignedMediaCacheKeys::stableKey)
            ?: return chain.proceed()
        val rewritten = request.newBuilder()
            .diskCacheKey(stableKey)
            .build()
        return chain.withRequest(rewritten).proceed()
    }
}
