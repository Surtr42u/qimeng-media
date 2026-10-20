# CHANGELOG - 变更历史

本文件只保留最近 30 笔；更早条目按 [历史索引](history/INDEX.md) 查询。历史条目正文及署名不改写，当前状态看 HANDOVER/能力地图与现行规范。
## AI 署名约定（沿用旧项目 QimengMedia 惯例）

- 每个变更条目标注实际执行该改动的 AI 模型完整名称与变体（如 GPT-6.1 Sol），以会话元数据或用户明确确认为据；Codex 仅作工具/客户端说明，不能代替模型名，具体版本也不能简写成模型系列。无法确认时注明未知，禁止猜测。
- 每个条目单独署名；多 AI 协作时各条目自行署名。
- 子代理执行的工作标注"（执行子代理）"，主对话直接完成的标注"（主代理）"。
- 署名自查（2026-09-05 补）：每条变更由执行会话先确认自身实际运行模型的真实名称再署名（GLM-5.3 与 GLM-5.3-Flash 是两个不同模型名），禁止沿用上一会话或上一条目的署名行；历史条目真实署名不动。
- 署名更正（2026-10-20，用户明确确认）：2026-10-19～20 的第五百三十八至第五百四十五笔原误署 GPT-6 或仅署 Codex，统一更正为 GPT-6.1 Sol；保留各条主代理/执行子代理及复核分工。


## chore(docs): 按用户最新要求恢复依赖升级机器人（2026-10-20 第五百六十一笔）

执行 AI：未知（会话未提供可核实的完整模型名称与变体；主代理）

- 恢复 Docker、Go、Web/desktop npm、桌面 Cargo 与 Actions 每周检查，每生态最多 1 个常规待处理 PR；恢复自动安全升级 PR，保留漏洞告警和安全 CI，不启用自动合并。
- 同步协作规范、安全、架构、工具入口与当前交接；ADR-0047 的补丁决策不变，机器人开关由现行安全规则负责。本轮不升级依赖、不改应用或部署，不执行 NAS 安全计划。
- 验证：提交前执行文档检查；远端配置、自动安全升级开关及自动合并关闭状态在推送后核验。本次只改机器人配置和配套文档，无应用语义变化，不要求应用构建/设备验收。

## fix(desktop): 回移植 glib 安全补丁并关闭自动依赖升级 PR（2026-10-20 第五百六十笔）

执行 AI：未知（会话未提供可核实的完整模型名称与变体；主代理）

- 按用户要求移除 Dependabot 定期升级配置，关闭自动安全升级 PR；保留漏洞告警和安全 CI，协作规范、安全与架构入口记录未经用户明确要求不得恢复机器人。
- 官方 glib 0.18.5 归档通过原锁文件 SHA-256 核验，保留原 121 文件及 MIT 许可，只回移植上游 #1343 两行可变出参修复；Cargo 使用本地补丁，版本不伪装，不忽略/手工关闭告警。源码约 1.7MB，来源与退役条件见 ADR-0047。
- 新增快照/依赖解析校验与 Linux release 上游迭代器回归门禁，区分原版运行时失败与网络/编译失败，恢复补丁后执行相同测试；桌面及补丁测试锁文件均纳入审计。Rustdoc 链接按其符号语法处理，普通失效链接仍检查。
- 本地来源校验、10 项工具测试及文档检查通过；原版缺陷复现、修复版优化测试、Windows 桌面与完整远端门禁结果以本批 GitHub 记录为准。同步安全、架构、桌面/工具入口、交接及 ADR 索引，历史完整条目原样归档。不改 UI/API/schema/领域公式，不操作正式 PC 包或手机、不向 8420 写测试数据。

## fix(server+web): 修复依赖升级的生成依赖与 PWA 配套约束（2026-10-20 第五百五十九笔）

执行 AI：未知（会话未提供可核实的完整模型名称与变体；主代理）

- 接手 13 个 Dependabot PR，核对当前 head、逐项文档影响和失败日志；兼容升级在完整门禁通过后合并，最终合并与验证结果见 HANDOVER。
- 生成接口层的 runtime 根包以手写依赖声明保留，使干净检出/Dependabot 的 tidy 不再删除生成代码所需的 go-jsonmerge 间接依赖；不手改生成物、协议或 SDK。
- PWA 图标生成器 2.x 配套 vite-plugin-pwa 2.0，遵守上游 peer 范围和现有 ESM 配置；不使用 force/legacy-peer-deps 跳过依赖约束。
- 本地验证：共同 Go 修复全量测试/vet，移开生成文件后 tidy 保持 go.mod/go.sum 不变；Web 干净安装/audit=0、312 测试、生产构建和 CI 范围 knip 通过；TS SDK 16 文件指纹一致，隔离目录六个图标生成成功。其他 Go 升级本地大包下载受限，远端完整门禁负责兼容性验证。
- 同步服务端/Web 开发入口和交接边界；更正依赖审查 Action 的版本注释，不改门禁。历史最旧完整条目原样归档。不操作手机或 PC 正式服务，不向 8420 写测试数据；glib 中危告警继续保留，不忽略、不关闭。

## perf(web): 跨端刷新与推荐跨页快照，并修复 GitHub 检查（2026-10-20 第五百五十八笔）

执行 AI：未知（会话没有可核实的完整模型名称与变体；主代理）

- Web 库变更/重连刷新统计、排行与历史；成功便携导入广播变更。推荐输入/评分/分页下沉 recommendfeed，共享 60 秒限容量 ID 顺序，预热 Web seed=0 与 App seed=1；页摘要现取且重新校验资格，实际返回页才累计展示。评分公式与权重不变，协议描述先更新、三端 SDK 与 sdk.lock 再生。
- Go 构建/扫描统一 go.mod 的 1.27.2 安全补丁，lint 升级 v2.15.0 并同工具链源码构建；四处 G703 跨函数根校验误报有逐行依据，未关闭规则或豁免 depguard。shadcn CSS 按原版字节及 MIT 许可保留，移除 CLI 的漏洞链，现有组件/视觉不变。
- 核对 13 个 Dependabot PR 的改动范围并补文档无影响依据，文档检查全部转绿；启用仓库 Dependency Graph/漏洞告警，依赖审查重跑成功。没有合并依赖升级或放宽安全阈值。
- Go 全量测试/vet/lint（0 issues）、Web 312 项测试/生产构建/knip、文档工具 5 项自测与文档检查通过；Go 可达漏洞扫描通过，Web npm audit=0。实现提交 726bba35 的远端 ci/文档/依赖安全全部通过，含 Linux race 与 Android/桌面构建；GitHub 仍有桌面 Linux glib 中危提示，未关闭或忽略。
- 隔离实例实看统计页：其他客户端扫描后总文件 0→65，v2 导入后浏览 0→1 且内容榜出现条目，均未重进/刷新页面；推荐分页 60+5 项无重复。合成 2 万候选/40 万事件三轮，冷首屏约 303.16ms、命中后续页约 0.196ms（中位数，仅编排/SQL，不含 HTTP/媒体/展示回写，不当作旧版或真实设备加速倍数）。未写 8420 测试数据，未操作 Android；本机 CGO=0，race 由远端 CI 验证。
- 同步推荐/API/架构/UI/安全/观测、Web/服务端入口、能力地图、交接与 ADR-0046。PC 旧后端正常退出后已无头更新，运行文件哈希与验证包一致、healthz/readyz=200，旧包本地备份；Android 未安装。范围见 HANDOVER。

## chore(app+repo): 汇总待提交改动，完成新版内嵌 Release 装机与收尾（2026-10-20 第五百五十七笔）

执行 AI：未知（本会话未提供可核实的完整模型名称与变体；Codex，主代理）

- 用户授权全量提交主分支现存代码/文档，涵盖文档按需维护/门禁、上传目标绑定、列表/聚合/推荐优化与统计详情柔影修正；原 UI 分支已安全删除。
- 最终 arm64 内嵌 Release 构建通过（1m52s，约 25.25MiB）；签名、非 Debug 标志、arm64 三件套、FFmpeg 哈希与 Go build ID 核对通过。同签名覆盖安装保留数据，已装 APK 字节一致；冷启动/内嵌子进程及 healthz/readyz 正常，近期日志无 fatal/ANR。装机前手机独立目录实例的鉴权/列表/统计/推荐只读 API 通过，临时进程与转发清理；指定模拟器已关闭。
- Go 全量测试/vet/lint、Web 311 单测/构建及 lint（既有警告）、Android 定向验证、工具 5 项测试及文档检查通过。Web 仍有 7 项开发依赖 high，模拟器 x86_64 内嵌 SQLite SIGSYS 未修复，未冒称安全/全设备门禁全绿；手机 arm64 已独立验证。详见 HANDOVER。
- 同步 Android README、外观交付记录与交接/能力边界；不写入 8420 测试数据，不替换 PC 日常实例。历史正文原样归档，设备标识和现场日志仅留本地。

