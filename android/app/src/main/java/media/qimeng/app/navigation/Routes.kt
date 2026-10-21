package media.qimeng.app.navigation

object Routes {
    /**
     * 覆盖页面：搜索（首页搜索框进入；GUIDE_UI §导航结构 入栈隐藏底栏）。
     * 任务I I3 加可选携词参数（GUIDE_UI §统计详情页「标签 → 搜索页携词跳转」管道）：
     * 未带词的入口仍 navigate 到 [SEARCH_NAV]（可选参数缺省走 defaultValue）。
     */
    const val SEARCH = "search?q={q}"

    /** 搜索页无参导航地址（q 可选参数缺省空串=入口态，行为与旧无参路由一致） */
    const val SEARCH_NAV = "search"

    /** 搜索携词参数键（[SEARCH] 路由占位符） */
    const val KEY_SEARCH_QUERY = "q"

    /**
     * 搜索携词导航地址（任务J J1 接线：统计常看标签条目→搜索页携词，GUIDE_UI §统计详情页）：
     * query 必须 URL 编码——标签可含中文/空格/&/# 等，裸拼会劈裂 query 或吞掉后续参数。
     */
    fun searchRoute(query: String): String = "$SEARCH_NAV?$KEY_SEARCH_QUERY=${encodeQueryValue(query)}"

    /** 覆盖页面：收藏（我的页入口行；M4-6 完整我的页前的临时入口） */
    const val FAVORITE = "favorite"

    /** 覆盖页面：浏览历史 */
    const val HISTORY = "history"

    /** 覆盖页面：作者管理 */
    const val AUTHORS = "authors"

    /** 覆盖页面：上传（M4-5；入口 = 系统分享接收 / 后续设置页入口，不进底栏） */
    const val UPLOAD = "upload"

    /** 覆盖页面：服务器设置（U10-4：设置页「服务器」入口行 → 地址修改/本机模式/换址说明子页） */
    const val SERVER = "server"

    /**
     * 覆盖页面：上传收件箱与归档设置（2026-09-25 暂存区重做：收件箱路径选择子页，路径
     * 持久化后作为上传页「从收件箱导入」的扫描源；2026-09-28 归档文件夹批：子页扩归档
     * 文件夹双设定并改由数据管理 hub 进入——路由串不变，仅入口行搬家）
     */
    const val INBOX = "inbox"

    /**
     * 覆盖页面：数据管理 hub（U10-6：我的页「数据管理」合并入口 → 上传文件/库管理
     * 二级分类入口；上传页复用既有 [UPLOAD] 路由不搬家）
     */
    const val DATA_MANAGE = "dataManage"

    /** 覆盖页面：库管理（U10-6：数据管理 hub → 库列表/注册媒体目录，信息架构对齐 Web 文件管理页） */
    const val LIBRARY_MANAGE = "libraryManage"

    /** 覆盖页面：作者 TXT 导入（U10-6b：数据管理 hub → 片段列表/导入/移除/重放，
     *  对齐 Web 文件管理页 TxtAuthorImportCard） */
    const val AUTHOR_TXT_IMPORT = "authorTxtImport"

    /** 覆盖页面：备份导入导出（U10-6b：数据管理 hub → 全量备份导出/qimeng_backup.json 幂等导入恢复） */
    const val BACKUP = "backup"

    /** 覆盖页面：缩略图缓存（2026-09-16 用户反馈：缩略图生成进度 + 缓存上限合并页，
     *  自我的页「缓存区」退役迁入，经数据管理 hub 二级入口可达） */
    const val THUMBNAIL_CACHE = "thumbnail_cache"

    /**
     * 覆盖页面：词表维护（ADR-0035，2026-10-04：数据管理 hub → 本机内嵌库词表直接编辑
     * 子页——出处组/变体/角色/别名/停用词内存编辑 + 整体保存；协议零改动。词表合并同步
     * 不设子页——用户拍板入口迁入备份页 VocabularyMergeSyncCard，随 ADR-0035 定稿）
     */
    const val VOCABULARY_EDIT = "vocabularyEdit"
}


/**
 * query 值百分号编码（[Routes.searchRoute] 专用；RFC 3986 unreserved 之外一律 %XX）。
 * 为什么自持编码器而不用 android.net.Uri.encode：与 feature:author 的 encodeRouteSegment
 * 同款理由——行为确定性优先（Uri.encode 的默认保留集含 &/= 等 query 结构字符的版本行为
 * 不做记忆依赖），且本地 JVM 单测跑在 android.jar stub 上平台 API 不可用。不复用
 * encodeRouteSegment 本体：internal 跨模块不可见，query/路径段语义略异——两处注释互指，
 * 标签词表变更时同步自查。internal 供同模块单测锁定（encodeRouteSegment 同款处置）。
 */
internal fun encodeQueryValue(value: String): String = buildString {
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val c = byte.toInt() and 0xFF
        val unreserved = c < QUERY_ASCII_BOUNDARY &&
            (c.toChar().isLetterOrDigit() || c.toChar() in QUERY_UNRESERVED_SYMBOLS)
        if (unreserved) append(c.toChar()) else {
            append('%')
            append(QUERY_HEX_DIGITS[c ushr 4])
            append(QUERY_HEX_DIGITS[c and 0xF])
        }
    }
}

/** ASCII 单字节边界（≥128 的多字节 UTF-8 序列成分，恒编码） */
private const val QUERY_ASCII_BOUNDARY = 0x80

/** RFC 3986 unreserved 符号集（字母数字之外；与 feature:author encodeRouteSegment 同集） */
private const val QUERY_UNRESERVED_SYMBOLS = "-_.~"

private const val QUERY_HEX_DIGITS = "0123456789ABCDEF"
