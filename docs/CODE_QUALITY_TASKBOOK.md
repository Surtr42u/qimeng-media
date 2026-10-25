# 全项目代码质量、解耦与规范化任务书

> 2026-10-24；供用户直接交给 GLM-5.3 在项目分支中独立实施，完成后交回主审复核。本文是本轮有边界的实施要求，不新增一套长期架构权威，也不替代 [AGENTS](../AGENTS.md)、[协作规范](../AI_README_FIRST.md) 和各领域专题。
>
> **状态：已据此实施，并由主审修正复核；实际代码、验证与合并记录见 [HANDOVER](HANDOVER.md)。以下是2026-10-24原实施范围、旧分支现场和参考代码，未提交/待验等说法仅描述当时；不得据此重新执行旧指令。示例不等于最终生产源码；不得把本文、模型回复或旧 CI 记录当作当前改动完成的证据。**

## 1. 可直接交给 5.3 的总指令

你负责在现有项目中完成本任务书：核实全仓生产代码的质量与依赖方向，修复已确认缺陷，对其他代码按风险和职责作必要精简，补合成行为回归和防回退门禁，同步权威文档，取得当前提交的云端验证结果。直接修改工作分支，不把补丁逐段交给用户或原主对话代写；不要停在计划、建议或“准备开始”。完成所有可执行项目后一次性交付实际 diff、检查证据与剩余边界，等待主审复核，不自行合并或部署。

仅使用用户指定的 GLM-5.3；不要悄悄换成 Flash 或其他模型，不新增代理分工。系统繁忙时保留当前工作和状态，不新开大量重复任务，不声称成功。需要恢复时继续原会话，避免重复读取已掌握的内容。

按 AGENTS 的路由读取相关文档；本任务跨端，但不要求默认通读全部 ADR、历史或全仓源码。先建立受控文件/依赖/复杂点清单，再按调用链检查。本文给出的具体修复均须核对实际代码，不可盲贴示例。

## 2. 分支、现场与不可触碰范围

- 编写本任务书时基线为 `ecabd03b`，工作分支为 `codex/code-quality-20261024`，从 `codex/ai-maintenance-foundation` 分出。执行前重新核实 HEAD、branch、status 和 diff，本文的快照不覆盖后续变化。
- 已落盘的唯一代码改动是 `AssetEditViewModel.kt` 文件名联想的 `delay(200L)` 改为既有 `AUTHOR_SUGGEST_DEBOUNCE_MS`；import 原本已存在，数值仍为 200ms。这是 GLM-5.3 出具、主审静态核对后落盘的单行改动，未提交、未编译、未跑业务测试。继承并核对，勿重复添加 import。
- 用户未跟踪文件 `1。html` 保留原样，不读其内容、不格式化、不加入提交。
- 暂停的 zcode 任务在最后一次工作区快照中尚未产生作者事务重构文件。用户可能随后自行操作，必须以新的 git diff 为准，不删除不理解的改动。
- `build/quality-review.local/` 中的旧审查材料和服务提案只是忽略的本地草稿，不是交付来源；旧服务提案包含不存在的构造函数，禁止原样复制。本文附录 A 已纠正该问题。
- 不操作 8420 真库、现用 PC/手机包、真实媒体、备份、设备或 NAS；不启动真实服务做故障注入。
- Android 必须明确选择 `qimeng_api35` 或用户指定真机；严禁向 `emulator-5554` 安装/启动项目或执行 wm/settings 调试。本任务本身不包含设备操作。
- 不改协议、schema、领域公式、删除语义、签名语义、路径安全或上传幂等口径。确有必要的语义变化单独提出证据，不能混入结构重构。
- 不新增运行时依赖、不更换框架、不重写播放器/文件系统/同步引擎、不批量升级依赖，不动受审计的第三方 vendor 补丁。
- 不手改 SDK、sqlc 生成物或历史 migration。协议未变，不为了“清理”重生成 SDK；若经授权改 SQL，按既有生成链生成受控 sqlc 输出。
- 不强推，不改写旧历史，不合并 PR，不自动部署。用户将本任务书交给你作为实施指令后，可在所分配分支完成常规实现和必要的云端验证；推送前先通过本机隐私 hook，验证使用专用草稿 PR，勿将新改动推回旧维护分支。
- 当前沿用“云端构建/业务测试、本地只做源码检查/格式化/文档检查”的偏好。不要用本任务书自行解除该约束，也不要因此省掉最终云端验证。

## 3. 目标、非目标与通过标准

目标是：职责能定位，依赖单向，权威数据与规则单源，失败行为明确，复杂度有理由，同语义常量不漂移，改动后的行为有证据。

| 必须达到 | 不能拿来冒充完成 |
|---|---|
| 已确认的正确性问题修复，失败/取消/陈旧响应有回归 | 只跑静态检查或只改注释 |
| 多步编排在专职业务层，HTTP/UI 保持薄适配 | 仅把大函数切成同包小函数，业务仍留在适配层 |
| 同语义数据、参数、签名和路径规则只有一个维护点 | 建一份巨大 constants/utils，把无关概念强行耦合 |
| 有依据地删除死代码/重复实现，保留实际兼容边界 | 删除测试、缩短注释、关闭 linter 或增加豁免 |
| 当前提交适用门禁通过，文档如实记录限制 | 引用旧提交/旧安装包/NAS 历史结果 |
| 全仓各范围均有审查结论及处理证据 | 只修几个文件就声称“全项目无技术债” |

不追求所有文件同样短、不追求零字面量、不追求抽象数量增加。复杂算法、静态数据、路由装配和播放器状态机可有合理规模，必须说明原因和边界。原则上不增加新框架或第二份状态来换取表面行数下降。

## 4. 全项目规范：落实已有约束，补足实际缺口

### 4.1 依赖、数据与职责

