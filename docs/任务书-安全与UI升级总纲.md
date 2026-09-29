# 任务书 · 安全与 UI 升级总纲

> **版本**：v1.2（定稿）· 2026-09-30（v1.1 基础上补入用户拍板一项：Web 端由"轻量毛玻璃"升级为**液态玻璃**，见第六章 D3-3）
> **来源**：2026-09-29 隔离实例截图走查（Web 桌面 1440 / Web 手机 390 / Android App qimeng_api35 实机走查）+ 三路代码调研 + 技术方案核实，全部拍板项已经用户确认。
> **用途**：主 AI（GLM-5.3）按第二章编排模型拆卡派发，子代理（GLM-5.3-Flash）执行。本文件自包含，执行时不依赖走查对话。
> **注意**：文中所引文件行号基于当前 HEAD（393002a）调研，执行前必须按第七章第 0 步重新定位。

---

## 第一章 总则

### 1.1 目标

四批升级：**批 A 安全卫生收口** / **批 B Web UI 修复** / **批 C App UI 修复** / **批 D 视觉升级（液态玻璃 + M3 Expressive）**。每批 = 可用闭环，做完就能验收。

### 1.2 分支策略

- 批 A 在 **master** 直接做。
- 批 B/C/D 统一走 **`feature/ui-refresh`** 分支，按 B→C→D 依次推进，每批独立 commit + 验收，**全部完成后由用户确认合并回 master**（合分支动作必须等用户拍板，任何代理不得自行合并）。

### 1.3 四件套纪律（项目老规矩）

每个任务 = 代码 + 测试 + 文档同步 + 门禁绿，缺一不可：

- 涉协议先改 `api/openapi.yaml` 再 `make sdk`；生成物不手改；同 commit 更新 `api/sdk.lock` 指纹锁。
- 新 ADR 同步 `docs/adr/INDEX.md`。
- 文档与代码同一 commit（格式：`类型(scope): 简述 | 文档: 已更新XXX`，scope 区分 api/web/app/server/docs）。
- 每批完成同步 `docs/HANDOVER.md`（现状区）与 `docs/CHANGELOG.md`（新增一笔）。

### 1.4 git 纪律保护条款（最高优先级，防并发执行搞乱提交）

1. **所有子代理（执行/取证/报告代理）一律禁止 commit / push / 建分支 / 切分支**——所有提交由主 AI 统一执行，格式严格按 1.3 的 commit 格式。取证代理发现的问题走返工卡闭环，不直接改码。
2. `feature/ui-refresh` 分支由主 AI 在批 A 完成后从 master 切出，一次性建好，子代理只在其上改文件。
3. 任何子代理发现工作区存在"不是自己造成的"未提交改动，立即停卡写入报告，不覆盖不提交。
4. 主 AI 每批提交前必须核对 `git status` 与该批预期改动文件清单一致，多一个少一个都要查明原因。

### 1.5 明确不做（用户已拍板，任何代理不得擅改）

- 详情页对不存在资产的 404 空态（用户明确不做）。
- 瀑布流布局（用户之前尝试过、判为不规范；维持统一比例裁切）。
- dev 免密机制（保留原样，批 A 只加"内嵌形态密钥"，见第三章）。

---

## 第二章 执行编排模型

### 2.1 分工原则

**判断归 5.3，劳动归 Flash。** 凡"一次想对就省三轮返工"的事（拆卡设计、改法方案、验收标准制定、争议裁决、整体品味）由主 AI 亲自做；凡"照图施工、照单验收"的事（编码执行、跑命令取证、拼报告初稿）下放 Flash。主 AI token 只花在 ~10% 的高杠杆节点。

### 2.2 角色表

| 角色 | 模型 | 干什么 | 不干什么 |
|---|---|---|---|
| **主 AI** | GLM-5.3 | ①设计：拆卡+写每张卡的改法与验收标准 ②裁决：对取证清单做通过/返工判断 ③亲自操刀"设计敏感件"（见 2.3）④批末联审（见 2.6）⑤攻坚：2 轮返工不过的硬骨头亲自下场 ⑥唯一 git 提交权 | 不跑腿：不逐行读全部 diff、不跑常规命令、不拼报告 |
| **执行代理** | GLM-5.3-Flash | 照卡施工：改码+自测+四栏报告（改动文件/自测结果/遗留问题/存疑点） | 不做设计决策、禁 git 一切操作、禁碰任务卡外文件 |
| **取证代理** | GLM-5.3-Flash | 照单验收：复跑验收命令、读 diff 找疑点，产出**证据与疑点清单**（不下最终结论，判断留给主 AI） | 不裁决、不改码 |
| **报告代理** | GLM-5.3-Flash | 按模板拼阶段报告/汇总初稿（四段小白话，见第八章） | 不改码、不添判断 |

