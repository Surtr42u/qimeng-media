# ARCHITECTURE - 架构总纲

> 本文是 qimeng-media 的架构唯一权威文档。技术选型的"为什么"见 `docs/adr/`，业务规则见 `docs/DOMAIN_RULES.md`。
> 最后更新：2026-10-01（§5 模块边界表补 0025-0030 批次四包：backup/libraryrevision/uploadsess/localsync）。2026-09-25（§5 模块边界表 `authorattach` 行职责补编辑编排——资产作者/来源编辑端点走 authorattach/edit.go，ADR-0024）；同日此前（§5 模块边界表增 `authorattach`——上传挂靠编排包，ADR-0023/ADR-0019 首个新流程落地）；2026-09-21（维护批一致性清偿：§2/§8 桌面壳由「后置」改为已交付——ADR-0020，desktop/）；2026-09-05（§10 CI 第五 job android 门禁，M4-0）；2026-08-26（底层重构对齐：§5.1 模块边界强制、§10 CI 实况、Makefile 命令名；2026-08-22 v0 项目创立）

## 1. 需求起源与产品定位

用户十条核心需求（2026-08-22 确认）：

| # | 需求 | 架构落点 |
|---|---|---|
| 1 | 解放手机/电脑存储 | 媒体全存 NAS，客户端零库存储（仅可控缓存） |
| 2 | 工具与 UI 解耦、多模块 | §4 三层解耦 + §5 模块边界 |
| 3 | 网络安全 | `SECURITY.md` 全文 + 安全测试进 CI |
| 4 | UI 可重做、改得快、支持动效 | UI 是最便宜可换层；Web 用 React+shadcn+设计 token+Framer Motion |
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
                      │  SQLite(单文件) + 数据目录(缩略图/回收站)   │
                      │  /media 媒体库目录(可写,受控操作)          │
                      └──────────────────────────────────────┘
                                 ▲
                          openapi.yaml（协议宪法）
                          自动生成 Go接口层 / TS / Kotlin 三端 SDK