1. Go：`cmd/qimeng` 是生产组合根；HTTP → 用例/编排 → store。禁止业务反向 import HTTP/SDK；生成协议类型只在 httpapi 使用。纯算法包不做 IO，也不能经业务包间接绕入 IO。
2. HTTP 只解码、鉴权、协议校验、调用用例和响应映射。单条只读/CRUD 不必机械增加“服务层”；资产/片段/关联/事件/文件等多步事务必须有明确的业务宿主。
3. 低层原语与事务用例区分：需要参与外层事务的函数接受同一个 qtx，不能偷偷开启/提交新事务。事务内读取决定写入的权威事实，不能拿旧快照跨过边界。
4. Web：数据访问走 hooks，组件/页面渲染；纯转换不夹带请求。长期上传队列由模块级唯一状态持有，切路由不能让队列取消，也不能拆成多份镜像。
5. Android：UI → ViewModel → Repository → SDK；feature → core。页面和 VM 不暴露生成 SDK 对象，不直接知道地址、认证或 HTTP 错误结构。
6. 桌面只做连接壳：设置/托盘/导航/窗口控制，不复制 Web 页面、领域规则或播放内核。
7. 通知通过既有事件机制；显式同步调用、事务内纯函数和依赖注入仍遵循架构文档，不把所有函数调用机械改成事件。
8. 执行前后画出发生变化的包依赖关系，确认没有循环、反向边界或隐式时序依赖。

### 4.2 常量、配置与硬编码

| 类型 | 正确处理 | 禁止 |
|---|---|---|
| 环境参数：根路径、监听地址、部署差异 | 既有 QIMENG_* / config / 忽略的 local 配置 | 个人路径、真实内网地址或设备标识入库 |
| 策略参数：超时、退避、并发、缓存 TTL、大小上限 | 就近具名常量或现有配置；写清单位与原因 | 调用点散落裸数字运算 |
| 协议路径/header/topic/分页默认值 | 同语义第二次出现前提取单源；与 OpenAPI 双写处互指 | 改协议值却只改一端常量 |
| 领域公式/阈值/排序口径 | 保留领域专题权威定义和纯函数 | 按代码美观改公式、近似替代或客户端重算 |
| Web 颜色/材质/动效/层级 | 使用既有 qm token，保持主题和降级 | 把重复硬编码搬进另一个 CSS 文件 |
| Android 主题/尺寸/交互参数 | 复用现有 theme/组件/就近语义参数 | 机械把每个 dp/sp 都塞进全局 constants |
| 0/1/空串等自明值，测试输入，struct tag | 根据既有豁免和具体语义判断 | 为清零扫描结果制造无意义常量 |

新增或修改的字面量必须用 rg 查同语义重复；相同数值但不同职责不合并。可配置参数要验证合法范围、默认回落、单位、覆盖顺序和错误提示，不能把安全阈值“配置化”成可失效的开关。

### 4.3 错误、并发与生命周期

- 禁止新增 `_ =` 吞错。事务正常结束的 `sql.ErrTxDone` 与真实回滚失败区别处理；后者必须可观察。
- 响应已经开始后的写失败只能记录，不能补写另一个 JSON/状态码。
- 文件写入成功与清理失败分开，不能因后处理失败重新上传、删除既有目标或误报事务未提交。
- Kotlin 的取消异常必须重新抛出，不能被 runCatching/getOrElse 包装为业务失败；Go context 贯穿请求查询，不为方便改 Background。
- 异步 UI 回填必须校验请求代际和草稿代际；取消旧任务是优化，代际校验才是防旧响应覆盖的正确性条件。
- 订阅、timer、goroutine、Worker、播放器等有唯一 owner 和清理路径；不要用全局状态“修”生命周期问题。
- 并发按既有 errgroup/Coroutine/Flow 模式处理；UI 同线程约束、锁顺序、事务边界和提交后副作用应有说明。
- 日志只记录服务端所需诊断，不能把 token、URI、设备标识、私人路径回传或写进公开文档/测试。

### 4.4 规模、复用与删除

遵守既有警戒线：Go 函数约 100 行/文件约 600 行；React 渲染逻辑约 300 行/文件约 500 行。Android 大文件按职责与状态 ownership 评估，不临时虚构一个全局 Kotlin 行数硬门槛。

先消除重复业务、重复状态和无效分支，再考虑文件拆分。相似 UI 可复用，但业务语义不同的流程不因代码形似就合并。删除必须证明无调用、无反射/生成/路由/平台使用；knip 不是删除所有导出的唯一依据。禁止通过改名、移动或删注释“通过”规模检查。

## 5. 已确认问题：必须完成的改动

### Q1 作者编辑事务下沉与请求取消（P2）

证据：`server/internal/httpapi/author_edit.go` 和 `authors_manage.go` 各有三处 BeginTx；存在忽略 Rollback 错误。`writeAuthorOrInternal` 使用 `context.Background()`，丢失请求取消。

实施：

1. 新增 `server/internal/authoredit`，复用 authorattach 原语，承接六个用例；附录 A 给出可核对的完整参考服务。
2. 生产 main 显式装配，同一 Conn/Queries/Attach/Mirror/Now/Logger 经 Deps 注入；HTTP New 的缺省装配只沿用现有隔离测试惯例，不创建第二套权威状态。
3. HTTP 改调一次用例，移走事务、解析/按名创建/片段与关联组合流程。来源项数/字符、作者新名字、写入模式等纯校验保留在协议层。
4. 资产和库类型、当前文件名在同事务中读取；未知库能力复用 `scanner.SupportsAuthorAttach`，禁止硬编码 kind 列表。
5. 镜像在 Commit 成功后才刷新，失败不刷新；原推荐缓存失效和详情响应装配保持。不能重复刷新镜像或擅自新增库事件/修订号变化。
6. 新哨兵对应原资产 404/不支持库 400；authorattach 的 COS、身份冲突、作者不存在等哨兵原样映射。成功码、幂等、删除保留媒体、TXT 真相与镜像尽力而为均不变。
7. `writeAuthorOrInternal(ctx, ...)` / `findAuthorRow(ctx, ...)` 贯穿 `r.Context()`。
8. 清理迁移后无用 import 和过时注释，不能留下“HTTP 管事务”“编排全部下沉但实际没有”的矛盾表述。

必须验证：新作者/改名/删除、sources replace/append、清空作者、按名新建/复用、COS/不存在/不支持库、事务中途失败、提交后镜像失败、请求取消、原响应字段及计数。加入针对这两个已迁移文件的 AST 事务边界回归；不把其他存量 handler 列为新增豁免。

### Q2 Android 词表编辑的 SDK 类型泄漏（P2）

证据：`VocabularyEditGroupsCard.kt` 和 `VocabularyEditViewModel.kt` 直接 import SDK 的 CustomSourceGroup/Character/Groups；`VocabularyEditRepository` 的接口也返回 SDK 对象，与模块的“零 SDK 引用”注释冲突。

