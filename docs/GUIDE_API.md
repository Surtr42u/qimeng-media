# GUIDE_API - API 导读与专题目录

> 传输结构的唯一权威是 [api/openapi.yaml](../api/openapi.yaml)。本指南解释意图与用法，不维护第二份 schema。只读相关端点/schema 和命中专题。
> SDK 由 `make sdk` 生成，生成物不入库、禁止手改；同批更新 `api/sdk.lock`。

## 全局约定

- 前缀 `/api/v1`；免Bearer端点由OpenAPI各处 `security: []` 明确列出：健康/就绪探针、setup/login/dev-login及HMAC签名媒体直链（见下），其余端点继承全局bearerAuth。根路径 `/healthz`、`/readyz` 是运维探针别名，行为一致，不入协议面；setup仅未初始化时可用，dev-login仅开发模式开启时可用。
- 资产/历史等游标列表响应带 `nextCursor`，为 null 表示到底；推荐/排行使用 offset，续页按 `X-Qimeng-Next-Offset`（-1 到底）判断，不能用资格复核后的页长代替，详见 engagement 专题。
- 错误统一 `Error{code, message}`；`code` 机器可读（如 PATH_ESCAPE / TOO_LARGE / INVALID_TYPE）
- 媒体直链（/media/**）不走 header 鉴权，用短期 HMAC 签名 URL（默认 6h），参数 `exp`（过期时间戳）+ `sig`；**exp 按窗口对齐**（ADR-0027，2026-10-01 起）：exp = 下一窗口上界 + TTL（窗长=token_ttl 缺省 6h），同一窗口内所有响应签出**逐字节相同**的 URL（浏览器 HTTP 缓存/ETag 可命中），有效期恒 ∈ (6h, 12h]——URL 格式与参数未变，客户端零适配
- **页面投放**：服务端存在 Web 构建产物（`web.static_dir`，默认 `../web/dist`）时 `/` 与 `/index.html` 托管 SPA（静态资源直发 + 前端路由回退）；产物缺失时回退内嵌验收页。内嵌验收页另挂 `/_debug/`（永远可访问，调试用）。页面均为免鉴权静态资源，页面内数据请求照常走 Bearer

## 按任务读取

| 任务 | 专题 |
|---|---|
| 鉴权、配置与系统 | [auth-system](api/auth-system.md) |
| 媒体浏览、检索与直链 | [browse](api/browse.md) |
| 行为、标签、推荐与统计 | [engagement](api/engagement.md) |
| 作者编辑与词表 | [authors](api/authors.md) |
| 上传、整理与回收站 | [files](api/files.md) |
| 便携迁移与库文件快照 | [backup](api/backup.md) |

## 关键机制定位

筛选/COS 隔离/缩略图缓存见 browse；会话去重/事件幂等/播放进度见 engagement；TXT 真相/来源词表见 authors；文件恢复/续传重试见 files；v1/v2 兼容与快照见 backup。领域语义另查 [DOMAIN_RULES](DOMAIN_RULES.md)，安全限制另查 [SECURITY](SECURITY.md)。

## 维护

端点数量以 OpenAPI 为准，不手工维护易漂移计数。用法变化原位更新专题正文，不追加覆盖旧描述的补丁节；版本兼容用独立标题。历史细节查 CHANGELOG/ADR。
