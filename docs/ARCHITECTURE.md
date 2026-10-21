# ARCHITECTURE - 架构总纲

> 本文维护当前系统结构与模块边界。协议、安全、领域分别由各自规范负责；不以本文覆盖其语义。技术选型的"为什么"见 `docs/adr/`，业务规则见 `docs/DOMAIN_RULES.md`。
> 维护日期：2026-10-21。变更经过见 CHANGELOG 与 git 历史。

## 阅读范围

只读任务命中章节：系统/选型 §2–4，服务端边界 §5/§5.1，身份/事件 §6–7，客户端 §8，部署 §9，CI §10，迁移 §11。当前使用形态与验证边界见 [HANDOVER](HANDOVER.md)；历史决策先查 ADR 索引。

## 1. 需求起源与产品定位

用户十条核心需求（2026-08-22 确认）：

| # | 需求 | 架构落点 |
|---|---|---|
| 1 | 解放手机/电脑存储 | 媒体全存 NAS，客户端零库存储（仅可控缓存） |
| 2 | 工具与 UI 解耦、多模块 | §4 三层解耦 + §5 模块边界 |
| 3 | 网络安全 | `SECURITY.md` 全文 + 安全测试进 CI |
| 4 | UI 可重做、改得快、支持动效 | UI 是最便宜可换层；Web 用 React+shadcn+设计 token+CSS/原生动效 |
| 5 | 先用飞牛 fnOS 虚拟机测试 | M5 里程碑，VirtualBox 桥接网络 |
| 6 | 领域规则继承、技术全面更新 | `DOMAIN_RULES.md` + 新技术栈 |
| 7 | 保留原图主旨 | 原图永不转码；缩略图/预览副本存独立数据目录 |
| 8 | 架构与项目管理完整 | 本文档 + ADR + 里程碑 + 文档体系 |
| 9 | 服务端有负载/流量监控 | `OBSERVABILITY.md` |
| 10 | AI 友好、记录完备 | `AI_README_FIRST.md` + ADR 制度 |

产品形态：**内网（可扩展 Tailscale 远程）自托管家庭媒体库**。单用户起步，表结构预留多用户。明确不做：AI 识别/人脸聚类、实时转码、公网多租户、iOS（详见 `adr/0002` 排除清单）。

## 2. 总体形态

```
                      ┌──────────────────────────────────────┐
                      │  NAS / fnOS / 任意常开电脑 (Docker)     │
                      │  qimeng-server (Go 单进程)             │
手机 App (Compose) ───┤  ├─ scanner    扫描器(fsnotify+轮询兜底) │
浏览器/PWA (React) ───┤  ├─ thumbnail  缩略图管线(ffmpeg+工作池) │
桌面壳 (Tauri)     ───┤  ├─ search     FTS5 全文检索            │
未来: TV           ───┤  ├─ recommend 推荐算法(纯函数)           │
                      │  ├─ stats      统计聚合(纯函数)           │
                      │  ├─ sourcematcher 出处/角色匹配(纯函数)   │
                      │  ├─ authoring  作者TXT解析/匹配(纯函数)   │
                      │  ├─ filing     文件操作(上传/移动/回收站)  │
                      │  ├─ httpapi    oapi-codegen 生成的接口层  │
                      │  ├─ store      sqlc 数据访问 + migrations │
                      │  ├─ events     进程内事件总线 + SSE 推送   │
                      │  └─ sysmon     系统监控(gopsutil)        │
                      │  （其他包未画入图：auth 鉴权/备份快照 backup/
                      │   配置 config/库修订号 libraryrevision/
                      │   断点续传会话 uploadsess/本机同步 localsync/
                      │   作者挂靠 authorattach/事件统计 eventstats——职责见 §5 边界表）│
                      │  SQLite(单文件) + 数据目录(缩略图/回收站)   │
                      │  /media 媒体库目录(可写,受控操作)          │
                      └──────────────────────────────────────┘
                                 ▲
                          openapi.yaml（协议宪法）
                          自动生成 Go接口层 / TS / Kotlin 三端 SDK
```