### 2.3 主 AI 亲自操刀白名单（设计敏感件，不下发）

1. 协议改动（`api/openapi.yaml` 的 A1 header 设计）与 `make sdk`。
2. ADR-0026 撰写（依赖白名单外扩论证）。
3. **GlassSurface 封装**（D2 整个组件的分档设计——库 API 收口是全批架构核心）。
4. MaterialExpressiveTheme 迁移（动全局主题，一处错全局花）。
5. `stageBackdropColor` 新口径设计（C1，行为被测试锁定，口径即契约）。
6. B1 响应式断点的**布局方案设计**（底部导航结构；具体 CSS 落字可下放 Flash）。

其余执行卡（B2+B3、B4~B9、A1 的 App/server 侧、D3 落点接线、D5 开关）全部走 Flash。

### 2.4 任务卡模板（主 AI 派发前必须填齐，Flash 靠它自主运转）

每张卡六段，缺一不派：

1. **目标**：一句话说清做什么。
2. **现状**：文件路径:行号 + 现状代码行为描述（从本任务书抄录；执行代理动手前自行重定位防漂移）。
3. **改法**：具体步骤（改哪些文件、怎么改、样式值给参考）。
4. **约束红线**：禁碰的文件/目录、禁改的冻结口径（如 BiliPlayerView G1~G9）、发现工作区不明改动立即停卡上报。
5. **验收标准（明确指令）**：逐条"跑什么命令 + 预期看到什么"（例：`npm --prefix web run build` 退出码 0；390px 视口截图无横向滚动条；删除路径仍弹确认框）。
6. **报告格式**：四栏填空（改动文件/自测结果/遗留问题/存疑点）。

### 2.5 工作流（取证与裁决分离）

```
主 AI 设计并派卡 → 执行代理施工+自测 → 执行报告
→ 主 AI 转 → 取证代理照验收单复跑 + 读 diff → 证据/疑点清单
→ 主 AI 裁决（看清单做决定，单次成本极低）
   ├─ 通过 → 收卡，git status 核对后提交
   └─ 返工 → 原卡 + 疑点清单合并派返工卡（最多 2 轮）
        └─ 仍不过 → 主 AI 亲自攻坚
```

### 2.6 批末联审（主 AI 品牌把关）

每批全部卡通过后，主 AI **通读本批完整 diff 一遍**——子代理各卡各干容易风格漂移（间距/命名/交互习惯不一致），联审专看跨卡一致性与"像不像一个产品"，发现问题派一张精修卡收口。每批一次，不是每卡一次。

### 2.7 并发安全铁律（文件不相交原则）

1. 每张任务卡声明目标文件清单；主 AI 派发前校验：任意两张在跑卡的文件集**无交集**，有交集的任务归并同路串行（例：批 B 多项任务都碰 `web/src/styles/prototype.css`，该文件全部改动归并 B1 卡一口做完）。
2. 协议与 SDK 生成操作（改 openapi.yaml、make sdk）影响全仓，**只由主 AI 串行执行**，永不下发子代理。
3. 构建产物目录（`web/dist/`、android 构建输出）不属于任何任务卡目标文件。

### 2.8 并发拓扑

**至少 3 个执行代理持续并发、异步后台执行。** 任务卡队列制：主 AI 维护待办队列，任一执行代理完成即刻补派下一张，保持 3 路持续满载直到本批队列清空。取证代理与执行代理错峰并行（A 卡在取证时 B/C 卡在执行），主 AI 维持"3 执行 + 1~2 取证"的流水线。

---

## 第三章 批次 A · 安全卫生收口（master 分支）

> **人话**：这批做完，你手机"本机模式"的服务器就只有启梦 App 自己能连，同机的其他 App 连不进来；顺带修两处文档里说错的话、给维护页加一条"开发模式未关"的提醒。你电脑上的免密登录完全不变。