实施：

- core:model 定义编辑域模型，列表在端内统一为非 null。
- core:data 唯一完成 SDK 双向映射；Repository 接口返回端内模型，feature 的 UI/VM/测试全部换域类型。
- stopWords/groups 保存继续显式数组、整体替换；variants/characters/aliases 的协议可省字段保持现有空列表映射语义，不能把“空=清空”改成“不提交=保留”。
- 不改本机词表通道、临时拉起内嵌服务端、错误分类或两端并集合并规则。VocabularyMerger 暂留 core:data 内部并不构成页面泄漏，别为了类型统一重写同步算法。
- 核对 feature 的生产源码无 SDK 引用，并增加可维护的源依赖守卫；不只改 Gradle 注释。优先复用现有工具，不另造复杂 Kotlin 解析器。

参考模型与映射见附录 B。必须增加 mapper 的字段/缺省/空数组回归，适配既有 VM 测试。

### Q3 Android 词表保存回读覆盖新草稿及取消异常（P1）

证据：`VocabularyEditViewModel.reloadAfterSave` 只检查 saving。保存成功后 saving=false，UI 可继续编辑；较慢的旧回读仍能覆盖新组/角色/停用词。当前 runCatching 还会把 Repository 主动传播的 CancellationException 收作失败。

实施：

1. 每次实际草稿变更递增草稿修订号；纯无效/越界/no-op 操作不得因此变脏。
2. load/save 产生请求代际，后续新读写令旧回读失效；必要时取消旧回读 Job。
3. 保存快照必须在启动操作时固定，成功只把同代草稿标为已保存；回读只在请求代际、草稿修订号匹配且无新 dirty/loading/saving 时回填。
4. 即使旧操作不响应取消，代际守卫也能阻止回填。不要仅用 dirty 或仅取消 Job 代替代际控制。
5. 所有 load/save/静默回读路径都传播取消；取消不显示“加载/保存失败”，不发起成功后回读；busy 状态按操作 ownership 正确收尾。
6. 正常保存后的服务端规范化仍可回显；回读失败不把已提交保存改成失败。

必须写可控延迟 fake 的行为测试，覆盖“保存后回读未完成→编辑→旧回读返回”、连续两轮保存回读反序、旧回读吞取消、加载/保存/回读取消。现有快速 fake 不足以证明这些场景。附录 C 给出代际与取消处理的核心代码。

### Q4 共用防抖常量的残留字面量（P3）

`AssetEditViewModel.kt` 文件名联想的 200L 已在本分支改为 `AUTHOR_SUGGEST_DEBOUNCE_MS`。核实继承该改动，检查本次其他同语义重复值。不要把搜索建议原有 300ms 与作者/文件名联想 200ms 强行合并。无需为这一个等值替换新增镜像实现式测试，靠 diff、编译和既有行为回归验证。

### Q5 Web 数据访问边界检查缺口（P2，属于门禁缺口）

`web/tests/architecture.test.ts` 使用 TypeScript AST，但尚未覆盖运行时 re-export 和方括号直接调用。本次检索没有发现页面已实际使用这些绕过语法，不能把检查缺口写成已发生的业务故障。

在已有检查器内扩充，不引新 linter：

- 拒绝 export { client } / export * / export * as ... from API 的值导出，允许纯类型导出。
- 拒绝 window['fetch']、globalThis['XMLHttpRequest'] 等直接请求写法，与现有 fetch/axios/XHR 检查一致。
- 核对 import-equals/require/dynamic import 的现有禁止策略和 type-only 例外，不误伤合法组件懒加载。
- 测试应含正反例与实际全目录扫描，说明仅证明直接语法边界，不宣称能证明任意 barrel/包装器后的全调用链纯度。

附录 D 是增量代码骨架。源码适配 TypeScript 实际版本和现有 lint 配置后，执行当前提交的 Web 测试与构建。

### Q6 Go 忽略错误清理（P2/P3，按调用点区分）

静态搜索发现多包仍存在 `_ =`：HTTP、backup、store、events、filing，以及 auth/eventstats/scanner/search/thumbnail/main 的个别位置。不同位置风险不同，不能把所有 Close/响应断开都写成数据损坏。

必须逐调用点登记并处理：

| 位置/类别 | 需要做到 |
|---|---|
| 事务回滚 | 已结束正常；真实失败记录，不覆盖原业务错误 |
| JSON/文本响应写失败 | 记录或按既有响应工具处理；禁止重写响应/重试业务 |
| 只读资源 Close | 使用所属模块日志/错误链，不能用空 if 吞错替代 |
| 写文件 Close/Sync | 明确是否影响发布成功；提交前失败不报告成功，保留源和无覆盖语义 |
| 启动/后台任务返回错误 | 核实底层是否已记录，明确单点记录，避免双日志/静默失败 |
| 不支持 deadline 等可接受分支 | 判断准确错误，注释原因，其余可观察；不能无条件丢弃 |

先复用同包既有 rollback/close 方法；确有跨模块同语义复用证据再提共享件，不新增“万能安全工具包”。测试验证有实质失败行为的路径；无须为每行只读 Close 日志写冗余测试。

### Q7 Web 上传模块职责提取（P3）

`web/src/lib/upload-queue-store.ts` 约 690 行，已有“状态内聚”例外说明；这不是单凭行数判定的错误。其 import 已使用 `lib/query-keys`，没有发现 lib→hooks 反向依赖，不修不存在的问题。

可安全提取的是不持有队列状态的“上传成功后挂靠”服务：响应资产 ID 解析、先替换资产作者再 append 来源、错误转为“已入库待挂靠”。队列的 entries/XHR/canceledIds/draining/恢复/订阅与终态推进继续唯一持有。

先核对现有测试，再落实这项职责提取。不得把提取变成重新设计队列，不新增公共 reset 测试接口；保留直传/分片成功、失败、取消、刷新恢复、后处理失败不重传和跨路由存活行为。若某一拟拆段确实依赖共享状态，则在交付表说明保留的具体理由，不能仅写“太难”。

## 6. 全仓其余范围：必须审查，按证据修改

下表不是已确认缺陷清单。每个范围必须给出“已审查/改动/保留理由/验证”的结论，发现问题先定位实际调用链，不根据名字或文件长度批量重写。

