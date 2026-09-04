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
 * 未收敛进本文件的键（config/prefs/client-logs/stats/rankings/history/
 * system-status）：无跨处失效需求、字面量仅出现一次，按卫生约束第 2 条
 * 「第 2 次出现才提取」留在各自 hooks。stats/rankings/history 由浏览行为
 * 驱动，SSE 桥不失效（库变更对它们的影响经 scan 完成后的页面重挂载体现）。
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

/** 已导入作者 TXT 片段列表（/authors/import-txt） */
export const TXT_FILES_QUERY_KEY = [...AUTHORS_QUERY_KEY, 'import-txt'] as const

export const TRASH_QUERY_KEY = ['api/v1/trash'] as const

/** 推荐流（limit 追加为第二段；上传入库后首页卡片流需刷新） */
export const RECOMMENDATIONS_QUERY_KEY = ['api/v1/recommendations'] as const

/** 出处分组计数 */
export const SOURCES_QUERY_KEY = ['api/v1/sources'] as const