**调研修正（勿重复立项）**：原 HANDOVER 待办"收敛两份 bat / compose 二选一"已于 2026-09-22 收敛完毕（bat 收敛到 `_server-common.cmd` 单点、根 compose 已删），本批只剩 A2 的文档残留修正。

### A1 内嵌形态共享密钥校验（核心，SECURITY.md:104 规划项）

- **威胁**：Android 的 loopback 端口全设备共享，同一部手机上的其他 App 可连 `127.0.0.1:18430` 走 dev-login 免密拿到管理员 token（App 持 MANAGE_EXTERNAL_STORAGE，后果放大）。
- **现状**：`android/core/data/src/main/java/media/qimeng/app/core/data/embedded/EmbeddedServerConfig.kt:67` 注入 `QIMENG_AUTH_DEV_MODE="1"`；服务端 dev-login（`server/internal/httpapi/authapi.go:296` 起）无额外校验；监听 127.0.0.1:18430（回环绑定已挡局域网面，防的是同机）。
- **改法**：App 拉起内嵌进程时生成随机密钥，经子进程环境变量注入（`QIMENG_AUTH_DEV_SHARED_SECRET`）；服务端 dev-login 端点**在该密钥已配置时**校验请求头（`X-Qimeng-Dev-Secret`），不匹配返回 401；App 侧 devLogin 调用带上该头（从 ServerConfigDataSource 读）。**密钥未配置时服务端行为完全不变**——Web 端 dev-login、日常 bat 免密零影响。
- **协议**：openapi.yaml 的 `/api/v1/auth/dev-login` 加可选 header 参数 → 主 AI 执行 `make sdk` 三端 + `api/sdk.lock`。
- **并发拆卡**：
  - 卡 A1-a（Flash）：server 侧校验 + 测试（文件限 `server/internal/httpapi/authapi.go`、`auth_dev_test.go` 及 auth 相关；三分支测试：带对密钥 200 / 带错密钥 401 / 未配置密钥不校验）。
  - 卡 A1-b（Flash）：App 侧密钥生成注入 + devLogin 带头 + 测试（文件限 `android/core/data` embedded 相关、`android/core/network` ServerConfigDataSource/AuthApi 调用侧）。
  - 卡 A1-c（Flash）：A2+A3（纯文档 + 维护页状态条，见下）。
  - 主 AI 串行段：协议改动 + make sdk（A1-a/A1-b 完成后统一执行，然后提交）。

### A2 文档残留修正

- `docs/HANDOVER.md:51`："根 docker-compose.yml 与 deploy/ 二选一"已过时（根 compose 已删），改为"生产样例唯一权威 = `deploy/docker-compose.yml`"。
- `docs/SECURITY.md:36`：bat 的 dev 模式位置描述失实（实际在 `_server-common.cmd:25` 单点），修正指向。

### A3 维护页开发模式状态条（Web）

维护页顶部当 `/system/status` 报告 dev 模式开启时显示"开发模式未关"提醒条；仅可见性，不动免密机制。文件：`web/src/pages/MaintenancePage.tsx` + 少量样式。

---

## 第四章 批次 B · Web UI 修复（feature/ui-refresh 分支）

> **人话**：这批做完，手机浏览器连 NAS 时页面变成正经手机样子（底部导航、不再挤成一列），详情页按钮不再挤出屏幕、删除按钮收进"更多"不再吓人，一串小毛病（胶囊形状不一、字太浅、路径溢出、首屏空白）一起治好。

**背景事实（调研确认）**：Web 布局全部由 `web/src/styles/prototype.css` 自定义类驱动（业务代码零响应式，Tailwind 断点业务侧未用）；`.sidebar` 宽 64px（prototype.css:58、:128）无任何收起逻辑；全文件 6 处 @media 无一涉及侧栏；详情页已有 `@media (max-width:1000px)` 右栏换行先例（:1553）。

### B1 响应式壳层（本批核心；**prototype.css 的全部改动归并本卡**）

- **改法**：新增 768px 断点——侧栏隐藏，改**底部导航条**（首页/相册/我的/数据四 tab，主题/设置收纳进入口），与 Android App 端形态呼应；`.content` 边距收窄；两个 FAB（`web/src/components/shell/AppShell.tsx:98-118`）上移避让底部导航。
- **布局方案设计由主 AI 出**（底部导航结构、tab 与现有路由映射），CSS 落字与接线可下放 Flash。
- 文件：`web/src/components/shell/AppShell.tsx`、`Sidebar.tsx`（或新增 BottomNav 组件）、`prototype.css`。
- **注意**：B4/B5/B6/B7/B9 的 prototype.css 改动段由本卡在布局段之后一并落（或这些卡排在 B1 之后串行），避免并发写同一文件。