| 范围 | 重点与候选入口 | 完成要求 |
|---|---|---|
| Go 剩余厚 handler | engagement/tags、作者/词表管理、libraries、portable import/export、recommendations、facets | 标出事务、规则、读写/提交后副作用；有实际多步业务编排则迁专职包；不为单条 CRUD 加空壳 |
| 组合根和配置 | main/wire、config.go、Server.New、配置落盘/回显 | 显式依赖、共享唯一服务，默认值/单位/环境覆盖一致；构造器直线装配不按行数强拆 |
| 四个纯算法包 | recommend/stats/sourcematcher/authoring | 检查 IO 和业务依赖；保留公式/静态数据；不得为了“通用化”把计算挪回客户端 |
| 文件/上传/同步/回收站 | mediawrite/filing/uploadsess/scanner/localsync/syncchannel/authorimport | 根内路径、无覆盖、锁顺序、恢复意图、预算、完成凭据与资产同事务、成功后清理不触发重传；避免复制规则 |
| 数据访问 | SQL 源/查询装载/sqlc 使用 | 参数化、分页后装配、列投影、无逐项 N+1；不引入缓存或索引却没有失效/EXPLAIN 证据 |
| Web hooks/lib | 请求 key、查询失效、分页、SSE、认证、持久队列 | 单源 key/参数、短页/空页续页、取消/重连/失效范围一致；不以错误空数组冒充真实空数据 |
| Web 页面/播放器 | Albums/AssetEdit/AssetDetail、image-viewer/video-player/TopBar | 业务转换移出渲染、必要组件复用、主题 token、窗口/zoom/全屏契约保持；纯渲染拆分不带第二套状态 |
| Android VM | Home/Album/Detail/AssetEdit/Upload/Search/Stats 等 | 取消传播、请求代际、分页重试、单一状态；服务端统计/推荐算法不客户端复制 |
| Android 大 UI 文件 | BiliPlayerView、StatsDetailScreen、DetailScreen、NavHost、FloatingTabDock、VideoStage、Upload/All/Home/Settings | 先 ownership 再拆渲染/纯格式化；播放器/导航状态不拆成多个 owner；AndroidView 既有桥接例外如实保留 |
| Android 数据/网络 | SdkMediaRepositories、Worker/上传、embedded 服务、本机配置 | SDK 映射、地址单源、取消持久化、成功后归档清理边界、唯一进程/恢复看护，不更换系统方案 |
| 桌面壳 | main.rs/server_config.rs/titlebar.js/setup.html | 地址规范化单源、导航守卫、窗口标记协议与旧 Web 兼容；不碰 vendor/glib 补丁来源 |
| 脚本/部署/工具/CI | Makefile、启动链、deploy、tools、workflows | 无个人路径/凭据、适用平台明确、工具版本锁、生成链一致；禁止弱化现有门禁或把测试转交用户执行 |
| 重复与死代码 | 全部受控生产源，排除第三方/生成内容 | 用静态工具+调用链双证据删除；同步调用方/文档/测试，避免顺手升级依赖 |

扫描线索可使用 rg、git ls-files、现有 Go/TS AST、knip 和 Gradle 依赖声明；工具命中只是线索。对测试/示例/规范常量的豁免不能自动扩大到生产代码。

较大文件的基线线索包括：Go 的 matcher/namesuggest/import/server/config；Web 上传队列和媒体查看器；Android 的播放器、统计详情、详情页、首页 VM、导航及悬浮底栏。执行前重算，不把静态快照当硬阈值或性能结论。

## 7. 推荐实施顺序与停止条件

1. 固定基线和现场；核对未完成工作，建立全范围审查表。
2. 先修 Q3 草稿覆盖/取消，再做 Q2 类型隔离，或同一逻辑批一次完成并证明兼容。
3. 完成 Q1 事务下沉及独立失败回归，再同步模块职责。
4. 完成 Q4/Q5/Q6/Q7，逐项审查第 6 节范围，落实实际发现的重复/耦合/死代码问题。
5. 统一自审 diff、生成物/migration/隐私/文档影响，执行当前提交云端门禁；能修的相关失败先修。
6. 对每个 Q 和全范围审查项给出实际文件/commit/验证/保留理由，一次性交付主审。提交格式、pull/冲突处理、署名和文档归档遵守项目规范。

普通拆分、复用、日志处理和测试适配可自主完成，不逐项向用户请求确认。出现下列情况应隔离该项、继续其他工作并明确报告：需要改领域公式/协议兼容/schema/安全机制；必须引入运行时依赖或更换框架；需要真实数据/设备/NAS 验收；当前环境无法执行必需生成/云端检查。不能用这些限制把本可完成的源码工作全部搁置，也不能伪造通过。

## 8. 验证、文档与最终交付

### 8.1 测试与门禁

本地允许：git diff --check、gofmt、针对新增字面量和依赖的静态检索、文档检查。不要在本机执行下列业务测试/构建；放到已有云端工作流或合适的隔离云端验证中。

| 范围 | 云端验证基线 |
|---|---|
| Go | 固定版本重建协议层；golangci-lint（含 depguard）、go vet、gofmt、go test ./... -race -count=1、go build ./... |
| Go Windows 文件操作 | 既有 Windows 原生 filing 与隔离启动链门禁 |
| Web | npm ci、固定协议生成、现有 knip 命令、tsc/build、vitest；本次变化还须 oxlint |
| Android | 生成 SDK/内嵌产物前置不省略；wrapper 执行 assembleDebug、testDebugUnitTest、:core:model:test、lintDebug |
| 桌面 | cargo test --locked、cargo build --locked；不省略既有安全补丁门禁 |
| 全局 | 文档、历史 migration、防隐私泄漏、SDK 锁和安全工作流等既有必需检查 |

仅按影响添加有意义的回归，不为等值常量替换写重复测试。跨端结构变化完成后，最终以同一 head 的完整适用门禁为准；记录 SHA、run 链接、完成状态、失败和限制。文档检查通过不代表示例代码编译通过；构建通过不代表设备/NAS 验收。

### 8.2 必须同步的正文