## fix(app): 补齐统计详情白卡和排行行的柔和边界（2026-10-20 第五百五十六笔）

执行 AI：未知（本会话未提供可核实的完整模型名称与变体；Codex，主代理）

- 统一 StatCard/RankRow 的日间共享轻影，覆盖分布统计、内容榜、常看作者与标签的摘要/洞察/分布/列表卡；共用容器的趋势详情同样受益。不改纯白底、原尺寸、统计或排序规则，深色不加新影。
- Debug 构建（36s）及统计 39 项单测通过；指定 qimeng_api35 只用隔离合成数据实看三页，并核对排序切换与返回。同步 UI 指南、Android README、外观交付记录与 HANDOVER。
- 继续用户授权的全量提交、内嵌后端验证与 Release 覆盖装机流程，实际完成状态见交接；未向 8420 写入测试数据。最旧完整条目原样归档。

## style(app): 统一纯白背景与柔和边界，补齐相册胶囊并交付主分支（2026-10-20 第五百五十五笔）

执行 AI：未知（本会话未提供可核实的完整模型名称与变体；Codex，主代理）

- 用户确认最终白底/轻影方案，补齐相册维度及值胶囊；整页纯白、共享中性轻影、首页入口和主胶囊、设置/统计/管理卡及“外观”改名一同交付。原布局/比例/交互保持不变，无业务、API、原件或新依赖改动。
- 最终工作区 Debug 及独立暂存代码构建通过，后者 1m12s；core:ui、首页、相册、设置、统计共 155 项单测在两处均零失败；指定 qimeng_api35 用隔离合成数据实看相册胶囊与展开收起。真机观感/帧率/功耗/API ≤30 未验收，未替换手机 Release。
- 用户授权合入 master；仅整理本次 UI 与相关文档，其他已有未提交内容保留。同步 Android README、UI 指南、交付记录和交接；历史完整归档。提交与同步状态以 Git 为准。

## fix(app): 补回首页主分段胶囊的轻微立体边界（2026-10-20 第五百五十四笔）

执行 AI：未知（本会话未提供可核实的完整模型名称与变体；Codex，主代理）

- 根据用户截图，为推荐/COS/排行榜三胶囊复用同一日间白卡轻影；保留原选中染色、未选白色与尺寸、字号、按压和切页逻辑，不加硬描边或灰底。
- QimengChipRow 允许传入单枚修饰符，默认无变化；只由首页主分段行使用，其他页面/周期行与深色不扩改。无新依赖或业务变化。
- Debug 构建与指定模拟器实览范围见 HANDOVER，纯视觉未重跑业务单测。同步 UI 指南、Android README、试调记录和交接；仍留试调分支，不合并/推送主分支，不替换手机 Release。最旧完整条目原样归档。

## fix(app): 恢复首页白色玻璃，仅以轻微软影增强边界（2026-10-20 第五百五十三笔）

执行 AI：未知（本会话未提供可核实的完整模型名称与变体；Codex，主代理）

- 用户否定上轮灰底/细边，明确参考白卡轻微浮起感。首页搜索、筛选和列数入口撤去新增染色与硬描边，恢复原玻璃样式，仅复用 SoftDaylight 的中性轻影，不改原几何和按压交互，深色无新增投影。
- 撤回上轮专用 tintOverlay 透传 API，两份 core 玻璃文件恢复原实现；无新依赖或业务变化。最终 Debug 构建通过，指定模拟器预览范围见 HANDOVER，未重跑业务单测。
- 同步 UI 指南、Android README、试调记录和交接；仍在试调分支供用户看，不合并/推送主分支，不替换手机 Release。最旧完整条目原样归档。

## fix(app): 提高纯白背景上首页搜索与操作按钮的可辨识度（2026-10-20 第五百五十二笔）

执行 AI：未知（本会话未提供可核实的完整模型名称与变体；Codex，主代理）

- 按用户截图反馈，为首页搜索、筛选及列数入口增加日间中性浅灰染色和 1dp 细边，保留整页纯白、原尺寸/间距/图标及按压反馈。
- 复用现有 GlassSurface 染色，玻璃图标钮和真采样面板补可选 tintOverlay 透传，API ≤30 降级同源；默认 null，其他调用与深色保持原样，无新依赖或业务变更。
- Debug 构建及指定模拟器检查范围见 HANDOVER；纯视觉未新增或重跑业务单测。同步 UI 指南、Android README、试调记录和交接；保留当前试调分支，不合并/推送主分支，不替换手机 Release。

## fix(app): 日间整页改为均匀纯白，移除偏色渐变与有色软影（2026-10-20 第五百五十一笔）

执行 AI：未知（本会话未提供可核实的完整模型名称与变体；Codex，主代理）

- 用户澄清问题在整页白底显脏。核对 Google 官方表面色角色与 Compose 教程后，取消冷灰渐变，背景/卡面/窗口统一 #FFFFFF；辅助容器转中性灰，双向高光与紫灰投影改为低强度中性阴影。纯白为本项目的选择，不宣称是 Google 唯一指定色。
- 保留原布局、交互、“外观”改名、文字可读性和深色材质；不改业务/API/依赖。验证范围见 HANDOVER，未重跑业务单测。
- 同步 GUIDE_UI、Android README、试调记录和交接；仍在 codex/android-ui-modernization 待用户审阅，不提交/合并/推送主分支，不替换手机 Release。最旧完整条目原样归档。

## style(app): 根据用户参考图试做同色柔白背景与双向软阴影（2026-10-20 第五百五十笔）

执行 AI：未知（本会话未提供可核实的完整模型名称与变体；Codex，主代理）

- 用户授权按柔白参考执行；缩小背景/卡面的灰白差，页面近白至冷柔白轻渐变，卡面 #F8F8FC。窗口/启动帧同步 #F7F7FB；文字保持可读，原布局、相册 16:9、筛选与导航瞬切不变，“外观”改名保留。
- core:ui 的 SoftDaylight 用现有 Compose gradient/dropShadow 统一右下淡冷灰软影与左上白高光；我的入口/数量/外观选择卡、统计及数据管理入口复用。日间关闭原窄硬 elevation，深色仍用既有材质；不引依赖，不自建位图/模糊管线。
- Debug 构建通过，指定 qimeng_api35 同签名覆盖安装；用隔离合成数据实看首页/我的/统计与主题切换，具体范围见 HANDOVER。只改颜色/绘制，无新增镜像单测；真机帧率、耗电与原生屏幕观感未测。
- 继续在 codex/android-ui-modernization 工作区待审阅，不提交/合并/推送主分支、不替换手机 Release，保留既有未提交内容。UI 指南、Android README、试调记录和交接同步；最旧完整条目原样归档。

## style(app): 按用户要求撤回布局改造，仅保留外观改名与背景对比度试调（2026-10-20 第五百四十九笔）

执行 AI：未知（本会话未提供可核实的完整模型名称与变体；Codex，主代理）

- 用户取消上一批其他 UI 建议。撤回首页/页头/相册比例/筛选分组和外观返回箭头，恢复原布局与交互；只保留“外观”改名、次级文字/系统栏可读性，原多批次任务改写为当前收窄范围，取消项不再默认挂待办。
- 日间柔和浅灰背景 #F4F5F7 与纯白内容卡分层，窗口颜色同步；胶囊/辅助面/分割线中性化，玻璃提高白色遮盖并减轻紫灰内影与投影，统计日间卡面白色、深色卡面不变。没有更改布局尺寸、媒体字节、数据接口或运行时依赖。
- 继续在 `codex/android-ui-modernization` 工作区，用户确认前不提交/合并/推送主分支，不替换手机 Release；只在指定模拟器用隔离合成数据预览新版 Debug。构建、文档与实览范围见 HANDOVER；未写入 8420。
- GUIDE_UI、Android README、当前试调记录、导航和交接同步用户新范围；第五百一十九笔完整原样归档。

## feat(app): Android UI 任务书与第一批构图、层级、可读性调整（2026-10-20 第五百四十八笔）

