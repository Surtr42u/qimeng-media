# 统计口径

> 本专题是领域规则正文；[领域目录](../DOMAIN_RULES.md)保留原 §编号。公式与常量保持原义，标记「逐字遵守」者未经用户明确要求不得改变。

## 5. 统计口径

- 图片 viewCount：进入详情页 +1，**同一会话内只计一次**（横滑切走切回不重复计）。
- 视频 playCount：点击播放 +1，同会话只计一次（play 事件覆盖面=播放器起播路径 ∪ 原生 video play 兜底，同一次起播客户端防重只报一条；暂停后重新起播属新起播、逐条上报，同会话当日的重复起播去重由服务端幂等吸收）。
- 浏览时长：详情页停留秒数（段级上报：一次连续停留=恰好一条 dwell 事件，页面隐藏期间不计入；**不足 1s 的停留段不上报**——2026-09-12 W6 #45 用户拍板回退 V5 口径 B，恢复原口径：浏览 open 事件已计入访问，快速划过的零值停留段冗余、不上报）；秒数整数口径（2026-09-20 闭环）：客户端可上报小数秒，服务端入库时逐事件向零截断为整数（engagement.go int64 转换），聚合链路全整数。
- 点赞：每资产**每日可赞一次**，次日重置；累计 likeCount 永久保留。
- 收藏/关注：布尔标记，无次数。
- 底层数据 = **ViewEvent 事件流**（只追加），一切统计从事件聚合；按天聚合表是物化视图（可随时从事件流重建）。**唯一真相源口径 = 按「文件×天」聚合**（每文件每天一行，无限保留），数字卡、趋势、窗口排行全部同源同口径，主页面与详情页一致。
- **趋势图「全部」分桶口径**：按数据实际跨度动态选粒度（≤12 周按周 / 12 周~24 月按月 / 更长按季），从最早数据铺到当前周期**全量不丢弃**，各桶之和 = 全量总和；周对齐用"距周一回退"法（从周一算起，与系统 locale 无关）；数据不足 2 个周期时补前导 0 桶保证折线可画；空数据显示"暂无趋势数据"。
- **趋势图固定 range 窗口表**（GET /stats/trends 的 range 枚举，2026-09-03 补全）：day=近 30 天逐日、week=近 12 周、month=近 24 月、quarter=近 8 季、year=近 5 年、**7d=近 7 天逐日、90d=近 90 天逐日**（7d/90d 为 2026-09-03 新增档位）、all=「全部」动态粒度（见上）。
- **统计聚合榜单口径**（GET /stats/most-viewed、/stats/top-authors、/stats/top-tags，2026-09-09 协议批 P2 新增；range 窗口一律复用上方固定窗口表，起点=窗口首日本地日历日 00:00，all=不设窗口）：
  - **常看文件**：metric=views = 窗口内 kind='open' 事件次数倒序；metric=seconds = 窗口内 dwell 事件 seconds 累加倒序，**无 dwell 事件的文件不参与 seconds 榜**；榜单响应与 metric 对应（value 字段统一承载）。
  - **常看作者**：窗口内该作者关联资产的 open 次数倒序；资产×作者多对多，一次 open 对每个关联作者各计一次；**COS 作者按其 COS 关联资产计**（asset_authors 对 COS 作者只含 COS 关联，前缀隔离天然成立，无需额外过滤）。
  - **常看标签**：窗口内带该标签资产的 open 次数倒序（按标签名聚合）。
  - **现存资产限定**：三个榜单一律内连接 assets——事件流无 FK、可能引用已删资产（ADR-0005），其历史计数仍由总览类纯计数承载，但榜单须返回文件信息，已删资产不可入榜。
  - **同分 tie-break** = 主键字典序升序（assetId/authorId/tag 名），保证分页与跨端渲染排序确定。
