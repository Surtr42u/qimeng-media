/**
 * 全局常量：魔法值唯一来源（AI_README_FIRST「代码卫生约束」）。
 * 与协议（api/openapi.yaml）与服务端（server/）双写的值，一律在常量注释里
 * 标注同步责任，改动时双向检查。
 */

/** 认证 token 的 localStorage 键名：与 M1 验收页（server/internal/httpapi/static/index.html TOKEN_KEY）兼容，勿改 */
export const TOKEN_STORAGE_KEY = 'qimeng_token'

/**
 * 每标签页会话 ID 的 sessionStorage 键名。
 * 服务端按 assetId+kind+sessionId+当日 做会话级去重（DOMAIN_RULES §5），
 * 因此每标签页独立会话：同一天同一标签页的重复上报不重复计数。
 */
export const SESSION_ID_STORAGE_KEY = 'qimeng_session_id'

/**
 * 列表默认分页大小（协议 limit 默认值）。
 * 同步责任：与 server/internal/httpapi/pagination.go defaultPageLimit 对齐，
 * 注释即双方互指——协议侧改动须同步此处，反之亦然。
 */
export const DEFAULT_PAGE_SIZE = 60

/**
 * 列表单页大小上限（协议 limit maximum）。
 * 同步责任：与 server/internal/httpapi/pagination.go maxPageLimit 对齐。
 */
export const MAX_PAGE_SIZE = 200

/** 事件流端点路径（协议 GET /api/v1/events；SSE 端点要求 Bearer 鉴权，见 lib/sse.ts） */
export const EVENTS_PATH = '/api/v1/events'

/** 上传端点路径（协议 POST /api/v1/assets/upload；body=原始字节流，元数据全在 query） */
export const UPLOAD_PATH = '/api/v1/assets/upload'

/**
 * 断点续传会话通道基础路径（协议 /api/v1/uploads 三路径模板：POST 创建、
 * GET/PATCH/DELETE /{id} 探测/追加分片/放弃、POST /{id}/complete 完结）。
 * 供 lib/upload-chunked.ts 的 XHR 直连传输使用（不走生成 SDK 的选型理由见
 * 该文件头记档）。协议侧改动须同步此处，反之亦然（Android 侧对应物为
 * core/data/upload/OkHttpUploadSessionClient，双侧注释互指双同步）。
 */
export const UPLOADS_SESSION_PATH = '/api/v1/uploads'

/** SSE 断线重连基础延迟（ms）：服务端不发 retry: 帧时的默认值，fatal 后 3s 重连 */
export const SSE_RECONNECT_DELAY_MS = 3000

/** SSE 重连延迟下限（ms）：retry: 帧值小于此值时按下限处理，防 0/负数造成忙循环 */
export const SSE_MIN_RECONNECT_DELAY_MS = 1000

/** SSE 重连延迟上限（ms）：retry: 帧指定的值夹在 [1s, 此值] 之间，防服务端异常值造成饥饿 */
export const SSE_MAX_RECONNECT_DELAY_MS = 30_000

/** 中文排序 locale（相册按名称排序/搜索标签池升序共用——与旧项目排序口径一致） */
export const LOCALE_ZH = 'zh-Hans-CN'

/** 系统状态轮询间隔（ms）：维护页性能监控 2s 轮询（曲线需连续采样，速率差分按此周期折算 B/s）。
 *  消费方 hooks/use-system-status.ts（refetchInterval）与 pages/MaintenancePage.tsx（差分除数），
 *  两端同源引用，改周期须意识到速率曲线纵轴随之变化。 */
export const STATUS_POLL_INTERVAL_MS = 2000

/** TanStack Query 全局 staleTime（ms）：数据一致性主要由 SSE 事件桥主动失效保证，
 *  staleTime 只是无事件时避免高频重拉的兜底（消费方 main.tsx 全局默认）。 */
export const QUERY_STALE_TIME_MS = 20_000

/**
 * AppShell 刷新按钮 → 首页旧版 refreshSeed++ 全量重排的广播事件名。
 * 跨文件契约：dispatch=components/shell/AppShell.tsx，监听=pages/HomePage.tsx，
 * 两端必须引用本常量，禁止再手抄字面量。
 */
export const QM_REFRESH_EVENT = 'qm:refresh'

/** SSE 业务事件主题（协议事件名）。
 * 同步责任：与 server/internal/events/bus.go Topic* 常量一致——协议侧改动须同步此处，反之亦然。 */
export const EVENT_TOPIC_SCAN_PROGRESS = 'scan.progress'
export const EVENT_TOPIC_LIBRARY_CHANGED = 'library.changed'
export const EVENT_TOPIC_UPLOAD_DONE = 'upload.done'
/** thumbnail.progress 当前无发布者（server/internal/events/bus.go 注释：载荷契约待定），客户端忽略 */
export const EVENT_TOPIC_THUMBNAIL_PROGRESS = 'thumbnail.progress'
