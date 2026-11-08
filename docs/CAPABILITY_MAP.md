# CAPABILITY_MAP - 能力与缺口

> 新功能提案先查本表和相关 ADR。每域只维护能力状态/缺口，机制详情链接专题；当前事项见 HANDOVER。维护日期：2026-11-08。

本表描述已实现能力与真正缺口；PC/手机的实际安装来源、现场结果、最近源码门禁及审查结论只在[HANDOVER](HANDOVER.md)维护，不把源码合并当已安装，也不把单项成功当完整验收。

现用客户端为本项目App/Web，Android内嵌单机仍受支持；停用的是另一个旧纯本地项目。旧v1备份映射仅作历史，已迁入的本项目历史身份与当前v2继续保留；旧项目App卸载和残留数据不自动清理。

## 状态口径

**已有**表示已交付功能，不代表所有异常、硬件和迁移路径均已验收；**部分已有**区分可用基座与待完善项；**规划中**写触发条件，不承诺日期；**明确不做**须有决策依据。三端差异及实测范围单独说明，禁止把设计当实现或把自动化当设备验收。

当前维护优先保障PC服务端/Web/PWA、arm64 Android连接/内嵌和Windows桌面壳。Termux为既有可选路径；qimeng_api35主要用于连接模式，x86_64内嵌为已知失败路径。NAS/其他目标环境另行验收，不重启此前已关闭事项。