执行 AI：未知（本会话未提供可核实的完整模型名称与变体；Codex，主代理）

- 新增 Android UI 现代化任务书，明确第一部分、后续页面整理与动效原型的范围/验收。按用户要求切到 `codex/android-ui-modernization`；保留原工作区未提交内容，用户确认前不合并本地或 GitHub 主分支，不替换手机 Release。
- 相册方形槽位/Fit 保留横竖构图，其他媒体流维持原预览；提高两主题次级文字对比，系统栏图标跟随 App 主题。首页标题、搜索与 M3 页签分层，仅 COS 展示实际生效的筛选；共享列表页头数量移到标题下；筛选增加把手与分组；“外观”补共享顶栏返回。
- 使用指定 `qimeng_api35` 与隔离合成媒体实例，移除模拟器旧版/实验安装，仅保留新版 Debug。相关构建、单测与可见验证结果和未测范围统一见 HANDOVER；没有写入 8420 真库。无协议/schema/SDK/服务端生成策略/运行时依赖变化。
- 文档：GUIDE_UI 维护现行交互，Android README 维护预览/色板范围，任务书记录分批方案，HANDOVER 记录验证；llms 增导航，最旧完整历史原样归档。

## perf(server): 联想投影、词条预归一化与排行按页装载，保留原件直出（2026-10-20 第五百四十七笔）

执行 AI：GPT-6.1 Sol（Codex，主代理）

- 联想仅查询目标库全部 file_name，不用粗筛截候选，不改扫描对账整行查询；两万合成资产三轮中位数 40.41→7.92ms，分配 46.27→2.48MB。词表反查键在内置加载/自定义原子快照构建时预计算，保留字段/原词/去重顺序；dva 1.058→0.108ms、女武神 1.059→0.071ms，宽查询收益较小，见专题边界。
- 排行 IO 与选页下沉 ranking 包；总榜仅取必要累计热度键，周期榜仅读日物化统计/窗口点赞，选页后取该页元数据和累计角标计数，摘要重查存在/启用库/非 COS；并发变更允许短页，无跨查询快照。两万资产/四十万事件、200 条页，旧取数路径已含 RankTop，三轮中位数总/周/月 264.02/278.59/286.13→91.54/50.15/64.04ms，分配 34.62/39.29/43.22→10.87/7.52/11.84MB。不等于 HTTP/真库/手机/NAS 延迟。
- 完整原实现逐字段/顺序与全部周期/分页、脏时间及等时刻异时区对照，停用/COS/删除并发、查询错误、词层替换/清空及结果不污染回归、既有索引计划锁通过。原图/视频直出未改编码/尺寸/像素切块；合成完整响应与 Range 均逐字节等于原文件对应字节，列表缩略图仍为独立表示。
- Go 全量测试/vet/lint（0 issues）、Windows/Android arm64 构建及内嵌 Release 通过；CGO 关闭且无 C 编译器，race 未执行。包含上笔改动共同替换：PC 经正常退出按标准无头链重启、运行文件哈希一致；手机同签名覆盖安装保留数据，已装 APK 字节一致、三件套装配/Go build ID/ABI/FFmpeg 哈希核对通过。两端 healthz/readyz 正常，手机 App/后端进程正常、近期 App 日志采样无 fatal/ANR，旧产物已备份。未做本批手机算法基准/长期后台/NAS 验收，8420 无测试写入。
- 文档：文件/匹配/推荐/缩略图专题同步执行策略、测量与原件品质边界；server/架构同步 ranking 只读编排职责；API 用法、能力地图、交接同步当前状态与限制。无 API/schema/索引/SDK/运行时依赖或评分公式变化；最旧完整历史条目原样归档并更新索引。

## perf(server): 联想族前缀查表与排行榜限量选择（2026-10-20 第五百四十六笔）

执行 AI：GPT-6.1 Sol（Codex，主代理）

- 作品名联想按规范化族键的空格边界由短到长查已有族，保持原最短主体前缀/多角色轮转，消除命名族全表互扫。匹配梯度、序号风格/位宽、排序和 8 条上限不变。Windows 三轮完整纯函数中位数：2000 并列族 26.98→3.33ms，8000 并列族 433.15→18.15ms，2000 多层前缀族 37.55→4.85ms；分配量基本不变。
- 排行小页用标准库堆保留前 offset+limit 项，仅排序/复制这些 Item；热度/mtime 共用比较口径，同分按输入下标保持原稳定序，完整候选与窗口准入不变。最大 offset 不相加溢出、越界空数组。2 万候选返回 200 项，纯排序三轮中位数：总榜 13.76→0.155ms、周榜 10.68→0.156ms，分配 3.842→0.044MB；不同输入顺序/深页对照与限制见推荐专题，不代表 SQL/HTTP/真实手机耗时。
- 原聚类算法 1000 余键/8 种插入顺序对照、既有联想完整结果；排行原全量稳定排序逐字段/同分/等时刻异时区/各窗口/空输入/限量/输入只读及 HTTP 最大 offset 回归通过。Go 全量测试、vet、lint（0 issues），Windows/Android arm64 服务端构建通过；本机 CGO 关闭且无 C 编译器，race 未执行。本批未替换正在使用的后端、未重新打包/安装手机，8420 无测试写入。
- 输入取数仍有后续空间：联想装载整行再取 filename、词条反查重复归一化、排行复用全量推荐聚合；本轮只记录静态依据，未声称这些路径已提速。趋势已由 SQL 按天聚合，未因纯函数理论规模泛化为当前瓶颈。
- 文档：文件/推荐权威专题同步执行策略、基准与复跑命令，能力地图和交接同步实际完成/未部署与剩余候选。无领域口径、API/schema/SDK、缓存或运行时依赖变化；最旧完整变更条目原样归档并更新历史索引。

## perf(server): 推荐下标排序与限量混合，核实手机后台冻结现场（2026-10-20 第五百四十五笔）

执行 AI：GPT-6.1 Sol（Codex，主代理）

- 完整评分/稳定排序/全部桶洗牌后，按完整媒体组的剩余比例仅生成所需前缀；中间只搬下标和分数，最终仅复制返回 Item。保留公式/权重、同 seed 顺序、输入只读、查询/缓存/展示计数，无新依赖或协议/schema/SDK 变化。
- 2 万合成候选返回 200 项，PC 三轮中位数：图片 25.34→13.97ms、混合 27.61→14.52ms；累计分配分别 14.44→6.96MB、22.13→7.13MB。用户真机独立低优先级测试进程、旧/新交替三轮：图片 125.24→67.11ms，混合 149.96→54.61ms；调度波动范围见推荐专题，不代表真实页面/SQL/媒体 SLA 或峰值 RSS。
- 13 组优化前全量输出摘要及每组 6 种 limit 的完整字段/前缀回归通过；Go 全量测试、vet、lint 通过。本机 CGO 关闭且无 C 编译器，race 未执行。Windows 后端及内嵌 arm64 Release 构建通过，FFmpeg 哈希锁、三件套装配/符号裁剪/Go build ID/ABI 核对通过。
- 用户随后明确授权替换：PC 后端经 Ctrl+C 正常退出后换入新二进制，按标准无头启动链重启；运行文件哈希与验证构建一致，healthz/readyz 均成功。手机同签名覆盖安装保留数据，已装 APK 与新包逐字节一致，App/内嵌子进程正常、healthz/readyz 成功；本次冷启动约 587ms（单次观察），新 App PID 的近期日志采样无 fatal/ANR。旧 PC 二进制与手机 APK 已留本地忽略目录备份。仅启动/健康验收，不代表真实推荐页面性能或后台冻结长期验收。
- 手机前台服务仍在但进程组冻结，端口监听而 healthz 超时；系统 isFrozen 与 cgroup 两标志共同确认。用户切回前台后原 PID 解冻、healthz/readyz 正常，没有重拉；系统策略未改，冻结来源未精确定位，未声称解决后台永久在线或验收异常重拉自愈分支。
- 文档：推荐权威专题同步执行策略、对照基准与取证限制，HANDOVER 同步构建/部署验证/冻结恢复边界；最旧完整条目原样归档。后台合成测试文件与 USB 转发已清理，8420 无测试写入。

## fix(server): 限制推荐同分桶跨度并完成手机覆盖安装（2026-10-20 第五百四十四笔）

执行 AI：GPT-6.1 Sol（Codex，主代理）

