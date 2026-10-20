# API：行为、标签、推荐与统计

> [API 目录与全局约定](../GUIDE_API.md)。本页解释用法，字段/路径/schema 以 [OpenAPI](../../api/openapi.yaml) 为准；正文日期只标记机制来源。

## 行为上报

端点：POST /events/view、PUT /assets/{id}/like、PUT /assets/{id}/favorite

ViewEvent 只追加（open/play 按 assetId+kind+sessionId+当日去重，dwell 不去重——秒数每次有效；
**2026-09-09 协议批：dwell `seconds` 加 format:double 根修序列化 400**——根修前 dwell 自 M4-3 起从未落库属已知少计缺口）；
**2026-09-09 任务L L5：ViewEventReport 增必填 `clientEventId`（UUID 客户端幂等键）**——客户端本地暂存持久携带同一 id，服务端唯一索引幂等：同 id 重发 202 但不重复入库/计数（dwell 秒数不重复累加）；
列可空，旧格式请求按 NULL 放行（migration 0010）；
点赞 toggle 每日一次；
收藏布尔；
打点同步累加 asset_daily_stats 物化表（可由事件流重建）；
**2026-10-11 跨库兜底重映射（ADR-0037）**：ViewEventReport 增可选 `fileName`/`relPath` 文件身份（本地优先队列事件被投递到与产生时不同的服务器时用），assetId 未命中本库且携带 fileName → 按文件名匹配本库资产（唯一命中，或经 relPath 精确等值消歧后唯一）→ 事件记入该资产并正常物化计统计，202 响应体 EventsViewAccepted `outcome=remapped`；
零命中/仍歧义/未携带 → 维持「已删资产迟到事件」口径（事件入库、统计跳过、`outcome=unmatched`）；
assetId 命中 → `outcome=ok`（带 fileName 也不重映射）；
202 响应体供 App 如实提示，旧客户端忽略响应体行为不变

## 播放

端点：PUT /assets/{id}/progress

断点续播进度上报（心跳式，只保留最新值；
**不进事件流、不计 playCount**，与 ViewEvent 的分工见「关键机制」；
**2026-09-09 协议批：`positionSeconds` 加 format:double 根修序列化 400**——根修前 App 端进度自 M4-3 起从未落库（断点续播起点回落 0），Web 端不受影响）

## 标签

端点：GET/POST /tags、DELETE /tags/{id}、PUT /assets/{id}/tags、**DELETE /assets/{id}/tags/{tag}**

全局池；
删除级联清理关联；
替换式绑定；
**2026-09-09 协议批 P2 新增逐条解除**（#32）：按 URL 编码标签名删单条关联，其余关联 created_at 不动（区别整体替换的刷新语义）；
幂等=标签存在但未挂载→204，资产不存在或标签池无此名→404（含 `/` 的标签名不适用，走 PUT 全量替换）

## 时间轴

端点：GET/POST /assets/{id}/timeline-tags、**PUT** /assets/{id}/timeline-tags/{tagId}、DELETE /assets/{id}/timeline-tags/{tagId}

视频内时间点标记，独立于文件标签；
**2026-09-09 协议批 P2 颜色协议化**（#32）：TimelineTag 增可选 `color`（hex 6 位如 "#d6336c"；
缺省/空=服务端不存颜色，客户端自兜底；
预设类型不入协议），创建/更新/响应三处透传，非 hex6 → 400；
**PUT 为本批新增更新入口**（全量替换语义：timeMillis/name 必填、color 省略=清除，tagId 不属于该资产→404）

## 推荐排行

端点：GET /recommendations、GET /rankings、GET/PUT /recommendations/prefs

10 维算法（seed 控制打散；
**2026-09-05 增 `cosOnly` 参数**（true=COS 推荐模式）；
**同日增 `offset` 参数**（/recommendations 与 /rankings 各一，default 0/min 0，与 limit 组合翻页切片——首页三 tab 触底加载的数据面；
推荐流每次请求按当下打分排序切片，同流连续翻页由客户端按 assetId 去重兜底）：true=COS 推荐模式——候选集限定 COS 关联资产，同套打分/权重回收/每日惩罚照跑，首页 cos tab 数据源；
false=常规流缺省排除 COS）；
纯热度排行（日/周/月/年/季（近 90 天）/总，period=quarter 为 2026-09-03 新增；
limit 缺省 50、协议已声明 minimum 1/maximum 200 越界 400（2026-09-20 审计清偿批 R5 补声明，协议债关闭——与 server pagination.go maxRankingLimit 双写同步）；
2026-09-03 起响应填充 viewCount/playCount 供卡片角标）；
8 个加权维度 + 随机扰动上限，共 9 项偏好

