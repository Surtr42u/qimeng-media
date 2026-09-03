# GUIDE_API - API 端点使用指南

> 本文是 `api/openapi.yaml`（协议宪法）的人读版导读：端点怎么用、机制怎么运作。
> 传输结构的唯一权威是 openapi.yaml 本身；本文解释意图与用法，两者冲突以 openapi.yaml 为准。
> 三端 SDK 由 `make sdk` 自动生成：Go 接口层（server/internal/httpapi/gen/）、TS 客户端（web/src/api/generated/）、Kotlin 客户端（android/sdk/）。生成物不入库，改协议后重跑即可。
> 最后更新：2026-09-03（全部界面接真实数据服务端扩展：/assets 加 liked 点赞筛选与 sort=favoriteAt 收藏时间排序、新增 GET /history 观看历史、/stats/trends range 加 7d/90d、/rankings period 加 quarter、Ranking 响应 viewCount/playCount、Author.viewCount；此前 2026-09-02 M4 播放端协议基座、2026-08-31 M3 收尾）

## 全局约定

- 前缀 `/api/v1`；除 `/healthz`、`/readyz` 两个探针外全部要求 `Authorization: Bearer <token>`（探针在 openapi.yaml 标 `security: []`，其余端点继承全局 bearerAuth）
- 分页统一 cursor 式：响应带 `nextCursor`，为 null 表示到底
- 错误统一 `Error{code, message}`；`code` 机器可读（如 PATH_ESCAPE / TOO_LARGE / INVALID_TYPE）
- 媒体直链（/media/**）不走 header 鉴权，用短期 HMAC 签名 URL（默认 6h），参数 `exp`（过期时间戳）+ `sig`
- **页面投放**：服务端存在 Web 构建产物（`web.static_dir`，默认 `../web/dist`）时 `/` 与 `/index.html` 托管 SPA（静态资源直发 + 前端路由回退）；产物缺失时回退内嵌验收页。内嵌验收页另挂 `/_debug/`（永远可访问，调试用）。页面均为免鉴权静态资源，页面内数据请求照常走 Bearer

## 端点分组速览（37 路径）

| 分组 | 端点 | 说明 |
|---|---|---|
| 认证 | POST /auth/setup、POST /auth/login、POST /auth/verify | 首次设密码领 token（只显示一次）；密码登录换新 token（换设备/清缓存找回通道，登录即重铸旧 token 失效）；校验 token |
| 认证（开发专用） | POST /auth/dev-login | 仅服务端 `auth_dev_mode=true` 时可用：免密码直接签发 token（未初始化自动建 admin 占位）。404 = 未开启，生产零行为变化；仅限本机调试，禁止配合公网/隧道（SECURITY.md「开发模式」） |
| 库管理 | GET/POST /libraries、POST /libraries/{id}/scan | 注册媒体目录、触发全量扫描（进度走 SSE）；POST 可选 `kind`（normal 默认 / cos——COS 作者库按 `作者/作品/文件` 目录结构扫描建 cos_ 作者，DOMAIN_RULES §6） |
| 实时推送 | GET /events | SSE：scan.progress / library.changed / thumbnail.progress / upload.done |
| 资产浏览 | GET /assets、GET/DELETE /assets/{id}、GET /sources、GET/PUT /sources/custom | 唯一列表口径（筛选/排序/搜索全参数化；2026-09-03 加 `liked` 点赞筛选与 `sort=favoriteAt` 收藏时间排序）；DELETE=进回收站；出处列表（按规范名分组的文件计数，fileCount 降序，name=null=无出处文件，默认排除 COS）；自定义出处整体替换（见「关键机制」） |
| 观看历史 | GET /history | 2026-09-03 新增：每资产最近一次 open 事件时间倒序（每资产一条），点击产生；排除已删资产与 COS（includeCos=true 包含），cursor 分页；响应 HistoryItem = AssetSummary + lastViewedAt |
| 媒体文件 | GET /media/orig、/media/thumb（size=sm/md/lg） | 签名直链：原图/视频支持 Range 拖动，**查看永远发原件（无缩放副本）**；缩略图 immutable 缓存 |
| 上传整理 | POST /assets/upload、POST /assets/{id}/move、GET/POST /dirs | 直传 NAS（流式；四道校验；libraryId 必填查询参数，同名自动重命名 "名 (2).ext"，上限 upload.max_bytes 默认 2GB）；移动/重命名保关联（目标冲突 409）；目录树与新建（幂等） |
| 回收站 | GET /trash、POST /trash/{id}/restore、DELETE /trash/{id}、DELETE /trash | 恢复（冲突自动重命名）/单个物理删除/清空（均二次确认场景） |
| 行为上报 | POST /events/view、PUT /assets/{id}/like、PUT /assets/{id}/favorite | ViewEvent 只追加（open/play 按 assetId+kind+sessionId+当日去重，dwell 不去重——秒数每次有效）；点赞 toggle 每日一次；收藏布尔；打点同步累加 asset_daily_stats 物化表（可由事件流重建） |
| 播放 | PUT /assets/{id}/progress | 断点续播进度上报（心跳式，只保留最新值；**不进事件流、不计 playCount**，与 ViewEvent 的分工见「关键机制」） |
| 标签 | GET/POST /tags、DELETE /tags/{id}、PUT /assets/{id}/tags | 全局池；删除级联清理关联；替换式绑定 |
| 作者 | GET /authors、POST /authors/import-txt、PUT /authors/{id}/follow | 常规/COS 双体系（type 区分）；TXT 三格式自动识别统一重建；关注布尔；2026-09-03 响应加 viewCount（作者作品累计浏览次数） |
| 时间轴 | GET/POST /assets/{id}/timeline-tags、DELETE /assets/{id}/timeline-tags/{tagId} | 视频内时间点标记，独立于文件标签 |
| 推荐排行 | GET /recommendations、GET /rankings、GET/PUT /recommendations/prefs | 10 维算法（seed 控制打散）；纯热度排行（日/周/月/年/季（近 90 天）/总，period=quarter 为 2026-09-03 新增；2026-09-03 起响应填充 viewCount/playCount 供卡片角标）；9 维权重偏好 |
| 统计 | GET /stats/overview、GET /stats/trends | 总览面板（animated_image 计入 imageCount；计数直接数事件流，含已删资产历史——事件流无 FK 设计）；趋势按 asset_daily_stats 物化表（仅现存资产，随资产删除级联清理、可由事件流全量重建）；分桶：range=day/week/month/quarter/year 固定粒度 + 2026-09-03 新增 7d（近 7 天逐日）/90d（近 90 天逐日），all 按数据跨度动态选粒度（≤12 周周 / 12 周~24 月月 / 更长季）全量不丢弃、总和守恒（DOMAIN_RULES §5） |
| 系统 | /healthz、/readyz、/metrics、GET /system/status | 探针（healthz/readyz）免鉴权（openapi.yaml:374/380 `security: []`）；/metrics 与 /system/status 要求管理 token；负载/流量面板 |
| 迁移 | POST /import/qimeng-backup | 旧版备份一次性导入：作者/标签/关联/时间轴按唯一键 upsert，统计转 ViewEvent 回放（dailyBrowse 全量 + mediaStats 差额 + history 补漏）；幂等 = 同 exportedAtMillis 批次事件只回放一次 + 段级 upsert 不翻倍；scanSources/settings/albumRules 不导入进 warnings（TXT 请重走 import-txt） |

## 关键机制

- **排序**：`sort` 八键（default/fileDate/addedDate/viewCount/playCount/sizeBytes/name/favoriteAt——favoriteAt=收藏时间，仅 favorite 筛选时语义成立）+ `order`，语义见 DOMAIN_RULES §3
- **标签排序**：`GET /tags` 按名称升序（筛选面板等）；资产详情 `tags` 按关联时间倒序（详情弹窗"最近添加置顶"——PUT 整体替换即刷新全部关联时间，LEGACY_REQUIREMENTS §A），两者口径不同不要混用
- **COS 隔离**：列表默认排除 COS 作者关联文件（独立入口），`includeCos=true` 才包含
- **会话去重**：`ViewEventReport.sessionId` 由客户端生成（App 会话/浏览器标签页），服务端按 (assetId, kind, sessionId, 当日) 去重（DOMAIN_RULES §5）
- **播放进度与编码字段**（M4 播放端基座，migration 0006）：`PUT /assets/{id}/progress` 心跳式上报断点续播位置（建议 10s 间隔 + 暂停/离开播放页各补一次），服务端只保留 `assets.last_position_seconds` 最新值——**进度是"最新状态"而非统计事实**，不写 ViewEvent 事件流、不参与 playCount（播放计数仍走 play 事件 + 会话去重），上报也不 bump updated_at；"已看完"徽标 = lastPositionSeconds >= durationMs/1000，由客户端推导。AssetSummary/AssetDetail 新增播放字段：`durationMs`（上移至 Summary，列表/详情同源）、`lastPositionSeconds`（Summary，未播过=null）、`videoCodec`/`audioCodec`（Detail，ffprobe codec_name）——codec 仅 VIDEO 扫描时探测，探测失败/存量资产未重探（size+mtime 变更检测跳过 ffprobe）=null，消费方按"尝试播放、失败再兜底"处理
- **搜索与筛选叠加**：`q`（FTS5 全文）与全部筛选参数同时生效。实现状态：M2 已实现（2026-08-29）——迁移 0002 建 FTS5 trigram 索引+聚合视图+触发器全集自动同步，`internal/search` 负责关键词解析与索引重建；详细语义见 DOMAIN_RULES §3 全文搜索口径
- **出处/角色富化**（M3）：扫描入库与移动/重命名时服务端按文件名匹配 130 组内置出处表（`internal/sourcematcher`），写 `assets.source` 与 `asset_characters`；未命中 source 为 NULL，显示层兜底"其他"。作者双体系（TXT 导入 + COS 目录扫描）见 DOMAIN_RULES §6，作者端点已全量接线（501 已清零）
- **自定义出处**（`GET/PUT /sources/custom`，M3 收尾）：用户手动添加的分区名自动加入识别（DOMAIN_RULES §4）。PUT 为整体替换，服务端规范化（trim+去空+去重+升序）后持久化到 kv_settings `custom_sources`，存储形态=匹配引擎生效形态；空数组=清空。PUT 成功后：运行中匹配引擎立即替换，且服务端**后台自动对全部常规库资产重算出处/角色**（资产 size+mtime 未变时全量扫描只跳过，不显式重算 stock 数据不会更新）——重算完成发 `library.changed` 事件，客户端收到后刷新即可；重算期间源/角色写以匹配引擎当前名单为准，与扫描并发不破坏最终一致性