[优化任务书](OPTIMIZATION_TASKBOOK_20261025.md)已选代码/职责整理及首批自动化已实施；原专项量化、逐阶段故障和扩展交互未全覆盖，缺口见任务书§11，R01–R07仍待选。用户不承担手动维护步骤；审查执行见[AGENTS](../AGENTS.md#review-guidelines)。

## 能力域

| 能力域 | 已有能力与依据 | 真缺口/触发条件 | 状态 |
|---|---|---|---|
| 媒体库 | 注册、全量/增量扫描、日期网格、相册、目录与回收站；[文件规则](domain/files.md) | 动态库监听/结构事件保身份对账和有界队列已通过云端验证并部署至 PC/arm64 内嵌，范围见 HANDOVER；虚拟合集、多库切换体验待具体需求 | 已有 |
| 检索/筛选 | FTS5、出处/角色/作者维度、建议补全；本轮修复「名称+作品编号」按独立数字段匹配（不再数字子串命中 110/2011）、组图页码 `(n)` 定位、计数/分面/列表同谓词；Web 搜索结果文件名可辨认、详情完整文件名；Android 搜索结果缩略图下短名单行省略、详情完整名与复制；[筛选](domain/filtering.md)、[匹配](domain/matching.md) | 检索高亮；数字段判定为 ASCII 邻接，单个数字关键词仍偏宽（已文档化）；双端真机/内嵌模式现场验收 | 源码与隔离测试交付；安装/真机验收见 HANDOVER |
| 作品编号与组图命名 | 命名约定「作品名 作品编号 (页码)」：一组多图只占一个作品编号、页码独立；Go 命名解析纯函数（server/internal/filing/workname.go）区分主编号/页号，`GET /assets/name-suggestions` 对组图页建议下一作品编号而非页码；AI 工具层规划引擎按「识别分组→分配作品编号→生成页码→预览确认→写入回读」执行，补图只追加页码、缺页不重排、并发回读校验；[文件规则](domain/files.md)、[任务书 §6.6](AI_PLATFORM_TASKBOOK.md) | 真实批量整理/并发执行、乱码名与 AI 分组证据的现场验收；服务端无作品表（编号仍是文件名约定，未来影视/剧集需要时再走领域设计） | 源码与合成测试交付；现场未验收 |
| 缩略图 | 三档、封面/代表帧策略、EXIF 转正、表示版本；共用生成、分页回填及前台优先准入已云端验证并部署 PC/arm64 内嵌，范围见 HANDOVER；[缩略图](domain/thumbnails.md) | 实时首帧/预生成代表帧双管分派、孤儿全量对账；大库需求触发 | 已有；增强待办 |
| 桌面播放 | Tauri WebView2 + 标准 ArtPlayer，共享 Web UI；[桌面说明](../desktop/README.md) | 无自动更新器（自用接受）；libmpv 路线已废弃，不列未来接线任务 | 已有 |
| 局域网连接 | Android本机/NAS切换、最近5个成功地址记忆、历史优先及IPv4 UDP发现；[连接说明](../android/README.md#登录与局域网连接) | 已合并并同源更新日常PC/手机，隔离呈现及恢复原电脑连接通过，来源与范围见HANDOVER；IPv6-only/容器bridge走手填，跨Wi-Fi/网线与NAS设备另验 | 已有 |
| 外网访问 | 隧道访问架构已决策；SECURITY 远程访问节 | Tailscale 实际启用/引导待远程需求，不能写成已部署 | 设计已定；启用规划中 |
| 多用户 | 表结构预留，当前单用户实现 | 多用户运行时能力；用户需求触发 | 规划中 |
| 便携备份/迁移 | 当前源码仅v2原事件身份合并、TXT keep；v1导入按ADR-0050退役，其他版本写前拒绝；维护目录「当前/上次」两代布局已在本轮源码实现（服务端镜像按配置 path 目录命名约定推导上一代、Web/Android 客户端同约定、迁移工具只读核对+复制不删除）；[备份规则](domain/migration.md) | 已入库legacy/无键历史不清理；分段失败/字段边界保留，源码与实际包见HANDOVER；现装机仍三代 flat3 布局、未安装新布局；真实目录迁移与双端现场未验收 | v2已有；两代布局源码交付，安装/真机验收待做；精简版同源门禁与双端安装/隔离后端验证通过，现场范围见HANDOVER |
| 库文件快照 | 在线快照、定时轮转、API 调度参数热生效；[备份 API](api/backup.md) | Web 调度卡按用户要求撤下，不列接口缺口；冷拷贝/恢复指南已补（RESTORE）；远端/异地备份与目标设备恢复待验收 | 已有 |
| 统计/推荐 | 事件流、物化趋势、周期排行榜、个人浏览回顾、可调推荐；[统计](domain/statistics.md)、[推荐](domain/recommendation.md) | 双端消费精确计数/窗口汇总/同轴趋势；浏览回顾替代重复候选区（首访/回访、等长窗口兴趣变化、24小时停留/媒体类型构成），失败与空态分开，见 [消费约束](domain/statistics.md#统计数据消费约束)；当前 head 云端验证及部署边界见 HANDOVER；真实大库/设备验收另计 | 已有 |
| Android | 浏览/播放/上传/整理、离线事件队列、缓存、内嵌/Termux 单机；[Android 入口](../android/README.md) | 扫描调度随内嵌服务端交付；当前arm64 code23已同签名保留数据覆盖并核对实际APK；本次按用户要求不启动App，真实库迁移/冷启动/SAF导入未验，旧限定校时与恢复NAS连接记录见HANDOVER；新包真实采集/切端上传全流程与长期后台仍未逐项验收，x86_64 SQLite SIGSYS 待修 | 已有 |
| 上传/同步/整理 | 直传、双端分片续传、目录拖拽、SAF/分享、归档一键上传、本机同步；[API files](api/files.md) | 首次手机上传使用本次时间、归档再传PC/NAS保留，七天完成凭据、会话预算和手机归档外部上传后释放已云端验证并部署，状态见 HANDOVER；此前 Android 目标 URL 与分片把手持久化/单测通过，手机由用户自测确认，切服/重启等场景未逐项记录；新增异常场景已过云端合成回归，真实设备全流程另验收 | 已有；增强待办 |
| 本机同步 | 库名映射、30s 轮询、根 TXT keep 导入与归档 | GBK/GB18030 待实际非 UTF-8 输入；fsnotify/指标化待延迟需求；词表 UTF-8 门禁待异常输入 | 已有 |
| 标签/收藏/点赞/时间轴 | 各端管理和事件联动；[行为规则](domain/engagement.md) | 2026-11-06 第二批需求取消标签加密、外部代号与 AI 标签维护（[ADR-0052](adr/0052-外部AI维护需求修订.md)）；标签维持现行明文/FTS/UNIQUE 与搜索筛选关联语义，不再做隐私增强 | 基础能力已有；加密明确不做 |
| 作者/词表 | TXT/COS双体系、资产编辑、作者新建/改名/删除、词表维护与并集合并；附属公开档案、候选审核、认证头像代理与可选资料/RSS检查；[作者](domain/authors.md) | 档案Web/API已随3525cb6a部署，Android code23已静默安装但按要求未启动验收；真实身份资料仍需审核且本轮未填充；重复作者合并端点已实现（第六百九十一笔；不可撤销）；本轮 AI 工具层已交付作者批量清理/去重（cleanup prepare/apply、merge、档案 prepare/update）与人工字段保护/计划指纹/回读，现场未验收（[ADR-0052](adr/0052-外部AI维护需求修订.md)）；COS由目录管理，手工改名/删除不适用 | 已有；AI 清理工具源码交付、安装/真实库未验收；安装范围见HANDOVER |
| 监控/探针 | 内置状态、指标、维护页；[监控](OBSERVABILITY.md) | Grafana 长期曲线后置；访问日志及脱敏；readyz 媒体根/磁盘检查已云端验证并部署，边界见 HANDOVER | 已有；增强待办 |
| 部署 | amd64 Docker/compose 与 fnOS 历史彩排；[部署](../deploy/README.md) | 当前无 NAS，未来设备重新验收；arm64 按需储备 | 已有交付；目标设备待验收 |
| 新库类型 | normal/cos 接口与分派基座（ADR-0012） | 其他 kind 按实际目录需求接入 | 基座已有 |
| 视频画质/插帧 | Web/桌面 HTML5 视频与 Android Media3 原片播放 | 未接入视频超分、HDR 增强或插帧；Go 可调度外部处理，实时增强须适配播放器帧/GPU 管线及目标硬件，不能按 Go 插件加载等同处理。用户 2026-10-22 决定暂时不做，PC/手机均不列入当前实施计划；重提时再评估 | 暂缓；未实现 |
| 插件/外部扩展 | OpenAPI 与三端 SDK 可作外部工具接入契约；内部模块、库 kind 可按源码扩展（ADR-0012/0019） | 无通用插件安装器、运行时注册/加载、独立权限和兼容版本协议；第三方插件须按其具体协议评估适配，不能直接通用安装 | 接口基座已有；插件运行时未实现 |
| 外部AI接入平台（AI 工具层） | 本轮按[统一任务书 V2](AI_PLATFORM_TASKBOOK.md)与 [ADR-0056](adr/0056-独立AI接入平台架构.md) 交付源码：规则源/生成器升级为分离版本与按工具能力核对（端点+方法+字段）、显式工具注册表、统一 CLI（保留旧只读子命令 + tool list/call/version）、stdio MCP 适配器（协议层合成客户端验证）、计划/确认/执行/回读框架、作者链工具（档案 prepare/update、cleanup prepare/apply、merge、关联 prepare/apply）、组图命名规划引擎、`backup.export`；旧 `import_creator_profiles.py` 转发统一实现；扩展性增量（2026-11-07 用户补充）；2026-11-08 增至 43 个工具（新增 maintenance.claim/heartbeat/step.record/artifact.record/complete、research.image.*/author.probe/media.probe/avatar.fetch、trash.list/restore、asset.trash、asset.tags.replace 等 14 项，`backup.export` 与 `device.apply.*` 由 planned 转 supported），含 `platform.catalog` 精简领域目录、`platform.tool.describe/platform.rules` 按需 Schema/规则、惰性实现模块加载、废弃/禁用/模块缺失如实降级、权限与发现分离（`QIMENG_AI_GRANTED_PERMISSIONS` 强制检查）、MCP 完整 `tools/list` 兼容入口；`platformVersion`/`toolContractVersion` 1.1.0、`rulesVersion` 2026-11-08.2，错误目录新增 PERMISSION_DENIED/TOOL_DEPRECATED/FORBIDDEN_SCOPE/LEASE_LOST | MCP 真实 AI 客户端（Codex/Claude 等）接入实测、Windows 客户端写入工具的现场验收、服务端最小权限/受限凭据（当前管理员 Bearer 可绕过工具层，风险已评估）、NAS 执行器、真实外部执行器/多执行器接入验收；Web 无 AI 客户端管理 UI、无动作级确认 token、无多实例共享限流、backup.import 仍规划；AI 工具层未安装到现装环境 | 源码与本机测试（Python 164 项）已交付；云端 CI 与安装状态见 HANDOVER |
| Go 细粒度权限与 AI 客户端凭据 | [Go API 缺口与 AI 客户端权限模型](AI_PLATFORM_API_GAPS.md) + [ADR-0057](adr/0057-AI客户端凭据与细粒度权限模型.md)：G1/G2/G8（凭据/scope/撤销/审计）与 G4（外部执行器领取）已落地源码——migration 0025/0026、aiauth、method+path scope 强制、未映射写默认 deny、管理端点仅管理员会话、审计查询、按客户端限流；G3 高风险动作级确认仍只有 scope | 动作级确认 token、多实例共享限流、Web 管理 UI、设备配对（G6）、未来作品模型（G7）、真实管理员验收与越权测试 | P1/P2 源码已交付；未安装、未跑全量测试/CI |
| 独立 AI 客户端凭据/scope/审计（ADR-0057 P1/P2） | migration 0025（ai_clients/ai_audit）+ server/internal/aiauth + httpapi 鉴权分支：管理员会话行为不变，AI 客户端独立 token/scope/设备绑定/写审计，未映射写默认 403；8 个写 scope（asset.write/asset.upload/asset.delete/tag.write/source.write/library.write/trash.restore/maintenance.execute）；`GET/POST /api/v1/ai-clients`、`DELETE /{id}`、`POST /{id}/rotate`、`GET /api/v1/ai-audit` 仅管理员会话，AI token（含 admin scope）403；按客户端令牌桶 600/分、突发 120（0 关闭，429 + Retry-After）；`qimeng ai-client` 本地管理，明文 token 只输出一次 | Web 管理 UI、动作级确认 token、多实例共享限流、真实 AI 客户端接入与真实库验收；撤销/轮换约 5 秒缓存生效 | P1/P2 源码与本机定向测试；未安装、未跑全量测试/CI |
| Android 后台设备任务 Worker | 复用 device/apply-tasks 与维护账本：WorkManager 周期+立即执行、设备标签/专用设备令牌、本机内嵌服务拉起、SAF 授权目录写入、领取/下载/校验/应用/回读/回传；2026-11-08 增领取/待办按 nextCursor 全量翻页（100/页、上限 2000）、本机模式自助签发设备令牌、设置页只读最近任务卡；详见 docs/android-background-execution.md | 设备配对/设备身份证明、临时包 GC、真机 Doze/厂商后台/SAF/内嵌服务可用性、真实备份应用与恢复、必须 UI 的系统授权路径 | 源码 + JVM device/token 50 项与编译通过；未安装、未真机验收 |
| 维护任务队列 | `server/internal/maintenance` 账本 + migration 0023 三表 + 六个 `/api/v1/maintenance/jobs` 端点；状态机覆盖 queued→inventory→research→planned→applying→verifying→completed、waiting_*、paused/partial/failed/cancelled；幂等键终态后唯一；Go 内部 worker（按 kind 领取、租约/心跳、逐步骤记录、暂停/取消恢复、重启续跑不重复、无 handler kind 保持 queued）、同幂等键不同载荷指纹 409、`backup_export` 内置 handler；2026-11-08 增外部执行器受控通道 `/api/v1/maintenance/executor/*`（claim 204 + heartbeat/steps/artifacts/complete，fencing leaseToken 30–1800 秒，旧 token 409 LEASE_LOST，claim 返回完整 completedSteps）并修复 >50 步恢复重复副作用；Web 任务中心（只读列表/详情/取消）同批交付。[维护任务 API](api/maintenance.md)、[ADR-0054](adr/0054-持久维护任务队列.md) | 真实外部执行器/多执行器并发与 NAS 未验收；PC→手机桥接的 Android 设备配对（服务端结果包 API 见下行）；本切片源码已交付但**未安装**，现装仍为 HANDOVER 记录版本 | 源码交付（worker/执行器通道/冲突保护/Web 任务中心），未安装与真机验收 |
| 设备结果包 API | `server/internal/deviceapply` 账本 + migration 0024 两表 + 七个 `/api/v1/device/apply-tasks` 端点（登记/列表/详情/领取/段下载/回传/取消）；段字节落 `<dataDir>/device-apply-packages/<taskId>/`，写库前复算 SHA-256/字节数；2026-11-08 增 manifest 可选 `expectedState` 与回传时服务端绝对计数/段摘要核对（结果暴露 authoritativeState/expectedStateMatches/expectedStateDiff/hashVerified），migration 0026 只增 nullable `verification_json` 且不参与幂等重放比较；领取、相同结果回传与取消的幂等语义见专题。[设备结果包 API](api/device.md)、[ADR-0055](adr/0055-手机无前台维护结果包协议.md) | 设备配对/设备身份证明、临时包 GC、真机后台领取/应用/回传、真实库回传验收；Web 无结果包界面；本切片源码/SDK 已交付但**未安装** | 服务端已实现未安装；配对/GC/真机未做 |
| 研究/核验 API | 服务端 provider 网关与 Lens 代理已实现：`GET /api/v1/research/context`、`POST /providers/{provider}/query`（fxtwitter/pixiv/yandere/e621/wayback/steam/bing 固定矩阵）、`POST /lens`（multipart，loopback sidecar）；安全 HTTP 出口、进程内预算、证据落盘与三端 SDK 同批生成。[研究 API](api/research.md)、[ADR-0053](adr/0053-研究流水线API集成.md) | 默认关闭（嵌入式/Android 同样默认关闭）；已随 796d813f 安装并跑通 context 与真实 Lens；provider 查询在本机因 DNS fake-IP 被 SSRF 防护拦截（ADDRESS_BLOCKED），真实平台查询尚未验收；Bing/第三方接口改版只能回退；Lens `links` 常空；证据目录无自动轮转 | 已实现并安装；真实 provider 查询待本机 DNS 条件满足后验收 |

## 可靠性与工程待办

| 范围 | 当前结论 | 仍需处理 |
|---|---|---|
| 本轮性能与可靠性源码 | S01–S08/Q01–Q10实现与结构审查已在[PR #12](https://github.com/Surtr42u/qimeng-media/pull/12)，现用192ebaaa精简包已安装PC/原手机，本次隔离后端验证旧版本拒绝与v2不重复；旧九组为8f6a3a3c历史证据；具体职责与验证见[任务书](OPTIMIZATION_TASKBOOK_20261025.md)§10/§11 | 安装已核对同源完整门禁/身份/签名，用户不等新增自动审查；反复大批量上传不属此次个人验收，专项量化/逐阶段故障/扩展交互未齐事实保留（任务书§11）。Q06真实失败阻断/正常成功上传均已验证（工件为5eaa181c，未部署）。Q09仅四个合成浏览器场景及确定性Android单测，设备/窗口/NAS另验；R01–R07产品候选待选 |
| 扫描/恢复 | ADR-0039 自动化通过；静态链接拒绝、恢复意图和固定落点重试 | 本机真实两卷成功/冲突/中断已测；NAS 卷权限/掉电验收仍待执行；外部换链仍是 SECURITY 接受边界 |
| 备份/统计 | ADR-0040 身份守恒、事务重建、并发/失败重试回归通过 | 旧聚合不擅自清洗；真实设备迁移/恢复验证 |
| 缩略图/预取 | 表示身份贯通、预取检测策略指纹；缺失尺寸后台准备、Android取色/单次原图/有界预取与响应体取消已实现，装机及合成验证边界见HANDOVER | 相同 size/mtime 原件改写属检测边界；实机验证。Windows core:data 本次 311 项通过，旧 DataStore 文件替换失败未复现，根因未定位 |
| 原件灾备 | PC 完整副本和必要数据/9 个原件样本恢复曾核对通过；本轮额外备份已按用户要求删除，两个批次目录均不存在 | 历史验收不等于当前持有可用副本；不自动重建或扩大测试。手机备份/恢复本轮不做；未来需释放原件时重新确认独立副本（RESTORE） |
| 鉴权/数据权限 | 多会话和单会话登出已有 | 远程吊销端点、401 专用错误码；设备退役/远程常态化触发。DB/WAL/快照 0600 加固待办 |
| 容器/CI 供应链 | 基础构建与 CI 已有 | Actions SHA/只读权限、镜像 digest、出包 Go 补丁与 sdk.lock 门禁已对齐；非 root/降权叠加配置已提供，当前镜像构建和目标卷权限待验收 |
| 依赖漏洞门禁 | 五类生态、六个目录配置的每周候选及自动安全升级 PR 已恢复；人工审阅后合并，门禁与开关规则见 [SECURITY](SECURITY.md#依赖与供应链) | #2～#15 已合并，glib 上游补丁及 release 回归通过；2026-10-20 核验基线 70764391 的主分支 CI/安全/文档成功、GitHub 未解决漏洞告警为 0。后续新 PR、扫描与运行包部署分别核验，不能据此声称所有 Cargo 警告为 0 |
| 服务端结构/查询 | 作者预聚合，上传/回收站/同步/TXT/便携备份编排下沉；列表先定页、限定日期索引路径、总数/分桶合并（ADR-0042/0043） | 存量写入编排渐进处理；复杂筛选、稀疏库、冷缓存与真实库测量，见 [查询策略](domain/filtering.md#查询执行策略与性能边界) |
| 统计热榜性能 | 按资产窗口聚合 open 再展开关联；旧 SQL 字段/顺序对照与索引计划通过（ADR-0044） | [合成共享场景已测](domain/statistics.md#热榜查询策略与性能边界)；真实库分布、窗口与目标硬件另验收 |
| 推荐冷启动性能 | 已安装基线具备ID快照跨页复用/双seed输入复用及store宽窄装载（ADR-0044/0046）；现用精简包保留直接ID、seed无关准备复用、逐项LRU和可取消共享生命周期；上轮个人规模电脑分页证据见HANDOVER历史记录 | [窄/宽/空/半量及双 seed 合成基准已测](domain/recommendation.md#16-输入装载策略与性能边界)；完整顺序冷算/双 seed 预热有分配和 CPU 代价，容量淘汰会重排，真实库与目标硬件需复测 |
| 推荐排序质量 | 规则评分及有界同分桶打散；修正相邻分差链式吞桶，标签与缓冲优化（ADR-0045） | [评估边界](domain/recommendation.md#17-算法评估与本轮调整)：作者/出处去重、时间衰减/负反馈未实现，实际偏好质量仍需反馈对照 |
| 作品名联想性能 | 命名族按空格边界查表取最短已有前缀，避免全族互扫；[合成基准与原算法对照](domain/files.md#作品名联想执行策略与性能边界) | 文件名专用投影与快照词条预归一化已落地，旧结果/并发词层对照及合成基准见专题；真实设备端点性能待测；本批部署状态见 HANDOVER |
| 排行小页排序 | 标准库堆选择前 offset+limit 项，热度/mtime/同分稳定序及分页回归通过；[边界与基准](domain/recommendation.md#排行执行策略与性能边界) | 专用热度键与当前页累计计数装配已落地，周期榜不先读全量原始事件；总榜/日统计聚合仍随规模增长，深页/真实设备待测；本批部署状态见 HANDOVER |

## 明确不做

| 项目 | 依据 |
|---|---|
| 公网裸端口映射/自管 HTTPS 直连 | ADR-0006，只走隧道 |
| 实时转码、查看时按需缩放原件 | ADR-0002 与用户查看原件要求；远程按需转码档位仅为 PROJECT_PLAN 储备，不能自动实施 |
| 原生AI识别引擎/人脸聚类、iOS、公网多租户 SaaS | ADR-0002 与产品定位；外部AI研究维护按ADR-0051/0052纳入规划 |
| 跨端 UI 框架（Flutter/RN/Compose Multiplatform） | ADR-0008 |
| 旧 UI 协议扩展组：收藏/历史多选筛选、history 作品维、统计旧口径组、标签逐条即时移除、facets 作者收窄（原台账 #29～#34）及榜单批次播种入口（#21） | 用户 2026-09-20 放弃全量旧交互复刻；本条记录放弃范围，已独立交付的相关端点不因此退役，端点现状查 OpenAPI/API 专题 |

## 维护

只改受影响域；功能、实现状态、自动化验证、设备验收分开表述。缺口销项直接更新本表，过程进 CHANGELOG；不在文件头累积更新记录，不在 HANDOVER 复制完整待办。未来储备及里程碑见 PROJECT_PLAN，提案先查 ADR 状态。