核心原则：列表/搜索/推荐/统计的领域计算与权威业务数据在服务端；客户端负责展示、直链播放、行为上报及自身队列/缓存/同步编排。Android 单机形态内嵌同一服务端，不在 UI 重复实现算法。

## 3. 技术选型定论

| 层 | 选型 | 一句话理由（详见对应 ADR） |
|---|---|---|
| 服务端语言 | Go 1.27 | 单二进制、低内存、交叉编译、AI 生成质量最稳（PhotoPrism/Gitea/Syncthing 同路线） |
| HTTP 路由 | 标准库 Go 1.22+ ServeMux | oapi-codegen std-http-server 生成模式直接注册（v0 曾选 chi，代码已无引用、go.mod 2026-08-31 tidy 移除，此处如实记录） |
| 接口层生成 | oapi-codegen | 从 openapi.yaml 生成 server 接口与类型 |
| 数据库 | SQLite（modernc.org/sqlite 纯 Go 驱动） | 零运维单文件；无 CGO 交叉编译无痛（adr/0003） |
| 数据访问 | sqlc | SQL 先写，类型安全 Go 代码生成 |
| 数据库演进 | golang-migrate | 版本化迁移，schema 只能这样改 |
| 目录监听 | fsnotify + 定时轮询兜底 | 双保险，Windows/Linux 行为差异由轮询补齐 |
| 媒体处理 | ffmpeg（镜像内置）+ Go 图像库 | 缩略图/抽帧离线管线 |
| 实时推送 | SSE（进程内事件总线） | 扫描进度/库变更实时到端 |
| 系统监控 | gopsutil + prometheus client | 内置仪表盘 + 标准 /metrics（OBSERVABILITY.md） |
| Web | React + Vite + TypeScript + shadcn/ui + Tailwind + TanStack Query；CSS/原生 View Transition 动效（ADR-0031） | AI 语料最大；组件生态解决 UI 美观；动效生态成熟 |
| Android | Kotlin + Jetpack Compose + Coil 3 + Media3 | 用户 Kotlin 经验延续；Coil/ExoPlayer 原生支持 HTTP 直链 |
| 客户端 SDK | openapi-generator（TS: @hey-api；Kotlin: 生成器） | 协议改动三端自动同步（adr/0001） |
| 部署 | Docker 镜像（linux/amd64 已交付；arm64 按需储备）+ deploy/ 三件套 | 复用服务端实现；迁移需核对库根、权限与配置，目标硬件另行验收（`deploy/README.md`） |

## 4. 三层 UI 解耦（需求 #2 的落地）

1. **服务端与 UI 分层**：API 返回领域数据（排好序的列表、算好分的推荐），不返回 UI 结构；禁止为某端开专属接口（BFF）。换 UI/加 TV 端优先复用现有协议；新数据需求仍需按 OpenAPI 流程扩展，不能承诺服务端零改动。
2. **协议生成层共享**：openapi.yaml 生成的 SDK 是三端共享的数据接入层，天然一致。
3. **客户端内部逻辑/UI 分层**：Web 端数据获取全部走 TanStack Query hooks（组件零请求代码）；Android 端 ViewModel 管状态、Compose 只渲染。UI 组件禁止直接调 API、禁止内嵌业务规则。

换皮可以复用领域逻辑和数据获取层，但布局、交互、无障碍及桌面契约仍需验证，相关 UI 测试随行为调整。分层减少改动面，不代表切换视觉无需验证。

## 5. 服务端模块边界（server/internal/）