- 推荐按桶首最高分限制 0.05 分差（含边界），修复相邻小差值将高低分串为一大桶的问题；保留评分/权重/每日惩罚、随机哈希及视频图片混合。刷新顺序有意修正，同输入/时间/seed 仍可复现。
- 每项完整标签复用一次，连续输出切片洗牌，混合按实际数量分配且单类型跳过缓冲。2 万候选三轮纯算法中位数：图片 36.36ms→24.42ms、混合 36.52ms→26.28ms；累计堆分配 49.18MB→14.44MB/22.13MB，不等于手机延迟或推荐质量指标。
- 补连续分差、精确边界、32 个 seed、固定维度分数与输入不被改写回归；Go 全量测试/vet/lint 通过。Android network 37 项与上传载荷 26 项定向单测通过，未声称 core:data 全套通过。
- 最新 Go arm64 内嵌服务端及哈希锁 FFmpeg 装配，Release 约 25.24MiB；符号裁剪链/Go build ID/旧新签名复核一致，同签名覆盖安装到 NX721J，未清数据。已装 APK 字节一致；启动成功、App/内嵌进程存活、只读 healthz 正常，近期进程日志无 fatal。真机推荐质量、切服/迁移/恢复未据此验收。
- 文档：推荐专题对齐分桶、权重项数/扣分符号、冷启动多样性未完成边界与算法评估；API 行为、能力地图/交接及 ADR-0045 同批同步。协议/schema/SDK 无变化，未对 8420 真库写测试数据；最旧完整历史条目归档。

## perf(server): 热榜按资产预聚合与推荐输入按规模选路（2026-10-20 第五百四十三笔）

执行 AI：GPT-6.1 Sol（Codex，主代理）

- 作者/标签热榜先按资产窗口聚合 open 再展开关联；保留共享/COS 归属、现存资产、停用库参与、窗口及同分顺序。无 schema/索引/协议变化。
- 推荐只读装载下沉 store：默认宽流保持原两查询，显式媒体/COS 流先观察规模，不超过总资产一半时仅读候选事件与标签；否则顺序聚合。规模不截断候选，评分/缓存/单飞/预热/每日展示计数不变，无新运行时依赖。
- 合成基准三轮中位数：热榜作者 1387ms→17.34ms、标签 774.9ms→12.57ms；2 万资产/40 万事件，1% 推荐输入 227.2ms→7.80ms、累计堆分配 14.85MB→0.389MB，默认宽流基本持平，99% 候选因 COUNT 约慢 4.2%。均为查询/装载测量，不代表 HTTP、真实库或 NAS/手机 SLA。
- 验证：旧 SQL 逐字段/顺序对照、媒体/COS/库开关/空库、规模过时不漏候选、关键计划、Go 全量测试/vet/lint 通过。sqlc 按源查询重建，未改 SDK；仅用临时合成数据，未操作日常实例/设备。
- 文档同步统计/推荐权威专题的执行策略、复跑命令及代价，API 专题、架构/server 职责、能力地图/交接与 ADR-0044 对齐；监控说明标签映射移入 query 计时的归属变化；CHANGELOG 最旧完整条目归档。

## perf(server): 列表先限页、日期索引分页与首屏计数合并（2026-10-20 第五百四十二笔）

执行 AI：GPT-6.1 Sol（Codex，主代理）

- 通用列表先按完整筛选/排序/游标限定页，再装配收藏/点赞；仅投影实际消费字段，Asc/Desc 用同构转换替代两份字段搬运。无 schema、协议、公式、缓存或运行时依赖变化。
- 默认/日期排序且只限库/COS 分区时走既有 mtime 复合索引；首屏/游标互斥 UNION、深页 seek，任何其他或未来筛选自动回退通用查询。保留停用库、三态 COS、升降序与同时间戳 tie-break。
- dateCounts 首屏一次分组同时生成总数和日期桶；未知日期用内部 NULL 桶计入总数、响应不输出，翻页不重复统计。SQLC 只重建 DB 查询，不触碰 SDK。
- Windows 5 万资产/25 万事件：核心 SQL 原首屏约157ms→通用31ms/限定日期0.355ms，原游标约83ms→通用21.8ms/限定日期0.367ms；计数+分桶51.6ms→38.4ms。HTTP 50003资产/25万事件，预热后5次中位数：首屏含总数12.5ms、含日期桶34.5ms、翻页1.0ms。均不代表真实库/NAS/手机 SLA，不包含媒体读取/缩略图生成。
- 验证：分页/字段与通用查询对照、白名单拒绝全部未支持字段、未知日期及多时区总数回归、关键计划锁、显式大库 HTTP 探针；Go 全量测试/vet/lint 通过。文档同步筛选/API/架构/交接/能力地图及 ADR-0043；后续候选热榜关联放大、推荐候选读取范围只记静态依据，未声称收益。

## fix(server): 完善上传归属、恢复演练与维护门禁（2026-10-19 第五百四十一笔）

执行 AI：GPT-6.1 Sol（Codex，主代理）

- Android 持久上传固定入队服务器，直传/分片/挂靠同址，切服及旧任务无目标拒绝，禁重定向；绑定请求迟到 401 核对当前凭据。业务拒绝不取消整条队列。URL 绑定不等于实例 UUID，已发请求可在原端完成。
- docs-check/PR 模板及 CI 落地：专题同步或具体无影响理由，链接/索引/归档/入口与 Actions 固定/只读权限基线；新增错误场景自测。五生态 Dependabot、Go/npm/Cargo/PR 扫描配置；固定基础镜像 digest，显式容器降权叠加配置；手动出包恢复 sdk.lock 门禁。
- 真迁移库快照在新目录重开、事件/收藏核对与原件独立副本；Windows 实际两卷成功保时/冲突不覆盖/中断保源通过。HTTP 上传源 mtime 未实现，补测试及恢复指南明确边界。
- localsync 只读扫描计划下沉；作者 open 先按资产聚合，共享/零作品语义回归及计划验证通过，合成基准 179.2ms→20.5ms（约 8.7 倍）。5 万资产/25 万事件探针确认日期 CASE 仍需临时排序（首屏约 157ms/游标约 83ms），均不代表真实库 SLA。
- 验证：Go 全量测试/vet/lint；Android 定向单测及 Kotlin 编译；Web 311 测试/生产构建/lint（既有警告）；TS 16 文件重生指纹不变；文档工具 5 测试及结构/YAML 检查通过。未触碰 8420 真库或设备。
- 限制：Web 漏洞 30→7 high/0 critical，剩 braces 传递链暂无补丁；desktop npm 0 项；Go/Cargo 扫描受网络限制未完成，远端 CI 未执行。无 NAS/Docker 实际部署/掉电/设备切服验收。受影响专题、当前状态与范围同步见 ADR-0042/RESTORE/HANDOVER/能力地图。

## docs(docs): 收敛 AI 入口与按需专题文档（2026-10-19 第五百四十笔）

执行 AI：GPT-6.1 Sol（Codex，主代理）

- AGENTS 为唯一全量必读入口；协作细则按任务章节读取，明确扩读条件、文档职责/权威与同步触发矩阵（ADR-0041）。
- DOMAIN_RULES 保留兼容 §编号目录，11 节原文分为 10 个专题，公式/常量不变；API 按 6 个专题保留 23 组端点与 18 项机制，现行 v2/缓存/会话语义归位。补本仓 UI 约束，旧仓只历史参考。
- HANDOVER 只留当前形态/事项/验证边界，能力地图整理真实缺口；修正 ServeMux/动效/本地持久化/词表取代状态等文档冲突。近期 CHANGELOG 保留 30 笔，较早完整条目原样移至分期档，历史索引可查。
- 验证：文档链接/锚点、领域原文和历史归档守恒、端点/机制迁移、适用路由及敏感串检查；纯文档交付，未运行应用构建/测试，未操作真实媒体/数据库/设备。

## fix(api): 完成第二轮备份、统计与缩略图一致性修复（2026-10-19 第五百三十九笔）

执行 AI：GPT-6.1 Sol（Codex，主代理）

