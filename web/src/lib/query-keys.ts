/**
 * TanStack Query 查询键唯一来源（2026-09-05 拍板修复 §5 第 9 条 SSE 失效键错配）。
 *
 * 为什么集中：SSE 桥（SseBridge）跨端失效与 hooks 本地失效必须引用同一常量——
 * 键形态靠单一来源保证，不靠人手抄（AI_README 代码卫生约束 2/4）。
 *
 * 前缀匹配语义（TanStack 逐元素相等，非字符串前缀）：根键 ['api/v1/assets']
 * 只命中以同值元组开头的键；同端点族的子键一律以 [...根键, '子族名', ...] 构造，
 * 禁止另起 'api/v1/assets/xxx' 形态的首段（那会脱离根键失效范围——本文件
 * 存在之前 SseBridge 失效全部落空的根因）。
 *
 * 未收敛进本文件的键（config/prefs/client-logs/system-status）：无跨处
 * 失效需求、字面量仅出现一次，按卫生约束第 2 条「第 2 次出现才提取」留在
 * 各自 hooks。stats/rankings/history 由浏览行为驱动，SSE 桥不失效（库变更
 * 对它们的影响经 scan 完成后的页面重挂载体现），但备份导入（use-backup）
 * 会跨域失效这三族——2026-09-06 审查清偿时收敛（此前 use-backup 手写
 * ['api/v1/stats'] 与 use-stats 的 ['api/v1/stats/overview'] 形态错配，
 * 逐元素匹配下失效落空，即「死键」）。
 *
 * 协议侧改动须同步此处（键首段 = api/openapi.yaml 路径），反之亦然。
 */

/** 资产族根键（覆盖 list/total/detail/facets/timeline-tags 全部子键） */
export const ASSETS_QUERY_KEY = ['api/v1/assets'] as const

/** 资产列表（无限滚动；params 追加为第三段） */
export const ASSETS_LIST_QUERY_KEY = [...ASSETS_QUERY_KEY, 'list'] as const

export const LIBRARIES_QUERY_KEY = ['api/v1/libraries'] as const

/** 目录树（libraryId 追加为第二段） */
export const DIRS_QUERY_KEY = ['api/v1/dirs'] as const

export const TAGS_QUERY_KEY = ['api/v1/tags'] as const

export const AUTHORS_QUERY_KEY = ['api/v1/authors'] as const

/** 搜索补全族根键（输入补全/随机推荐词两 hook 共用，按卫生约束第 2 条收敛；
 * 无跨处失效需求——补全与推荐词不要求实时性，SSE 桥不失效，仅靠 staleTime
 * 与 enabled 切换控制重取） */
export const SEARCH_SUGGESTIONS_QUERY_KEY = ['api/v1/search/suggestions'] as const

/** 已导入作者 TXT 片段列表（/authors/import-txt） */
export const TXT_FILES_QUERY_KEY = [...AUTHORS_QUERY_KEY, 'import-txt'] as const

/** 作者联想（q 追加为子段；/authors/suggest——资产编辑页添加作者消费，无失效需求靠 staleTime 控制） */
export const AUTHOR_SUGGEST_QUERY_KEY = [...AUTHORS_QUERY_KEY, 'suggest'] as const

/** 通用来源词表（/authors/source-vocabulary——来源建议小清单，服务端手动维护；
 *  设置页词表卡读取/保存，资产编辑页来源区快捷选项共用） */
export const SOURCE_VOCABULARY_QUERY_KEY = [...AUTHORS_QUERY_KEY, 'source-vocabulary'] as const

/** 单作者来源区根键（GET/PUT /authors/{authorId}/sources；authorId 追加为
 *  子段，键形 [...本键, authorId]——保存后按作者粒度失效） */
export const AUTHOR_SOURCES_BY_ID_QUERY_KEY = [...AUTHORS_QUERY_KEY, 'sources'] as const

/** 作者总表镜像配置（/authors/mirror——设置页镜像卡读取，保存后失效） */
export const AUTHOR_MIRROR_QUERY_KEY = [...AUTHORS_QUERY_KEY, 'mirror'] as const

export const TRASH_QUERY_KEY = ['api/v1/trash'] as const

/** 推荐流（limit 追加为第二段；上传入库后首页卡片流需刷新） */
export const RECOMMENDATIONS_QUERY_KEY = ['api/v1/recommendations'] as const

/** 出处分组计数 */
export const SOURCES_QUERY_KEY = ['api/v1/sources'] as const

/** 统计族根键（overview/trends 子键由 use-stats 以 [...本键, 子族名] 构造） */
export const STATS_QUERY_KEY = ['api/v1/stats'] as const

/**
 * 排行族根键（use-stats 两个 hook 的子键均以本键为首段；备份导入后整族失效）。
 * 注意子键自带 'paged' 段区分整表/分页两种形态，根键失效两者都命中。
 */
export const RANKINGS_QUERY_KEY = ['api/v1/rankings'] as const

/** 观看历史族根键（limit 追加为第二段；备份导入会重写历史，需整族失效） */
export const HISTORY_QUERY_KEY = ['api/v1/history'] as const
