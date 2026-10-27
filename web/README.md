# web/ — React Web/PWA

> 本端开发入口；全局规则按 [AGENTS](../AGENTS.md) 读取。交互查 [GUIDE_UI](../docs/GUIDE_UI.md)，视觉改动读本文设计语言/分层；背景按需查 ADR-0008/0031。

## 技术栈（定论，禁止擅改）

React + Vite + TypeScript(strict) + Tailwind CSS 4 + shadcn/ui + TanStack Query + PWA 插件。
动效零 JS 库（全 CSS / 原生 View Transition API，ADR-0031）。

## 设计语言（ADR-0031「绮梦流光 · Aurora Glass」，2026-10-02 起）

- **暗色优先**：无手动选择时默认深色（不再跟随系统）；浅色是完整度相同的「晨雾玻璃」变体。
  主题默认值在 `index.html` 首帧内联脚本与 `lib/theme.ts` 双写，改动须两处同步。
- **视觉规范源 = `src/tokens.css`（token 体系 v2）**：画布极光（`--qm-aurora`）、玻璃材质组
  （surface/strong/soft + glass-blur/saturate + glass-highlight）、文字四级、主色三件套、图表五色、
  阴影/圆角/动效时长/缓动/层级全 token 化；shadcn 主题变量在 `src/index.css` 从 `--qm-*` 反向桥接
  （shadcn ← qm 单向）。组件**只准消费 `--qm-*`**（旧名兼容别名仅供存量代码，新代码禁用）。
- **主题样式层 = `src/styles/glass.css`**（v1 `prototype.css` 已删）：类名契约与组件 DOM 不动，
  改视觉只动这个文件；backdrop-filter 不支持时经 `@supports` 整组退化为不透明面板（布局零变化）。
- 全局 `html { zoom: 1.1 }` 及其三处补偿（radix popper wrapper / 图片查看器 `--zoom-inverse` /
  `:fullscreen zoom:1`）是 load-bearing 实证修复，改动 zoom 须同步 glass.css 内注释标注的互指点。

## 分层纪律（铁律）

```
src/
├── api/        # make sdk 生成的客户端（禁止手改）
├── hooks/      # TanStack Query hooks：全部数据获取在此层
├── components/ # 纯渲染组件：禁止 import api、禁止业务规则
├── tokens.css  # 设计 token（颜色/玻璃材质/间距/圆角/动效/层级），组件只引用变量
├── styles/     # 主题样式层 glass.css（类名契约的视觉实现）
└── pages/
```

- 组件内出现 `fetch/axios/api.` = 违规
- `tests/architecture.test.ts` 随 `npm test` 用 TypeScript AST 自动检查 pages/components 的运行时 API 导入与直接请求；SDK 纯类型导入和非 API 组件懒加载允许。Node 源码检查/桌面契约测试位于 tests/，由 tsconfig.node 类型检查，不向浏览器代码引入 Node 全局类型。不把静态检查当作全调用链业务规则证明。
- 例外：`lib/upload-chunked.ts` 传输层 XHR 直连不走生成 SDK（abort 支持缺失+403 拦截器误伤，ADR-0028 记档）
- 上传链路 lib 内分工（2026-10-24 提取批）：队列状态（entries/XHR 句柄/取消旗标/恢复/订阅/终态推进）唯一持有在 `lib/upload-queue-store.ts`；「上传成功后挂靠」无状态服务（响应资产 id 解析、先 authors 替换后 sources append、失败转「已入库待挂靠」）在 `lib/upload-attach.ts`；分片传输在 `lib/upload-chunked.ts`；刷新持久化端口/装配在 `lib/upload-queue-persist*.ts`，IO 合并、失败重试与健康在 `lib/upload-persistence.ts`（不持传输状态）。纯提取，交互行为不变。
- 出现硬编码颜色 = 违规（一律 `var(--qm-*)`）
- 数据转换/业务判断放 hooks 或服务端，不进组件
- 设置的配置草稿/修订/提交快照由 `use-config-form` 单独持有；慢 PUT 只确认自己的版本，保存期间的新输入保留。保存前作废在途旧 GET，扫描参数仍仅持久化预留。
- 开发代理目标默认 8420；隔离调试实例可用 `QIMENG_DEV_PROXY_TARGET=http://127.0.0.1:<port>` 覆盖

## 开发与验证

仓库根 `make web-dev`、`make web-test`，测试/构建脚本以 package.json 与 Makefile 为准。日常由后端托管 web/dist，交付本地可见改动前 `npm --prefix web run build`。只验证受影响页面和行为；API/领域变化另补相关专题。修改 TopBar 时读 [desktop README](../desktop/README.md) 的 titlebar.js 类名/title/header 契约。