```

核心原则：**服务端是唯一事实源和唯一有"脑子"的地方，所有客户端都是薄壳**。列表/搜索/推荐/统计全部服务端算好；客户端只做展示、直链播放、行为上报。

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
| Web | React + Vite + TypeScript + shadcn/ui + Tailwind + TanStack Query + Framer Motion | AI 语料最大；组件生态解决 UI 美观；动效生态成熟 |
| Android | Kotlin + Jetpack Compose + Coil 3 + Media3 | 用户 Kotlin 经验延续；Coil/ExoPlayer 原生支持 HTTP 直链 |
| 客户端 SDK | openapi-generator（TS: @hey-api；Kotlin: 生成器） | 协议改动三端自动同步（adr/0001） |
| 部署 | Docker 镜像（linux/amd64 已交付；arm64 按需储备）+ deploy/ 三件套 | 开发 PC → fnOS → 真 NAS 零改动迁移（M5 已收官，`make docker-build` / `deploy/README.md`） |

## 4. 三层 UI 解耦（需求 #2 的落地）

1. **服务端与 UI 完全解耦**：API 只返回领域数据（排好序的列表、算好分的推荐），永不返回 UI 结构；禁止为某端开专属接口（BFF）。换 UI/加 TV 端，服务端零改动。
2. **协议生成层共享**：openapi.yaml 生成的 SDK 是三端共享的数据接入层，天然一致。
3. **客户端内部逻辑/UI 分层**：Web 端数据获取全部走 TanStack Query hooks（组件零请求代码）；Android 端 ViewModel 管状态、Compose 只渲染。UI 组件禁止直接调 API、禁止内嵌业务规则。

推论：**换皮成本极低**——AI 可并行生成多套 UI 风格供用户挑选，选中即换，逻辑与测试不动。

## 5. 服务端模块边界（server/internal/）

| 包 | 职责 | 禁止 |
|---|---|---|
| `httpapi` | 请求校验、鉴权、调编排服务、序列化 | 业务规则 |
| `scanner` | 全量/增量扫描、变更检测、移动合并 | 直接写库（经 store） |
| `thumbnail` | 缩略图生成队列（工作池、懒生成、缓存键、静图输出格式自适应：libwebp 缺失时 mjpeg 降级——内嵌形态，U11 批次D） | 阻塞 API 请求 |
| `search` | FTS5 索引维护与查询 | — |
| `recommend` | 推荐/排行算法 | **任何 IO**（纯函数，输入输出领域结构体） |
| `stats` | 统计聚合、趋势分桶 | **任何 IO** |
| `sourcematcher` | 出处/角色匹配引擎（130 组内置检索表 + 前缀匹配） | **任何 IO**（纯函数；内置表数据文件除外） |
| `authoring` | 作者 TXT 三格式解析、作者-文件匹配规则、authorId 生成 | **任何 IO**（纯函数） |
| `authorattach` | 作者挂靠编辑与本地镜像编排（上传挂靠参数已随 ADR-0024 退役；ADR-0023/0024，ADR-0019 首个新流程落地）：TXT 片段存取单一来源 + 挂靠写入与资产作者/来源编辑编排（编辑走 edit.go，在调用方事务内）+ 重导入保护元数据 + 本地总表镜像（尽力而为原子写）；依赖 store/authoring，被 httpapi 调用 | 绕过调用方事务自行提交；片段本体之外的第二真相存储 |
| `filing` | 上传、移动、重命名、回收站 | 绕过路径安全校验 |
| `store` | sqlc 生成代码 + migrations | SQL 字符串拼接 |
| `events` | 进程内事件总线、SSE | 模块间直接函数调用（跨模块通知走事件） |
| `auth` | token 签发/校验、签名直链 | — |
| `sysmon` | 系统指标采集 | — |
| `backup` | 库文件热备快照调度：VACUUM INTO 在线快照 + 定时调度 + 轮转保留 + 快照目录管理 | 碰媒体库文件与业务表（只管 DataDir/backups 快照） |
| `libraryrevision` | 库内容修订号单调计数（kv_settings 持久化唯一真相源，ADR-0026） | 语义扩展到资产集合之外的变更面 |
| `uploadsess` | 断点续传上传会话：内存注册表 + DataDir/uploads-tmp 分片暂存与过期清扫（ADR-0028） | HTTP/协议语义与入库编排（在 httpapi） |
| `localsync` | 本机同步通道纯逻辑：库名匹配/目录扫描分类/根重叠检查（ADR-0030） | 导入编排与数据库访问（无自有 IO 状态） |
| `config` | 配置加载（env + yaml） | 任何硬编码路径 |

依赖方向：`httpapi → 各业务模块 → store`；业务模块之间通过 `events` 解耦；`recommend`/`stats`/`sourcematcher`/`authoring` 不依赖任何其他业务模块（只依赖领域类型定义包）。scanner 在扫描入库时调用 sourcematcher/authoring 做出处/角色/作者富化（§4/§6「匹配发生在服务端扫描入库时」）。

### 5.1 模块边界强制与防漂移（ADR-0010 / ADR-0009 / ADR-0011 / ADR-0019）

**编排下沉（ADR-0019）**：多步业务编排（上传落盘入库、移动先文件后库行、统计事件重建等）必须放在专职编排包（`server/internal/orchestration/` 或等价 app 层），`httpapi` 禁止业务编排，只保留鉴权、参数校验、序列化与对编排入口的一次调用。既有厚 handler 增量迁移；新增多步流一律不得在 httpapi 落地。不引入编排框架，`cmd/qimeng` 仍为唯一组合根。

边界不是口头约定，是**三层机器强制**（决策见 ADR-0010）：

1. **Go `internal/` 编译器边界**：所有业务包都在 `server/internal/` 下，Go 编译器保证仓库外任何模块无法 import——物理隔离第一层。
2. **depguard 两条红线**（`server/.golangci.yml`，golangci-lint v2.13.1 强制执行）：
   - `httpapi/gen`（oapi-codegen 生成接口层）只允许 `httpapi` 包引用；
   - 业务包与 sysmon **禁止反向依赖 `httpapi`**，唯一例外 `cmd/qimeng`（main 组合根，依赖注入只在 main 发生）。
3. **golangci-lint 门禁**：11 个 linter 进 CI server job 与本机 `make lint`，违规即失败（见 §10）。

防漂移配套纪律：

- **SDK 生成物不入库**（ADR-0009）：`server/internal/httpapi/gen/*.gen.go`、`web/src/api/generated/`、`android/sdk/**` 全部 .gitignore；CI `sdk-chain` job 从 `api/openapi.yaml` 重建验证可生成。**AI 禁止手改任何生成物**——协议改动只改 openapi.yaml 后 `make sdk`。
- **迁移纪律**（ADR-0011）：schema 演进只经 golang-migrate **新增**文件（只加不改不删）；改/删既有结构走 expand-migrate-contract 两步迁移，禁止手改历史迁移文件与运行中的库。

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

- 列表永远服务端分页（cursor 式），客户端永不持有全库
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

客户端本地只允许两类持久化：登录配置、可控的媒体缓存（上限可设，自动 LRU 清理）。

## 9. 部署与运行环境

- **开发期**：PC 直跑（`make server-run` / `make web-dev`，见根 Makefile），媒体目录指向小样本库，手机同 WiFi 联调
- **验收期**：fnOS 虚拟机（VirtualBox 桥接）+ Docker Compose 部署，手机真机访问虚拟机 IP
- **生产期**：真 NAS 上 Docker 部署，compose 卷：`/media`（媒体库，可写但受控）、`/data`（数据库+缩略图+回收站，服务端私有）
- **远程**：Tailscale 隧道，服务端零改动（adr/0006）

## 10. 工程基础设施

- **CI（GitHub Actions，`.github/workflows/ci.yml`）五 job**，push/PR 全绿才许合并：
  1. `openapi`：redocly 协议校验（error 失败，warning 不阻塞）
  2. `server`：oapi-codegen 重建 Go 接口层 → golangci-lint（静态检查 + depguard 模块边界）→ go vet → go test（-race）→ go build
  3. `web`：npm ci → openapi-ts 重建 TS 客户端 → tsc --noEmit → npm run build → npm test（vitest，ADR-0017）
  4. `sdk-chain`：`make sdk` 三端生成链可重建（生成物防漂移的结构性门禁，ADR-0009）
  5. `android`：`make sdk` 重建 android/sdk → temurin 21 + gradle wrapper 缓存 → assembleDebug + testDebugUnitTest + lintDebug（M4-0 起）
- **安全测试无独立 job**：401/路径穿越/签名防伪/超限上传等安全用例以单元测试形式随 server job 的 `go test` 运行
- **镜像构建**：`make docker-build` 构建 amd64 镜像（debian-slim + ffmpeg + 静态二进制 + SPA，deploy/Dockerfile），2026-09-22 fnOS 虚拟机彩排 + 真机 NAS 测试通过（M5 收官）；CI 暂无镜像 job，arm64 双架构与「CI 构建镜像才允许部署 + digest 固定」为规划项
- **依赖更新**：Dependabot 自动 PR（计划中，尚未配置）
- **Makefile 一键命令**：`make sdk / lint / server-run / server-test / web-dev / web-test / docker-build`（lint = redocly + golangci-lint + TS 三连）
- **版本策略**：API `/api/v1` 前缀；镜像 tag 当前手动（qimeng-media:1.0），semver 规则随 CI 镜像 job 一并落定

## 11. 与旧项目的数据迁移

备份导入端点（幂等合并语义，2026-10-01 澄清）：上传旧版 `qimeng_backup.json`（17 数据段）→ 服务端按 `DOMAIN_RULES.md` §10 的映射表合并入库（recordKey → 通过文件名匹配**既有**资产建立 asset_id 关联，不创建资产、不删既有数据，同源跨批次内容键去重）。旧 App 用户数据零损失升级；亦适用于本机模式导出→登 NAS 导入的跨端迁移（文件本体与 TXT 片段不在备份内，先行走上传/本机同步通道与 import-txt）。
