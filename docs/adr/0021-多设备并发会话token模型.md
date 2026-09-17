# ADR-0021：多设备并发会话 token 模型（auth_sessions）

## 背景（Context）

用户实际多端并用（PC Web + Android App + 模拟器/调试客户端），而 M1 鉴权是"单用户单 token"：每次登录 `UPDATE users.token_hash` 覆盖唯一哈希，旧 token 立即失效。任一端登录（含 dev-login）即把其他端踢回登录页（401）——2026-09-17 手机反复掉线的根因。备选方案：a) 多会话表（每登录一条会话）；b) 客户端停止重登（治标，无法解决"新设备合法登录"）；c) 信任设备指纹（协议面大改，YAGNI）。

## 决策（Decision）

采用方案 a：新增 `auth_sessions` 表（migration 0011，只加不改），**每次登录/setup/dev-login INSERT 一条会话**（token SHA-256 哈希 + User-Agent 截断 128 字符做 device_label），既有会话不失效；`POST /auth/logout`（协议新增）按请求 token 哈希吊销单条会话。内存鉴权态改为哈希集合（map），启动从会话表加载，热路径零库查询承诺不变。每用户会话上限 16（防 dev-login 每次冷启动无限涨行，超限裁最旧）。`users.token_hash` 弃用不删（NOT NULL 兼容照填占位，删除需 expand-migrate-contract，无必要）。附带决策：auth 三端点（setup/login/dev-login）加进程内固定窗口限速（10 次/分钟，标准库实现，不引 x/time/rate）；Bearer token 仍无 TTL（延续现状，过期机制留待需求驱动）。

## 状态（Status）

Accepted（2026-09-17）

## 后果（Consequences）

- 积极：多端并存互不挤兑（本 ADR 的目的）；logout 语义补齐 SECURITY.md「管理端重置 token」规划项的单设备半边；限速缓冲 argon2id（m=64MB/次）的暴力猜解与内存 DoS。
- 代价与风险：token 泄露后不再能靠"自己再登录一次"挤掉它——吊销路径变为 logout（单条）/ 全量吊销仍走删除数据目录重置；会话表需裁剪兜底（已做，上限 16）。
- 联动影响：`server/migrations/0011_*.sql`、`server/internal/store/queries/sessions.sql`、`server/internal/httpapi/authapi.go`（authState/login/setup/dev-login/logout）、`authlimit.go`、`errors.go`（RATE_LIMITED）、`server.go`（nosniff 头、authLimit 装配）、`server/internal/auth/middleware.go`（Principal.TokenHash 复用）、`api/openapi.yaml`（login/dev-login 描述改多会话、/auth/logout、429）、`docs/SECURITY.md` 鉴权设计节、`docs/CHANGELOG.md`。
