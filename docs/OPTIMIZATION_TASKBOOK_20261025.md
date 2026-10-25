# 性能、可靠性与代码规范改进任务书（2026-10-25）

> **当前结论：A–E 的 S01–S08、Q01–Q10 已完成代码实施或职责审查；不能据此称所有验收要求全部完成。** 运行代码基线 `08a15e9b` 已通过16类云端必需检查。Q09只完成首批最小交互场景；专项性能量化、逐阶段故障矩阵与最终提交的新增自动审查等缺口见§11。源码尚未合并/部署，R01–R07未选定、未实施。
>
> 初稿以 `b13bb21a` 为静态审查基线。§4–§5的“基线证据”描述实施前的触发条件，原要求和验收标准保留；每项“当前实现”及§10/§11记录后续结果，不能把初稿证据当作现有缺陷重新立项。当前源码/运行包与审查状态见[HANDOVER](HANDOVER.md)，能力状态见[CAPABILITY_MAP](CAPABILITY_MAP.md)。此前[代码质量任务书](CODE_QUALITY_TASKBOOK.md)的已交付Q1–Q7不重复执行。

## 1. 目标与审查口径

延续凌晨三条主线：减少重复计算/遍历、限制后台资源占用、保护前台响应与数据一致性。进一步收敛异步草稿、取消传播、导入边界、发布门禁和职责分层。

初稿审查进行了受控源码清单与规模扫描，并沿扫描、缩略图、推荐、上传、设置保存、Android 导入/列表、便携迁移、桌面配置和 CI 的关键路径静态检查。没有逐行审查所有源文件、复现所有交互或读取真实库；“全项目覆盖”指检查面覆盖各模块，不等于全仓无缺陷证明。

证据分类：

- **实现已确认**：源码可定位该行为；是否已在用户设备造成问题，仍需隔离回归证明。
- **优化候选**：重复工作/资源边界可定位，收益尚未测量，不承诺百分比。
- **验证缺口**：已有源码/单测不能替代相应环境或交互验收，不直接判为功能缺陷。
- **产品候选**：改变用户可见语义或增加能力，须先选定范围，再制定协议和迁移方案。

优先级：P1 为可能丢失用户输入或产生明显资源风险的修复；P2 为常用路径性能/可靠性改进；P3 为条件触发的维护或增强。优先级不是安全漏洞等级。

## 2. 所有任务共用的边界

1. 遵守 [AGENTS](../AGENTS.md) 与 [协作规范](../AI_README_FIRST.md)。实施时按需读取对应权威专题，不默认通读历史。
2. 构建、自动化单测/基准及 `tools/check_docs.py` 门禁走云端；用户本次另授权云端产物安装与电脑隔离后端运行验证（个人日常规模、无前台操作），本地不编译。正式交付核验产物同源提交的检查结果，不能借用凌晨基线通过。
3. 禁止向 8420 真库写测试数据；使用临时数据库、合成媒体、隔离端口。Android 仅显式选择 `qimeng_api35` 或用户指定真机，禁止操作雷电设备。本任务书不安排 NAS、手机备份恢复或原件释放。
4. 不改评分公式/权重/随机数顺序、原件画质/字节、抽帧规则、身份与关联语义；不通过少读候选或删安全校验获得“加速”。SQLite 持久性不降级。
5. 协议变化先改 OpenAPI，再走云端生成链并取回 `sdk.lock`；SQL 改动改查询源，由云端 sqlc 生成，禁止手改生成文件。schema 只加 migration；索引改变补同表查询族计划对照。
6. 不擅自升级主栈、扩大依赖或批量重写；优先现有平台/标准库/依赖。新增多步编排进入业务包，HTTP 只作适配；UI 不承担服务端算法。
7. 不把当前未跟踪文件或无关工作纳入交付。不自动部署、合并 PR、修改现用包或清理媒体；代码实施与最终发布分别按会话授权执行。
8. 新预算、超时、退避值须具名并说明依据；不能在本任务书中凭空确定生产值。预算默认值以合成负载与目标用途验证后决定。

## 3. 批次与实施范围

| 批次 | 任务 | 原排期 | 对应验收要求 |
|---|---|---|---|
| A 用户输入与导入边界 | Q01、Q02、Q03 | 优先；各自小批提交 | 慢响应/取消/超限的行为回归 |
| B 明确的重复工作 | S01、S02、S03、S06 | 第一批性能优化 | 相同输出、查询次数/分配基准、当前 head 门禁 |
| C 跨事件/计算复用 | S04、S05 | B 后推进 | 事件覆盖与算法顺序逐项对照 |
| D 并发与服务生命周期 | S07、S08 | 独立批次，不夹带 UI | 排队/取消/关闭/失效 race 回归 |
| E 工程门禁与结构 | Q04–Q10 | 按影响逐项处理 | 保留既有能力、异常行为和可验证边界 |
| F 产品增强 | R01–R07 | 待用户选择 | 不自动排入 A–E 的实施范围 |

初稿排期已执行A–E；当前完成度逐项见§11。F仍是待选产品范围，本表不自动授权新增产品功能或现场部署。

## 4. 八项性能与并发任务

### S01 推荐顺序直接输出 ID（P2）

**当前实现**：已实现直接ID装配与唯一评分/混合核心，Item接口/完整顺序保持；输出与合成基准通过，真实端点前后对比未量化。