### B2+B3 详情操作行收纳 + 假删按钮进"更多"

- **现状**：`.detail-actions`（`web/src/pages/AssetDetailPage.tsx:275-315`，样式 prototype.css:1463）flex 无换行无滚动，赞/藏/编辑/整理/删 5 钮在 390px 必溢出（走查实测"编辑被截断、收藏竖排"）；红色"删除"钮常驻操作行（`web/src/components/detail/FileOpsButton.tsx:31-38`，title 已注明是移入回收站）。
- **改法**：点赞/收藏/标签保留主行；编辑/整理/删除收进"更多"下拉菜单（radix DropdownMenu——项目未用过但 `radix-ui` 统一包已含，零新依赖；样式循 `web/src/components/ui/popover.tsx` 先例）；删除确认弹窗（ConfirmDialog danger 态）流程不变。
- **验收**：390px 视口无溢出无截断；删除路径仍二次确认；桌面端操作不退化。
- 文件：`AssetDetailPage.tsx`、`FileOpsButton.tsx`、新 DropdownMenu 封装组件、（样式段经 B1 路或本卡独占段）。

### B4~B9 散项（一张卡打包，prototype.css 段排在 B1 后）

- **B4 筛选胶囊统一**：`.more-filter`（prototype.css:1015，8px 圆角矩形）改胶囊形对齐 `.pill`（:489，999px 圆角）——一处类改，相册页 + 搜索页（`SearchPage.tsx:138` 同类）自动同步。
- **B5 FAB 一致性**：refresh-fab（AppShell.tsx:98-108，纯图标）加文字标签，与回顶钮（:109-118，有"顶部"字样）形态统一。
- **B6 卡片日期对比度与格式**：`--text-sub`（#9499a0 亮色，prototype.css:11；用于 `.card--date` :933）亮色主题适度调深；卡片日期格式统一（核 `web/src/lib/format.ts` 现状定唯一格式，连动其测试）。**红线：列表"分组标签"（今天/昨天/周几/yyyy-MM-dd）是 DOMAIN_RULES §189 口径，不动。**
- **B7 功能页宽屏收口**：维护/设置/我的页容器（`.page`，prototype.css:459，无 max-width）加 max-width≈1200px 居中。克制：只收宽度，不改卡片内部布局。
- **B8 路径显示优化**：`web/src/pages/LibraryRegistryPage.tsx:110`（库根路径，已有 260px 省略兜底）与 `web/src/pages/MaintenancePage.tsx:92`（磁盘挂载点直拼）改中段省略 + title 全量提示。（走查原判"我的页裸路径"系误判，该页无路径渲染。）
- **B9 首屏骨架**：卡片封面加 CSS 脉动占位类（animate-pulse 风格自建，项目无 skeleton 组件、不引组件库），改善首扫后冷启动观感。

---

## 第五章 批次 C · App UI 修复（feature/ui-refresh 分支）

> **人话**：这批做完，手机 App 看图时背景不再是一张白纸，有"在看大片"的沉浸感。

### C1 详情页沉浸背景（核心项）

- **现状**：`stageBackdropColor()`（`android/feature/detail/src/main/java/media/qimeng/app/feature/detail/DetailScreen.kt:335-339`）chrome 显示时用主题背景（浅色主题=纯白，走查"图片贴白纸"根因）、沉浸/播放态纯黑，瞬切无过渡；行为被 `StageBackdropTest` 纯函数锁定。
- **改法**：图片浏览（chrome 显示）态背景从纯主题色改**中性暗底**（或随封面主色暗化，口径由主 AI 设计定案）；同步改 `StageBackdropTest`（新旧口径对照注释）；如加过渡用 animateColor 渐变。
- **约束红线**：不动手势/显隐状态机（VideoStageStateMachine 等测试面）、不动系统栏三分支逻辑（StatusBarLuminanceTest 面）、不碰 BiliPlayerView。
- 文件：`DetailScreen.kt`、`StageBackdropTest.kt`。

### C2 记录澄清（无需动作）