- R1/R5：v2 便携备份透传原始事件身份/会话/时间/秒数/日键，旧 v1 聚合回放维持兼容；0019 新增无键事件身份旁表与触发器，不修改历史原文。导出全程同事务快照；recordKey 全局查重，多库导入唯一消歧，歧义/多对一跳过告警。
- R2/R4：eventstats 专职编排承载持写锁事务内重建，stats 保留纯函数；恢复原身份和该资产日统计同事务提交，失败保留意图重试，不扫描全站历史。
- R3：原件 size/mtime、策略、输出格式及配置共同决定缩略图版本，贯通 URL/磁盘/Android 稳定键；实际字节 ETag，过期 URL 410/no-store，旧无版本 URL 须重验证；预取 revision 与 thumbnailVersion 同时比较/持久化。
- 补 A→A/A→B→A、实时补传交叉、三库同路径、恢复/失败重试、并发重建、缩略图替换及客户端缓存键回归。三端 SDK 重生并更新 sdk.lock；Go 全量测试、vet/lint、Linux amd64 构建、Web 构建和 Android App 编译/32 项定向单测通过，关键服务端回归连续三轮通过。Android core:data 全套有 Windows DataStore 文件替换失败，包含未改动的暂存仓测试；不声称全端全绿或真实 NAS/跨卷/掉电验收。只使用隔离合成数据，未操作日常实例。
- ADR-0040 与领域规则、API 导读、架构、交接、能力地图、服务端/Android 说明同批同步。原件 mtime 上传保留、完整灾备、切服务器上传目标验收仍为另列缺口。

## fix(server): 保护扫描身份与文件恢复重试，收紧链接访问（2026-10-19 第五百三十八笔）

执行 AI：GPT-6.1 Sol（Codex，执行子代理；主代理复核）

- 修复扫描入库/遍历失败时仍以不完整seen删除资产及关联：失败停止破坏性差集、已提交变更照常广播。
- 扫描探测留锁外，单文件写入/逐条对账共用filing库锁；复核现行身份、路径及文件状态，只消费扫描自身新建的候选。候选按size+mtime分桶排序、逐次复核；合并删除与身份迁移为事务，失败回滚不丢候选关联。增量删除同样在锁内核实文件仍不存在。
- 路径层逐段拒绝符号链接/junction；原件读取与可行的库内写入改用os.Root。目录树、扫描、缩略图预热和本机同步补横向检查。保留跨根搬移/硬链接快路径，外部恶意换链与ffmpeg路径输入仍有已记档窗口。
- 恢复先原子写meta意图，库锁内搬回/重建原ID后才删meta；失败/普通进程重启后可按同一落点重试，扫描显式避让。意图索引缓存的重建发布与失效互斥，清空/物删/清扫均失效；到期清扫跳过未完成恢复。跨卷复制保留mtime，异常版本保留文件及meta。
- 续传源保留到入库成功或明确清理：同卷硬链接预置、不可用则复制，会话保存最终落点。封存后拒绝PATCH；同会话complete/Drop/Close串行，清扫避让在途提交；失败重试不产生重复文件。会话仍不跨重启持久化。
- 增加原审计5场景正式回归、合并事务回滚、扫描不吞并并发上传身份、恢复失败后重启式扫描避让、封存不改源、Drop/Close等待完成、复制mtime保持等测试。未修改协议/schema/SDK锁，未写入日常实例或真实媒体。
- 文档：新增ADR-0039，更新SECURITY、DOMAIN_RULES §9、ARCHITECTURE、GUIDE_API及server说明。真实NAS/双卷/断电/磁盘满验收尚未执行。
- 主代理补充需求复查并更新HANDOVER/CAPABILITY_MAP：当前用户尚无NAS，近期按手机单机/PC过渡验收；新增确认但未修的原生备份回导重复计数、多库导出键冲突、恢复后日统计缺失、重建统计覆盖并发增量、缩略图内容版本与缓存不自洽五项分别入待办。另记录源mtime保留、完整灾备和切服务器时上传任务归属的设计/验收缺口，不擅改既定领域口径。
- 验证：主代理独立复跑后端全量测试、go vet、lint（0 issues）及Linux amd64交叉构建通过；本机CGO关闭，race未运行，不替代跨平台CI或实际NAS验收。代码未提交/推送。

## chore(desktop+web): 清理桌面端 mpv 内核死代码与脚手架残留——全端统一收敛 ArtPlayer 播控（2026-10-17 第五百三十七笔）

执行 AI：Gemini-3.8-Flash（主代理）

- **背景与根因剖析**：
  - 用户询问 Web 与 App 画质增强及桌面端媒体内核现状，指出桌面壳早已在第四百九十五笔（`ee3d86d3`）用户拍板回归与 Web 端完全一致的标准播控，为何代码库中仍残留 mpv 遗留痕迹。
  - 实查确认：
    1. 前端在 495 笔全面遵循 ArtPlayer 官方规范重构后，不论是纯浏览器/PWA 还是 Tauri 桌面壳，实际渲染与交互已 100% 运行标准 HTML5 `<video>`（ArtPlayer）；
    2. 但桌面壳 Rust 后端 `desktop/src-tauri` 仍保留 `mod mpv;`、`src/mpv/` 模块、`permissions/mpv-commands.toml` 及 `setup-mpv.ps1`；前端 `video-player.tsx` 中保留防守性 `closeOrphanMpvInShell()`，文档 ADR-0036 与 `PLUGIN_PLAN.md` 处于未归档/滞后状态，给后续协作造成认知混淆与死代码包袱；
  - 用户明确指令：“清理掉吧”。
- **改动内容**：
  1. **桌面壳 Rust 后端彻底清理**：
     - 彻底删除 `desktop/src-tauri/src/mpv/` 目录（`player.rs`, `commands.rs`, `ffi.rs`, `win32.rs`, `mod.rs`）；
     - `desktop/src-tauri/src/main.rs` 移除 `mod mpv;`、`tauri::generate_handler!` 中的 5 条 mpv 命令、`.manage(mpv::commands::MpvState::default())` 状态挂载与退出时的 `close_session` 回收调用；
     - 彻底删除 `desktop/src-tauri/permissions/mpv-commands.toml`、`desktop/src-tauri/setup-mpv.ps1` 与 `desktop/PLUGIN_PLAN.md`；
     - `desktop/src-tauri/capabilities/default.json` 移除 5 项 `allow-mpv-*` 权限声明；
     - `desktop/.gitignore` 移除 `src-tauri/mpv/` 路径；
     - `desktop/README.md` 移除 `原生播放内核（libmpv）` 章节，更新为全端统一使用标准 ArtPlayer 的说明。
  2. **Web 前端防御性死代码与未引用模型清理**：
     - `web/src/components/media/video-player.tsx`：移除 `closeOrphanMpvInShell()` 定义与 `useEffect` 中的调用，移除遗留未引用的 `nativeTitle?: string` 属性；
     - `web/src/pages/AssetDetailPage.tsx`：移除向 `VideoPlayer` 传递的 `nativeTitle` 属性；
     - `web/src/lib/engagement-reporting.ts` 与 `engagement-reporting.test.ts`：移除仅由早期 mpv 轮询使用的未引用类型定义（`NativeTrackInfo`、`NativeMediaProbe`、`NativePollStatus`、`NativePollSignal`）与 `nativePollSignal` 纯函数及相关测试用例；
     - `web/src/lib/player-labels.ts`：更新注释描述，彻底去除历史 mpv 术语。
  3. **文档与架构决策同步**：
     - `docs/adr/0036-desktop-playback-kernel-libmpv.md` 状态正式标记为**已废弃（Superseded / Retired）**，详细记档回归 ArtPlayer 的决策历程与本笔清理范围；
     - `docs/adr/INDEX.md` 同步更新 ADR-0036 状态为已废弃；
     - `docs/CAPABILITY_MAP.md` 桌面播放内核行同步更新为“全端统一收敛标准 ArtPlayer（已有）”。
- **自测验证**：
  - Web 端：`npm test` 29 个测试套件 311 项单元测试全绿通过；`npm run build` 打包成功（零类型错误、零警告）；
  - 桌面壳：`cargo test` 17 项单元测试全绿通过（零错误、零 dead_code 警告）。
- **文档**：`docs/adr/0036-desktop-playback-kernel-libmpv.md`、`docs/adr/INDEX.md`、`docs/CAPABILITY_MAP.md`、`desktop/README.md`、`docs/CHANGELOG.md`（本笔）。

## fix(server): 后端安全与可靠性加固批次（2026-10-17 第五百三十六笔）

执行 AI：DeepSeek-Flash（主代理；4 路子审计代理并行产出 security/commits/quality/ops 报告，主代理逐条复核并修复）