**基线证据与入口（实施前）**：[recommendfeed/page.go](https://github.com/Surtr42u/qimeng-media/blob/b13bb21a8b36e93d6b8896e81da46ef3efd9ab10/server/internal/recommendfeed/page.go) 的 `orderItems` 用完整候选长度调用 `Recommend`，再提取 ID；[recommend/mixing.go](https://github.com/Surtr42u/qimeng-media/blob/b13bb21a8b36e93d6b8896e81da46ef3efd9ab10/server/internal/recommend/mixing.go) 先分配并填充完整 `[]Item`。

**实施要求**：

- 提取唯一的评分/排序/混合核心，允许最终装配为下标、ID 或既有 Item；不可复制两套算法。
- 顺序快照走 ID 出口；保留现有 Item 接口的字段与 Limit 行为，按实际调用者决定是否继续使用。
- 不截短完整顺序，不改变 seed=0/负数/正数、同分稳定性、桶洗牌与视频图片混合的 RNG 调用顺序。

**验收**：已有输出摘要基线逐条一致；覆盖空集、单项、单媒体/混合、同分桶、多种 seed/limit，以及分页拼接。输入与标签不被修改。基准比较全量 ID 顺序及真实 `LoadPage` 冷首屏，报告 ns/op、B/op、allocs/op；不能用小页 Item 基准冒充全序快照收益。

**文档与依赖**：推荐专题与 server README；无协议/schema 变化。可独立实施，为 S05 提供唯一顺序核心。

### S02 合并上传等变更后的指标统计（P2）

**当前实现**：已实现单worker/脏位合并、失败保值重试与关停收齐；百次通知和在查询中再变更回归通过，批量上传延迟专项报告未完成。

**基线证据与入口（实施前）**：[mediawrite/ingest.go](https://github.com/Surtr42u/qimeng-media/blob/b13bb21a8b36e93d6b8896e81da46ef3efd9ab10/server/internal/mediawrite/ingest.go) 每项成功后调用 [service.go](https://github.com/Surtr42u/qimeng-media/blob/b13bb21a8b36e93d6b8896e81da46ef3efd9ab10/server/internal/mediawrite/service.go) 的 `RefreshLibraryFileMetrics`，同步执行全部库分组计数，独立超时为 3 秒。[监控规则](https://github.com/Surtr42u/qimeng-media/blob/b13bb21a8b36e93d6b8896e81da46ef3efd9ab10/docs/OBSERVABILITY.md) 允许指标秒级陈旧。

**实施要求**：

- 在业务/组合层建立一个有界、可取消的统计刷新任务，成功提交后仅标脏；连续上传合并，不每个文件另开 goroutine。
- 覆盖上传、回收站、恢复、扫描等现有刷新入口；正在刷新时的新变更留待后续刷新，避免丢通知。
- 指标查询失败保留上次成功值并可再次刷新；业务已成功不能因指标失败转为上传失败。
- 首次刷新、最长陈旧时间、关闭顺序明确；不机械使用容易漂移的内存加减计数替代数据库真相。

**验收**：合成连续 100 次变更，断言查询次数合并且最终数量正确；覆盖查询期间再变更、失败再成功、停机、重复启动防护。测上传路径延迟和指标查询次数，单独报告指标收敛延迟。

**文档与依赖**：OBSERVABILITY、server README；调度职责改变时同步架构。与上传成功回执/入库事务保持独立。

### S03 扫描专用窄快照（P2）

**当前实现**：已使用五列扫描投影及短锁实时完整行复核；SQL装载/索引基准与平台回归通过，完整空扫/批量改名端到端性能未量化。

**基线证据与入口（实施前）**：[queries/scanner.sql](https://github.com/Surtr42u/qimeng-media/blob/b13bb21a8b36e93d6b8896e81da46ef3efd9ab10/server/internal/store/queries/scanner.sql) 的 `ListAssetsByLibrary` 仍 `SELECT *`；[scanner.go](https://github.com/Surtr42u/qimeng-media/blob/b13bb21a8b36e93d6b8896e81da46ef3efd9ab10/server/internal/scanner/scanner.go) 已改下标索引，但完整行快照、knownIDs、seen 仍驻留整轮。

**实施要求**：

- 先列出变更检测、差集、移动候选选择、并发复核真正读取的字段，新增专用投影类型；其他调用者仍需完整行时保留原查询。
- 窄快照继续整库一致视野；需要完整实时行的短提交点仍重新读取。不得改成每个未变化文件一次 SQL。
- `seen` 能否使用快照下标标记应另测，不能把与快照无关的新路径误判为已存在；不要求一次性做所有容器优化。
- 保持 size+mtime、updated_at 复核、候选 tie-break、恢复意图避让和部分失败不删差集。

**验收**：原扫描/移动/并发更新/部分失败/恢复回归全通过；加窄字段与旧快照结果对照。基准覆盖快照 SQL 装载与索引构建、空扫、批量改名；报告完整扫描与局部索引两个口径。云端 Linux race + Windows 原生扫描回归。

**文档与依赖**：文件专题、server README；SQL 云端生成，不新增索引也不改变 schema。

### S04 缩略图按变更范围回填（P2）

**当前实现**：已实现内部资产/库/全量范围、有界提升和独立全量兜底；局部不发布全库覆盖，对外SSE载荷未变。

**基线证据与入口（实施前）**：[thumbnailwarmup/service.go](https://github.com/Surtr42u/qimeng-media/blob/b13bb21a8b36e93d6b8896e81da46ef3efd9ab10/server/internal/thumbnailwarmup/service.go) 的 `Notify` 只有信号，`inspect` 每轮读取全部启用库；[httpapi/server.go](https://github.com/Surtr42u/qimeng-media/blob/b13bb21a8b36e93d6b8896e81da46ef3efd9ab10/server/internal/httpapi/server.go) 消费 library.changed 后统一通知。

**实施要求**：

- 建立有界的“资产/库/全量”待办，明确范围逐级扩大规则；精确信息缺失或超预算时退回整库/全量，不丢任务。
- 现有不同事件生产者载荷并不一致。先审查扫描、监听、上传、移动、恢复、删库、库启用及配置改变；优先内部类型化通知。若改对外 SSE，必须 OpenAPI/SDK 同步。
- 开机和 10 分钟全量兜底保留，修复外部删除缓存；30 秒变更合并边界如需调整，单独论证。
- 数据范围缩小不缩小安全检查；缺图实际访问仍校验根内路径/链接/原件版本。游标前插入及执行中通知必须最终收敛。
- 局部回填结果不得冒充全库覆盖进度；进度分母和 Covered 仍采用现行口径，必要时仅使全库快照失效。

**验收**：多库仅一个资产变化时检查范围受限；同资产重复、混合通知、超预算、库开关、执行中通知、外删缓存、停机均覆盖。用 spy 统计 SQL 页数/缓存探测次数，并与全量对账最终结果对照。

**文档与依赖**：缩略图、架构、SECURITY、OBSERVABILITY；涉及事件契约时加 API 专题。宜在 S02 通知生命周期设计明确后实施，但不强造通用调度框架。

### S05 双 seed 复用算法准备结果（P2）

**当前实现**：已复用本轮seed无关准备，每个seed独立评分/随机/排序；固定输出和拆分基准通过，不建立常驻候选缓存。

**基线证据与入口（实施前）**：[recommend/recommend.go](https://github.com/Surtr42u/qimeng-media/blob/b13bb21a8b36e93d6b8896e81da46ef3efd9ab10/server/internal/recommend/recommend.go) 每次都构建 tagsByItem、标签相关度、topTags、归一化分母；[recommendfeed/warm.go](https://github.com/Surtr42u/qimeng-media/blob/b13bb21a8b36e93d6b8896e81da46ef3efd9ab10/server/internal/recommendfeed/warm.go) 当前仅复用 Item 输入。

**实施要求**：

- 将与 seed 无关且输入只读的准备结果限制在本次 WarmSeeds 生命周期，固定输入、偏好、Now、Day、Revision。
- 每个 seed 仍独立计算随机项、完整排序、桶洗牌与混合。先复用准备步骤，不随意改写浮点求和次序或预先合并分数。
- 全命中不准备；部分命中只准备一次；空候选、输入错误和代次失效沿现有边界处理。
- 结构失效仅阻止旧结果发布/后续 seed 复用；不能再次把预热中止错误传给共享前台。

**验收**：基线双 seed 完整顺序逐 ID 一致；偏好、日期、稀疏标签、空历史、混合媒体覆盖；重用对象不能跨轮泄漏或被排序改写。拆分测输入装载、准备、每 seed 排序和整轮耗时，不能把累计分配写成 RSS。

**文档与依赖**：推荐专题、SECURITY；依赖 S01 的公共算法出口更合适。继续保留凌晨失效/共享前台回归。

### S06 推荐缓存按预算逐项淘汰（P2）

**当前实现**：已实现过期优先、逐项LRU、原TTL及超预算单流不缓存；功能/race回归通过，命中率/锁竞争专项负载对比未完成。

**基线证据与入口（实施前）**：[recommendfeed/cache.go](https://github.com/Surtr42u/qimeng-media/blob/b13bb21a8b36e93d6b8896e81da46ef3efd9ab10/server/internal/recommendfeed/cache.go) 超过 32 流或 100000 ID 时清空整个 entries。

**实施要求**：

- 先删过期项，再淘汰足够数量的冷项；采用简单可审阅的最近使用策略，保留已有预算与 60 秒 TTL。
- 命中更新时间不等于延长 TTL；结构性 Clear 仍完整失效并推进 generation。
- 单条超过预算仍完整冷算但不缓存，绝不截候选；总 ID 计数在替换/过期/淘汰中正确。
- 淘汰是性能策略，不宣称能保证跨 TTL/结构变化的分页快照永久稳定。

**验收**：热点流与冷流交替、容量边界、空序列、超大单条、Clear 与迟到结果、并发命中/淘汰；race 通过。比较命中率、重算次数、锁竞争与分配，不能只测 map 插入速度。

**文档与依赖**：推荐专题、SECURITY 中预算说明。无需新依赖/schema/API。

### S07 缩略图有界等待与完整生命周期（P2，压力场景重点）

**当前实现**：已实现统一表示预算、前台准入保留、可取消等待、服务级关闭收齐及低基数指标；正常关停与资产故障区分，资源边界/平台回归通过。

**基线证据与入口（实施前）**：[thumbnail/detached.go](https://github.com/Surtr42u/qimeng-media/blob/b13bb21a8b36e93d6b8896e81da46ef3efd9ab10/server/internal/thumbnail/detached.go) 按不同键注册并起续生 goroutine；生成闸只限执行数；[generate.go](https://github.com/Surtr42u/qimeng-media/blob/b13bb21a8b36e93d6b8896e81da46ef3efd9ab10/server/internal/thumbnail/generate.go) 的 Close 仅等待 pool。

**实施要求**：

- 明确工作池队列、同表示生成组、外层尺寸集合续生组各自的 ownership；统一计算不同表示待处理任务的预算，避免双层重复计数。
- 同键等待者继续合并；已接纳任务不因视口滑出取消；前后台提升与保留名额不回退。
- 超预算行为须先设计可重试语义，避免无限等待或静默成功；不得未经协议审查随意更换 HTTP 错误码。
- Close 停止接纳、通知所有等待者、在明确期限内收拢后台及前台续生体；已开始单尺寸仍受生成超时约束。请求取消与服务关闭使用不同生命周期。
- 补充低基数的等待/执行/合并/拒绝/生成失败可观测信息；不把资产 ID 或路径做指标 label。

**验收**：大量不同键、同键风暴、重叠尺寸、单核/多核、后台被提升、等待者全取消、超预算重试、Close 与入队竞争、panic、原件变化。断言队列/执行资源上界和关闭可终止；有效缓存不被误删。Linux race 与 Windows 原生覆盖。

**文档与依赖**：缩略图、架构、SECURITY、OBSERVABILITY、server README；如出口响应变化再更新协议。独立提交，禁止顺手更改画质/抽帧/缓存键。

### S08 共享计算与可取消等待分离（P2）

**当前实现**：已分离推荐DoChan共享计算与请求等待，进度使用可取消闸；关闭/失效/取消竞争回归及合成响应时间/峰值记录通过。

**基线证据与入口（实施前）**：[recommendfeed/cache.go](https://github.com/Surtr42u/qimeng-media/blob/b13bb21a8b36e93d6b8896e81da46ef3efd9ab10/server/internal/recommendfeed/cache.go) 同步 `singleflight.Do`；[page.go](https://github.com/Surtr42u/qimeng-media/blob/b13bb21a8b36e93d6b8896e81da46ef3efd9ab10/server/internal/recommendfeed/page.go) 去掉请求取消；[thumbnailwarmup/service.go](https://github.com/Surtr42u/qimeng-media/blob/b13bb21a8b36e93d6b8896e81da46ef3efd9ab10/server/internal/thumbnailwarmup/service.go) 的 Progress 在 mutex 上等待。

**实施要求**：

- 请求取消只退出本次等待，共享计算仍服务其他请求；计算有服务级关闭/超时约束，不依附首个 HTTP 请求。
- 推荐与进度分别设计，不为两者硬造统一缓存框架。优先使用现有 singleflight 的异步结果或小范围可取消闸。
- 热命中保留简单快路径；进度的较旧快照不能覆盖新快照，取消不能被缓存为业务结果。
- 分别审查“同键合并”与“不同键同时冷算”的资源预算；后者不是 singleflight 自动提供的保护。

**验收**：两个等待者中一个取消，另一方仍正常；所有等待者取消后的既定完成行为；取消与完成同时发生；Clear 后旧结果不回填；关闭后无永挂；进度锁持有期间新请求取消及时返回。记录取消响应时间与不同键压力下在途数量。

**文档与依赖**：推荐/缩略图专题、架构、SECURITY。推荐失效不得重新导致前台 500，必须保留 `50e1f3c2` 对应回归。

## 5. 扩展审查发现与代码规范任务

### Q01 Web 设置保存不能清掉后续草稿（P1，实现已确认）

**当前实现**：已由use-config-form持草稿修订和提交快照，查询/保存响应不覆盖新输入；三个设置浏览器场景通过。

**基线证据（实施前）**：[SettingsPage.tsx](https://github.com/Surtr42u/qimeng-media/blob/b13bb21a8b36e93d6b8896e81da46ef3efd9ab10/web/src/pages/SettingsPage.tsx) 保存期间只禁用保存按钮，输入框/开关可继续编辑；成功回调无条件 `setCfgDraft(null)`。[use-config.ts](https://github.com/Surtr42u/qimeng-media/blob/b13bb21a8b36e93d6b8896e81da46ef3efd9ab10/web/src/hooks/use-config.ts) 先把旧提交的结果写入查询缓存。触发序列：提交 A → 编辑为 B → A 成功 → B 草稿被清掉。

**任务**：优先采用草稿修订号与提交快照；成功只确认本次提交，修订未变时才清草稿/回填规范化值。也可选择保存时锁定完整表单，但须统一交互，不只锁按钮。业务转换/校验收敛在 hook/纯辅助模块，页面只负责渲染和动作。

**验收**：延迟 A 响应后编辑 B，B 保留且仍可保存；即时响应、失败重试、连续保存、查询刷新与初次加载不会覆盖输入。补实际组件交互或浏览器行为验证，不能只测“修订号函数返回 true”。同步 GUIDE_UI 与 Web 入口。

### Q02 Android 取消传播和异步状态写回（P2，实现已确认；影响逐入口验证）

**当前实现**：已处理任务命中的首页/搜索/管理/导入及扫描调用链取消与代际清理；确定性单测通过，不声称所有设备生命周期场景已验收。

**基线证据（实施前）**：[LibraryManageViewModel.kt](https://github.com/Surtr42u/qimeng-media/blob/b13bb21a8b36e93d6b8896e81da46ef3efd9ab10/android/feature/manage/src/main/java/media/qimeng/app/feature/manage/LibraryManageViewModel.kt) 的 refresh/registerAndScan 等直接捕获 Exception；[HomeViewModel.kt](https://github.com/Surtr42u/qimeng-media/blob/b13bb21a8b36e93d6b8896e81da46ef3efd9ab10/android/feature/home/src/main/java/media/qimeng/app/feature/home/HomeViewModel.kt) 和 [SearchViewModel.kt](https://github.com/Surtr42u/qimeng-media/blob/b13bb21a8b36e93d6b8896e81da46ef3efd9ab10/android/feature/search/src/main/java/media/qimeng/app/feature/search/SearchViewModel.kt) 多处 suspend 调用使用 runCatching 后写失败态。已有代际校验能阻止部分旧响应，但不能代替取消传播。

**任务**：按调用链区分纯解析/平台探测和 suspend 业务调用，只为后者显式重新抛出 CancellationException；防止取消触发“失败”提示、普通重试或覆盖新代状态。审核 finally 中 loading/busy 的 owner，避免旧请求取消清掉新请求状态。已提交操作与后续刷新分别记账。

**验收**：请求挂起后离开、刷新换代、切分区、重试退避时取消、保存成功后回读取消。断言取消继续传播、不发布伪失败、不重试已成功写入。不要全仓字符串替换所有 runCatching。同步 Android README/GUIDE_UI；SDK 隔离门禁不放宽。

### Q03 Android TXT 导入前有界读取（P1，实现已确认）

**当前实现**：已把SAF读取下沉可注入辅助件，扩展名/声明大小预检、流上限+1和实际SDK JSON字节双重保护；合成流/序列化回归通过，真实provider未验收。

**基线证据（实施前）**：[AuthorTxtImportScreen.kt](https://github.com/Surtr42u/qimeng-media/blob/b13bb21a8b36e93d6b8896e81da46ef3efd9ab10/android/feature/manage/src/main/java/media/qimeng/app/feature/manage/AuthorTxtImportScreen.kt) 的 `readUtf8` 直接 `stream.readBytes()`；选文件后先读完整内容，扩展名判断在之后的 [AuthorTxtImportViewModel.kt](https://github.com/Surtr42u/qimeng-media/blob/b13bb21a8b36e93d6b8896e81da46ef3efd9ab10/android/feature/manage/src/main/java/media/qimeng/app/feature/manage/AuthorTxtImportViewModel.kt)。大文件/未知长度 provider 会先占满内存，服务端体积拒绝来不及保护客户端。

**任务**：平台读取进入可注入的数据层辅助件；先做文件名/声明大小预检，实际流读取仍按上限+1 截止，不能相信 provider 声明。上限必须与作者导入 JSON 请求的真实字节限制、转义开销及 Web 入口对齐；不要套用本机同步 10MB 或便携备份 64MB 的其他通道口径。UTF-8/BOM/无效字节策略先对照权威规则，不能悄悄增加编码支持。

**验收**：空文件、无大小、假报小体积、读取中增长、刚好上限、超限、打开失败、取消、非法编码；失败前不出网、不写片段，提示清楚且仍可重新选文件。使用合成流，不读取私人 TXT。同步 Android、作者 API/领域对应限制；新增协议限制须先改 OpenAPI。

### Q04 便携导入导出编排继续下沉（P2，结构改进）

**当前实现**：已迁入portablebackup，HTTP不持导入分段或导出快照事务；契约、往返、部分提交失败/取消及重试回归通过，尚未穷举每个阶段故障。

**基线证据（实施前）**：[httpapi/import.go](https://github.com/Surtr42u/qimeng-media/blob/b13bb21a8b36e93d6b8896e81da46ef3efd9ab10/server/internal/httpapi/import.go) 的 legacyImport 持有 Server、ResponseWriter、aborted，分阶段业务步骤直接决定 HTTP 错误；[export.go](https://github.com/Surtr42u/qimeng-media/blob/b13bb21a8b36e93d6b8896e81da46ef3efd9ab10/server/internal/httpapi/export.go) 在协议层持事务和备份装配。当前架构允许存量渐进迁移，不把现状误报为新边界违规。

**任务**：按 v1/v2 解析校验、身份匹配、分段写入、结果汇总、提交后通知提取专职服务；传域输入/结果，不能让新业务包依赖 gen。导出保持同一事务快照。不得借提取更改部分成功、幂等、TXT keep、事件身份或错误响应。

**验收**：v1/v2 往返、重复导入、A→B→A、实时补传交错、歧义文件、非法 v2 写前拒绝、任一阶段故障后重试、取消和部分提交通知。先查现有回归再补缺口，不复制整份旧实现到新包。同步架构 §5/§5.1、server README、迁移专题。

### Q05 固定协议检查工具版本（P2，工程缺口）

**当前实现**：已固定Redocly 2.71.0并由Makefile/CI共用，其他主动下载工具核对锁/摘要；干净云端校验和SDK锁检查通过。

**基线证据（实施前）**：[Makefile](https://github.com/Surtr42u/qimeng-media/blob/b13bb21a8b36e93d6b8896e81da46ef3efd9ab10/Makefile) 的 REDOCLY 与 [.github/workflows/ci.yml](https://github.com/Surtr42u/qimeng-media/blob/b13bb21a8b36e93d6b8896e81da46ef3efd9ab10/.github/workflows/ci.yml) 的协议检查使用未指定版本的 `npx -y @redocly/cli`；同一源码不同日期可能使用不同校验器。

**任务**：核对现用兼容版本后固定，复用一个可追踪版本来源或增加同步断言；不在本任务书猜版本、不顺带升级 OpenAPI 生成器。检查其他构建工具的明确版本/锁与自动下载行为，区分平台提供的工具和项目主动拉取的包。

**验收**：干净云端环境可复现 lint，Makefile 与 CI 一致；校验级别不放宽，生成锁不意外变化。同步工具说明/CI 规范；工具失败不能被静默吞掉。

### Q06 出包成功与可发布资格自动区分（P2，工程缺口）

**当前实现**：已让单测/Release Lint阻断候选上传；真实失败注入确认无artifact，正常同SHA路径确认成功上传，详见HANDOVER。

**基线证据（实施前）**：[build-apk.yml](https://github.com/Surtr42u/qimeng-media/blob/b13bb21a8b36e93d6b8896e81da46ef3efd9ab10/.github/workflows/build-apk.yml) 先上传 APK，再执行带 `continue-on-error: true` 的单测；HANDOVER 依赖发布者单独核对日志。凌晨记录单测通过，不代表配置中的非阻塞行为已经移除。

**任务**：确认旧失败用例在当前 head 的结果；把正式交付所需单测设为阻断，或明确保留“诊断产物”与“可发布产物”两种资格。只有完整必需检查通过的同 SHA 产物可进入部署流程，不能仅根据 artifact 存在或总 workflow 绿色判断。原始失败证据和调试包可保留，但标签不得混淆。

**验收**：合成/受控测试失败时发布资格失败；成功时才放行。同源提交、签名、版本、内嵌三件套和哈希校验保持。初稿未查询远端工作流；后续真实正反运行证据见§11及HANDOVER。同步部署入口与 HANDOVER 的发布核验要求。

### Q07 清理会误导实施的现行注释与豁免（P2/P3，已确认示例）

**当前实现**：已修正命中的预留配置/扫描入口注释并移除失效Close豁免，Lint通过；历史日志与旧决策正文保留。

**基线证据（实施前）**：[use-config.ts](https://github.com/Surtr42u/qimeng-media/blob/b13bb21a8b36e93d6b8896e81da46ef3efd9ab10/web/src/hooks/use-config.ts) 头注写 scan 参数“重启后生效”，与 OpenAPI、页面和服务端“预留，重启也不生效”冲突。[scanner.go](https://github.com/Surtr42u/qimeng-media/blob/b13bb21a8b36e93d6b8896e81da46ef3efd9ab10/server/internal/scanner/scanner.go) 的 gate 注释仍描述旧 processFile 阻塞路径，现入口为 TryLock 的 updateKnownFile。[server/.golangci.yml](https://github.com/Surtr42u/qimeng-media/blob/b13bb21a8b36e93d6b8896e81da46ef3efd9ab10/server/.golangci.yml) 保留 fsnotify.Close 排除，而当前 watch.go 已显式处理关闭错误。

**任务**：以当前调用链为依据修正注释、过时路径引用、已完成 TODO 与能撤回的豁免；检查豁免是否还有其他生产调用后再移除。不改历史日志/旧 ADR 正文来制造“从未存在问题”。历史经过放 CHANGELOG，头注只保留现行职责、约束和非显然原因。

**验收**：跨端相同契约描述一致；移除豁免后 lint 仍通过；不得把所有注释当冗余删除。按实际影响同步专题，纯注释说明无运行语义影响。

### Q08 大文件和重复逻辑按 ownership 收敛（P3，审查线索）

**当前实现**：九个规模线索已逐项评估；提取统计展示映射与请求中间件，其余单一状态/资源owner保留依据及测试见§10。

基线受控生产源码规模：BiliPlayerView 1283 行、StatsDetailScreen 1045、DetailScreen 881、HomeViewModel 854、QimengNavHost 810；Go import.go 645、server.go 637；Web upload-queue-store 626、image-viewer 507。行数会变化，实施前重算。source_groups_data 的大体积静态词表不因行数拆分。

**任务**：先画清状态、资源和副作用归属；优先拆无状态渲染/格式化、结果映射和独立业务步骤。播放器/导航/上传队列只保留一个状态 owner，不为达到行数目标增加跨组件同步。复核同语义常量、超时、重复加载/失败态；第二处出现时评估现有共享件。Web AST、Go depguard/纯算法守卫、Android SDK 隔离继续复用。

**验收**：每个超线文件有“保留理由/拆分职责/实际测试”结论；新增大函数有明确解释。既有行为测试先于重构通过，后续同样通过；未使用导出用工具与调用链双证据判定，不全量扩大 knip 阻断范围制造无关返工。同步相关端入口，只有边界改变才改架构。

### Q09 补关键用户路径的实际交互验证（P2，验证缺口）

**当前实现**：首批最小范围已完成：四个真实DOM合成场景及Android确定性取消/恢复单测；建议交互矩阵尚未全部覆盖，缺口见§11。

**基线证据（实施前）**：当前 Web 测试以 Vitest 纯函数/源码架构/桌面脚本契约为主，Web README 明确尚未接入 Playwright；Android CI 为构建/单测/Lint，桌面为 Rust test/build。它们都不能证明真实 DOM、生命周期或窗口动作已验收。

**任务**：先选择最小关键场景，优先复用现有工具；是否引入浏览器自动化开发依赖另按选型约束评估，不在本任务书预先指定版本。场景包括 Q01 慢保存、推荐空页/重复页继续加载、SSE 断线恢复、上传刷新重选/取消/挂靠失败、统计失败与真实空态、桌面标题栏/浮层/全屏。Android 增加请求取消和前台恢复的确定性单测，设备验收只按明确目标另执行。

**验收**：只用隔离服务/合成数据，测试失败有可定位的状态或截屏证据；键盘焦点、对话框返回、加载/错误/重试随相关页面检查。明确“代码单测通过/浏览器验证/模拟器/真机”四种状态，禁止互相代替，不为凑覆盖率写镜像测试。

### Q10 Web 上传持久化降级可见（P2，现行为已确认，增强提案）

**当前实现**：已显示存储健康和本地IO重试，处理提交期abort/写删竞争/终态防复活与UUID键；单测及降级浏览器场景通过，双重存储失败边界保留。

**基线证据（实施前）**：[upload-queue-store.ts](https://github.com/Surtr42u/qimeng-media/blob/b13bb21a8b36e93d6b8896e81da46ef3efd9ab10/web/src/lib/upload-queue-store.ts) 的 persistPut/persistDelete 静默 catch；Web README 已接受 IndexedDB 不可用时最佳努力降级，但用户当前队列不会据此知道刷新恢复失去保障。删除失败也不总等于“原记录不存在”。

**任务**：记录一次可恢复的持久化健康状态，提示“本次任务仅当前页面有效/刷新可能无法恢复”，提供重试或自动恢复；不阻塞已经进行的传输，不把持久化失败误报成服务端上传失败。恢复和终态删除失败分别处理，复核旧记录是否可能重新显示，保持 uploadId 与后处理事实。

**验收**：存储初始化/写入/删除失败及恢复、刷新残留、服务端已成功但本地状态未落盘、取消与重建竞争。确认没有重复上传或误提示可恢复；UI 文案与协议七天凭据边界一致。同步 Web/GUIDE_UI/上传专题相关边界。

## 6. 全项目覆盖结论

此表是当前结论；初稿静态依据保留在§4–§5，未执行的验收不销项。

| 检查面 | 当前实现/结果 | 保留边界 |
|---|---|---|
| Go扫描/推荐/缩略图 | S01–S08已实现，功能/并发/平台回归与部分合成基准通过 | 专项量化未齐；合成收益不外推端点/设备/NAS |
| Go写入/统计 | S02合并指标刷新，保持业务事务与上传重放 | 没有完整批量上传延迟/收敛实测报告 |
| Go导入导出 | Q04已进入portablebackup，域DTO不依赖gen，同事务快照导出 | 部分提交/通知/取消已测，逐阶段故障矩阵未穷举 |
| Web页面/hooks/状态 | Q01/Q07/Q10实现，Q09接入四个合成DOM场景 | 其他推荐/SSE/上传/统计/键盘路径未完整浏览器验收 |
| Android feature | Q02/Q03及Q08评估完成，保留SDK隔离/请求代际 | 真SAF provider、设备生命周期与窗口手势未新增验收 |
| Android运行/上传 | 保留既有目标绑定/恢复与内嵌守护 | 后台冻结来源、切服/重启/归档现场矩阵未据此关闭 |
| Desktop | 薄壳及现有配置/脚本/构建回归保留 | 标题栏/浮层/全屏现场未新增；配置原子写为独立候选 |
| 协议/SDK/数据库 | 本轮未改协议/schema；SQL由锁定云端生成，SDK锁未变 | DTO契约回归不等于任意旧客户端均能无损转存v2 |
| 安全/文件操作 | 路径/链接/回收站/恢复意图/上传/WAL-FULL约束保留 | 已接受的外部换链/相同size+mtime/硬件持久性边界不消失 |
| CI/发布/工具 | Q05/Q06完成；08a15e9b完整16类必需检查通过，Release正反门禁验证通过 | 源码、同SHA产物、实际安装和业务验收分别记录 |
| 代码规范/门禁 | Q07/Q08完成，复用Go/Web AST、depguard和SDK隔离 | 大文件保留依据不代表播放器/导航可省现场验收 |
| 产品/文档 | R01–R07未选定；现行文档与基线/历史分开标识 | 最终提交新增自动审查尚缺，未合并/部署 |

## 7. 产品需求候选：先选择，不自动实施

### R01 设置项与真实生效参数统一（建议优先）

当前 Web 的 scan.workers/thumbEdge 可编辑并持久化，但服务端不消费；实际缩略图配置与扫描配置另有来源。页面诚实标注了预留，不是“已经接好但失效”的 BUG。

可选方案：先隐藏/禁用无效编辑、展示实际生效值；或设计真正生效的配置接口。后一方案须确定扫描并发与缩略图 worker 的不同语义、启动/热更新边界、手机与服务器配置归属、像素改变的缓存版本/重建与磁盘代价。不能简单把两个同名字段接线当完成。

验收目标：用户看到的当前值、保存值、何时生效一致；成功提示不能冒充运行态已变化。协议变化走 OpenAPI/SDK。

### R02 回收站恢复关联信息（价值较高，但改变现有语义）

[文件规则](domain/files.md) 明确：恢复保原 asset_id/事件历史，但标签、收藏、点赞因删除级联不恢复。若用户期待“撤销删除后全部还原”，需要独立需求。

方案需保存关联快照并版本化 meta，定义目标作者/标签已删除时如何处理、幂等重试、恢复意图与事务顺序、旧 meta 兼容及容量；禁止仅重建资产行后宣称完整恢复。先确认用户想保留哪些关联，不把既定行为作为本轮可擅改缺陷。

### R03 后台任务状态与失败重试入口（建议优先）

S07已提供内部低基数的表示占用/等闸/运行与合并/拒绝/失败指标；现行产品页尚未完整呈现这些状态或持续失败原因。产品层候选仍是进一步展示缩略图失败数/最近原因、扫描监听是否降级轮询、上传已入库待挂靠和服务端身份。

验收目标：明确“已投递/已生成/失败/等待”，重试是有界且幂等的；错误不暴露路径/令牌，不能靠页面频繁整库统计。新端点/字段需协议先行，UI 不计算业务真相。

### R04 缩略图旧版本与孤儿维护（按磁盘压力触发）

现有删除联动不等于全量孤儿对账。候选功能先做只读估算/预览，再提供独立管理清理，限定缓存目录；永不按文件数量上限删除有效缓存。

需处理旧策略/像素/格式、在途生成、库暂时离线/禁用、扫描尚未收敛与临时文件，不能把“本次没查到”直接等同孤儿。验收含与生成并发且不删除当前有效表示，绝不触碰媒体原件。

### R05 检索高亮与结果解释（低风险体验候选）

能力地图明确高亮未做。可先解释命中的文件名/出处/角色/作者字段，并以安全文本片段渲染；不把用户输入拼接为 HTML，不在客户端重复实现服务端匹配规则。按命中字段协议能力决定是否增字段。

验收覆盖多词、标点、别名、Unicode、无命中、恶意 HTML 字符；高亮不能改变排序或过滤口径。

### R06 大体积便携备份的可恢复性（规模触发）

当前导出包含完整历史，导入 JSON 存在 64MB 护栏。先用合成事件验证导出体积、峰值内存和能否再次导入，不能断言用户当前备份已超限。

若触发规模边界，评估流式/分段容器或异步导出任务，并定义版本、校验、身份守恒、失败续作及旧客户端兼容；不能仅把内存上限无限调大，也不能静默截断历史。与 Q04 分层提取分开交付。

### R07 作者合并、虚拟合集与多库体验（按使用反馈排序）

能力地图中已有候选：作者身份冲突目前返回 409，不合并；虚拟合集、多库体验尚需具体需求。合并需处理 TXT 真相、关注/来源/关联与冲突审阅，COS 作者仍由目录派生；虚拟合集应明确静态选集还是动态条件、跨库范围与删除语义。

先明确真实操作场景再排期，不为“功能完整”一并增加。推荐负反馈/作者去重会改变评分或后处理，也应独立确认，不能塞入 S01/S05 性能改造。

**继续暂缓/明确不扩展**：多用户、公开端口访问、视频超分/插帧、原件实时转码、通用插件运行时、自动更新器，以及未来 NAS 硬件验收。既有取舍见能力地图和 ADR；本次全面检查不撤销它们。

## 8. 代码实施与验证交付模板

每一批先记录源码基线与选定任务 ID，再实施；不要求把所有候选塞进一个巨大 PR。

| 交付字段 | 必填内容 |
|---|---|
| 问题与触发条件 | 用户操作/负载 → 旧行为 → 新行为；区分静态依据与复现证据 |
| 实现范围 | 具体业务职责与调用方；未实施项和原因 |
| 兼容性 | API/SDK/schema/领域输出/缓存与重试是否变化 |
| 验证 | 当前 SHA、云端测试名称/结果、失败修复过程；未执行项明确列出 |
| 性能 | 同环境基线/候选交替多轮；中位数和范围；耗时、分配、SQL/进程次数、峰值指标分别报告 |
| 文档影响 | 更新的权威专题和原因；无语义变化的明确依据 |
| 发布边界 | 源码完成、CI 通过、产物已出、实际安装、业务验收分别记录 |

最低行为矩阵：

- 纯计算：旧/新完整结果对照，不只比长度；空/大/混合输入，输入只读。
- 并发：真实进入等待再取消，双调用共享、失效中计算、关闭与提交竞争、panic/错误回收；避免 sleep 猜时序。
- 文件：合成根目录、链接/路径边界、原件在执行中变化、Windows 平台行为；不以 Linux 单测替代 Windows。
- UI：慢成功、失败重试、输入继续修改、取消/离开、空页与真实空态；保留单一状态 owner。
- 发布：同 SHA 的完整必需检查、审查线程、产物身份/摘要。出包成功与部署成功不能互相代替。

**停止/降级条件**：输出顺序变化、事件/身份不守恒、安全校验减少、不可解释的队列增长、失败引发重复业务写入、实测收益不足或维护成本明显增大时，暂停该实现方案并修正；仍可继续独立任务。不得降低门禁、截断数据、扩大 TTL 或改变公式来让基准好看。

## 9. 文档同步与本轮实际交付

> 本节记录任务书初稿的文档交付；后续代码实施结论见§10/§11，当前状态见HANDOVER。

代码实施已按任务更新推荐/缩略图/文件/迁移、SECURITY、OBSERVABILITY、模块 README 和必要 API 专题；重大架构决策再补 ADR 与索引。普通内部提取不伪造协议/schema 更新。每批代码与受影响文档同批交付。

初稿编写阶段仅交付本文、HANDOVER/能力地图入口、llms导航与CHANGELOG/历史轮转，当时未实施代码或执行门禁。此为初稿历史，不是当前状态；后续代码、云端检查和Release正反验证已完成的范围见§10/§11，设备/部署边界仍保留。


## 10. 结构审查与验证结论（Q08）

2026-10-25 实施快照；行数含注释/空行，仅作重新评估触发器，不作拆分指标。下表的验证名称指实际纳入云端的现有回归；08a15e9b已通过包含Q04/Q08的16类必需门禁，不能把策略单测写成窗口/手势实机验收。

| 文件（当前行数） | 状态/资源归属与结论 | 实际验证及边界 |
|---|---|---|
| BiliPlayerView（1285） | 保留一个View持有Player.Listener、手势、PopupWindow与回调释放；无状态时长/倍速/全屏策略已在PlayerMath/状态机。保留已登记AndroidView桥接及参数冻结，不复制播放器owner | PlayerMathTest、VideoStageStateMachineTest、VideoFullscreenStateMachineTest；编译/Lint。播放器手势/浮层设备验证未新增 |
| StatsDetailScreen（822） | 拆出240行StatsDetailPresentation（摘要/洞察/排行/分布展示行与文案）；VM继续持加载/失败，页面继续独占趋势选择/排序，卡片渲染靠近样式常量保留 | StatsDetailViewModelTest、StatsViewModelTest、StatsFormattersTest；编译/Lint。没有重算服务端领域统计；视觉设备验证未新增 |
| DetailScreen（883） | 保留播放器/缩放沉浸/系统栏及预载的同一页面生命周期；无状态系统栏、亮度、viewport、预载、滑动策略已独立并有回归。拆散Effect会增加窗口写回竞争 | DetailViewModelTest、DetailPreloadPolicyTest、SystemBarIconAppearanceTest、InstantSystemBarsTest、StageViewportHeightTest等；设备窗口验收未新增 |
| HomeViewModel（873） | 保留唯一StateFlow裁决三Tab请求代际、刷新/续页、筛选草稿；分页/预取策略已复用core。Q02已修取消传播和generation守卫，不能按Tab再造VM副本 | HomeViewModelTest含确定性取消/前台恢复与独立seed/刷新回归；SDK隔离与Lint |
| QimengNavHost（812） | 单NavController、驻留Tab条目与返回结果交接保持一个owner；纯路由编码/Tab点击决策已提取。声明式路由表不机械拆成同步管理器 | TopLevelDestinationTest、TabTapDecisionTest、EncodeQueryValueTest；编译/Lint。系统返回/窗口设备验证未新增 |
| httpapi/import.go（39） | Q04已迁入portablebackup，HTTP仅有界解码/错误与结果映射；业务import.go 599行保留分段过程的共享计数/映射owner，具体事务不再持HTTP响应 | v1/v2往返、A→B→A/实时补传、歧义、TXT keep、标签组时间、故障重试、取消及契约回归；Linux race/Windows |
| httpapi/server.go（491） | 拆出163行middleware.go负责Host/安全头/鉴权/媒体签名管线；Server.New保留显式装配顺序和唯一依赖对象，不引入服务容器或隐藏状态 | HTTP完整集成、鉴权/签名/Host/安全头/健康探针与协议回归；Go lint/depguard/race |
| upload-queue-store（622） | 传输队列/XHR/pump唯一owner保留；挂靠已在upload-attach，Q10把持久化IO/健康放upload-persistence，未给后者传输状态决策权 | 上传队列、分片、挂靠/恢复、IDB提交失败、写删竞争与UUID防碰撞单测；浏览器降级/重试场景。真实刷新重选流程仍独立验收 |
| image-viewer（508） | 外层portal/焦点/chrome与内部ViewerCanvas手势现场职责已明确，换src重挂canvas重置手势；缩放/坐标公式已在image-viewer-math，无另建状态owner的收益 | image-viewer-math与Web AST/构建；完整触摸手势/桌面全屏动作未新增现场验收 |

同语义常量继续复用MediaTypeKeys/SourceKeys/核心分页/播放器数学与现有纯函数；本轮不扩大knip未用导出阻断面。source_groups_data是静态词表，不以行数拆分。新增纯展示文件无IO，新增业务包不引用gen；请求中间件只持Server引用，不复制配置或鉴权状态。

Q06补充：`tools/test_release_gate.py`在既有云端docs unittest中检查候选上传顺序、完整Gradle命令和默认success条件，并用内存中的受控错误配置（吞退出码、continue-on-error、always上传、上传提前）验证拒绝。只支持本仓固定模板，不能替代GitHub解析、Release整包运行或同SHA完整门禁；后续已用隔离单测失败分支验证真实Release候选阻断（§11），未改签名或部署任何候选包。


## 11. 当前实现与验收清单

**结论口径**：代码或职责审查已落实，不等于原验收栏每项测量/现场交互全部完成。`08a15e9b`是本轮已验证运行代码基线，16类必需云端检查全绿；此后纯文档修订的检查记录独立采集。最终提交尚无新增Codex审查结论，不能写为“最终审批通过”。

| 项目 | 实现状态 | 已完成验证 | 尚未完成/未覆盖 |
|---|---|---|---|
| S01 | 已实现直接ID与唯一评分/混合核心 | 固定完整输出、seed/limit/只读与分项/冷首屏基准 | 无同环境旧/新HTTP端点延迟对照，分配不是RSS |
| S02 | 已实现单worker脏位合并/失败重试/关闭 | 百次通知、查询中再变更、失败保值、重复启动/取消回归 | 批量上传路径延迟及真实查询收敛专项报告 |
| S03 | 已实现五列快照/短锁实时复核 | 身份/移动/并发/部分失败回归；两万行SQL+索引及纯索引基准 | 完整空扫/批量改名端到端性能量化 |
| S04 | 已实现范围待办/超限提升/独立全量兜底 | 精确子集/混合/溢出/执行中通知/独立tick、SQLite资格/游标 | 真实多库负载及外删缓存的现场演练 |
| S05 | 已实现本轮准备复用，seed独立计算 | 完整顺序/输入只读/命中/错误/失效及拆分基准 | 目标设备整轮预热测量 |
| S06 | 已实现过期优先/逐项LRU/原TTL/超大流不缓存 | 热冷交替、计数/容量/替换/失效与race回归 | 命中率、重算次数和锁竞争专项负载对比 |
| S07 | 已实现256表示预算（后台新增255、前台保留1）、关闭回收/指标 | 大量不同键拒绝/同键与重叠/单多核/提升/取消/关闭/panic/源变化，Linux/Windows通过 | 目标设备压力/停机现场数据；OS不可中止文件调用的边界仍在 |
| S08 | 已实现服务级DoChan/可取消准入与进度闸 | 双/全部取消、完成竞争、Clear/关闭、预算峰值1/2；真实入闸后取消微秒记录 | 不构成真实网络/SQL端点p95或SLO |
| Q01 | 已实现草稿修订/提交快照保护 | 三个设置DOM场景：慢保存后编辑、失败重试/规范化、迟到查询/焦点刷新 | 更完整键盘/对话框矩阵见Q09 |
| Q02 | 已修任务指定调用链的取消/代际写回 | 首页/搜索/管理/导入及扫描相关确定性回归、SDK隔离 | 非所有页面/设备生命周期的穷举证明 |
| Q03 | 已实现SAF预检/有界流/实际JSON双重限制 | 合成流、未知/假报大小、超限、取消和转义序列化边界 | 真实SAF provider及设备文件选择流程 |
| Q04 | 已下沉portablebackup与提交后通知 | v1/v2、重复、A→B→A/实时补传、歧义/非法v2、TXT keep、部分提交失败/取消/重试、DTO契约 | 未对作者/标签/事件/点赞收藏/时间轴/关注/偏好每阶段逐一故障注入 |
| Q05 | 已固定Redocly并复用版本源 | 干净云端校验/SDK锁与工具版本审查 | 不包含主栈或依赖批量升级 |
| Q06 | 已阻断失败候选上传 | 固定模板四类错误配置；真实失败40174082810无artifact，正常40174932988成功上传 | 正常工件仅对应5eaa181c；不是后续源码的同源包，未部署 |
| Q07 | 已清理命中注释/失效豁免 | 当前调用链核对、Lint/既有守卫通过 | 不改写历史记录以抹掉旧问题 |
| Q08 | 已完成九文件职责评估与必要提取 | §10逐文件保留/提取理由、现有行为/格式化/AST/depguard/SDK隔离回归 | 原生窗口/手势验收不由这些测试替代 |
| Q09 | **首批最小场景完成，扩展矩阵未全覆盖** | 4个Chromium DOM合成场景及Android确定性取消/前台恢复单测 | 推荐空/重复页、SSE断线恢复、上传刷新重选/取消/挂靠失败、统计失败/真空态、键盘/对话框和桌面窗口动作未完整浏览器/现场验收 |
| Q10 | 已实现存储健康/串行IO意图/终态防复活与UUID | 初始化/写删/提交期abort/竞争/恢复单测及存储降级DOM | 真刷新重选完整流程见Q09；双重存储失败仍可能残留旧条目 |

### 本次个人使用验收范围（用户2026-10-25补充）

用户确认不会反复大批量上传，要求先把同源云端包安装电脑/原手机，并在电脑后端验证，前台不操作。日常规模的隔离后端运行验证按本次授权本机执行，自动编译/单测/基准与文档门禁仍走云端；不写8420真库测试数据、不把手机装机当作手机业务全回归。反复大批量上传专项不再作为此次个人安装的阻断项，其未测事实保留；Q09扩展前台矩阵不在此次操作范围。用户明确不等新增Codex审查，已有意见仍保留修复事实，完整CI/安全/签名和数据保留检查继续执行。产物及实际装机结果统一记录HANDOVER，不自动合并PR。

### 不应写成完成的事项

- **补充量化**：S02上传/收敛、S03整轮扫描、S06缓存负载的专项对比未形成报告；现有分项基准不代替这些结果。
- **补充故障与交互矩阵**：Q04未穷举每个分段故障，Q09仅首批范围完成；已有测试通过不能扩写为全功能验收。
- **自动审查/发布**：三条已出现的Codex有效P2已修复并解决；最终提交新增审查尚缺，曾提示额度限制。未合并/部署，未改用户信用偏好。
- **范围外**：R01–R07未选定；NAS硬件、手机备份恢复、原件释放和新实机/窗口现场操作不在本轮已执行范围。不得为销项自动启动这些工作。

SQL取回云端codegen 40164563955/40168795821产物，SDK锁未变；构建/测试/基准/文档门禁均在云端，仅用合成/临时数据，没有8420真库测试写入。性能与取消原始证据见推荐/文件专题，合成分配与耗时不冒充RSS、整机或NAS收益。当前源码/运行包与逐次PR检查见HANDOVER。

实施与文档复核实际读取：AGENTS.md、AI_README_FIRST.md、本任务书、HANDOVER、CAPABILITY_MAP、DOMAIN_RULES目录与domain/recommendation、files、thumbnails、authors、migration对应专题；api/openapi相关schema、api/authors与files专题；GUIDE_UI；SECURITY、OBSERVABILITY、ARCHITECTURE§5/§5.1/§10；ADR索引与0003/0004/0011（实施期）、0039/0042的后果与现行索引（文档复核）；server/web/android/desktop入口README、tools/README、deploy/README、deploy/embedded/README；README、备份API、RESTORE、PROJECT_PLAN与旧质量/外观任务记录的命中范围；本轮修改的CHANGELOG与历史索引/归档。没有把历史设备验收当作本轮设备证据。