- authoredit/模块依赖变化：server README、ARCHITECTURE §5/5.1、相关包职责注释。
- Android 模型/状态所有权：android README 对应维护定位；Q3 的可见保存/回读约束同步 GUIDE_UI。
- Web 提取与 AST 直接边界范围：web README；实际交互未变不杜撰视觉变化。
- 若发现并实际改变领域、安全或 API 行为：先遵循各权威专题流程，不用 CHANGELOG 代替正文。
- HANDOVER 只记录当前分支交付/验证状态；CHANGELOG 写真实执行模型、结果和限制。不要把本任务书里的计划改写成已验证。
- 本任务书不是第二份交接。完成后保留本轮范围与结果入口，最终验证身份只在 HANDOVER 维护；不再新增平行 TODO/交接文件。
- 新重大决策才补 ADR 和索引；普通内部拆分不滥建 ADR。
- 提交前执行 `python -X utf8 tools/check_docs.py`；涉及 PR 时还要检查相关专题影响与说明。

### 8.3 5.3 的最终回复必须包含

1. 当前分支和最终 SHA，实际改动摘要。
2. Q1–Q7 的完成表；第 6 节每个范围的审查/处理结论与源码证据。
3. 删除/提取内容的理由、最终依赖方向、唯一状态/规则/常量所在位置。
4. 新增/适配测试和同 head 云端检查链接，明确未执行、失败、受限项。
5. 文档影响，以及实际读取文档列表。
6. 未改动的真实数据/运行包范围；需要主审复核的问题和剩余合理例外。
7. 不请求用户运行维护命令，不将“编译可能通过”“以后再测”作为已完成。

主审最终复核重点：行为与协议、事务回滚和提交后副作用、取消/陈旧响应、SDK 隔离、文件安全、硬编码语义、重复真相、有效测试、同 head 证据、文档准确性。源码完成但验证受限应写“待验证”，不能自行标“审批通过”。

## 附录 A：authoredit 完整服务参考实现

以下依据已核实的现有函数签名整理；GLM-5.3 原提案中的不存在构造函数已在本任务书纠正。此代码未编译/测试；5.3 仍须检查实际版本、错误映射与现有调用点后落盘。不要把代码先放进生产路径再省略接线/测试。

建议目标：`server/internal/authoredit/service.go`。现有 authorattach 的事务内原语保持兼容。