- **来源**：2026-10-17 全仓审计（四路并行：后端安全 / 最近提交 / 整体质量性能 / 运维供应链，报告见 gitignored `build/audit/`）。本笔只落地**高价值、低风险、不改协议、不动用户既定免密用法**的项；P0/P1 无确认缺陷，不做全量重构。
- **安全修复（服务端）**：
  - **JSON Content-Type 强制**（`internal/httpapi/errors.go` `requireJSONContentType`）：全部 `decodeJSON` 端点拒绝非 `application/json` body。关闭"任意网页用跨站简单请求（no-cors+text/plain）向未初始化 setup 抢注密码 → 用户被 409 锁死"的伪造面，也堵住未鉴权方消耗 auth 限速配额的旁路。合法 SDK 本就声明 JSON，三端零适配。
  - **auth 限速计数顺序修正**（`authapi.go`）：limit 计数移到实际进入凭据校验之前；`/auth/dev-login` 在 dev 模式关闭时先 404（不消耗配额）、再限速、再共享密钥（顺序=404→限流→401，SECURITY 同步）。修复"未鉴权请求可把共享窗口打满、令管理员 setup/login 永久 429"的可用性 DoS。
  - **明文 token 响应 `Cache-Control: no-store`**（setup/login/dev-login 统一 `writeAuthToken` 单点）。
  - **ffmpeg/ffprobe 输入协议白名单**（`thumbnail/ffmpeg.go`、`blackframe.go` 全部 `-i` 调用点加 `-protocol_whitelist file`）：库内媒体是不可信输入，阻断 HLS/concat 等 demuxer 的外部 URL/本地文件引用面（SSRF、本地文件读取侧信道、解码器暴露面收窄）。新增 `protocol_test.go` 锁参数形态。
  - **local-sync trigger 409 脱敏**（`localsync.go`）：通道级 lastErr 可能含库根/数据目录绝对路径，改为固定文案 + 详情只进服务端日志（SECURITY 红线 7 例外收口）。
- **可靠性修复（服务端）**：
  - 新增 `internal/safego` 包：长驻后台 goroutine 统一 panic 兜底（扫描、缩略图预热、缩略图工作池/后台续生、回收站清扫、上传清扫、本机同步轮询、备份调度、推荐预热、库变更订阅、自定义出处重算、启动自愈），单个任务崩溃不再终止整个服务进程；工作池任务级 recover 后 worker 继续消费。
  - 优雅关闭：`srv.Shutdown` 超时不再 `os.Exit(1)` 跳过清理，改为 Warn + `srv.Close()` 断开剩余连接（SSE 长连接场景）后继续关闭工作池/上传会话/总线/数据库，退出码恢复 0。
  - HTTP Server 增 `IdleTimeout=120s` + `MaxHeaderBytes=64KB`（大文件直链不受影响；空闲连接与超长 header 的内存/fd 放大面收窄）。
  - SQLite 连接池显式上限（`MaxOpenConns=6`/`MaxIdleConns=2`；DSN `cache_size(-32000)` 是每连接 32MiB，先前不限连接数与手机单机形态内存冲突），facets 六路聚合改 `errgroup.WithContext` + `SetLimit(4)`（失败即取消其余）。
  - 缩略图预热单飞（`runWarmupOnce` + `warmupMu.TryLock`）：周期兜底与扫描后触发不再并发整库 `os.Stat`/重复 Submit。
  - 回收站恢复路径失败残留清理补 `ErrMoveDidNotCreateTarget` 双哨兵（ADR-0038 决策 2 三调用点一致；此前漏判，窄窗口下可能误删外部并发创建的完整目标文件）。
- **数据库（migration 0018，只加文件）**：`idx_asset_authors_author(asset_id→author 反查)`、`idx_asset_tags_tag(tag_id)`、`idx_likes_day(day, asset_id)`、`idx_assets_mtime(mtime DESC, asset_id DESC)`——补齐作者/标签列表与重建、周期榜单/推荐窗口聚合、默认浏览排序（2026-09-17 起=fileDate=mtime）的索引缺口；down 为对称 DROP INDEX。`store_test` 增加 0018 索引存在性用例与 down/up 链验证。
- **用户拍板保留项**：日常启动链 `_server-common.cmd` 的 `QIMENG_AUTH_DEV_MODE=1` 与 `:8420` 全接口监听**不改**（用户明确"免密是我自己再用"）；SECURITY 将其记为风险接受项并保留生产部署必须关闭 dev 模式的既有约束。
- **本次未做（审计报告已列，留待后续批次）**：httpapi 编排下沉（ADR-0019，L）、`GET /authors` view_count 预聚合、Web/Android 大文件拆分、Docker 非 root/digest 固定、CI Actions pin SHA + permissions、Dependabot/govulncheck 门禁、scanner 软链跳过、uploadsess 总量上限、DB 文件 0600、访问日志。均为 M/L 成本或需部署侧验证，避免本批动到用户日常形态。
- **自测**：`go build ./...`、`go vet ./...`、`gofmt -l .` 全清；`go test ./... -count=1` **20 包全绿**（新增 safego 包与 4 组加固回归测试：Content-Type 拒绝/放行、dev 404 不消耗限速、token no-store、回收站恢复 `ErrMoveDidNotCreateTarget` 清理跳过、0018 索引与 down/up 链）。
- **CI 回修（同日）**：首次推送后 server job 的 `go test ./... -race` 暴露两处数据竞争——① 备份调度接入 `safego` 时闭包延迟读取 `m.schedInterval`（锁内写 vs goroutine 读），改为锁内快照 `intervalSnapshot` 后传参；② 新增 `safego` 单测用共享 `bytes.Buffer` 写读，改为互斥 `syncBuffer`。本地全量测试通过，race 结论以 CI 复核为准。
- **文档**：`docs/SECURITY.md`（新增「2026-10-17 安全加固」节 + 红线 6/7、auth 限速、dev 校验顺序、上传 ffmpeg 白名单、作者镜像能力口径、镜像构建现状勘正）、`docs/CHANGELOG.md`（本笔）、`docs/HANDOVER.md`（服务端现状与遗留缺口）、`docs/GUIDE_API.md`（端点计数口径勘正 74→75/94→97）、`.github/workflows/build-apk.yml`（过时的 sdk.lock 红灯注释更新）。



## feat(app): 搜索结果增加类型与分区选项卡 + 悬浮底栏左右空白防穿透强化（2026-10-16 第五百三十五笔）

执行 AI：Gemini-3.8-Flash（主代理）

- **背景与需求**：
  1. 用户体验 App 搜索功能时反馈搜索结果无选项页，提出参考 B 站及本项目 Web 端（`SearchPage.tsx`）的选项页逻辑，在搜索完成后为 App 加上符合当前 Aurora Glass 视觉的选项卡（支持看具体内容分区与媒体类型）。
  2. 用户真机实测反馈悬浮底栏（FloatingTabDock）虽然底部间隙已不误触，但左右边缘与胶囊端部仍存在微小漏点误触到背后媒体卡片，要求沿用此前 `consumeTaps()` 隔离方案对左右两侧做完整防穿透优化。
- **设计与实现**：
  - **搜索选项卡（对齐 Web 与 B 站）**：
    - **视觉组件**：复用既有通用组件 `QimengChipRow` 与 `QimengPill`（ADR-0008 / ADR-0031）；结果态 `ResultPhase` 顶部固定展示双排胶囊栏（行 1：媒体类型；行 2：内容分区），与下方支持双指缩放的媒体网格分离。
    - **媒体类型四档**：综合（`mediaType=null`，缺省）、视频（`VIDEO`）、动图（`ANIMATED_IMAGE`）、图片（`IMAGE`），对齐 Web `TYPE_TO_MEDIA` 映射。
    - **内容分区三档**：全部（`includeCos=true, cosOnly=null`，缺省）、常规（`includeCos=null, cosOnly=null`，服务端默认排除 COS）、COS（`cosOnly=true, includeCos=null`），遵循 `DOMAIN_RULES.md` §6 映射口径。
    - **交互与重置**：切换选项即时滚动回顶（`gridState.scrollToItem(0)`）并重拉首屏；新词搜索或返回清词重置为默认档位；重复点击当前项操作幂等。
  - **悬浮底栏左右空白防穿透强化**：
    - **根因定位**：悬浮底栏在胶囊两端内部保留了 18dp（`SlotInnerPadding`）槽内呼吸间距，且 `DockBody` 本身及外层 `Row`、`Column` 容器此前未挂载触摸隔离，手指按压偏向胶囊两端或左右外距交界处时，点击穿透至底层的媒体瀑布流。
    - **全链路隔离**：在 `FloatingTabDock` 的外层 `Column`、`Row`、`DockBody` 及内层 `DockItemsRow` 容器上全链路挂载 `consumeTaps()`（`detectTapGestures {}` 空实现只消费点击，不拦滑动/拖动手势）；各 Tab 项 `Modifier.clickable` 按 Compose 规则优先消费自身点击，外围与端部呼吸空隙的误触被彻底拦截。