| 包 | 职责 | 禁止 |
|---|---|---|
| `httpapi` | 请求校验、鉴权、调编排服务、序列化 | 业务规则 |
| `mediawrite` | 直传、续传创建/完成、共用入库、实时上传策略和库变更指标/事件；生产在 main 显式装配 | HTTP/SDK 类型、领域公式、第二份业务数据 |
| `scanner` | 全量/增量扫描、变更检测、移动合并 | 直接写库（经 store） |
| `thumbnail` | 缩略图生成队列（工作池、懒生成、缓存键、静图输出格式自适应：libwebp 缺失时 mjpeg 降级——内嵌形态，U11 批次D） | 阻塞 API 请求 |
| `search` | FTS5 索引维护与查询 | — |
| `recommend` | 推荐/排行算法 | **任何 IO**（纯函数，输入输出领域结构体） |
| `recommendfeed` | 推荐输入装载、调用纯函数评分、限容量短期 ID 顺序快照及当前页资格/元数据装载 | HTTP/协议对象、写入业务数据、修改评分公式 |
| `ranking` | 只读排行榜热度键装载、调用 recommend 选页、按页装配元数据/累计计数；httpapi 仅适配协议 | 写入业务数据、HTTP 依赖、复制热度公式 |
| `stats` | 统计聚合、趋势分桶 | **任何 IO** |
| `sourcematcher` | 出处/角色匹配引擎（130 组内置检索表 + 前缀匹配） | **任何 IO**（纯函数；内置表数据文件除外） |
| `authoring` | 作者 TXT 三格式解析、作者-文件匹配规则、authorId 生成 | **任何 IO**（纯函数） |
| `authorattach` | 作者挂靠编辑与本地镜像编排（上传挂靠参数已随 ADR-0024 退役；ADR-0023/0024，ADR-0019 首个新流程落地）：TXT 片段存取单一来源 + 挂靠写入与资产作者/来源编辑编排（编辑走 edit.go，在调用方事务内）+ 重导入保护元数据 + 本地总表镜像（尽力而为原子写）；依赖 store/authoring，被 httpapi 调用 | 绕过调用方事务自行提交；片段本体之外的第二真相存储 |
| `filing` | 上传、移动、重命名、回收站 | 绕过路径安全校验 |
| `store` | sqlc 生成代码、migrations 与只读装载策略（推荐输入的计划选择/标签装载） | SQL 字符串拼接、领域评分、HTTP 依赖 |
| `eventstats` | 事件日统计持写锁事务重建，单资产恢复与全量管理重建共用；调用 stats 纯函数与 store | HTTP 依赖、事务外读真相再替换 |
| `events` | 进程内事件总线、SSE | 模块间直接函数调用（跨模块通知走事件） |
| `auth` | token 签发/校验、签名直链 | — |
| `sysmon` | 系统指标采集 | — |
| `backup` | 库文件热备快照调度：VACUUM INTO 在线快照 + 定时调度 + 轮转保留 + 快照目录管理 | 碰媒体库文件与业务表（只管 DataDir/backups 快照） |
| `libraryrevision` | 库内容修订号单调计数（kv_settings 持久化唯一真相源，ADR-0026） | 语义扩展到资产集合之外的变更面 |
| `uploadsess` | 断点续传上传会话：内存注册表 + DataDir/uploads-tmp 分片暂存与过期清扫（ADR-0028） | HTTP/协议语义与入库编排（在 httpapi） |
| `localsync` | 本机同步通道：库名匹配/目录扫描分类/根重叠检查；PlanScan 只读扫描编排与存活集合（ADR-0030/0042） | 导入编排与数据库访问（无自有 IO 状态） |
| `config` | 配置加载（env + yaml） | 任何硬编码路径 |

依赖方向：`httpapi → 各业务模块 → store`；业务模块之间通过 `events` 解耦；`recommend`/`stats`/`sourcematcher`/`authoring` 不依赖任何其他业务模块（只依赖领域类型定义包）。scanner 在扫描入库时调用 sourcematcher/authoring 做出处/角色/作者富化（§4/§6「匹配发生在服务端扫描入库时」）。

### 5.1 模块边界强制与防漂移（ADR-0010 / ADR-0009 / ADR-0011 / ADR-0019）