App 端"假删按钮"已随 W3 批退役（`DetailScreen.kt:624-627` 注释，danger 机制保留但入口已移除）——**勿误立项**。Web 侧对应项在 B2+B3 处理。

### C3 待设计项（本批不盲改）

首页与相册页同构（tab 行与布局重复）——列为后续设计讨论项，待用户对双入口定位有想法再立项。

---

## 第六章 批次 D · 视觉升级：液态玻璃 + M3 Expressive（feature/ui-refresh 分支）

> **人话**：这批做完，看图/看视频时浮在画面上的操作条变成 iOS 那种"果冻玻璃"，按钮动画更有弹性。**克制使用**——只给浮在媒体内容上的层用，不满屏玻璃（用户拍板"适度即可"）。

### D1 依赖引入 + ADR-0026

- 引 `io.github.kyant0:backdrop` **2.0.1**（Maven Central 已核实存在；实施时按锁版纪律当场复核 maven-metadata 实存）+ `kyant-shapes`（透镜所需 G2 连续形状，版本随 backdrop 依赖核对）。
- 走 **ADR-0026**（编号已核实：0025 已被"库根自动重挂"占用）：ADR-0014 依赖白名单外扩，体例循 ADR-0018（vico 先例：拍板依据+官方源锁版论证+落选方案+受影响文件清单）。
- 文件：`android/gradle/libs.versions.toml`（带官方源链接+实查日期注释）、`android/core/ui/build.gradle.kts`（+头注白名单句同步改）、新 ADR 文件、`docs/adr/INDEX.md`。
- **ADR 撰写归主 AI（白名单件）**；toml/gradle 落字可下放 Flash。

### D2 GlassSurface 单点封装（core:ui，**主 AI 亲自操刀**）

- 封装 `GlassSurface` 组件，内部按系统版本分档：**API 33+** 完整效果（vibrancy + blur + lens 折射 + 边缘高光）；**API 31–32** 仅模糊；**≤30** 半透明表面 + 描边补偿。
- **铁律：页面层永不直接 import backdrop API**——全部经 GlassSurface，库将来升级只改这一个文件。
- 落 `android/core/ui` 组件目录。

### D3 落点（适度原则 = 只放媒体内容上的悬浮层）

1. **试点**：图片详情页底部操作条 `DetailBottomChrome`（`android/feature/detail/src/main/java/media/qimeng/app/feature/detail/DetailChromeBars.kt:216-346`）背景从 `chromeBarTint()`（95% 不透明底色）换 GlassSurface。
2. **播放器控制条（约束重）**：BiliPlayerView 属 ADR-0014 冻结件（传统 View、G1~G9 手势口径禁改）。两方案实施时由主 AI 定：a) Compose 侧 `VideoFullScreenOverlay` 做玻璃壳层叠在 AndroidView 之上；b) 保守方案——仅把 View 内硬编码背景色值（`0x88000000` 等）换成半透明渐变，零结构改动。
3. **Web 端液态玻璃（v1.2 升级：2026-09-30 用户拍板"给 Web 的也加液态玻璃"，替换 v1.1 的轻量毛玻璃口径）**：与 App 端同观感的液态玻璃材质——`backdrop-filter`（blur + saturate 润色）+ 透镜折射（倾向 SVG `feDisplacementMap` 位移贴图路线）+ 边缘高光描边，**不引库原则维持，全手写**；落点不变 = 详情页操作条 + 悬浮筛选条（克制原则照旧，不满屏玻璃）。降级分档：不支持 `backdrop-filter` 的环境退纯半透明表面；位移折射在低端设备/手机浏览器（390 走查口径）掉帧明显则退"毛玻璃档"（保 blur 去 displacement）。**材质配方（位移参数/边缘光/分档判定）为设计敏感件，由主 AI 设计定案；CSS/SVG 落字可下放 Flash。** 文件在 prototype.css + 内联 SVG filter（注意与 B 批段落的先后串行）。

### D4 M3 Expressive 动效（**主 AI 亲自操刀**）

- `QimengTheme`（`android/core/ui/src/main/java/media/qimeng/app/core/ui/theme/Theme.kt:98-108`）从普通 MaterialTheme 迁 `MaterialExpressiveTheme` + MotionScheme 弹性动效规格。
- **依据**：material3 已显式锁 1.5.0-alpha28（libs.versions.toml 覆盖 BOM 2026.09.00 管的 1.4.0），MaterialExpressiveTheme 自 1.4.0 转 public，项目已在消费其 LoadingIndicator（4 处 @OptIn）——属既有路线收口，非新开依赖。
- 自测：`make app-build && make app-test && make app-lint`，冒烟看动效不破坏既有交互。

