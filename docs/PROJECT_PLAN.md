# PROJECT_PLAN - 里程碑计划

> 每个里程碑都是**可用闭环**（做完就能用/验收），不做"半成品堆叠"。开发顺序经过依赖推理，禁止跳级。
> 通用纪律：每个功能完成 = 代码 + 测试 + 文档同步 + 门禁全绿，四件套缺一不可；门禁 = CI 四 job + golangci-lint（本机 `make lint` 同配置）。
> 重构后新增纪律（ADR-0009/0010/0011）：**生成物不手改**（只改 openapi.yaml 后 make sdk）、**新迁移只加文件**（只加不改不删）、**重大决策先写 ADR**（并同步 docs/adr/INDEX.md）。
> 最后更新：2026-08-27（M2 拆分后端先行/UI 后置 + sysmon 接线完成；2026-08-26 M2-M4 验收标准与底层重构对齐；2026-08-22 制定）

## M0 · 地基（协议与工具链）

**目标**：一切开发的前置设施就绪。

- [x] 安装开发工具（2026-08-22：Go 1.27 / Node 24 / JDK17 免安装 / make 4.4.1 ✅；Docker Desktop 按计划 M1 末手动下载安装；VirtualBox 按 M5）
- [x] `server/` Go 模块初始化（2026-08-22：module qimeng-media/server，chi+slog+config（yaml+env，默认 :8420），12 个 internal 包骨架+doc.go，healthz 闭环，build/vet/test 全绿）
- [x] `web/` Vite + React + TS + Tailwind + shadcn 初始化（2026-08-22：Tailwind v4 + shadcn(radix/nova) + TanStack Query + --qm-* 设计 token（tokens.css），build/tsc 全绿）
- [x] `api/openapi.yaml` v0 实例化（2026-08-22：评审定稿 v0.1.0——补排序/顺位/includeCos/sessionId/标签删除/时间轴删除/作者关注/回收站清空/缩略图尺寸等 9 处，redocly 0 error）
- [x] Makefile：`make sdk` 打通 oapi-codegen + TS + Kotlin 三端生成链（2026-08-22：oapi-codegen v2.8.0 → Go 接口层；@hey-api/openapi-ts 0.99.0 → TS 客户端；openapi-generator + 免安装 JDK → Kotlin SDK，三端全部实际生成通过）
- [x] GitHub 仓库 + Actions CI（2026-08-22：私有仓库 Surtr42u/qimeng-media 建立，四 job 首跑全绿；2026-08-26 重构后 server job 增 golangci-lint 门禁）
- [x] `docs/GUIDE_API.md`（2026-08-22：37 端点分组速览 + 关键机制导读）

**验收**：`make sdk` 一条命令生成三端 SDK 且全部编译通过；CI 在空测试下全绿。

## M1 · 服务端核心闭环

**目标**：手机浏览器已经能浏览媒体（图片与视频，虽然界面简陋）。

- [x] 配置系统（2026-08-22：yaml + QIMENG_* env，DataDir/DbPath/TokenTTL/MediaSecret；直链密钥持久化 dataDir）
- [x] SQLite + sqlc + golang-migrate 初始化；核心表 ×14（2026-08-22：modernc 纯 Go 驱动 WAL；view_events 只追加无外键——ADR-0005 实现澄清；keyset 分页零 OFFSET）
- [x] scanner：全量扫描 + fsnotify 增量 + 轮询兜底（5min）+ 移动合并启发式（2026-08-22：size+mtime 变更检测；数据目录自噬防御两道防线——注册互斥校验 + scanner SkipDir；Watch 增量删除不做合并属 M1 基线，见 watch.go 注释）
- [x] 身份机制：asset_id（UUIDv7）、路径规范化、size/mtime 变更检测（2026-08-22：移动合并真机验证通过——改名后 asset_id 不变）
- [x] thumbnail：ffmpeg 抽帧（黑帧检测"全部采样制"语义已澄清入 DOMAIN_RULES §11）+ 图片缩放 + 缓存键 + 三档 sm=256/md=512/lg=1024
- [x] httpapi：鉴权（argon2id+token 哈希）、库管理、资产列表（分页/筛选/排序；q=FTS5 搜索移至 M2）、详情、媒体直链（Range/206）、缩略图端点（immutable+ETag+304）
- [x] SSE：扫描进度推送 + 事件总线（慢订阅者丢弃隔离）
- [x] 内置极简验收页（中文界面：setup→注册库→扫描进度→日期分组网格→原图→视频播放；永久保留作调试工具）
- [x] 单元测试：9 包全绿（含路径穿越/签名防伪/Range/移动合并/每日点赞 toggle 端到端用例；口径：9 个有代码包 = auth/config/events/filing/httpapi/scanner/store/sysmon/thumbnail，recommend/search/stats 三个 doc.go 空壳包暂无测试）
- [x] 附带交付（M2 提前件）：filing 安全件（路径穿越/MIME 魔数/上传四道校验/回收站布局，142 子用例）、sysmon 监控采集层（gopsutil+prometheus，指标名入 OBSERVABILITY）