**编排下沉（ADR-0019）**：多步业务编排（上传落盘入库、移动先文件后库行、统计事件重建等）必须放在专职编排包（`server/internal/orchestration/` 或等价 app 层），`httpapi` 禁止业务编排，只保留鉴权、参数校验、序列化与对编排入口的一次调用。既有厚 handler 增量迁移；新增多步流一律不得在 httpapi 落地。不引入编排框架，`cmd/qimeng` 仍为唯一组合根。
**当前迁移状态（2026-10-21）**：以等价业务包落实编排层，不要求固定 orchestration 目录。authorattach/eventstats 已承载相应业务；mediawrite 已迁入直传收流/发布、续传创建/完成与共用资产入库，httpapi/upload(s).go 仅保留适配与会话单步/生命周期接线。存量 filing/trash、localsync_runner 的调度/状态/TXT/搬移仍在 httpapi，按实施清单继续下沉。ADR-0042 的 localsync.PlanScan 仍负责安全扫描计划；不能把上传下沉等同全部写入完成迁移。

边界不是口头约定，是**三层机器强制**（决策见 ADR-0010）：

1. **Go `internal/` 编译器边界**：所有业务包都在 `server/internal/` 下，Go 编译器保证仓库外任何模块无法 import——物理隔离第一层。
2. **depguard 两条红线**（`server/.golangci.yml`，golangci-lint v2.15.0 强制执行）：
   - `httpapi/gen`（oapi-codegen 生成接口层）只允许 `httpapi` 包引用；
   - 业务包与 sysmon **禁止反向依赖 `httpapi`**，唯一例外 `cmd/qimeng`（main 组合根，依赖注入只在 main 发生）。
3. **golangci-lint 门禁**：11 个 linter 进 CI server job 与本机 `make lint`，违规即失败（见 §10）。

额外边界回归：`internal/architecture` 用 Go AST 检查四个纯算法包的全部非测试源码（包括其他平台文件），只允许明确列出的内存算法/文本/时间标准包，禁止经第三方或其他业务包绕入 IO；Web 的 `tests/architecture.test.ts` 使用 TypeScript AST 检查 pages/components，禁止运行时 SDK 导入与直接请求，允许 SDK 纯类型及组件懒加载。两者分别随 Go/Web 测试进 CI；只证明列出的直接依赖约束，不是完整形式化纯度证明。

防漂移配套纪律：

- **SDK 生成物不入库**（ADR-0009）：`server/internal/httpapi/gen/*.gen.go`、`web/src/api/generated/`、`android/sdk/**` 全部 .gitignore；CI `sdk-chain` job 从 `api/openapi.yaml` 重建验证可生成。**AI 禁止手改任何生成物**——协议改动只改 openapi.yaml 后 `make sdk`。
- **迁移纪律**（ADR-0011）：schema 演进只经 golang-migrate **新增**文件（只加不改不删）；改/删既有结构走 expand-migrate-contract 两步迁移，禁止手改历史迁移文件与运行中的库。`tools/check_migrations.py` 对 PR base/push before 检查既有 SQL 文件不被修改、删除或改名，本机默认对 HEAD 检查暂存/工作区。

**文件一致性实现（2026-10-19，ADR-0039）**：`scanner/reconcile.go` 单独承载差集实时复核与事务合并；`filing/restore_intent.go` 负责从既有回收站meta读取未完成恢复路径索引及显式失效，供scanner在库锁内避让。filing路径层含文件系统链接检查，不再是纯词法函数；库内实际文件操作优先os.Root。上传会话记录已发布路径并封存源，跨根搬移的边界见SECURITY。原有httpapi上传/回收站编排仍为存量，未引入新框架。

## 6. 数据身份机制（adr/0004，本项目最重要的设计）

旧项目教训：「完整文件名做主键」在文件改名/移动/多目录同名时断链。本项目：

- **主键 = `asset_id`**（数据库生成，UUIDv7，终身不变）
- **路径是属性**（`library_id` + 规范化相对路径，唯一索引）
- **变更检测 = size + mtime**，不一致即重新探测元数据
- **内容哈希懒计算**：后台低优先级算，用于跨库去重提示，不阻塞扫描
- **移动合并**：
  - App/API 主动移动 → 服务端直接改路径属性，标签/作者/统计/收藏全部无感保留
  - 用户绕过系统直接在 NAS 文件管理器移动 → 扫描器发现「旧路径消失 + 新路径出现 + size/mtime 一致」→ 自动判定移动并合并（启发式，可配置关闭）