```go
// Package authoredit 编排作者编辑类业务用例：在同一事务内组合 authorattach
// 低层原语完成读写，提交成功后按用例需要触发镜像刷新。
//
// 边界约定：
//   - 本包只做事务编排与用例级错误归类；名字、列表长度、来源模式等纯协议
//     校验由 httpapi 层完成，本包不重复校验，也不持有协议或UI状态。
//   - 低层 authorattach 的哨兵错误原样透传，不改错误身份；本包仅新增
//     ErrAssetNotFound / ErrUnsupportedLibraryKind 两个哨兵。
//   - 推荐cache失效、事件与修订号由调用方在用例成功返回后处理，本包不触碰。
//   - 事务不导出；低层原语复用调用方事务 qtx，不在低层另开事务。
package authoredit

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"time"

	"qimeng-media/server/internal/authorattach"
	"qimeng-media/server/internal/scanner"
	"qimeng-media/server/internal/store/db"
)

// ErrAssetNotFound 目标资产不存在，HTTP层映射为404“资产不存在”。
var ErrAssetNotFound = errors.New("authoredit: 资产不存在")

// ErrUnsupportedLibraryKind 该库类型不支持作者挂靠，HTTP层映射为400。
var ErrUnsupportedLibraryKind = errors.New("authoredit: 该库类型不支持作者挂靠")

// Options 由 main 显式注入；Conn/Queries 必填，其他依赖按既有模式兜底。
type Options struct {
	Conn    *sql.DB
	Queries *db.Queries
	Attach  *authorattach.Service
	Mirror  *authorattach.MirrorWriter
	Now     func() time.Time
	Logger  *slog.Logger
}

// Service 作者编辑用例服务，只持有依赖引用，自身无协议/UI状态。
type Service struct {
	conn    *sql.DB
	queries *db.Queries
	attach  *authorattach.Service
	mirror  *authorattach.MirrorWriter
	now     func() time.Time
	logger  *slog.Logger
}

// New 装配 Service。nil 依赖兜底：Now→time.Now、Logger→slog.Default、
// Attach/Mirror→复用低层服务结构体；生产 main 仍应显式注入全部依赖，
// 兜底仅兼容既有隔离测试装配。
func New(opts Options) *Service {
	s := &Service{
		conn:    opts.Conn,
		queries: opts.Queries,
		attach:  opts.Attach,
		mirror:  opts.Mirror,
		now:     opts.Now,
		logger:  opts.Logger,
	}
	if s.now == nil {
		s.now = time.Now
	}
	if s.logger == nil {
		s.logger = slog.Default()
	}
	if s.attach == nil {
		s.attach = &authorattach.Service{Logger: s.logger}
	}
	if s.mirror == nil {
		s.mirror = &authorattach.MirrorWriter{Logger: s.logger}
	}
	return s
}

// withTx 在单个事务内执行 fn：fn 失败或 Commit 失败均向上返回错误；
// defer 回滚，回滚错误除 sql.ErrTxDone（事务已结束）外记录日志。
// 仅本包用例复用，不导出事务对象，也不做泛型封装。
func (s *Service) withTx(ctx context.Context, fn func(qtx *db.Queries) error) error {
	tx, err := s.conn.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer func() {
		if err := tx.Rollback(); err != nil && !errors.Is(err, sql.ErrTxDone) {
			s.logger.WarnContext(ctx, "authoredit: 事务回滚失败", "error", err)
		}
	}()
	qtx := s.queries.WithTx(tx)
	if err := fn(qtx); err != nil {
		return err
	}
	return tx.Commit()
}

// EnsureSourceVocabulary 单事务内确保来源词表就绪并原样返回结果
// （nil/空切片保持低层原返回，不做归一化）。词表不落TXT，本用例不镜像。
func (s *Service) EnsureSourceVocabulary(ctx context.Context) ([]string, error) {
	now := s.now()
	var sources []string
	err := s.withTx(ctx, func(qtx *db.Queries) error {
		var err error
		sources, err = authorattach.EnsureSourceVocabulary(ctx, qtx, now)
		return err
	})
	if err != nil {
		return nil, err
	}
	return sources, nil
}

// ReplaceAuthorSources 整体替换作者来源：事务内先解析常规作者显示名，
// 再以该显示名写入来源列表；提交成功后镜像刷新，任何失败均不镜像。
// 来源合法性由低层哨兵错误约束，本层不改错误身份。
func (s *Service) ReplaceAuthorSources(ctx context.Context, authorID string, sources []string, mode authorattach.SourcesWriteMode) ([]string, error) {
	now := s.now()
	var merged []string
	err := s.withTx(ctx, func(qtx *db.Queries) error {
		display, err := s.attach.ResolveRegularAuthor(ctx, qtx, authorID)
		if err != nil {
			return err
		}
		merged, err = s.attach.ReplaceAuthorSources(ctx, qtx, now, authorID, display, sources, mode)
		return err
	})
	if err != nil {
		return nil, err
	}
	s.mirror.Refresh(ctx, s.queries)
	return merged, nil
}

// CreateAuthor 创建（或复用已存在的）单个作者，名字先trim，其余合法性
// 沿用低层校验；单名输入理论上仅返回一项，len!=1 视为内部一致性异常，
// 在提交前终止事务。仅写作者行不改TXT，本用例不镜像。
func (s *Service) CreateAuthor(ctx context.Context, displayName string) (authorattach.AuthorRef, error) {
	now := s.now()
	var created authorattach.AuthorRef
	err := s.withTx(ctx, func(qtx *db.Queries) error {
		refs, err := s.attach.ResolveOrCreateAuthors(ctx, qtx, []string{strings.TrimSpace(displayName)}, now)
		if err != nil {
			return err
		}
		if len(refs) != 1 {
			return fmt.Errorf("authoredit: 创建作者期望返回1项，实际%d项", len(refs))
		}
		created = refs[0]
		return nil
	})
	if err != nil {
		return authorattach.AuthorRef{}, err
	}
	return created, nil
}

// RenameAuthor 重命名作者：事务内调用低层重命名（名字先trim），
// 提交成功后镜像刷新；低层哨兵错误（如作者不存在）原样透传。
func (s *Service) RenameAuthor(ctx context.Context, id, displayName string) (authorattach.AuthorRef, error) {
	now := s.now()
	var renamed authorattach.AuthorRef
	err := s.withTx(ctx, func(qtx *db.Queries) error {
		var err error
		renamed, err = s.attach.RenameAuthor(ctx, qtx, now, id, strings.TrimSpace(displayName))
		return err
	})
	if err != nil {
		return authorattach.AuthorRef{}, err
	}
	s.mirror.Refresh(ctx, s.queries)
	return renamed, nil
}

// DeleteAuthor 删除作者：事务内调用低层删除，提交成功后镜像刷新。
func (s *Service) DeleteAuthor(ctx context.Context, id string) error {
	now := s.now()
	err := s.withTx(ctx, func(qtx *db.Queries) error {
		return s.attach.DeleteAuthor(ctx, qtx, now, id)
	})
	if err != nil {
		return err
	}
	s.mirror.Refresh(ctx, s.queries)
	return nil
}

// ReplaceAssetAuthors 整体替换资产作者：既有作者按 ids 顺序解析
// （ids 已在 httpapi 层保序、trim并去重），新名字批量解析后合并，
// 用同事务内刚读取的 FileName 写回；提交成功后镜像刷新。
// 边界：本用例不触碰推荐cache、事件或修订号，由调用方在成功后处理。
func (s *Service) ReplaceAssetAuthors(ctx context.Context, assetID string, ids, newNames []string) error {
	now := s.now()
	err := s.withTx(ctx, func(qtx *db.Queries) error {
		asset, err := qtx.GetAssetWithLibrary(ctx, assetID)
		if err != nil {
			if errors.Is(err, sql.ErrNoRows) {
				return ErrAssetNotFound
			}
			return err
		}
		lib, err := qtx.GetLibrary(ctx, asset.LibraryID)
		if err != nil {
			// 库元数据读取失败属于内部错误，保持原样上抛。
			return err
		}
		if !scanner.SupportsAuthorAttach(lib.Kind) {
			return ErrUnsupportedLibraryKind
		}
		resolved, err := s.attach.ResolveRegularAuthors(ctx, qtx, ids)
		if err != nil {
			return err
		}
		refs := make([]authorattach.AuthorRef, 0, len(ids))
		for _, id := range ids {
			refs = append(refs, authorattach.AuthorRef{ID: id, DisplayName: resolved[id]})
		}
		if len(newNames) > 0 {
			created, err := s.attach.ResolveOrCreateAuthors(ctx, qtx, newNames, now)
			if err != nil {
				return err
			}
			refs = authorattach.MergeAuthorRefs(refs, created)
		}
		return s.attach.ReplaceAssetAuthors(ctx, qtx, now, assetID, asset.FileName, refs)
	})
	if err != nil {
		return err
	}
	s.mirror.Refresh(ctx, s.queries)
	return nil
}

```

接线必须由实现者完成：

- main：把既有 Attach 结构体提为 attachService，与 mirrorWriter 一起装配 authoredit；Deps 新增 AuthorEdit。不能使用不存在的 authorattach.New/Options/NewMirrorWriter。
- HTTP New：先获得同源 attach/mirror/logger/now，再使用注入 AuthorEdit；nil 时仅按既有隔离测试模式兜底。
- 作者管理方法调用 Create/Rename/Delete；作者来源调用 ReplaceAuthorSources；资产作者调用 ReplaceAssetAuthors；词表预填调用 EnsureSourceVocabulary。事务/镜像代码从 handler 删除。
- HTTP 的 writeAuthorEditErr 增加 ErrAssetNotFound / ErrUnsupportedLibraryKind 映射；原作者哨兵保持。
- 原推荐失效和详情装配保持；writeAuthorOrInternal 增 ctx 并传 r.Context。不要新增副作用或另存一份推荐真相。
- 若某内部错误的原公开错误文案不同，先核对现有测试/契约，不在重构里悄悄扩大公开信息。

### A.1 事务行为测试设计（不能仅测函数被调用）

用 store.Open + store.Migrate 建临时合成库，q=db.New(conn)，不手工复制 schema：