- **修改文件**：
  - `android/feature/search/src/main/res/values/strings.xml`：新增类型与分区选项文案。
  - `android/feature/search/src/main/java/media/qimeng/app/feature/search/SearchViewModel.kt`：新增 `SearchMediaType` 枚举、UiState 筛选字段及 `selectZone`/`selectMediaType` 动作。
  - `android/feature/search/src/main/java/media/qimeng/app/feature/search/SearchScreen.kt`：新增 `SearchFilterOptions` 组件并置顶挂入 `ResultPhase`，监听筛选切换回顶。
  - `android/core/ui/src/main/java/media/qimeng/app/core/ui/glass/FloatingTabDock.kt`：外层容器及 DockBody 补齐 `consumeTaps()` 隔离。
  - `android/feature/search/src/test/java/media/qimeng/app/feature/search/SearchViewModelTest.kt`：新增 4 组单元测试锁定分区/类型/重置/幂等逻辑。
- **自测与装机**：
  - 单元测试：`:feature:search:testDebugUnitTest` 25 例全绿，`:core:ui:compileDebugKotlin` 与 `:core:ui:testDebugUnitTest` 编译与测试全绿；
  - 打包与装机：`:app:assembleRelease` 构建通过（Release APK 26.4MB），通过 ADB 原地覆盖安装至真机（NX721J），保留全部应用数据与登录状态。
- **文档**：`docs/HANDOVER.md`、`docs/CHANGELOG.md`。

## fix(server+web+app+ci): 可靠性加固批次择优并入——跨端文件移动不覆盖既有目标（ADR-0038）+ 回收站净化 + Android 归档/编辑页一致性 + Web 确认与离开保护 + Windows 原生 filing CI（2026-10-15 第五百三十四笔）

执行 AI：DeepSeek-V4.1-Flash（主代理；文件级对抗审查由三个审查子代理并行完成）

- **来源与并入口径**：内容取自 `codex/reliability-hardening-ui-2026-10`（Draft PR #1）——按「择优并入」落地：不并入其 91 提交历史（英文提交与房规格式不符），`git merge --squash` 落地、唯一冲突是 CHANGELOG 笔号撞车（分支 529-532 vs 本地 529-533），按本笔重写解决；剔除 `docs/AI_HANDOVER.md`（仓库唯一交接文档是 `docs/HANDOVER.md`；分支新增的平行交接文档含入库即过期的快照与面向 AI 的合并禁令，属长期漂移源）。分支四笔 CHANGELOG 的内容并入本笔。
- **审查发现并修复的真缺陷（Android 归档快路径退化，本笔修复）**：分支以 `Files.getFileStore` 比较源/目标判定同卷，但 AOSP `UnixFileSystemProvider`/`LinuxFileSystemProvider.getFileStore` 是 Android-changed 的**无条件 `SecurityException`**（SELinux 策略，本机 android-36.1 源码实证）——`runCatching` 吞掉后 sameStore 恒 false，**真机上每次归档都退化为整文件复制**（大文件慢一倍 + 瞬时双倍空间 + 空间紧张时归档失败），且 JVM 单测结构上不可见（桌面 getFileStore 正常，分支 CI 全绿照不出来）。修复：`moveOrCopyNoReplace` 改「`exists()` 前置检查 + `File.renameTo` 优先（同卷瞬时、跨卷无害失败）→ 失败且目标仍空位才走受控复制」，去掉平台探针使真机与 JVM 同路径；同时不采用「跨卷直接交给 `Files.move`」（libcore 内部 copy+删源，绕开唯一临时文件+无覆盖发布的受控路径）。新增「首次归档走改名快路径不退化为整文件复制」mtime 断言测试，并经变异验证（临时强制复制路径时该用例确实翻红）。
- **服务端（ADR-0038，filing/httpapi）**：`MoveFile` 覆盖语义退役——`Lstat` 预检拒绝既有目标（哨兵 `ErrMoveTargetExists`，Unix rename / Windows MoveFileEx 的静默替换口子关闭）；跨卷 copy 回落 `O_CREATE|O_EXCL` 独占创建作第二道；非跨卷 rename 失败包装 `ErrMoveDidNotCreateTarget` 并保留底层错误（`errors.Is` 可判）；回收站三调用点的失败残留清理在两种哨兵命中时跳过（不误删非本次创建的目标）。filing 新增 3 例测试（冲突拒绝/无目标创建标记/copy 独占）。调用方语义变化均失败安全——本机同步「落位后移回」在源被重投放时改为保留库位件+告警、下轮重新观测；归档链路（ADR-0030 修订1「同名只留最新」先删后搬）与之相容，已用合并树全量 Go 测试实证。
- **Android 其余（归档与编辑页）**：归档（归档根/uploaded/镜像重命名）统一无覆盖语义——同名异容加序号、同内容保留既有目标只删重复源、源=目标路径幂等成功；跨卷受控复制=唯一 `.part` 临时名 + 无覆盖发布 + 失败只清本次临时文件；对外部 ContentProvider displayName 做文件名安全校验（分隔符/控制字符/Windows 非法字符/点段尾随点），归档目录拒绝符号链接别名与根外路径；`readFully` 短读修复（原实现在 offset>0 时越界抛异常被吞成「内容不同」）。编辑页：来源基线未就绪/读取失败 fail-closed（禁用整体替换防空数据误覆盖 TXT）、多步保存按成功步骤记账（重试只补失败项：重命名/关联/逐来源清脏）、保存中锁表单 + 顶栏与系统返回确认、COS 派生作者过滤（`isCos`/`cos_` 不进常规作者 PUT）。测试：InboxFileStoreTest 26 例、AssetEditViewModelTest 29 例、EventDbMigrationsTest 新增 3 例。
- **Web**：ConfirmDialog busy（成功才关闭、失败保留上下文可重试、执行中禁重复提交）；备份导入补重入守卫（与同批其余三处对齐）；资产编辑页多步保存只重试失败步骤 + 保存中禁用表单 + SPA 路由拦截与 beforeunload 未保存提示 + 来源基线未加载/失败禁止编辑；回收站恢复/彻底删除/清空的确认失败可重试，单条在途时批量入口一并禁用（`singleOpPending` 单源收口）；错误提取统一 `batchFailureReason`（Error/string/{message,status,code} 形态；仅 ≥400 打 HTTP 标签，杜绝「请求失败（HTTP 200）」），残余 `String(err)`（作者页/库注册页/设置页）清零；`AssetEditPage` 的 blocker ref 同步移入 effect（不在 render 期写 ref）。测试：vitest 321 例全绿（新增 status<400 边界 1 例）。
- **CI**：新增 `server-windows-filing` job（Windows 原生 runner 跑 `go test ./internal/filing`，覆盖 Linux job 不编译的 `move_windows.go` build-tag 路径——Windows 双盘为本机日常形态，2026-10-11 已有该路径漏判事故）；同 PR 连续推送自动取消旧运行（`cancel-in-progress` 收窄为仅 PR，master 运行不取消）；README/ARCHITECTURE/PROJECT_PLAN 同步七 job 口径（并注明本地 `make lint` 不替代完整 CI）；Go 补丁版本锁 1.27.0 未动。
- **测试与验证边界（诚实记档）**：① `EventDbMigrationsTest` 是 SQL 合约测试（锁定 1→2/2→3 迁移链与 ALTER 语句、复用生产迁移对象），**不等于**真实 SQLite 迁移验证——旧库文件真实迁移与历史数据保留仍待补齐（HANDOVER §5 待办）；② 真实双挂载点跨卷行为仍待部署环境端到端验收（SECURITY 矩阵）；③ TOCTOU 未消除——存在性检查与改名之间无跨进程原子保证，SECURITY 按事实记档、不宣称已解决。
- **自测**：server build/vet/gofmt + `go test ./... -count=1` 19 包全绿；Android `:core:data`/`:feature:detail` testDebugUnitTest 全绿（含快路径门禁的变异验证与还原）；Web tsc -b + vite build + vitest 321 例全绿；未跑 Android assembleDebug/lintDebug 与桌面壳（本批未涉桌面壳，Android 全量门禁留 CI 复核）。
- **文档**：ADR-0038（新增）+ `adr/INDEX.md`；`SECURITY.md`（「文件移动/回收站的跨卷安全边界」矩阵 + 「客户端归档与服务端文件移动」节）；`HANDOVER.md`（头部 + §4 三端条目 + §5 待办两条 + 记档）；`README.md`/`PROJECT_PLAN.md`/`ARCHITECTURE.md`（七 job 口径）。