## 7. 数据流模型（与旧项目的本质差异）

旧项目单进程零拷贝（直查库、直读文件，优化靠本地 IO）；本项目跨网络，**优化靠缓存设计与减少往返**：

- 列表永远服务端分页（cursor 式），客户端永不持有全库。查询先定页再装配，只投影实际消费字段；无额外筛选的日期页复用 mtime 索引，白名单之外回退完整筛选。首屏日期桶与总数合并扫描，未知日期不漏总数（ADR-0043；[性能范围](domain/filtering.md#查询执行策略与性能边界)）。不为本次提速新增缓存/失效链或索引。
- 统计热榜先按资产汇总事件再展开作者/标签关联；推荐默认宽流保留顺序聚合，显式媒体/COS 流按规模选择候选事件/标签读取或宽查询，全部合格候选仍送纯函数评分。计划选择在 store，推荐顺序缓存及分页装载在 recommendfeed，展示计数仍由 httpapi 收尾；代价与基准见 [统计](domain/statistics.md#热榜查询策略与性能边界)、[推荐](domain/recommendation.md#16-输入装载策略与性能边界)（ADR-0044）。
- 排行由 ranking 编排：总榜装载累计热度键，周期榜装载窗口日统计/点赞键，完整合格候选送 recommend.RankTop；仅读返回页的元数据和累计卡片计数，摘要再校验存在/启用库/非 COS。并发变更允许短页，无跨查询快照承诺。见 [排行执行策略](domain/recommendation.md#排行执行策略与性能边界)。
- 缩略图：服务端预生成 + 标准 HTTP 缓存头（ETag/Cache-Control immutable），客户端 URL 直取
- 原图：直链 + HTTP Range（视频拖动无缝）。**查看永远发原件，不做任何按需缩放**（用户明确要求：点开就是原汁原味，超大图也全量传原图——2026-08-22 确认）
- 行为数据（浏览/点赞/收藏）客户端上报 → 服务端写 **ViewEvent 事件流**（只追加不修改，adr/0005）→ 统计从事件流聚合，口径变更可全量重算

## 8. 客户端矩阵与职责

| 端 | 技术 | 职责边界 |
|---|---|---|
| Web/PWA | React | 全功能：浏览/播放/上传/文件整理/管理/监控面板 |
| Android | Compose | 全功能：浏览/播放/上传（含系统分享接收，"手机采集端"主通道）/整理 |
| 桌面壳 | Tauri 2 连接模式（ADR-0020，`desktop/`，2026-09-16 交付） | 薄壳：记住服务器地址→开窗加载服务端 Web UI + 托盘/导航守卫；UI 零独立实现 |
| TV（后置） | 未定 | 协议已就绪，UI 另做 |

客户端可持久化登录/偏好配置、可控媒体缓存、离线事件队列与传输任务元数据；这些不构成第二份权威业务库。Android 内嵌形态的业务数据库属于本机 Go 服务端。具体边界见各端 README 与领域专题。

## 9. 部署与运行环境

- **开发期**：PC 直跑（`make server-run` / `make web-dev`，见根 Makefile），媒体目录指向小样本库，手机同 WiFi 联调
- **验收期**：fnOS 虚拟机（VirtualBox 桥接）+ Docker Compose 部署，手机真机访问虚拟机 IP
- **生产期**：真 NAS 上 Docker 部署，compose 卷：`/media`（媒体库，可写但受控）、`/data`（数据库+缩略图+回收站，服务端私有）
- **远程**：Tailscale 隧道，服务端零改动（adr/0006）

构建/扫描的 Go 补丁版本统一读取 server/go.mod（当前 1.27.2）；golangci-lint 固定 v2.15.0，使用同一 Go 从源码构建，避免旧预编译工具无法读取新补丁导出格式。依赖 PR 仍须通过文档影响检查、构建和安全扫描，不按机器人身份豁免。

## 10. 工程基础设施

- **CI（GitHub Actions，`.github/workflows/ci.yml`）七 job**，push/PR 全绿才许合并：
  1. `openapi`：redocly 协议校验（error 失败，warning 不阻塞）
  2. `server`：Linux runner 上重建 Go 接口层 → golangci-lint（静态检查 + depguard 模块边界）→ go vet → go test（-race）→ go build
  3. `server-windows-filing`：Windows 原生 runner 上运行 `go test ./internal/filing -count=1`，覆盖 Linux job 不会编译的 `move_windows.go` build-tag 路径
  4. `web`：npm ci → openapi-ts 重建 TS 客户端 → tsc --noEmit → npm run build → npm test（vitest，ADR-0017）
  5. `sdk-chain`：`make sdk` 三端生成链可重建（生成物防漂移的结构性门禁，ADR-0009）
  6. `android`：复用 sdk-chain 生成的 android/sdk → temurin 21 + gradle wrapper 缓存 → assembleDebug + testDebugUnitTest + lintDebug（M4-0 起）
  7. `desktop`：Tauri 2 壳 cargo test + build（windows runner + rust-cache，debug 链不出安装包；第四端门禁，ADR-0020，2026-10-03 补）
- **安全与文档流水线**：安全行为用例仍随 server 的 go test 运行；`security.yml` 另做 govulncheck、Web/desktop npm audit、桌面/补丁测试 Cargo audit、glib 上游源码核验及 Linux release 原版/修复版迭代器回归，以及 PR dependency-review（high 起阻断），`docs.yml` 检查链接/ADR/归档/入口/Actions 固定与权限、PR 专题同步。配置已落地不等于本地/远端扫描全绿；当前结果见 HANDOVER。所有外部 Actions 固定完整 SHA，顶层 contents: read，checkout 不保留写凭据。
- **镜像构建**：`make docker-build` 构建 amd64 镜像（debian-slim + ffmpeg + 静态二进制 + SPA，deploy/Dockerfile），2026-09-22 fnOS 虚拟机彩排记录保留；当前无 NAS 硬件，未来设备另行验收；CI 暂无镜像 job，基础镜像已固定清单 digest，arm64/当前镜像实际构建和 CI 镜像产出仍待验收
- **依赖更新**：按用户最新要求恢复 Dependabot 每周版本升级及自动安全升级 PR；每个更新配置（生态 + 目录）最多 1 个常规待处理 PR，只自动提出候选，人工审阅且当前 head 完整门禁通过后才合并，不启用自动合并。漏洞告警和安全 CI 保留，规则见 SECURITY。glib 最小上游补丁及退役边界见 ADR-0047；扫描配置与未修复漏洞不混记为验收通过。
- **Makefile 一键命令**：`make docs-check / sdk / lint / server-run / server-test / web-dev / web-test / docker-build`（lint = redocly + golangci-lint + TS 三连）
- **版本策略**：API `/api/v1` 前缀；镜像 tag 当前手动（qimeng-media:1.0），semver 规则随 CI 镜像 job 一并落定

## 11. 数据迁移与表示一致性

SQLite 业务库默认 WAL/FULL（ADR-0048），以每次提交同步 WAL 换取提交持久性；连接池新连接和重连均由 DSN 保持。原件/回收站 meta 与 DB 不因此成为跨系统原子事务，真实掉电/设备验证单列。

便携 JSON 迁移不含媒体原件，只合并目标库既有资产。当前导出 v2，以原始事件身份合并；旧 v1 保持聚合回放兼容，两版本映射/去重边界独立定义在 [迁移专题](domain/migration.md)。TXT 片段随备份 keep 合并，先行传输原件。禁止声称旧聚合身份可逆或所有迁移均零损失；必须同步升级客户端与服务端。

eventstats 编排在写事务内读取真相并重建派生统计；stats/materialize 保持纯函数。回收站恢复在原资产事务内调用单资产重建；portable_backup.go 适配既有导入/导出，migration 0019 只加无键身份旁表与触发器（ADR-0040）。缩略图表示版本贯通 URL/磁盘/客户端预取，规则见 [缩略图专题](domain/thumbnails.md)。