1. 预置 normal 库、资产、作者/TXT 片段和已有作者关联。
2. 注入 asset_authors 写入失败的 SQLite 测试触发器，调用 ReplaceAssetAuthors，并请求按名新建另一作者。
3. 断言返回错误；原片段、上传条目、原关联保持；新作者未提交；镜像文件未变化。
4. 去掉触发器再执行，断言片段作品行、关联、新作者一起成功；重试不重复。
5. 镜像路径指向合成不可写目标，确认事务成功仍返回成功且提交数据可见；原媒体不受影响。
6. 作者删除/改名的中途写失败也应整体回滚；失败不是“只剩最终表对了但 TXT 已变”。
7. canceled context、COS 作者、缺失资产、不支持库和身份冲突保持既有错误身份与 HTTP 状态。
8. 日志用测试 handler/缓冲捕获可观察性；不要为省事改变生产的日志/错误 API。

## 附录 B：Android 词表编辑域模型与映射参考

建议新增 `core:model/.../EditableVocabulary.kt`，下列类名在使用前先查仓内冲突。模型不依赖 SDK、Android 或 IO。

```kotlin
package media.qimeng.app.core.model

data class EditableVocabulary(
    val groups: List<VocabularyGroup> = emptyList(),
    val stopWords: List<String> = emptyList(),
)

data class VocabularyGroup(
    val canonical: String,
    val variants: List<String> = emptyList(),
    val characters: List<VocabularyCharacter> = emptyList(),
)

data class VocabularyCharacter(
    val canonical: String,
    val aliases: List<String> = emptyList(),
)
```

映射只在 core:data，不能复制进 feature。以下保留现有协议可省子列表行为；groups/stopWords 保存继续显式数组：

```kotlin
package media.qimeng.app.core.data.repository

import media.qimeng.app.core.model.EditableVocabulary
import media.qimeng.app.core.model.VocabularyCharacter
import media.qimeng.app.core.model.VocabularyGroup
import media.qimeng.sdk.models.CustomSourceCharacter
import media.qimeng.sdk.models.CustomSourceGroup
import media.qimeng.sdk.models.CustomSourceGroups

internal fun CustomSourceGroups.toEditableVocabulary() = EditableVocabulary(
    groups = groups.map { group ->
        VocabularyGroup(
            canonical = group.canonical,
            variants = group.variants.orEmpty(),
            characters = group.characters.orEmpty().map { character ->
                VocabularyCharacter(character.canonical, character.aliases.orEmpty())
            },
        )
    },
    stopWords = stopWords.orEmpty(),
)

internal fun EditableVocabulary.toSdk() = CustomSourceGroups(
    groups = groups.map { group ->
        CustomSourceGroup(
            canonical = group.canonical,
            variants = group.variants.ifEmpty { null },
            characters = group.characters.map { character ->
                CustomSourceCharacter(
                    canonical = character.canonical,
                    aliases = character.aliases.ifEmpty { null },
                )
            }.ifEmpty { null },
        )
    },
    stopWords = stopWords,
)
```

Repository 的签名改为 `load(): Result<EditableVocabulary>` / `save(payload: EditableVocabulary): Result<Unit>`。现有实现只在 API 调用边缘分别 .toEditableVocabulary() / payload.toSdk()，保留 LocalVocabularyChannel、IO dispatcher、CancellationException 和 VocabularySyncException 分类。

feature 中的 groups/characters/aliases/variants 全部非 null，移除为了 SDK 可省字段而存在的 removeAtOrNull；删除后为空在端内仍 emptyList，最后由 mapper 决定协议表达。既有测试的“子列表为空→null”断言应移到 mapper 测试，VM 改断言端内空列表，不删掉覆盖。两端词表同步算法不因此改动。

## 附录 C：草稿代际与取消传播核心参考

这是对现有 ViewModel 的**集成骨架**，不是可直接替换整个文件的完整类。保留已有编辑动作、错误文案和注入；把下列修订信息整合进同一 UI 状态，不另建一份 groups/stopWords。

```kotlin
// VocabularyEditUiState 中的本地草稿元数据，不是服务端词表版本。
val draftRevision: Long = 0L

// ViewModel 私有字段。相关操作延续现有 Main dispatcher 单线程约束。
private var readGeneration = 0L
private var readbackJob: Job? = null

private fun invalidateReadbacks(): Long {
    readGeneration += 1
    readbackJob?.cancel()
    readbackJob = null
    return readGeneration
}

// 所有实际编辑通过此口更新；transform 只改变草稿内容。
// 不能在 StateFlow.update 可重试的 lambda 内递增外部计数器。
private fun updateDraft(
    transform: (VocabularyEditUiState) -> VocabularyEditUiState,
) {
    _uiState.update { before ->
        val after = transform(before)
        if (after.groups == before.groups && after.stopWords == before.stopWords) {
            before
        } else {
            after.copy(dirty = true, draftRevision = before.draftRevision + 1)
        }
    }
}

private suspend fun <T> request(
    block: suspend () -> Result<T>,
): Result<T> = try {
    block().also { result ->
        val failure = result.exceptionOrNull()
        if (failure is CancellationException) throw failure
    }
} catch (cancel: CancellationException) {
    throw cancel
} catch (error: Exception) {
    Result.failure(error)
}

private fun reloadAfterSave(
    expectedRevision: Long,
    expectedGeneration: Long,
) {
    readbackJob = viewModelScope.launch {
        request { repository.load() }.onSuccess { payload ->
            _uiState.update { current ->
                if (
                    readGeneration != expectedGeneration ||
                    current.draftRevision != expectedRevision ||
                    current.dirty || current.saving || current.loading
                ) {
                    current
                } else {
                    current.copy(
                        groups = payload.groups,
                        stopWords = payload.stopWords,
                    )
                }
            }
        }
    }
}
```

save 的集成规则必须同时落实：

1. 通过 busy 门后，立刻固定 snapshot、payload、expectedRevision，调用 invalidateReadbacks() 取得 expectedGeneration，再启动异步保存；不要等进 coroutine 后才读“当前草稿”。
2. 用 request 调 Repository；取消继续抛出，不能进入失败提示或成功回读。
3. 成功时，仅在草稿修订号仍等于保存快照时清 dirty；否则保留新草稿 dirty。成功提示计数用保存快照，不能拿后来编辑过的计数冒充已保存。
4. 只有成功才调用 reloadAfterSave(expectedRevision, expectedGeneration)。后续新 load/save 都应推进 readGeneration，旧回读必失效。
5. busy 清理使用所属操作的 finally/代际 guard，避免旧任务把新任务的 busy 清掉。load 的响应也要按对应代际与草稿版本决定回填。
6. 新增/改名/删除组、变体、角色、别名和停用词都改走 updateDraft；无效索引、重复空编辑不得误改 dirty/revision。
7. 单纯 cancel Job 不够；fake 故意忽略取消时也必须由两个代际拒绝旧结果。