## fix(server+api+app+web): 作者管理族三端点（新建/改名/删除）+ 回写身份自洽修复（「选了来源后保存无效」事故）+ 手机端作者数据收敛（2026-10-13 第五百三十三笔）

执行 AI：DeepSeek-V4.1-Flash（主代理）

- **事故与根因（用户 2026-10-13 实测双症状）**：① 编辑页给作者加来源「选了后保存无效」（回读恒空）；② 作者列表出现多个零关联空壳变体（同日连造 3 个 hornyherring 变体）。根因链：按名新建输入 `hornyherring / Horny Herring Studios`（单空格斜杠）未被别名拆分（旧口径只拆两个以上空白）→ 落成**单别名含「 / 」字面**的作者行（id=整串派生 `hornyherring__horny_herring_studios`）；而回写路径 `displayNameAliases` 又盲目按「 / 」拆分显示名重建编号行 → 写出的块派生 id = `hornyherring`（错误身份）→ 来源/作品写进错块、按作者行 id 回读永远为空；每次保存再向片段追加一个错误身份重复块（实测片段积 11 个）。
- **修复（server，回写身份自洽不变量）**：新增 `authoring.AliasesForAuthorID(displayName, authorID)` 为程序化写块别名列表唯一来源——形态①按别名拆分归一、形态②单别名含斜杠字面整串保留，两形态依序尝试并保证「块回读 id == 目标作者行 id」；都推不出（病态存量）拒绝写入转 400 提示先改名。调用点全改走（`ReplaceAuthorSources`/`appendAssetWorkLines`/`Apply`）。`CanonicalAuthorNames` 拆分口径修订：两个以上空白 + 「 / 」同视分隔（显示名往返恒等，无空白斜杠不拆）——按名新建不再产生单别名斜杠字面。测试：authoring 四函数回归（含事故形态往返锁定）、authorattach 事故回归（单别名斜杠行写来源 → 块身份自洽 + 回读可读）。
- **作者管理族三端点（ADR-0024 修订 5，草案备选「只改 DB」被否：TXT 唯一真相）**：`POST /authors` 按名新建（仅作者行不关联文件；规范身份命中复用/未命中新建；非法名 400 带原因、COS 撞名 409）；`PUT /authors/{authorId}` 改名（全部片段块编号行原地重写 + 身份迁移：followed/created_at/origin 随迁、asset_authors 关联改挂、上传条目改指；同身份幂等；撞其他既有身份 409 不合并）；`DELETE /authors/{authorId}` 删除（TXT 块 + 关联 + 行三清、条目同清、**文件保留**〔DOMAIN_RULES §6〕；不触发统一重建，终态等价）。文本手术纯函数 `authoring.RemoveAuthorBlocks`/`RewriteAuthorBlockNames`（与解析器共用块边界口径）。编排下沉 `authorattach/manage.go`（ADR-0019）；sqlc 四条查询（DeleteAuthor/UpdateAuthorDisplayName/RenameAssetAuthors/CopyAuthorWithNewID）；httpapi 新文件 `authors_manage.go`（哨兵映射 404/400/409；`GetApiV1Authors` 行装配抽 `authorFromListRow` 共用防漂移）。
- **三端接线**：openapi 三端点 + `make sdk` 三端重生成 + sdk.lock（257 条）；**App**：`AuthorRepository` 扩 createAuthor/renameAuthor/deleteAuthor（4xx 语义化拒绝映射 `AuthorManageRejectedException`）+ `AuthorViewModel` 对话框状态机（新建/改名/删除三态；成功关闭+重拉+提示横幅；失败保持对话框透出原因；输入 200 rune 封顶协议双写）+ `AuthorScreen` 行尾「⋮」下拉菜单（改名/删除）+ 搜索行「新建作者」胶囊 + 删除确认（明示「关联文件保留」）+ 无 MoreVert 图标补入 core:ui；**Web**：`use-authors` 三 hook（失效作者族 + 改名/删除连带资产族）+ `AuthorsPage` 行内 pill（改名/删除，stopPropagation）+ 排序行「新建作者」+ 共享件抽出 `components/ui/prompt-dialog.tsx`（词表维护弹窗同形态第 2 次出现即抽，词表页转调）与 ConfirmDialog danger 二次确认。测试：App `AuthorViewModelTest` 6 例全绿；Web vitest 320 全绿。
- **手机端作者数据收敛（用户库，run-as 直读内嵌库 + 离线手术，改动前全库备档）**：三空壳（Horny Herring Studios / hornyherring / hornyherring_Horny Herring Studios 变体）按「以手机端为准」口径收敛——片段 126 号块原地改双别名 `hornyherring  Horny Herring Studios` + 补作品行「守望先锋  天使 15.mp4」+ 来源区保留并补入用户反复试加未果的 `r34`；块 128~140 十一个乱块全清；DB 三行删除 + 关联改挂（作者总数 150→147、片段 1625→1554 行）；隔离实例真重建验证终态稳定（唯一 hornyherring、1 文件、来源读写恢复）；**PC 端随后对齐同名**（同文件经 PC 服务端 import-txt 通道把块 126 改双别名，id/关联不动——两端显示名一致、id 一致）。手机端镜像文件（作者总表.txt）同步推送为修复后内容。
- **验证**：server 全量测试 19 包全绿；authorattach/authoring/httpapi 新测全绿；App `:feature:author` 单测 + 编译过；Web build/lint（0 error）/vitest 过；gofmt/go vet 干净。
- **联带**：手机端 debug 包覆盖安装（同 applicationId 同 debug 签名保数据，换取 run-as 直读内嵌库；最终以 release 包回归装机）。

## fix(app+web): 作者管理搜索大小写不敏感——大写作者名小写搜不到（2026-10-12 第五百三十二笔）

执行 AI：DeepSeek-V4.1-Flash（主代理）

- **背景（用户实测反馈）**：作者管理页搜作者时大小写敏感——作者名多含大写（`BubbleB`/`Regina3D`/`SKRevenant` 等），输全小写（如 `bubbleb`）零命中；中文不受影响。
- **修复（两端同口径；纯子串语义不变、仅折叠大小写）**：App `applyAuthorRows`（core:model 纯函数，关键词 `trim()` 后 `contains(kw, ignoreCase=true)`）；Web `AuthorsPage` 关键词双侧 `toLowerCase()`。扫描面：App 客户端关键词过滤仅此一处；Web 历史页 `groupHistory` 早已双侧 lowercase（无改动）。**不改分隔符语义**——「 / 」连接的显示名仍按段子串匹配。
- **测试**：`AuthorRowsTest` 新增「名字搜索大小写不敏感」（小写命中大写名/大写命中/混排/别名段整体小写，6 例全绿）；Android 门禁 `assembleRelease + testDebugUnitTest + lintDebug` BUILD SUCCESSFUL；Web `tsc --noEmit` 零错 + vitest 320 例全绿 + `web/dist` 重建（服务端按磁盘托管 web/dist，无需重启）。
- **装机（用户拍板「用昨天的方法」后执行）**：release APK 同签名（SHA-256 `8f62ba55…`）原地覆盖装机真机（`adb install -r` Success，2026-10-12 21:0x）。内置修复走 release 包即可，无需 debug 包（debug 仅调试用途，装机口径不变）。
- **手机端数据同步（KassioppiaVA 收尾）**：手机侧《作者总表.txt》（`1/HHH/4 作者/`）已推送修正版（与电脑端逐字节同哈希，第 1046 行 `78  skrev  SKRevenant`）；**手机内嵌库（本机模式）仍是旧显示名**——内嵌服务端 dev 共享密钥每次拉起随机生成、不落盘不进日志（adb 侧无法走 API），收敛通道 = App「我的→数据管理→作者 TXT 导入」重导同一份 txt（用户执行，步骤已交付；App 内置库路径 `/data/data` 私有、adb 不可达，只读诊断亦不可行）。
- **文档**：CHANGELOG.md（本笔）、HANDOVER.md（头部更新）。
