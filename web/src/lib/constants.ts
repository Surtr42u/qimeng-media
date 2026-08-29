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

/**
 * 信息来源端点路径（协议 GET /api/v1/sources，后端并行开发中）。
 * generated SDK 尚无对应函数，use-sources.ts 暂以直连实现（见该文件 TODO）。
 */
export const SOURCES_PATH = '/api/v1/sources'

/** 上传端点路径（协议 POST /api/v1/assets/upload；body=原始字节流，元数据全在 query） */
export const UPLOAD_PATH = '/api/v1/assets/upload'

/** 指标文本端点（协议 GET /metrics；管理员调试用，仅文本展示） */
export const METRICS_PATH = '/metrics'

/** SSE 断线重连基础延迟（ms）：服务端不发 retry: 帧时的默认值，fatal 后 3s 重连 */
export const SSE_RECONNECT_DELAY_MS = 3000

/** SSE 重连延迟下限（ms）：retry: 帧值小于此值时按下限处理，防 0/负数造成忙循环 */
export const SSE_MIN_RECONNECT_DELAY_MS = 1000

/** SSE 重连延迟上限（ms）：retry: 帧指定的值夹在 [1s, 此值] 之间，防服务端异常值造成饥饿 */
export const SSE_MAX_RECONNECT_DELAY_MS = 30_000

/** 系统状态轮询间隔（ms）：管理页 CPU/内存/网络卡片刷新周期；可被页面临时覆盖（暂停/恢复） */
export const STATUS_POLL_INTERVAL_MS = 3000

/** SSE 业务事件主题（协议事件名）。
 * 同步责任：与 server/internal/events/bus.go Topic* 常量一致——协议侧改动须同步此处，反之亦然。 */
export const EVENT_TOPIC_SCAN_PROGRESS = 'scan.progress'
export const EVENT_TOPIC_LIBRARY_CHANGED = 'library.changed'
export const EVENT_TOPIC_UPLOAD_DONE = 'upload.done'
/** thumbnail.progress 当前无发布者（server/internal/events/bus.go 注释：载荷契约待定），客户端忽略 */
export const EVENT_TOPIC_THUMBNAIL_PROGRESS = 'thumbnail.progress'