**验收**：PC 侧全链已验（setup→注册→扫描→列表→原图 200 全量→视频 Range 206 拖动无缝→缩略图懒生成→移动合并身份保持）。手机真机浏览器验收待用户执行（PC 同 WiFi 访问 http://<PC局域网IP>:8420，Windows 防火墙放行后）。

## M2 · Web 端完整体验

**目标**：Web 端可日常使用，UI 达到"好看"标准。

> **执行顺序调整（2026-08-27 用户拍板）**：M2 拆为**后端先行、UI 后置**两段——
> 先完成全部纯后端接线（sysmon、filing 端点、标签/时间轴端点、search FTS5、推荐流热度占位），
> UI 部分（布局/设计 token/列表页/详情页/仪表盘/PWA）后置到后端验收后再启动。

- [ ] 布局系统 + 导航（首页推荐/全部/相册/统计/管理）【后置】
- [ ] 设计 token 体系（明暗主题、间距、圆角、动效时长统一变量）【后置】
- [ ] 列表页：网格 + 日期分组 + 筛选面板 + 排序 + 搜索（search 包填充：FTS5 索引维护与查询，doc.go 空壳已存在）+ 无限滚动【搜索为后端项，其余后置】
- [ ] 详情页：图片（缩放/预加载）、视频播放器（手势/倍速/时间轴标记）【后置】
- [ ] 推荐流 + 排行榜（调用 M3 前先用热度排序占位）【热度占位为后端项，其余后置】
  - [x] 后端：推荐流热度占位端点（2026-08-29：viewCount 降序复用 browse.sql 既有排序键，seed 参数 M2 忽略、M3 十维算法接入时只换实现；3 用例）
- [ ] 上传（拖拽 + 文件选择 + 进度）与文件整理（移动/重命名/回收站）【端点接线为后端项，UI 后置】
  - [x] 后端：移动/重命名端点 + 删除→回收站 + 回收站列表/恢复/物理删除/清空五端点（2026-08-27：httpapi/filing.go + trash.go；身份保持、冲突 409、恢复冲突自动重命名、TrashMeta 扩 LibraryID/MediaType、遍历两遍式 os.Root 防 TOCTOU；12 用例全过、golangci 0 issues；恢复语义与已知限制入 DOMAIN_RULES §9）
  - [x] 后端：上传端点（2026-08-29：流式接收 + 四道校验 + config upload.max_bytes（默认 2GB，env QIMENG_UPLOAD_MAX_BYTES）+ 冲突自动重命名 + 视频探测同 scanner 语义 + media_type 用 scanner.ClassifyMedia 唯一口径；协议补 libraryId 必填参数（多库定位）并 make sdk 三端重建；upload.done 事件以最小载荷发布、SSE 载荷 schema 待补；7 用例）
  - [x] 后端：目录树端点（2026-08-29：GET 遍历磁盘（目录是文件系统现实，空目录可作整理目标）+ POST 幂等新建 + 穿越拒绝；3 用例）
- [ ] 标签/收藏/点赞交互【端点接线为后端项，交互 UI 后置】
  - [x] 后端：标签池/资产标签（替换式，事务）/时间轴标签三件套（2026-08-29：store 新增 tags.sql 十查询（sqlc 注释须纯 ASCII——多字节文本使解析器报错，已验证）；重名 409、删除级联、跨资产时间轴删除隔离；6 用例）
- [x] sysmon 接线：/metrics、/system/status（PerCore 快照补进协议 + make sdk；采集层 M1 已提前交付）（2026-08-27：协议补 perCore → make sdk-go → httpapi/system.go 两端点接线（Bearer 鉴权、未装配 503、部分采集失败仍 200）→ main/wire 装配（挂载点动态=DataDir+全部库根、version 常量单一来源）→ 7 用例 + 真机冒烟（20 核差分/真实磁盘容量/metrics 文本输出）全过）
- [ ] 监控仪表盘 `/admin`（OBSERVABILITY.md 内置指标）【后置】
- [ ] PWA：可安装、离线壳【后置】