### D5 玻璃效果开关

- 设置页加"玻璃效果"开关（默认开）+ DataStore 持久化；关闭时 GlassSurface 直走 ≤30 档渲染（纯半透明表面），照顾低端机/省电。

### D6 性能验收（用户节点）

- **模拟器看不出 shader 帧率**，须真机实测：图片详情页与播放器两场景、开/关玻璃对比帧率；掉帧明显则默认值改关闭。此项依赖用户配合，主 AI 到此节点先出报告等用户。

---

## 第七章 执行顺序、依赖与开工检查

### 7.1 第 0 步（开工必做，主 AI 执行）

1. `git status` 核查工作区未提交改动（用户有微调残留）——逐文件确认归属：用户想留的先提交或暂存，保持工作区干净再开工。
2. 全部任务卡执行前重新定位文件行号（行号基于 393002a 调研，可能因用户微调漂移；以内容搜索为准，不以行号硬对）。
3. 确认当前在 master、`feature/ui-refresh` 尚不存在。

### 7.2 顺序与依赖图

```
批 A（master，3 路并发：A1-a ‖ A1-b ‖ A1-c；主 AI 串行段=协议+make sdk）
  → 主 AI 切 feature/ui-refresh
批 B（3 路：B1[prototype.css 总闸+布局设计] ‖ B2+B3[详情组件簇] ‖ B4~B9[散项，prototype.css 段排 B1 后串行]）
  → 批 C（C1 单路；期间主 AI 可并跑 D1 依赖/ADR 准备）
  → 批 D（D1 → D2[主 AI] → D3/D4/D5 并发 → D6 用户节点）
  → 全批完成：整分支前后截图对比 → 用户验收 → 用户拍板合并
```

关键依赖：D3 试点落点依赖 B2 完成后的操作条形态；D3-Web 段依赖 B 批 prototype.css 段落完成。

---

## 第八章 报告与验收

### 8.1 验收命令

- Web：`npm --prefix web run build && npm --prefix web run lint && npm --prefix web run test`
- Android：`make app-build && make app-test && make app-lint`
- server（批 A）：`cd server && go build ./... && go test ./...`
- 截图复验（批 B/D 完成后）：隔离实例 + 虚构数据（复用走查方法：临时数据目录 + ffmpeg 生成测试图 + 127.0.0.1 隔离端口），**真库（8420）永不截图**；Android 截图必须显式 `qimeng_api35` AVD，严禁碰雷电模拟器。

### 8.2 阶段报告（每批完成后，报告代理草拟、主 AI 校对转发）

固定四段，小白能懂：

1. **这批做了什么**（一句话一件，不用术语）
2. **你现在能看到什么变化**（打开哪个页面、看哪里）
3. **自测证据**（测试/构建/截图关键行，原样贴）
4. **有什么遗留 / 下一批是什么**

### 8.3 最终交付（四批全完后）

总报告：每批一段人话总结 + 整分支前后截图对比 + 遗留清单 + 合并建议，交用户拍板。**合并动作在用户确认前禁止执行。**

---

## 附录 · 走查发现全记录（备查）

**已纳入本任务书**：Web 手机视口无响应式（B1）；详情操作行溢出（B2）；假删按钮（B2，App 侧已退役见 C2）；胶囊形差（B4）；FAB 形态不一（B5）；日期对比度低（B6）；功能页宽屏空白（B7）；路径溢出（B8）；首屏空白无骨架（B9）；App 详情白底沉浸弱（C1）；液态玻璃+Expressive（D）。

**用户拍板排除**：详情页 404 空态；瀑布流。

**走查口径更正两处**（防误传）："我的页裸路径"实为库管理页/维护页两处；App 假删按钮已随 W3 退役。

**技术核实**：backdrop 最新版 2.0.1（Maven Central，2026-09 实查）；ADR 编号顺延至 0026；material3 实锁 1.5.0-alpha28（MaterialExpressiveTheme 可用）；Google 官方无 Liquid Glass，Material 3 Expressive 为官方对位路线。