排行榜使用 ranking 专用装载与选页，周期榜热度取日物化统计/窗口点赞，响应 viewCount/playCount 仍为累计值；仅返回页装配元数据及累计计数，并发失去资格可不足一页。范围见 [排行策略](../domain/recommendation.md#排行执行策略与性能边界)。

推荐输入装载由 store 按候选规模选择计划，完整候选与评分、60s 缓存/展示计数语义不变；策略、额外 COUNT 代价和测量范围见 [推荐专题](../domain/recommendation.md#16-输入装载策略与性能边界)。

seed>0 的同分打散按桶首最高分限制 0.05 跨度，避免相邻小分差链式吞并；刷新顺序因此可能改变。固定评分公式与参数不变，评估和剩余边界见 [算法评估](../domain/recommendation.md#17-算法评估与本轮调整)。

## 统计

端点：GET /stats/overview、GET /stats/trends、**GET /stats/most-viewed、GET /stats/top-authors、GET /stats/top-tags**

总览面板（animated_image 计入 imageCount；
计数直接数事件流，含已删资产历史——事件流无 FK 设计；
**2026-09-09 协议批 P2 扩展**（#31）：响应增 `sourceNormalCount`/`sourceCosCount`（来源库存，normal+cos=totalFiles）与可空 `avgViewsPerFile`（窗口内 open 总数÷有 open 的不同现存文件数，range 参数定窗口缺省 all、分母 0→null）；
**2026-09-14 响应再增分类型/分来源大小**：`per_type_size_bytes`（image/video/animated_image 各自 size_bytes 求和，image 不含动图——物理占用口径与 imageCount 计数不同）与 `per_source_size_bytes`（normal/cos，谓词同来源库存），两组各键之和 = totalSizeBytes，无资产的键=0（DOMAIN_RULES §5））；
趋势按 asset_daily_stats 物化表（仅现存资产，随资产删除级联清理、可由事件流全量重建），**P2 增可选 `source=normal|cos` 来源桶过滤**（口径同 §6 分区判定）；
分桶：range=day/week/month/quarter/year 固定粒度 + 2026-09-03 新增 7d（近 7 天逐日）/90d（近 90 天逐日），all 按数据跨度动态选粒度（≤12 周周 / 12 周~24 月月 / 更长季）全量不丢弃、总和守恒（DOMAIN_RULES §5）；
**P2 新增三榜单端点**（事件流聚合、range 窗口与 trends 同款、limit 缺省 20 上限 50、仅现存资产入榜）：most-viewed（metric=views 按窗口 open 次数 / metric=seconds 按窗口 dwell 秒累计，无 dwell 不进 seconds 榜）、top-authors（作者资产 open 次数，COS 作者按 COS 资产计）、top-tags（带标签资产 open 次数）；
秒数全链路整数口径（2026-09-20 R7 闭环）：客户端可上报小数秒、服务端逐事件向零截断入库，物化/榜单/趋势聚合全整数（DOMAIN_RULES §5）

作者/标签热榜先按资产汇总 open 后展开关联，避免共享作品放大事件中间行；窗口、共享归属与同分顺序仍按 [统计专题](../domain/statistics.md#热榜查询策略与性能边界)。

## 标签排序

`GET /tags` 按名称升序（筛选面板等）；
资产详情 `tags` 按关联时间倒序（详情弹窗"最近添加置顶"——PUT 整体替换即刷新全部关联时间），两者口径不同不要混用

## 会话去重

`ViewEventReport.sessionId` 由客户端生成（App 会话/浏览器标签页），服务端按 (assetId, kind, sessionId, 当日) 去重（DOMAIN_RULES §5）

## 客户端幂等键

（任务L L5，2026-09-09）：`ViewEventReport.clientEventId`（必填 UUID）由客户端在事件产生时生成并随本地暂存持久（App Room 队列 / Web IndexedDB 账本），重试/补传/导出再 POST 携带同一 id；
服务端 view_events.client_event_id 唯一索引（migration 0010）+ INSERT OR IGNORE 路线：同 id 重复提交 202 成功返回但不入库不计数——支撑客户端「发送成功（2xx）才删本地暂存」与断网重传不双计；
历史数据/旧格式请求 id 为零值/缺失按 NULL 入库放行（唯一索引多 NULL 不冲突），幂等仅对携带 id 的事件生效

## 点赞初始态字段 `likedToday`

（2026-09-05，Q2-1 拍板）：AssetSummary/AssetDetail 新增可选布尔（Detail 经 allOf 继承 Summary），口径=当日（本地日历日）是否已点赞——与 `PUT /assets/{assetId}/like` 响应 `LikeState.likedToday` 完全同口径（likes 表 (asset_id, day) 行存在性，每资产每日一次、次日重置；
曾赞非今日 → false 但 `likeCount` 累计保留）。用途=详情页点赞按钮初始态；
`GET /assets` 与 `GET /assets/{id}` 两出口恒填充，其余返回 AssetSummary 的端点（推荐流/排行榜/历史）可省略

## 播放进度与编码字段

（M4 播放端基座，migration 0006）：`PUT /assets/{id}/progress` 心跳式上报断点续播位置（建议 10s 间隔 + 暂停/离开播放页各补一次），服务端只保留 `assets.last_position_seconds` 最新值——**进度是"最新状态"而非统计事实**，不写 ViewEvent 事件流、不参与 playCount（播放计数仍走 play 事件 + 会话去重），上报也不 bump updated_at；
"已看完"徽标 = lastPositionSeconds >= durationMs/1000，由客户端推导。AssetSummary/AssetDetail 新增播放字段：`durationMs`（上移至 Summary，列表/详情同源）、`lastPositionSeconds`（Summary，未播过=null）、`videoCodec`/`audioCodec`（Detail，ffprobe codec_name）——codec 仅 VIDEO 扫描时探测，探测失败/存量资产未重探（size+mtime 变更检测跳过 ffprobe）=null，消费方按"尝试播放、失败再兜底"处理

## 统计重建事务

回收站恢复以原身份与单资产日统计同事务提交；全量统计重建从同一写事务快照派生，避免覆盖新增量。stats 保持纯函数，eventstats 负责事务编排（ADR-0040）。