**验收**：日常"浏览-看图看视频-整理"全部在浏览器完成；Lighthouse PWA 可安装；手机浏览器体验流畅；golangci-lint 门禁绿、生成物不手改、新迁移只加文件、决策先写 ADR。

## M3 · 算法移植与领域完整

**目标**：旧项目的灵魂（推荐与统计）在新系统复活。

- [ ] recommend 模块：DOMAIN_RULES §1 全部公式 + 自适应权重 + 三个后处理机制 + 单测（照旧项目测试用例翻译）
- [ ] stats 模块：ViewEvent 聚合、按天聚合物化、趋势分桶口径（§5）
- [ ] SourceMatcher 移植：131 SourceGroup 检索表翻译 + 服务端入库时匹配 + 增补规范
- [ ] 作者体系：TXT 三格式导入 + 统一重建语义 + COS 目录扫描双体系
- [ ] 旧数据迁移端点（qimeng_backup.json，映射表 DOMAIN_RULES §10）
- [ ] 推荐偏好设置页（9 维权重 + 4 预设）

**验收**：迁移旧备份后，推荐/排行/统计行为与旧 App 口径一致（对比测试通过）；golangci-lint 门禁绿、生成物不手改、新迁移只加文件、决策先写 ADR。

## M4 · Android 客户端

**目标**：原生体验的薄客户端。

- [ ] 项目脚手架：Compose + Hilt + 生成 SDK + Repository 层
- [ ] **交互规格复刻**：以旧仓库（QimengMedia）`docs/GUIDE_UI.md` 及详情页相关指南为交互规格书，复刻已验证的交互体验——视频手势（暂停/倍速/长按 2x/静音）、图片双指缩放/双击还原/左右预加载、胶囊筛选联动、标签管理流、时间轴标签跳转回看等。只继承交互语义，实现全部用新技术（Coil/Media3/Compose）
- [ ] 登录（服务端地址 + token）、首页/列表/筛选/搜索
- [ ] 详情：Coil 图片（HTTP 直链 + 缩放）、Media3 视频（直链 + 倍速 + 时间轴标记）
- [ ] 行为上报（浏览事件/点赞/收藏，离线排队补传）
- [ ] **上传**：系统分享接收 + 文件选择 + 目标目录浏览 + 队列与进度（手机采集端主通道）
- [ ] 缓存策略（上限可设 LRU）

**验收**：手机完整日常使用；上传手机文件 → Web 端立即可见；离线时行为数据不丢；golangci-lint 门禁绿、生成物不手改、新迁移只加文件、决策先写 ADR。

## M5 · NAS 部署验收

**目标**：真实 NAS 环境全流程彩排。

- [ ] fnOS 虚拟机（VirtualBox 桥接）安装初始化 + 快照
- [ ] Docker buildx 双架构镜像（含 ffmpeg）
- [ ] docker-compose 生产样例（卷挂载/健康检查/自动重启）
- [ ] 部署验收清单：大库扫描压测、手机真机访问、断电重启数据完好、回收站恢复
- [ ] 部署文档 `deploy/README.md`

**验收**：虚拟机 NAS 环境全功能可用；快照回滚演练一次。

## M6+ · 后置储备（不承诺时间）

- Tauri 桌面壳、TV 端、Tailscale 远程启用、Grafana 增强包、多用户实现、视频按需转码档位（远程带宽不足时）

## 变更纪律

- 里程碑内容变更需用户确认并在本文更新（记日期）。
- 每完成一项勾选一项并注明 commit；禁止"做完不勾"或"没做先勾"。

## 常驻任务（每轮 AI 工作结束前自检，不计入里程碑勾选）

1. **文档漂移检测**：对照代码核对本文勾选状态、ARCHITECTURE 模块边界表、GUIDE_API 端点清单、OBSERVABILITY 指标表与实现是否一致；发现漂移即修复或记录待办。
2. **能力地图维护**：`docs/CAPABILITY_MAP.md` 三态（已有/规划中/明确不做）随里程碑验收同步勾选；用户新拍板的能力立即更新。
3. **ADR 索引维护**：新增 ADR 后同步 `docs/adr/INDEX.md` 索引行与受影响文档引用。