可控延迟 fake 测试应按如下顺序，而不是 advanceUntilIdle 一次就算验证：

```text
保存旧草稿成功
→ 拦住保存后的 load，使它暂不返回
→ 确认 saving 已结束，执行一次新的编辑
→ 放行旧 load
→ 断言新编辑仍在、dirty=true、revision 未回退

第一次保存成功并拦住回读 A
→ 新编辑并第二次保存成功，回读 B 返回
→ 再放行 A（即使 fake 吞取消）
→ 断言 B 的规范化结果未被 A 覆盖

分别让 load/save/readback 抛 CancellationException
→ 无业务失败提示、无错误成功回读、取消继续传播
```

## 附录 D：Web AST 检查的增量参考

在现有 violations 中复用 apiPath、errors、file 和 visit，不另建第二份检查器。下面补充运行时导出；type-only 的 export 子句和混合导出要分别测。

```typescript
if (ts.isExportDeclaration(node) &&
    node.moduleSpecifier &&
    ts.isStringLiteral(node.moduleSpecifier)) {
  const clause = node.exportClause
  const typeOnly = node.isTypeOnly || (
    clause && ts.isNamedExports(clause) &&
    clause.elements.length > 0 &&
    clause.elements.every(element => element.isTypeOnly)
  )
  if (!typeOnly && (
    apiPath(node.moduleSpecifier.text) ||
    node.moduleSpecifier.text === 'axios'
  )) {
    errors.push('UI 禁止运行时 API 重导出')
  }
}
```

现有 CallExpression/NewExpression 的 called 解析应识别字符串方括号成员；保留现有 Identifier/PropertyAccess 两支：

```typescript
const called = ts.isIdentifier(expression)
  ? expression.text
  : ts.isPropertyAccessExpression(expression)
    ? expression.name.text
    : ts.isElementAccessExpression(expression) &&
        expression.argumentExpression &&
        (ts.isStringLiteral(expression.argumentExpression) ||
         ts.isNoSubstitutionTemplateLiteral(expression.argumentExpression))
      ? expression.argumentExpression.text
      : ''
```

至少加入以下 fixtures，实际再核对 TS AST 与现有规则的兼容：

```typescript
// 必须失败
"export { client } from '@/api/generated'"
"export * from '../api/generated'"
"export { type Asset, client } from '@/api/generated'"
"window['fetch']('/api/v1/assets')"
"new globalThis['XMLHttpRequest']()"

// 必须允许
"export type { Asset } from '@/api/generated'"
"export { type Asset } from '@/api/generated'"
"export type * from '@/api/generated'"
"import type { Asset } from '@/api/generated'"
"const Detail = import('../pages/DetailPage')"
"const label = 'fetch /api/v1/assets'"
```

不要凭这些字符串测试就宣称整个调用链不可能访问网络；barrel 与包装层需要源依赖审查。实际新增保护的覆盖范围在 Web README/架构说明中如实表述。

## 附录 E：Go 已迁移事务边界的 AST 回归参考

建议同 `server/internal/architecture` 测试目录。以下测试只锁定本轮迁移完成的两个文件，防止回流；它不是全部 HTTP 纯度证明，也不能替代行为测试。

```go
package architecture_test

import (
    "go/ast"
    "go/parser"
    "go/token"
    "path/filepath"
    "testing"
)

func TestMigratedAuthorHandlersDoNotOwnTransactions(t *testing.T) {
    for _, name := range []string{"author_edit.go", "authors_manage.go"} {
        path := filepath.Join("..", "httpapi", name)
        positions := token.NewFileSet()
        source, err := parser.ParseFile(positions, path, nil, 0)
        if err != nil {
            t.Fatal(err)
        }
        ast.Inspect(source, func(node ast.Node) bool {
            call, ok := node.(*ast.CallExpr)
            if !ok {
                return true
            }
            selector, ok := call.Fun.(*ast.SelectorExpr)
            if !ok {
                return true
            }
            switch selector.Sel.Name {
            case "Begin", "BeginTx", "WithTx":
                t.Errorf("%s: 作者事务应由 authoredit 用例持有",
                    positions.Position(call.Pos()))
            }
            return true
        })
    }
}
```

需要按真实实现补检查器自身正反例，以及新 authoredit 不能依赖 HTTP/SDK 的现有 depguard 覆盖核对。不要为了通过检查把方法重命名后把事务留在 handler。

## 附录 F：错误处理示例与防机械替换

```go
// 所属业务包内复用，不新增全局万能清理框架。
func rollback(tx *sql.Tx, logger *slog.Logger) {
    if err := tx.Rollback(); err != nil && !errors.Is(err, sql.ErrTxDone) {
        logger.Warn("回滚事务失败", "err", err)
    }
}

// 已开始响应：只记录失败，不能再次写错误响应。
if _, err := w.Write(body); err != nil {
    logger.Warn("写出响应失败", "err", err)
}
```

若写入已发生主错误而关闭也失败，可按实际返回签名使用 errors.Join 保留两者；若成功路径的 Close/Sync 决定发布是否成立，则它属于主成功条件。必须阅读调用链后处理，不能把所有 defer 一律改成日志后忽略，也不能因日志改动增加客户端重试副作用。

## 附录 G：建议的交付结果表

在最终回复中给出结果，不必新增另一份长期文档：

| 项目/范围 | 实际结论 | 关键文件/提交 | 验证证据 | 保留理由/未完成边界 |
|---|---|---|---|---|
| Q1–Q7（逐项） | 已完成/已存在无需重复/受限待验证 | 可定位 | 同 head | 具体原因 |
| 第 6 节各范围（逐行） | 已审查并修改/已审查保留 | 可定位 | 按影响 | 不能只写“未发现问题” |
| 协议/schema/生成物/领域公式 | 是否变化及授权来源 | 可定位 | 锁/生成链/回归 | 禁止隐瞒 |
| 运行包/真实数据/设备 | 本任务是否实际操作 | 可核实 | 若无则明确未执行 | 不外推旧验收 |

本轮任务书的成立不代表全项目已通过审查或已经变得“无硬编码”；最终结论必须由实际分支改动与验证支持。