## 开发依赖安全

本轮收敛既有Dependabot候选：Vitest及配套mocker/spy 5.0.0→5.0.5（[上游修复说明](https://github.com/vitest-dev/vitest/releases/tag/v5.0.5)），锁文件同时采用其匹配的传递依赖；fast-uri 3.1.8→3.1.9（[URI解析安全修复](https://github.com/fastify/fast-uri/releases/tag/v3.1.9)），serialize-javascript 7.1.0→7.1.4（[序列化修复](https://github.com/yahoo/serialize-javascript/releases/tag/v7.1.4)）。均属现有开发/构建链，不增加应用运行时依赖，不改接口、UI或业务规则；合并后的锁文件须通过当前head的干净安装、SDK生成、Web构建/单测/浏览器及安全门禁。

新增 `npm run test:e2e` 使用锁定的 Playwright 1.64.1 和配套 Chromium，仅属开发依赖，不进入应用运行时；按 [官方 webServer](https://playwright.dev/docs/test-webserver) 与 [网络拦截](https://playwright.dev/docs/network) 使用静态预览、合成 API，无真实后端。CI在构建后运行慢保存、失败重试、迟到查询/焦点刷新和上传存储初始化失败→本地IO重试的四个DOM场景，失败保留trace/截图；其他交互矩阵见任务书§11。当前会话约定构建/测试只走云端；浏览器验证、纯函数单测与设备验收分别记录。保留现有 Vitest/AST 门禁，未覆盖交互见 HANDOVER。

PWA 图标生成器 2.x 与 vite-plugin-pwa 2.x 配套升级；后者的 peer 范围才接受生成器 2.x（[上游发布说明](https://github.com/vite-pwa/vite-plugin-pwa/releases/tag/v2.0.0)）。保留现有 ESM 配置、图标预设、manifest 与自动更新规则，升级后验证干净安装、SDK 生成、构建和隔离目录图标生成；不使用 force/legacy-peer-deps 绕过不兼容的 peer 约束。

shadcn/ui 组件及 components.json 保留；将唯一被构建消费的 dist/tailwind.css 按 shadcn@4.22.1 原样保存在 src/styles/shadcn-base.css（带原 SHA-256 与上游 MIT 许可），移除其 CLI 开发依赖，消除其 fast-glob/ts-morph/braces 漏洞链，避免无补丁传递依赖长期阻断安全门禁。现有组件和样式保持原实现，不自研替代；上游提供独立且可审计的样式包时复查并退役此快照。需要重新生成组件时先评估并固定已通过审计的 CLI，不执行未锁版本的在线命令。生成器链固定的旧 js-yaml 用 overrides 提升到 4.3.2，依据 [上游安全补丁](https://github.com/nodeca/js-yaml/releases/tag/4.3.2)；父包解除固定后复查并移除覆盖。协议仍用既定 openapi-ts 版本，不按 npm force 建议回退主版本。扫描结果及边界见 HANDOVER。

## 维护仪表盘

维护页使用紧凑概览：服务端运行时长、接收/发送小趋势、CPU小圆环、内存与存储条、文件管理/回收站、上传/异常快捷入口。流量卡显示近两分钟趋势、峰值、累计与采样点提示；重复的完整网络图已移除。客户端异常表继续常驻，服务器目录自动导入移入上传页高级入口。颜色/玻璃材质仍消费既有 token，不新增主题或运行时依赖，不改 TopBar/桌面窗口契约。

`use-maintenance-monitor` 持有展示转换与最近两分钟、最多60点的网络窗口；成功采样按实际时间差求速率，零累计仍有效，计数器/运行时长回退重新取基线。维护页不再订阅目录自动导入；`ServerImportPanel` 在上传页初次读取状态，展开时2s轮询、折叠时停止定时轮询，仍可手动触发/看失败与最近成功。未取得资源显示缺省符号，失败明确提示并可重试，不将旧读数或缺失值称为实时健康。存储为状态接口监控目录条目的原合计，明细保留全部路径；同卷目录可能重复计入，不称全机去重物理容量。

## 数据管理工作区

入口卡不带右上角跳转箭头，保留左侧图标及整卡链接。侧栏返回通过 `use-sidebar-back` 只消费壳内确认的历史键（最多64条），到首页后停用；直达、刷新或未知前序记录回落首页，不以全局history长度判断、不依赖Router私有state。未保存草稿仍走原路由blocker，浏览器原生后退不受该按钮约束。

入口按入库整理、作者词表、迁移恢复分组，管理子页共用 `ManagePageHeader`。上传与已入库文件整理分开，后者在 `/app/maintenance/files/browse`；原有上传、库、词表、TXT、备份、回收站路由保持。目录整理按 `nextCursor` 手动续页，每页200条，显式库下包含COS，换库/目录不复用旧目录文件占位；批量选择明确只作用于已加载项。列表失败显示重试，不当空列表。

`use-upload-target` 持有目标/挂靠草稿；目标列表或配置未知/失败、目标库停用或移除、服务端暂停接收时只阻止新入队，已冻结的队列仍由原 store 持有。作者挂靠只读 `capabilities.authorAttach`，更换作者清空其批次来源。注册库与触发扫描在 `useRegisterAndScanLibrary` 分别记账，扫描失败只重试扫描，不重复注册。移除库失败保留确认目标，停用需说明并确认；库变更成功共用失效映射。

上传内部采用位置/关联设置与添加区双列、任务全宽的工作台，窄窗口顺序排布。`UploadDestinationCard` 默认库根，按需展开/读取目录并选择后收起；`UploadMetadataCard` 默认折叠，折叠不丢作者/来源，右侧摘要始终显示本批目标。回车产生没有已有作者ID的意图时阻止新入队，明确要求联想选定或清空，避免静默漏关联。`UploadQueueList` 以响应式任务卡替代六列表格，目标/作者/来源取入队快照；进度、失败全文、重选/移除及持久化降级重试仍接原store。清理已结束记录只清任务记录，不删已入库文件；没有新增暂存/二次开始步骤、自动重传或上传协议。

来源词表/镜像初次查询失败不可保存空默认，保存期间锁定编辑、失败保留草稿；来源与镜像共用一个未保存离开/关闭保护，可明确放弃草稿。TXT/备份读取期间防重复选择；TXT移除先确认重建关联的影响，失败保留确认框。当前v2验证、TXT冲突keep/remove及文件安全口径均不变。

## 数据新鲜度

库变更、成功备份导入及 SSE 重连共用 query-invalidation 的失效映射，统计/排行/历史与推荐、资产同时校验。staleTime 只标陈旧，不是定时轮询。推荐初始 seed=0 与服务端 Web 预热互指；60 条分页共享最多 60 秒的顺序快照，跨失效边界仍按 ID 去重。推荐/排行按 `X-Qimeng-Next-Offset` 续页（-1 到底），短页/空页仍可继续；仅旧服务端无头时回退页长。每页完成后恢复触底观察，空页/全重复页不依赖网格高度变化才能继续；元数据留 hooks，页面仍消费数组。

## 当前备份格式

仅接收本项目v2备份，旧v1/缺版本/未知版本在lib解析阶段拒绝；v2必须带viewEvents数组（可为空）。确认摘要只数原始事件，展示用聚合段不重复计数；页面不承诺无损回灌旧App。服务端同样写前拒绝，当前合并/标签替换与历史身份保留规则见[备份专题](../docs/api/backup.md)和ADR-0050，不由UI重算。

## 上传任务恢复

上传页可选关联卡的作者建议面板启用Popover碰撞避让，位于窗口底部时可向上展开，避免建议存在但鼠标无法点选；其他作者字段沿用原入口的定位配置。

新队列项使用 UUID 本地键（兼容旧数字键），不依赖恢复完成先后，避免终态旧键过滤后被新任务复用；独立持久化 UUID uploadId 和 File.lastModified；直传/分片创建均透传，刷新恢复和会话重建保持任务 ID。重选文件校验名称、大小和已有修改时间；同 size/mtime 的内容替换仍不能检出。旧队列缺字段保持接收时间，并补一个新的任务 ID；旧请求成功窗口不能据此追溯去重。服务端重放期限及错误语义见 [上传 API](../docs/api/files.md#上传任务重放源时间与容量adr-0049)。IndexedDB 不可用或写入失败会在队列显示“本次任务仅当前页面有效/刷新可能无法恢复”，提供重试存储入口，不中断传输或重发业务请求。`upload-persistence` 只持 IO 意图/健康：单排空体按 ID 合并写删，终态删除排在旧写后，较晚进度不能复活；删除失败保留待删意图，尽力写 `terminal` 标记，恢复跳过并清理该标记。删除与标记均失败时，刷新仍可能出现旧条目，明确提示先核对入库事实；不可据此承诺永远恰好一次。重试恢复沿用 uploadId，七天完成凭据边界不变。共享 `idb.runTx` 只在事务 complete 后确认，request 成功后的 abort 仍拒绝；这也用于事件账本，浏览器回收/清理存储仍属平台边界。Playwright 的设置与队列降级场景使用合成 API，实际结果见 HANDOVER。

维护页流量消费`appRxBytes/appTxBytes`，只显示影库服务HTTP正文收发，计量边界见[监控](../docs/OBSERVABILITY.md#影库服务流量范围)；旧服务端显示需升级。详情推荐列表限高并内部滚动，换批/换资产重置滚动，加载/失败/空态分开；保留现有行式封面与导航语义。

## 统计消费

数据页按所选窗口读取 overview 汇总与同轴 trends；静图/动图/视频数量互斥，窗口浏览/播放分列。作者/标签统计榜调用服务端窗口端点，不从管理列表重排；查看榜单沿用窗口并标明上限。失败与真实空数据分开，新统计字段缺失明确提示服务端升级。口径与 Android 共用 [统计专题](../docs/domain/statistics.md#统计数据消费约束)。

`use-data-dashboard`统一请求与展示转换，页面只组织卡片；四个窗口指标、趋势/覆盖数量与当前库存分区，类型分布改为分段条，窗口浏览占比仍用环形图。range写URL且替换条目，完整榜单用真实Link沿用range，子页同风格并提供保持range的返回链接；各查询独立失败/重试并标记上次内容，不再因一个榜单失败隐藏整个页面。覆盖数量直接消费三个viewed计数，平均值null保留缺省，库存未知不伪零，既有统计计算及排序不改。

数据页底部标签榜、作者榜与作者总览共用紧凑单行，作者总览不再显示文件数副行；同排卡片的标题、说明、行边界与分隔线对齐，完整榜单页不受此密度调整影响。内容榜按用户最新反馈恢复封面在上、标题在下的样式，宽卡三列、窄卡两列、极窄卡单列；内容榜请求最多6项以填满三列两排（不足时按实显示），作者/标签仍为5项；仅微调间距并取消标题的额外两行占位，不增加序号、不强制与占比卡等高。视频详情移除播放器左上角完成角标，完成判定与续播/进度上报仍走原逻辑，历史列表徽标保留。

首页顶部分类采用独立作用域的紧凑分段导航，COS文案统一大写，选中状态同时提供aria-pressed；仅调整呈现，tab/period URL和搜索逻辑不变。窄窗口分类与搜索分行，保留桌面窗口动作/拖动属性及侧栏布局。

## 浏览候选与虚拟预览

首页/相册共用`BrowseToolbar`呈现当前流的已加载数量与就近操作；换批仍走现有seed/刷新键，相册清空统一重置页面维度与`usePanelFilters`，失败/续页失败与空结果分开。共享`MediaCard`保留无外框样式，补齐键盘打开和焦点语义。

人工预览与浏览器回归复用[tools/ui-preview](../tools/ui-preview/README.md)的144项合成夹具；预览只运行云端Web产物，不连接真实库。该工具只在开发/验证使用，不进入应用运行时或生产包。本轮UI候选按用户要求先预览再决定是否安装/合并，不能把预览启动记为日常后台更新。

## 我的内容工作区

环形图弧长仅由DonutCard按服务端数量占比生成，CSS颜色类不再写固定stroke-dasharray；零占比保留数字图例但不绘制圆头弧，避免显示不存在的浏览量。

`use-mine-workspace`持有现有关注/收藏/历史数据与分页/历史展示派生；收藏和历史仍仅活动分区加载，切隐藏分区不误触自动续页。媒体概况不虚构账户身份，三内容Tab及筛选保存在URL（tab/q），使用replace避免切Tab堆积返回记录。Tab可左右/Home/End切换，历史卡及榜单支持键盘激活；关注作者名可进入作品集合，取消关注失败保留列表并显示原因。收藏/历史失败区别于空内容，保留缓存并可重试，媒体库存失败不阻断个人内容。

历史q只按原groupHistory过滤已加载文件名，不增加服务端搜索参数；非空筛选期间停止自动续页、明确加载范围并提供手动“加载更多历史”，不自动扫描完整历史。非筛选继续用既有哨兵；手动/自动续页共用原游标且cancelRefetch=false防重叠取消。收藏时间排序、常规/COS合流及每文件最近一次历史语义不变；已看完徽标仅将既有派生从页面移入hook，原完成比较未改。


## 规模与状态所有权审查

上传队列保持模块级唯一传输/XHR/pump owner，挂靠和持久化IO分别复用独立服务；图片查看器已区分外层portal/焦点/chrome与ViewerCanvas手势现场，数学公式独立。Q08当前规模/保留依据和实际测试边界见[任务书结构结论](../docs/OPTIMIZATION_TASKBOOK_20261025.md)。不为行数增加同步状态，不扩张knip未用导出阻断范围。