- **来源桶口径**（/stats/trends 的 source 参数与 /stats/overview 的 sourceNormalCount/sourceCosCount，2026-09-09 协议批 P2 新增）：**引用 §6 既有分区判定，不新造口径**——normal=不关联 COS 作者的资产（NOT EXISTS asset_authors×authors type='cos'）、cos=关联 COS 作者的资产（EXISTS 同谓词）；库存两计数之和恒等于 totalFiles。
- **平均浏览次数**（/stats/overview 的 avgViewsPerFile，2026-09-09 协议批 P2 新增）：窗口内现存资产的 open 事件总数 ÷ 窗口内至少一次 open 的**不同**现存文件数；窗口由 overview 的 range 参数决定，缺省 all=全时段累计口径；**分母 0 → null**（不返回 0，空库/无浏览的空态语义）。
- **分类型大小**（/stats/overview 的 per_type_size_bytes，2026-09-14 新增）：现存资产按 media_type 分组各求和 size_bytes，键=image/video/animated_image 三协议值；image 键**不含** animated_image（与 imageCount「动图并入图片」的计数口径不同——大小是物理占用，动图独立成键才可与分类型行各值对账，per_type 各键之和 = totalSizeBytes）；空库/该类型无资产 → 0。
- **分来源大小**（/stats/overview 的 per_source_size_bytes，2026-09-14 新增）：现存资产按来源分区各求和 size_bytes，键=normal/cos；**引用 §6 既有分区判定，不新造口径**（与 sourceNormalCount/sourceCosCount 同谓词：normal=不关联 COS 作者、cos=关联 COS 作者），两键之和 = totalSizeBytes；空库/该来源无资产 → 0。

### 热榜查询策略与性能边界

作者/标签热榜先对有关联的资产按窗口聚合 open，再连接现存 assets 与作者/标签，求各自计数之和；避免每条事件先按关联数展开再 GROUP。仍对每位关联者完整计数，窗口起点含边界，零事件不入榜、已删资产不入榜、同分按主键升序。停用库资产仍按原热榜口径参与，不能套用推荐的启用库过滤。复用既有索引，无 schema/协议/缓存变化（ADR-0044）。

合成验证（2026-10-20，Windows）：500 资产、5 万 open、150 作者/15000 作者关联、100 标签/10000 标签关联，查询前 20 行。旧 SQL 与当前查询在同一基准对照，`-benchtime=1s -count=3` 三次中位数：作者约 1387ms→17.34ms，标签约 774.9ms→12.57ms。收益来自共享关联较多的负载，不能当作真实库、短窗口或整个 HTTP 请求的加速倍数。

基线测试逐字段/顺序比较窗口边界、共享/COS/停用库/已删资产与空榜；计划断言约束事件按资产索引读取，不依赖 CTE 是否展开。在 server 目录复跑，全部使用临时合成数据：

```bash
go test ./internal/store/db -run 'TestHotAggregates|TestCandidateAggregate' -bench '^BenchmarkHotAggregatesSharedWorks$' -benchtime=1s -count=3
```

### 跨库浏览记录兜底重映射（ADR-0037，2026-10-11）

本地优先队列的浏览事件可能被投递到**与产生时不同的服务器**（App「本机模式→NAS/PC」切换后同步）：事件的 assetId 属于源库、目标库不存在。处置口径（实时上报通道，即 `POST /events/view`）：

- **assetId 命中本库** → 原路径：事件记入该资产、正常计统计，202 响应 `outcome=ok`（即使请求携带 fileName 也不重映射——不误配）。
- **assetId 未命中且携带 fileName** → 按文件名匹配本库资产：**唯一命中**，或（多命中时）经 relPath **精确等值**消歧后唯一 → 事件记入该资产、正常物化计统计（跨库合并）、202 响应 `outcome=remapped`。
- **零命中 / 仍歧义 / 未携带 fileName** → 维持既有「已删资产迟到事件」口径：事件入库（真相源 `view_events`）、统计跳过（物化外键拒绝）、202 响应 `outcome=unmatched`。
- **与 §10 备份导入匹配的差异**：导入通道同名多命中取**最早入库行**宽松兜底（legacy 批量通道的历史约束）；实时通道**从严——歧义不猜、宁少勿错**（误配会把甲文件的浏览计到乙文件，污染推荐输入，少计优于误配）。
- **会话去重、幂等键口径不因重映射改变**：去重键 = **重映射后的目标资产**（同会话同 target 的第二发被会话去重挡下，回复 ok、不双计）；clientEventId 幂等命中同样回复 ok。响应 outcome 只透出处置结果、不落库。
