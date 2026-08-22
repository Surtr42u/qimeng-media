# GUIDE_API - API 端点使用指南

> 本文是 `api/openapi.yaml`（协议宪法）的人读版导读：端点怎么用、机制怎么运作。
> 传输结构的唯一权威是 openapi.yaml 本身；本文解释意图与用法，两者冲突以 openapi.yaml 为准。
> 三端 SDK 由 `make sdk` 自动生成：Go 接口层（server/internal/httpapi/gen/）、TS 客户端（web/src/api/generated/）、Kotlin 客户端（android/sdk/）。生成物不入库，改协议后重跑即可。
> 最后更新：2026-08-22（协议 v0.1.0，M0 定稿）

## 全局约定

- 前缀 `/api/v1`；除 `/healthz` 外全部要求 `Authorization: Bearer <token>`
- 分页统一 cursor 式：响应带 `nextCursor`，为 null 表示到底
- 错误统一 `Error{code, message}`；`code` 机器可读（如 PATH_ESCAPE / TOO_LARGE / INVALID_TYPE）
- 媒体直链（/media/**）不走 header 鉴权，用短期 HMAC 签名 URL（默认 6h），参数 `exp`（过期时间戳）+ `sig`

## 端点分组速览（37 路径）

| 分组 | 端点 | 说明 |
|---|---|---|
| 认证 | POST /auth/setup、POST /auth/verify | 首次设密码领 token（只显示一次）；校验 token |
| 库管理 | GET/POST /libraries、POST /libraries/{id}/scan | 注册媒体目录、触发全量扫描（进度走 SSE） |
| 实时推送 | GET /events | SSE：scan.progress / library.changed / thumbnail.progress / upload.done |
| 资产浏览 | GET /assets、GET/DELETE /assets/{id} | 唯一列表口径（筛选/排序/搜索全参数化）；DELETE=进回收站 |
| 媒体文件 | GET /media/orig、/media/thumb（size=sm/md/lg）、/media/preview（?w=） | 签名直链：原图支持 Range 拖动；缩略图 immutable 缓存；预览按需缩放副本（永不改原图） |
| 上传整理 | POST /assets/upload、POST /assets/{id}/move、GET/POST /dirs | 直传 NAS（四道校验）；移动/重命名保关联；目录树与新建 |
| 回收站 | GET /trash、POST /trash/{id}/restore、DELETE /trash/{id}、DELETE /trash | 恢复（冲突自动重命名）/单个物理删除/清空（均二次确认场景） |
| 行为上报 | POST /events/view、PUT /assets/{id}/like、PUT /assets/{id}/favorite | ViewEvent 只追加（sessionId 会话去重）；点赞 toggle 每日一次；收藏布尔 |
| 标签 | GET/POST /tags、DELETE /tags/{id}、PUT /assets/{id}/tags | 全局池；删除级联清理关联；替换式绑定 |
| 作者 | GET /authors、POST /authors/import-txt、PUT /authors/{id}/follow | 常规/COS 双体系（type 区分）；TXT 三格式自动识别统一重建；关注布尔 |
| 时间轴 | GET/POST /assets/{id}/timeline-tags、DELETE /assets/{id}/timeline-tags/{tagId} | 视频内时间点标记，独立于文件标签 |
| 推荐排行 | GET /recommendations、GET /rankings、GET/PUT /recommendations/prefs | 10 维算法（seed 控制打散）；纯热度排行（日/周/月/年/总）；9 维权重偏好 |
| 统计 | GET /stats/overview、GET /stats/trends | 总览面板；趋势分桶（动态周/月/季全量不丢弃） |
| 系统 | /healthz、/readyz、/metrics、GET /system/status | 探针免鉴权；Prometheus；负载/流量面板 |
| 迁移 | POST /import/qimeng-backup | 旧版备份一次性导入，幂等 |

## 关键机制

- **排序**：`sort` 七键（default/fileDate/addedDate/viewCount/playCount/sizeBytes/name）+ `order`，语义见 DOMAIN_RULES §3
- **COS 隔离**：列表默认排除 COS 作者关联文件（独立入口），`includeCos=true` 才包含
- **会话去重**：`ViewEventReport.sessionId` 由客户端生成（App 会话/浏览器标签页），服务端按 (assetId, kind, sessionId, 当日) 去重（DOMAIN_RULES §5）
- **搜索与筛选叠加**：`q`（FTS5 全文）与全部筛选参数同时生效
