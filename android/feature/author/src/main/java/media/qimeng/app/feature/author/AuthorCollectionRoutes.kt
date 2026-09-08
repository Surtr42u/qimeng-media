package media.qimeng.app.feature.author

/**
 * 作者集合页路由契约单源（任务G G1b，[media.qimeng.app.feature.detail.DetailRoutes] 同范式）：
 * 路由串/参数键收敛在 feature:author——feature 禁依赖 :app（ADR-0010），壳层 QimengNavHost
 * 反向引用此处合法；防 route 字符串第二次手抄。
 *
 * 路由带双参数：authorId（UUID，取数键——GET /assets authorId 精确过滤）+ authorName
 * （页标题展示用）。为什么不学 Web 用名字反查（Web /app/collection/author/{name} 按
 * displayName 找 /authors）：两处入口（作者管理行/详情作者卡）本来就持有 authorId，
 * 直传 id 免一次全量反查，也不会踩「剥 ·COS 后缀」的名字匹配边角；name 只作展示参数
 * 随路由传递（URL 编码），不参与数据定位。
 */
object AuthorCollectionRoutes {

    /** 路由参数键（路由占位符与 AuthorCollectionViewModel SavedStateHandle 读取同键） */
    const val KEY_AUTHOR_ID = "authorId"
    const val KEY_AUTHOR_NAME = "authorName"

    /** 作者集合页路由模式（入栈隐藏底栏，覆盖页面语义） */
    const val AUTHOR_COLLECTION_ROUTE = "author_collection/{$KEY_AUTHOR_ID}/{$KEY_AUTHOR_NAME}"

    /** 路由构建（authorName 必须 URL 编码——作者名可含中文/空格/斜杠，斜杠不编码会劈裂路径段） */
    fun authorCollectionRoute(authorId: String, authorName: String): String =
        "author_collection/$authorId/${encodeRouteSegment(authorName)}"
}

/**
 * 路径段百分号编码（纯函数，JVM 单测锁定）。为什么不用 android.net.Uri.encode：
 * 本地单元测试跑在 android.jar stub 上，Uri 方法恒抛 "not mocked"；自持编码器对
 * Navigation Compose 的 Uri.decode 读取端闭环成立（保留集取 RFC 3986 unreserved =
 * 字母数字 + "-_.~"，其余一律 %XX（UTF-8 字节序）——比 Uri.encode 默认保留集更保守，
 * 多编码的字符解码后无损还原，只影响可读性不影响正确性）。
 */
internal fun encodeRouteSegment(segment: String): String = buildString {
    for (byte in segment.toByteArray(Charsets.UTF_8)) {
        val c = byte.toInt() and 0xFF
        val unreserved = c < ASCII_BOUNDARY && (c.toChar().isLetterOrDigit() || c.toChar() in UNRESERVED_SYMBOLS)
        if (unreserved) {
            append(c.toChar())
        } else {
            append('%')
            append(HEX_DIGITS[c ushr 4])
            append(HEX_DIGITS[c and 0xF])
        }
    }
}

/** ASCII 单字节边界（≥128 的是多字节 UTF-8 序列成分，恒编码） */
private const val ASCII_BOUNDARY = 0x80

/** RFC 3986 unreserved 符号集（字母数字之外） */
private const val UNRESERVED_SYMBOLS = "-_.~"

private const val HEX_DIGITS = "0123456789ABCDEF"
