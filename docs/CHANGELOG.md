# CHANGELOG - 变更历史

本文件归档项目历次功能/修复/决策变更，每条对应 git 提交（commit hash 标注）。

## AI 署名约定（沿用旧项目 QimengMedia 惯例）

- 每个变更条目标注实际执行该改动的 AI 模型（真实命名，品牌-版本），便于追溯每次改动由谁完成。
- 每个条目单独署名；多 AI 协作时各条目自行署名。
- 子代理执行的工作标注"（执行子代理）"，主对话直接完成的标注"（主代理）"。

---

## 原型七组缺陷 web 正式端移植（协议 AssetSummary.authorNames 全链路）+ 原型 CSS 双缺陷修复 + 归档会话质量审查（2026-09-03 第七笔）

执行 AI：GLM-5.3-Flash（主代理，ZCode；执行×3 + 对抗审查×2 子代理）

背景：用户发现第六笔修复做在 `media-ui-prototype/` 原型而日常使用的是 `web/` 正式端（8420），要求盘点 WorkBuddy 全部归档会话改动并对抗审查后完成移植。审查结论（4 笔现役提交）：4320f3e 含一处 P2（相册页缺省分区漏传 partition）但已被 7d638d5 自愈；7d638d5、8a6b63f 合格；265a1a8 原型层另有 2 处 CSS 实缺陷（TXT 导入面板样式选择器 id 脱靶、作者行副标题两行布局在 flex 单行下不成立）。本笔全部修复：

### ① 协议扩展 + 卡片作者行/时长角标全链路（api+server+web）

- **openapi.yaml 先行**：AssetSummary 新增 `authorNames: array<string>`（该资产全部作者显示名，常规∪COS；GET /assets、GET /recommendations 返回，无作者为空数组；详情/历史/排行榜端点省略）；`make sdk` 三端生成物重建（validate→go→ts→kotlin 四步全过，重跑零 diff 复核）。
- **服务端**：`ListAuthorNamesForAssets`（json_each + narg 批量形状，ASCII-only 注释）+ handler `fillListAuthorNames` 装配（页大小一次批量查询无 N+1、失败仅记日志不炸列表、map miss 填空数组）；recommend.sql 补 `duration_ms` 列（推荐流角标数据）；新增 2 测试锁定多作者码点序/COS 作者/无作者空数组/视频图片 durationMs 差异。
- **web**：`assetToCard` 收敛全站 6 个 MediaCard 调用方——up=作者行（`formatCardUp`：首作者、多作者「名 等N」、无作者回退 source）、duration 角标仅视频传（`formatDuration` m:ss / h:mm:ss，空值不渲染）；MediaCard meta 改两行结构（标题下作者行、日期次行），样式与原型第六笔逐字对齐。

### ② 作者榜口径 + 作者副标题 + 相册时间分区移植（web）

- **作者榜 = 常看作者**（DataPage/RanksPage）：过滤 viewCount>0、按浏览数降序（DataPage Top5，RanksPage 全量），行计数=浏览次数，note 改「按浏览」；标签榜 fileCount 口径未动。原实现按作品数排序且 0 浏览占位（旧缺陷，审查确认服务端 rankings 热度口径与"常看"是两套，前端口径修正）。
- **作者总览/作者管理页行副标题**：RankRowList 扩展可选 sub 字段（两行结构，「N 个文件 · 浏览 M 次」，count 空串不渲染徽标）；AuthorsPage 行同步补副标题；`authorDisplayName` 助手给 COS 作者追加「 ·COS」标识（对照原型 authorDisplayName）。
- **相册时间分区**：`dateLabel` 助手（今天/昨天/2~6 天→周X/更早 yyyy-MM-dd/空值不分组，复刻旧版 MediaBrowserLogic）；AlbumsPage 网格分组渲染（组内保持列表原序、组间按组首 modifiedAt 降序、空日期组固定最后且不渲染组头；分区/类型胶囊切换后分组纯函数自动正确，翻页同 label 归并）。

### ③ media-ui-prototype CSS 双缺陷修复（style.css）

- TXT 导入面板选择器 `#importAuthorFile/#importAuthorText` → `#authorImportFile/#authorImportText`（与 index.html/app.js 实际 id 对齐，面板样式恢复生效）。
- 作者行副标题两行布局：`.a-list li`/`.rank-card li` 加 `flex-wrap:wrap`，`.a-sub,.rank-sub2` 加 `flex-basis:100%`，`.a-list .a-sub` 加 `order:1`（按钮留首行右侧）；无副标题的普通榜单行无副作用。

### ④ 其他

- `docs/HANDOVER.md` 「特色纪律」删除「并发子代理上限 2 个」条目（用户要求，编号重排）。
- 修正第五笔 CHANGELOG「六查询」措辞（实为四查询补 source 谓词）。
- 门禁：go test 14 包全绿、golangci-lint 0 issues、gofmt 干净、`make sdk`/`sqlc generate` 重跑零 diff、web tsc/build/lint 全绿（0 errors，15 warnings 全存量）；dateLabel 6 边界用例验证通过（web 无测试框架，脚本验证后清除）。

---

## media-ui-prototype 七组缺陷修复：榜单浏览口径/作者关联副标题/首页卡片元信息/相册时间分区/COS 搜索与推荐词/作者 TXT 导入（2026-09-03 第六笔）

执行 AI：DeepSeek-V4-Flash（主代理，WorkBuddy；执行子代理 ui-fix-dev@media-ui-fix）

用户对照旧版 Android 应用（QimengMedia）逐页验收 media-ui-prototype，报 7 组行为差异，要求"内容榜=常看文件、作者榜=常看作者、作者总览=作者管理"等旧版语义落地。仅改 `media-ui-prototype/`（app.js/index.html/style.css，纯 HTML/CSS/JS + mock，无框架/无后端）：

1. **数据页作者榜 = 常看作者**：只显示浏览>0 的作者、按浏览数降序取 Top5（旧版 DataStatsFragment renderTopAuthors 口径——旧实现按作品数排且 0 浏览也显示）；完整榜页同步。
2. **作者总览 = 作者管理入口卡**：每行补「X 个文件 · 浏览 Y 次」双关联副标题（旧版 AuthorListFragment onBindViewHolder 口径），COS 作者带 ·COS 标识；关注数变化实时同步。
3. **内容榜 = 常看文件**：mock 15 条全为正浏览量、按浏览降序、0 浏览不显示（旧版 renderTopFiles 口径）。
4. **首页/相册/搜索卡片元信息**：抽 `cardInner()` 统一模板——标题下作者单独一行、日期次行；视频时长角标仅 `m:ss` 格式显示（图片/动图/空串不显示，旧版卡片结构）。
5. **相册页按时间分区**：复刻旧版 MediaBrowserLogic.dateLabel——今天/昨天/周一~周日（距今 2~6 天）/yyyy-MM-dd 分组标题 +「N 项」计数，筛选胶囊先过滤后分组、空组不渲染。
6. **COS 内容生效 + 推荐搜索**：顶栏 cos tab 过滤卡片流/搜索池；搜索关键词真正参与结果过滤（补 SEARCH_STATE.query + matchQuery 六维子串命中：文件名/作者（去@前缀）/分区/作品/角色/类型 + cos 标记，搜 cos/COS/Cosplay 命中 cos:true 条目——根因：旧实现关键词从未接线，搜任意词结果相同）；推荐搜索区从占位文案改为 mock 推荐词 chips 点击即搜。
7. **作者管理页「导入 TXT」入口**：原只有列表找不到添加方式。新增导入按钮+面板（选 .txt 文件读取 / 粘贴文本），解析兼容旧版 AuthorImportUseCase 三种格式（一行一作者/逗号别名取首/冒号文件列表取作者名），去重并入 mock 并实时刷新榜单/总览/列表，纯内存态。

审查：主代理逐条核对 diff（333+/80-）、`node --check` 通过、56 处 DOM 引用无缺失（含打回一次：搜索关键词未参与过滤已补）、matchQuery 独立单测 ALL_PASS（cos 4 条/虚空行者 2/夜景 1/夜空机位 2/空词全量 13）、本地 8099 服务 curl 200。**待用户浏览器验收后决定是否保留；提交前未动 web/server/android。**

### 已知限制（记账）

- 原型层为 mock 数据：作者浏览聚合未模拟旧版时间窗口（用 browse 字段直排）、TXT 导入不持久化（刷新复原）——真实数据版本在 web 端（阶段 B/C）接入时需按 DOMAIN_RULES 口径实现时间窗聚合与持久化。
- 上传路径不做富化（source/角色/作者/cos_work，既有缺口）；ArtPlayer 播放器 UI、confirm 原型弹窗、设置页三卡持久化、客户端异常上报通道沿用记账。
- 相册页徽标仍为 4 个独立 facets 请求（本地聚合量级可控，无合并端点需求）。

---

## 相册作者/角色行语义修正（旧版「全部」tab 口径）+ 首页 cos/排行榜 + 文件管理 TXT 卡（2026-09-03 第五笔）

执行 AI：DeepSeek-V4-Flash（主代理，WorkBuddy）

用户指出 4320f3e（第三笔）相册四维聚合没做完的三件事 + 署名排查：① 相册「作者」行应是 **COS 作者 + 常规出处分组两个集合**（现在只有 COS 作者）、角色行缺 COS（作品）；② 首页 cos/排行榜 tab 依旧不生效；③ 文件管理缺 TXT 单独卡片（旧项目「数据管理」）。另按项目记忆修正第三/四笔署名工具名 ZCode→WorkBuddy（实际运行环境）。

### ① 相册作者/角色行语义对齐旧版「全部」tab（协议+服务端+Web 同改）

- **作者行语义（用户拍板，替代原 authorId 全量作者）**：作者行 = 常规**出处分组**（kind=source，含「其他」桶 = NULL source 且非 COS 关联；COS 资产永不进出处——browse.sql 的 source_is_other 谓词全查询加 NOT EXISTS cos 排除，与旧版 groupBySource(!isCosFile) 一致）∪ **COS 作者**（kind=author）按分区合并——常规分区只出处、COS 分区只 COS 作者、全部（all）分区两者合并。常规 TXT 作者表行**不进**作者行（作者体系保留在 /authors 与集合页）。FacetBucket 加 `kind` 枚举（source/author/character/work）——同一胶囊行混合两种候选时前端按 kind 分派筛选参数。
- **角色行**：全部（all）分区 = 常规角色名（kind=character）∪ COS 作品名（kind=work）合并（第三笔只合并了 author 维度，角色维只在单分区各自生效——已修）；character 与 work 同属角色行，排自身时一起忽略。
- **FacetSourceCounts 新查询**（facets.sql）：非 COS 关联资产按 source 分组（含 NULL 行=「其他」）；FacetAuthorCounts 收敛 `au.type='cos'`；其余四查询（角色/类型/分区两维）统一补 source 谓词（author 行选中对其他三维生效）与排自身维度表文件头。
- **Web 相册页**：修分区默认值 bug——原 `partition !== regular ? {partition} : {}` 在缺省分区不传参，服务端按 all 处理致作者行语义错乱；现在 partition **恒显式传参**。作者/角色值行候选携带 kind，点出处胶囊→source 筛选、点 COS 作者→authorId、点角色→character、点 COS 作品→work；分区缺省改「全部」（= 旧版「全部」tab，两集合并排可见），切分区清作者/角色（候选命名空间随分区变化）。作者行/角色行前补「全部」胶囊（value='' 清除本行）。
- 测试：facets_test 重写 2 用例 + 夹具补 source（a.jpg 无出处→「其他」、b.jpg kemono、c.mp4 视频无出处）锁定新口径（作者行 全部=其他2+kemono1+COS作者2、regular 只出处、cos 只 COS 作者、选角色后作者行=含该角色资产的出处、选出处后角色行排自身全量等）；go test 14 包全绿。

### ② 首页 cos/排行榜 tab 生效（Web，URL 驱动）

- 根因：HomePage 忽略 `?tab=` 恒渲染推荐流；顶栏 rankPeriod 是 TopBar 私有 state，榜单数据无入口。修复：tab/周期收敛 URL 参数（`?tab=recommend|cos|hot`、`?period=day|week|month|year`，TopBar 只写、HomePage 只读，刷新直达不丢态）；`lib/home-tabs.ts` 双端共享常量。hot 内容榜 = ContentRankGrid + useRankings(period)（纯热度口径 DOMAIN_RULES §2，周期行缺省日榜）；cos = GET /assets cosOnly 浏览流（COS 独立入口落地）。

### ③ 文件管理 TXT 卡片（旧项目数据管理「TXT导入作者」）

- **服务端**：GET/DELETE `/api/v1/authors/import-txt`（openapi→make sdk→sqlc）——GET 返回已导入片段名升序；DELETE 移除片段并从**剩余片段**统一重建（204/404）。authors.go 重建核心拆 `mergeTxtSources/upsertMergedAuthors/insertLinks/rebuildAll` 四件套复用：删除路径以「删除前」全量片段为删关联目标（作者只出现在被删片段时旧关联一并清掉）、「删除后」剩余为重建来源；作者行保留不级联删（openapi 语义）。删关联**不按 type='regular' 全删**——旧项目迁移（import.go）也写 regular 关联，只能删已导入片段涉及作者的关联。
- **Web**：use-authors 增 useTxtImportedFiles/useImportAuthorTxt/useDeleteImportedTxt；文件管理页（/app/maintenance/files）新增「作者 TXT 导入」卡——选 .txt 导入（读文件内容 POST，同名覆盖）、片段列表、逐份移除（toast 导入计数/成功反馈）。
- 测试：TestAuthorsTxtManageList（GET 升序、删单片段并集不受影响、作者只在被删片段→关联清空行保留、删光→空列表、404）+ 既有导入全链回归。

### 已知限制（记账）

- 上传路径不做富化（source/角色/作者/cos_work，既有缺口）；ArtPlayer 播放器 UI、confirm 原型弹窗、设置页三卡持久化、客户端异常上报通道沿用记账。
- 相册页徽标仍为 4 个独立 facets 请求（本地聚合量级可控，无合并端点需求）。

---

执行 AI：GLM-5.3-Flash（主代理，WorkBuddy）

HANDOVER_UI §5 待办项：搜索页类型 tab 从 综合/视频/图片 三档扩为四档（+动图=mediaType animated_image，计数走 useAssetsTotal）——与相册页类型维、协议 MediaType 枚举（image/animated_image/video）对齐。纯前端小改（SearchPage.tsx 四处），协议零变化；tsc + build 通过。用户拍板暂停后续小任务（confirm 原型风格弹窗、上传路径富化两项仍记账待做）。

---

## 相册四维聚合：GET /assets/facets + migration 0008 cos_work + 相册页四维胶囊（2026-09-03 第三笔）

执行 AI：GLM-5.3-Flash（主代理，WorkBuddy）

上一会话半成品接续（HANDOVER_UI §5 待办「相册作品/角色聚合维度端点」：协议与 facets.sql/migration 0008 已写、停在生成链重建之前），本会话完成生成链、scanner 写入、handler、web 接入全链路。

### 服务端（先 openapi 后 make sdk，禁止手改生成物）

- **migration 0008_cos_work**：assets 加 `cos_work` 列（COS 库 `作者/作品/文件` 第二段目录名；NULL=无作品子目录），instr/substr 存量回填（实测 5558 行 COS 资产 5557 命中、152 去重作品）+ 部分索引 idx_assets_cos_work（WHERE cos_work IS NOT NULL）。
- **GET /assets/facets**：相册四维筛选候选端点——partitions（全部/常规/COS 恒三项，常规=全部−COS）、authors（key=AuthorID）、characters（常规分区=角色规范名、COS 分区=cos_work 作品名，NULL 不列入）、types（all/image/animated_image/video 固定四项，key 可直接回传 GET /assets 的 mediaType）；全部查询带分区/作者/角色/类型/搜索全参数。**排自身口径**：计每维候选时忽略该维自身当前选择（character 与 work 同属角色维，两者一起忽略）——前端每维独立请求各缺自身参数，即得「排除自己后还剩什么」的正确计数。
- **GET /assets** 加 `cosOnly`（只要 COS）与 `work`（按 COS 作品名筛）参数；COS 隔离升级三态开关：includeCos=1→全量、cosOnly=1→只 COS、两者皆 0→常规（排除 COS，历史默认）；cosOnly 与 includeCos 同真时 cosOnly 优先（handler 层实现，SQL 三态谓词保持中立；sqlc 三值逻辑要求 cos_only 恒传 0/1——NULL 会把非 COS 行也排除）。
- scanner：`ingestCosFile` 扫描入库写 cos_work；`recomputeCosAuthor` 作者关联重算同步覆盖 cos_work（含清空 NULL）——移动/改名后作品目录变化自动跟随。
- 顺手修既有缺口：**回收站恢复后不做富化**（TrashMeta 不存 source/角色/作者关联，恢复后 mtime 未变重扫跳过）→ 恢复 UpsertAsset 后尽力而为 EnrichAsset 重算（失败仅告警，待重扫自愈）；**上传路径不做富化属同类既有缺口，本次未修记账**。

### Web（铁律 7：hooks 层接数据，页面零 SDK 调用）

- `hooks/use-assets.ts`：AssetListParams 加 work/cosOnly；新 `useAssetFacets`（GET /assets/facets）与 `facetToOptions` 助手；导出 Partition/FacetBucket/AssetFacets 类型。
- `pages/AlbumsPage.tsx` 重写为协议四维（原两维：分区=出处+类型）：分区（全部/常规/COS，缺省「常规」延续历史口径）、作者、角色（常规分区=角色名 / COS 分区=作品名，切分区重置角色——两者命名空间不同）、类型；四个 useAssetFacets 请求各缺自身参数实现排自身徽标计数；filter-card 布局与对齐数值未动（CSS 零改动，维度行改为协议四维循环渲染）。

### 测试与实测

- go test 14 包全绿（新 facets_test 4 用例：分区栏恒三项 / 三分区四维候选 / 排自身口径 / COS 参数与 cosOnly 优先级；scanner 测试补 cos_work 断言；store 回退序列补 0008 步；search 测试显式传 cosOnly=0 防 NULL 三值逻辑）；golangci-lint 0 issues（顺手清 libraries.go 3 处既有 unconvert）；tsc（-p tsconfig.app.json）与 npm build 干净。
- curl 实测（用户 PC 真库 6413 文件）：分区栏 全部 6413 / 常规 855 / COS 5558（和守恒）；COS 分区角色栏=作品名（崩坏星穹铁道 卡芙卡 286 / 黑天使 240 / …）；COS 作者栏 cos_ 前缀双体系（蠢沫沫 2852 / …）；类型栏 all=image+animated_image+video 守恒。
- UI 浏览器静止态对齐实测未做（CSS 零改动、风险低；用户拍板自行打开 8420 验收）。

### 已知限制（记账）

- 相册页徽标 = 每维独立 facets 请求（共 4 个）各缺自身参数；本地聚合量级可控，暂无合并端点需求。
- 上传路径不做富化（source/角色/作者/cos_work 全缺，既有缺口待做）。
- `.tmp-verify/` 为上一会话验证残留目录（t8.db），未入库未删。

---

## 全部界面接真实数据：搜索/我的/数据/榜单/集合/作者/顶栏/推荐偏好/维护监控（2026-09-03 第二笔）

执行 AI：DeepSeek-V4-Flash-Vision-Exp（主代理，ZCode；研究×2、执行×3、审查×1 子代理）

用户需求："现在把所有界面都接入数据，数据页面和我的页面以及搜索等等全部接入真实数据"——web 端剩余 mock 页面（pages/mock.ts 全量退役）。流程：研究（web 现状+后端能力核对）→ 方案基线（用户拍板：数据页 6 卡换真库数据、设置页扫描/上传/界面卡本次不动）→ 执行三片 → 对抗审查（需返工 3 项已修复）→ 实测。

### 服务端协议扩展（7 点，先 openapi 后 make sdk，禁止手写 SDK）

- `GET /assets` 加 `liked` 筛选（likes 表任意日行=已赞）；`sort` 枚举加 `favoriteAt`（favorites.created_at，仅 favorite=true 语义成立）——browse.sql 三查询同步谓词/排序键。
- **新增 `GET /history`**：观看历史——每资产最近一次 open 事件时间倒序（每资产一条），FROM assets 锚定排除已删（事件流无 FK，ADR-0005）、默认排除 COS 作者关联（includeCos=true 包含）、keyset 游标 (last_viewed_at, asset_id) 分页；HistoryItem=AssetSummary+lastViewedAt+**durationMs/lastPositionSeconds**（已看完徽标数据源，reviewer 返工补齐）。
- `GET /stats/trends` range 加 `7d`（近 7 天逐日）/`90d`（近 90 天逐日）；`GET /rankings` period 加 `quarter`（近 90 天窗口，语义沿用"窗口内活跃热度榜"）。
- `AssetSummary` 加可选 `viewCount/playCount`（仅 rankings 填充实测值，浏览列表传 nil 保持轻量）；`Author` 加 `viewCount`（作者作品累计浏览次数，作者页"经常浏览"排序数据源）。
- 新测试 8 用例（history 顺序/游标/COS/已删 + durationMs 返工补断言；liked 双向；favoriteAt 排序；7d/90d 桶数与守恒；quarter 计数；authors viewCount）——`go test ./...` 14 包全绿。

### Web 接真（铁律 7：页面零 SDK 调用，数据全走 hooks）

- **共享层**：`hooks/use-assets.ts`（AssetListParams 补全 13 个协议参数、`assetToCard` 摘要→卡片映射、`useAssetsTotal` 计数徽标）；新 hooks `use-authors`（列表+关注 PUT）/`use-tags`（列表+新建）/`use-stats`（overview/trends/rankings）/`use-history`（无限翻页）/`use-prefs`（9 维 GET/PUT+四预设常量）/`use-system-status`（2s 轮询）；`lib/format.ts` 补 formatCount/formatDateTime/localDateKey；路由键常量 迁移 `lib/route-keys.ts`，**`pages/mock.ts` 全量删除**。
- **搜索页**：q 从"不参与过滤"修复为真传 FTS5（六维：文件名/路径/标签/角色/作者/出处）；类型/排序/顺位/播放次数（**playRange**，reviewer 指出原误用 viewRange 已修）/文件大小/时间范围/年份区间/标签池+模式全部真实传参；类型徽标=totalMatched 真计数；结果无限滚动+点击进详情；音频档删除（协议 MediaType 无 audio）+「+ 添加」标签=POST /tags 后选中。
- **TopBar**：搜索历史 localStorage（key qimeng_search_history，20 条去重最新在前，清空按钮）；推荐搜索词=tag/作者 fileCount Top6 合并去重取 8。
- **我的页**：资料卡（库数/文件总数/容量=useLibraries+stats/overview）；关注 Tab（followed 过滤+关注 toggle）；收藏 Tab（favorite=true + sort=favoriteAt + 加载更多）；浏览历史 Tab（今天/昨天/更早分组、已看完=lastPositionSeconds>=durationMs/1000、观看时间、标题过滤、点击进详情）。
- **数据页**：6 指标卡真库数据（总文件/图片/视频/容量/今日浏览/累计浏览，用户拍板）；时段四档（近 7/30/90 天/全部）联动 trends（7d/day/90d/all）与内容榜（week/month/quarter/all）；双环（类型分布+浏览量占比近30天）；内容 Top5=rankings（formatCount 角标+点击详情）；标签/作者榜=/tags、/authors fileCount Top5（库存口径与原型一致）；作者总览=140 位真数+已关注。
- **完整榜单页**：内容榜=rankings 全量（日/周/月/年周期由数据页入口决定，子页固定全周期——现状无周期胶囊）；标签/作者榜全量降序。
- **集合子页**：tag/author 按名找实体→真资产网格（**includeCos=true 修复**：COS 作者如"蠢沫沫"2852 文件默认被排除导致空态，reviewer 实测覆盖）。
- **作者管理页**：140 位真作者（10 位有文件）、体系胶囊 常规/COS、排序 默认/经常浏览(viewCount)/文件数量、关注 toggle PUT。
- **设置页**：新增「推荐偏好」卡——4 预设（点击即保存）+9 维滑杆（拖动后保存），预设数值逐字抄 DOMAIN_RULES §1.3 表，PUT 全量 9 字段（协议整体替换语义）；其余三卡按用户拍板不动。
- **维护页性能监控**：4 圆环（CPU/内存/系统盘/存储合计=system/status 实时）、指标卡（上下行速率=2s 轮询本地差分、存储合计、运行时长）、网络曲线（60 点环形采样）；客户端异常表改空态（无上报通道，记账）。
- 新组件：`components/data/{TrendChart,DonutCard,ContentRankGrid,RankRowList}`、`pages/SearchFilters.tsx`+`search-state.ts`（搜索页拆 259 行内）；维护页速率曲线本地实现。

### 实测（用户 PC 真库：4 库/6413 文件/188.9 GB）

- 搜索 "2b" → 38 条"尼尔 机械纪元 2B"真资产；搜索"请不要带走我"（mock 历史词）→ 真无结果提示（证明链路真）；类型徽标 视频 392/图片 462。
- 我的页：资料卡 4 库/6413 文件/188.9GB；收藏 0 空态；浏览历史"今天 · 7"条真记录（9-3 16:53~16:54 观看）。
- 数据页：6 卡/趋势（09-02、09-03 两桶）/双环（图片 93.6%）/内容榜 Top5（最终幻想/拳皇/铁拳8/守望先锋）/作者榜（蠢沫沫 2852/橙子喵酱 451/…）/作者总览 140 位。
- 集合页：作者"蠢沫沫"·2852 个文件真网格（修复前空态）。
- 作者关注 toggle：关注↔已关注双向（PUT 生效+恢复原状）。
- 推荐偏好：点"新鲜优先"→9 滑杆变 0.10/0.05/.../0.35（与预设表逐字一致）→恢复"均衡推荐"→GET 回读默认值；首页推荐流正常渲染。
- 维护页：CPU 8%/内存 16.1/31.7GB/系统盘/存储合计 3382.2/4652.1GB/下行 7KB/s 上行 8KB/s/运行时长 0 天（重启后真实值）。
- 顶栏：推荐搜索=蠢沫沫/橙子喵酱/…；输入 2b 回车→URL /app/search?q=2b+历史入库+重新聚焦显示"搜索历史 · 2b"。
- 端点级：8 个新端点/参数 curl 全 200；history 返回 durationMs=14000（返工后）。

### 对抗审查（reviewer，全新上下文）与返工

- **需返工 3 项已修复**：① history 缺 durationMs/lastPositionSeconds→"已看完"徽标失效（history.sql SELECT 补列+组装+测试断言+curl 验证 14000）；② 维护页未交付（system/status 轮询+曲线+空态，本次补齐）；③ 搜索"播放次数"误映射 viewRange→playRange（DOMAIN_RULES §3 两行口径分开）。
- 审查独立重跑：go test 14 包全绿、tsc 干净、生成物符号核验通过、mock 残留 0。

### 已知限制（记账）

- 搜索首查较慢（FTS5 子串 instr 全表扫描，非本次引入；6413 文件约 2-4s）。
- 相册「作品/角色」聚合维度端点待做（阶段 B 已接分区+类型两维）；设置页三卡保存待配置端点；客户端异常上报通道未建（维护页空态）；ArtPlayer 播放器 UI 待做；SW 旧缓存会短暂显示旧 UI（PWA 特性，清缓存/等待新版接管）。
- 数据页内容榜子页固定全周期（无周期胶囊）；"本周"按周一为起点。

---

## 本机启动脚本开启开发免密登录：QIMENG_AUTH_DEV_MODE=1（2026-09-03）

执行 AI：DeepSeek-V4-Flash-Vision-Exp（主代理，ZCode）

用户需求："客户端启动服务器登录就要密码，输入后还显示密码错误，在没彻底做好之前都不需要密码"。根因：服务端 `auth_dev_mode` 默认 false 且 `启动服务端.bat` 未设置该 env——web 端免密通道（LoginGate 挂载自动调 `/auth/dev-login`）404 后回退到密码表单，输错密码（管理密码记录为 test-password-001）即报"密码错误"。按 HANDOVER 用户约定（项目未完成前不要密码流程）恢复免密。

### 变更

- `启动服务端.bat`：新增 `set QIMENG_AUTH_DEV_MODE=1`（英文注释注明生产/远程部署必须移除，指向 docs/SECURITY.md「开发模式」节）。
- 文档：SECURITY.md「开发模式」节新增"本机开发脚本"说明；HANDOVER.md 用户约定第 1 条同步（已默认写入启动脚本）。

### 实测（用户 PC 实机）

- 重启服务（旧的 qimeng.exe 进程已停止）后：`POST /api/v1/auth/dev-login` → 200 返回 64 位 hex token；带 token `POST /api/v1/auth/verify` → 204；首页 SPA 正常（title 绮梦影库）。
- 用户浏览器刷新 `http://127.0.0.1:8420` 即免密直达 UI；旧 token 因 dev-login 重铸失效属预期（与 /auth/login 同语义，签发即重铸）。

---

## 阶段 B 浏览链路接真实数据：首页推荐流/相册/详情页（图片大图+视频直链播放）（2026-09-03）

执行 AI：GLM-5.3-Flash（主代理，ZCode）

用户需求："我自己添加库去测试，可以显示实际内容"——浏览面从 mock 换成真数据。服务端零改动（M1~M4 协议基座全就绪），纯 Web 接线。

### Web 实现

- `hooks/use-assets.ts`（浏览面数据层）：`useRecommendations`（GET /recommendations，M3 十维算法）、`useAssetsInfinite`（GET /assets 游标无限滚动，参数含 libraryId/mediaType/source/sort/order/q）、`useAssetDetail`（GET /assets/{id}，签名原件直链）、`useSources`（GET /sources 出处分组计数）、`useReportView`（POST /events/view 行为上报）。
- `lib/format.ts`：formatDuration/formatShortDate/formatBytes（卡片角标与详情信息共用，纯函数）。
- **HomePage**：推荐流真数据（60 张签名缩略图卡 + 真推荐排序），卡片点击进详情；mock 卡片数据退役（MOCK_HOME_CARDS 删除）。
- **AlbumsPage**：真数据两段式筛选——分区维度=GET /sources（47 组真出处计数，null 名兜底"其他"）、类型维度=MediaType 三档；排序文案映射协议（精选=default/最新=最旧=fileDate/按名称=name）；「加载更多」游标分页。mock 的"作品/角色"维度待聚合端点（记账 HANDOVER_UI §5）。
- **AssetDetailPage**（新，/app/asset/:assetId）：图片/动图=签名原件直链大图（.asset-stage 黑底 contain）；视频=原生 `<video controls>` 直链播放（ArtPlayer 播放器 UI 升级为独立后续任务）；**不兼容编码提示**（hevc/av1 等黄条提示，不转码——"永远发原件"约定）；进入即上报 open 事件（会话去重）；信息卡（文件名/大小/出处/时长/浏览播放次数）。prototype.css 追加段 +3 条（asset-stage/codec-warn）。
- router 注册 /app/asset/:assetId； mock.ts 清理退役数据。

### 实测（用户 PC 实机库：样例测试 90 图 + 测试收藏库 7355 文件/434 视频）

- 首页 60 张卡全部签名缩略图渲染、0 坏图、真推荐排序；点卡片 → 详情大图（orig 直链 200）。
- 相册分区维度真出处计数（守望先锋 242/火焰纹章 108/英雄联盟 67/崩坏 星穹铁道 59…）；类型=视频 → 434 个视频列表。
- 视频详情页 `<video>` 直链播放（用户本人验证 OK）；open 打点入库。
- 过程修复：类型维度点击值误用 label（"视频"）而非枚举（"video"）致筛选空——label/value 拆分；SW 旧缓存干扰实测（unregister+caches 清理后正常，属测试环境非产品问题）。

### 已知限制（记账）

- 存量视频 durationMs/codec 为 null（M4 已知限制：size+mtime 未变不触发重探）——卡片无时长角标、兼容性提示不生效（播放本身不受影响）。
- 搜索页/集合页仍为 mock 池（其点击不产生真实资产跳转）；搜索接 FTS5、我的页 /stats 接线待做。

---

## 库启用/停用开关 + ADR-0012 库类型可扩展体系（2026-09-03）

执行 AI：GLM-5.3-Flash（主代理，ZCode）

用户需求：②库加开启/关闭选项——**关闭但不删除任何记录**（停用=浏览面隐藏，可随时恢复）；③把"库 = 内容组织方式 + 识别方式 + 展示方式"的体系理解写入文档，方便以后 AI 按 ADR 接入全新文件夹方式的库。另确认①的既有讨论：浏览器直链不兼容的编码（HEVC/AV1 等）播不了——方案 = ffprobe 编码检测（协议 0006 已铺）+ 前端按 codec 提示，**不做转码**（与"永远发原件"强偏好一致，转码档位在 M6+ 储备）。

### 数据库（migration 0007，只加不改 ADR-0011）

- `libraries.enabled INTEGER NOT NULL DEFAULT 1`；down 逆序 DROP COLUMN。TestMigrateDownThenUp 插入 0007 第一步（后续步骤顺延）。

### 协议（api/openapi.yaml → make sdk 重生成）

- `Library.enabled: boolean`（default true）+ 新端点 `PUT /api/v1/libraries/{libraryId}/enabled`（body {enabled}，204/404）。

### 服务端

- 4 个用户面查询加库开关谓词（browse.sql 两个列表+计数、recommend.sql 推荐输入池——搜索谓词在 browse 主查询内一并覆盖）：停用库资产从浏览/搜索/推荐消失。
- 有意不过滤的边界（migration 注释固化）：详情/签名直链（已获取 assetId 稳定）、管理面库列表（文件管理页需看到并重新启用）、磁盘文件/扫描、统计与事件流（用户拍板"不删记录"）。
- handler `PutApiV1LibrariesLibraryIdEnabled`（先查存在性 → SetLibraryEnabled → 广播 library.changed）；ListLibraries 响应带 enabled。
- 新增 `TestLibraryEnabledFilter`（停用→列表空/管理面保留 enabled=false/详情 200/启用恢复/未知库 404）+ TestMigrateDownThenUp 扩步；go test ./... 全绿。

### Web

- `use-libraries.ts` 加 `useSetLibraryEnabled`；文件管理页库表格加「启用」开关列（settings-switch 样式，toggle 即时生效 + toast 反馈）。

### 踩坑记录

- sqlc 对 SQL 注释/谓词顺序敏感：`WHERE AND EXISTS` 语法错误（谓词作首条件不带 AND）；flow mapping 内 description 含 `{libraryId}` 花括号被 YAML 解析为嵌套集合（redocly "missed comma"）——协议描述禁用花括号字面量。

---

## 维护页实际功能：文件管理（库管理）+ 回收站子页（2026-09-03）

执行 AI：GLM-5.3-Flash（主代理，ZCode）

用户需求：①我的页关注列表不出现未关注作者；②维护页「文件管理」「回收站」两张入口卡从 mock 占位变成实际可用的子页面——文件管理 = 管理文件库（用户自己添加/删除库来测试的入口，2026-09-02 拍板）；③另回答了"早期局域网看视频"问题（验收页仍在，新 UI 接视频待拍板）。

### 协议（api/openapi.yaml → make sdk 三端重生成）

- 新端点 `DELETE /api/v1/libraries/{libraryId}`（删库此前协议/服务端均缺，M1 只建了 store 查询）：204/404。删除语义 = 库登记行级联清除该库全部 assets 及关联（外键 ON DELETE CASCADE，migrations/0001）；**view_events 事件流保留**（历史统计，0001 注释不变量）；**磁盘文件与回收站条目不动**（铁律 4）。

### 服务端

- httpapi/libraries.go `DeleteApiV1LibrariesLibraryId`：先查存在性（DeleteLibrary 对不存在 id 影响 0 行不报错，无法事后区分 404）→ 删行 → 广播 library.changed。已知限制：扫描进行中删除 → 扫描 goroutine 的 upsert 因外键失败自然终止（调试场景可接受，注释说明）。
- browse_test.go `TestDeleteLibrary`：204 + 列表移除 + browse 空数组（级联断言）+ 重复删 404。go test ./... 全绿。

### Web（阶段 B 首批真数据页面）

- hooks：`use-libraries.ts`（列表/注册/重扫/删库）、`use-trash.ts`（列表/恢复/单删/清空）——TanStack Query + unwrapSdkResult，铁律 7 合规。
- `pages/LibraryManagePage.tsx`（/app/maintenance/files）：库表格（名称/类型/路径/计数/扫描态 + 重新扫描/删除 confirm）+ 注册表单（名称/绝对路径/kind select，注册成功自动补发扫描——POST /libraries 语义不自动扫）+ 目录浏览卡（/dirs DirTree 递归，未选库禁用查询）。
- `pages/TrashPage.tsx`（/app/maintenance/trash）：条目表格（原路径/大小/删除时间）+ 恢复（冲突自动重命名）+ 彻底删除 + 清空，三者全部 window.confirm 二次确认（原型风格弹窗阶段 B 后续替换）。
- MaintenancePage：两卡接导航；文件管理卡去「接入点预留」badge；回收站卡计数改真实数据（useTrash）。
- MinePage：关注作者列表只显示已关注（用户拍板）。prototype.css 追加段 +2 条最小样式（entry-card--link / settings-field select）。

### 实测（临时测试库全链）

注册「临时测试库」→ 列表即时出现 → 扫描 fileCount=3 → 目录树（根 2 + 子目录A 1）→ API 删 1 资产 → 回收站页 1 条（入口卡计数同步 1）→ UI 恢复 → 空态且文件归位磁盘 → 再删 → UI 彻底删除（confirm 文案正确）→ 空态 → 删库 204/重复 404 → 磁盘文件全部保持（恢复归位/彻底删除真物理删均符合语义）→ 临时目录清理。

---

## 排行榜行点击 → 标签/作者集合子页（2026-09-03）

执行 AI：GLM-5.3-Flash（主代理，ZCode）

用户反馈：数据页排行卡与完整榜单页的标签/作者行点击无反应，应与旧项目一致——点击弹出类似相册的子页面，列出该标签/作者的所有文件。本次补齐该交互闭环（阶段 A mock 语义）。

### 实现（web/）

- 新集合子页 `pages/CollectionPage.tsx` + 路由 `/app/collection/:kind/:name`（kind=tag/author）：page-head（名称 + 「标签/作者 · N 个文件 · mock 数据」）+ 相册同款 `.media-grid` 媒体卡网格 + 空态；侧栏返回按钮走历史栈可回退。
- 数据联动（mock 内自洽）：`pages/mock.ts` 新增 `TAG_AGG`（分区维度值 × 内容池计数，降序）、`AUTHOR_AGG`（MOCK_AUTHORS × up 出现次数，降序）、`filesByTag/filesByAuthor`（hitTag 同语义/up 子串）与 `COLLECTION_TAG/AUTHOR` kind 常量；数据页 Top5 与完整榜单页全量行的名称/数字改由聚合**派生**（原写死假数字与 mock 池脱节，点开必为空态；卡片样式零变化，阶段 B 换真聚合接口）。
- 行点击接线：DataPage `RankList` 加 `onSelect`（标签榜/作者榜/作者总览三处）；RanksPage 行 onClick（内容榜 rank-item 不动——单文件详情页属阶段 B）。

### 验证

- tsc + build 全绿；浏览器实测：数据页点「样例」→ /app/collection/tag/样例 4 卡 ✓；点「绮梦」→ /app/collection/author/绮梦 3 卡 ✓；完整标签榜（7 行真实计数）点「摄影」→ 2 卡 ✓；侧栏返回回榜单页 ✓。

---

## UI 原型移植 web 端 React 重建（阶段 A · mock）（2026-09-02）

执行 AI：GLM-5.3-Flash（主代理 + 2 执行子代理 + 1 审查子代理，ZCode）

`media-ui-prototype/` 原型敲定后按既定顺序第 1 步：九页移植进 web 正式代码，视觉/交互与原型逐像素对齐；数据仍为 mock（阶段 B 接真实 API）。

### 移植产出（web/）

- `styles/prototype.css`：原型 style.css 原样迁移（类名/对齐负 margin 零改动），main.tsx 中后于 index.css 导入使原型变量层最后生效；追加段仅 2 条（#root 高度 + `.rank-cards` 窄屏单列回退——HANDOVER_UI §5 记账债）。审查修复：`*` reset 注释说明与 preflight 等价、`button` reset 收窄 `.layout`（原全局无层级规则会压过 shadcn utilities）、`--accent` 让位（原型激活色统一走 `--qm-primary`，不再改写基建层 hover 灰）。
- `components/shell/`：AppShell（侧栏+顶栏+内容区+悬浮刷新，切页滚顶）、Sidebar（返回=历史回退/导航/主题月亮/维护/设置）、TopBar（推荐/cos/排行榜 tabs 挂 `/app/home?tab=` searchParams、搜索框+历史面板+窗口键装饰、排行榜周期行单选重置日榜）、icons（原型 SVG 逐字复刻）。
- `components/media/MediaCard.tsx`：合并原型 renderCard/mediaCardHtml（HANDOVER_UI §5 记账债第二笔）。
- `pages/` ×10：九页组件 + mock.ts（原型数据搬运，含 12 张首页卡全量、ALBUM 四维、AUTHORS 15 位、搜索标签池；阶段 B 接真数据时删减）。封面 14 张入 `web/public/covers/`，mock 引用根绝对路径 `/covers/...`（嵌套路由下相对路径会 404）。
- `router.tsx`：九页 lazy 挂 `/app` 下 AppShell；根路径 Navigate 到 /app/home。
- 主题：`lib/theme.ts` 手动选择优先（localStorage `qimeng_theme`，月亮按钮 toggle）+ 无选择跟随系统；**修 initTheme 不恢复 stored 选择的 bug**（刷新丢深色）；index.html 首帧内联脚本与 theme.ts 语义统一（读 stored、守卫系统同步、theme-color #0f0f0f/#ffffff 双写互指）。
- 常量收敛：`LOCALE_ZH`（zh-Hans-CN ×2）、`RANK_CONTENT/TAGS/AUTHORS`（rank 键单一来源）。

### 验证

- tsc（`-p tsconfig.app.json`，注意根 tsconfig 是 solution-style 直接 `tsc --noEmit` 是假通过）+ vite build + PWA 全绿；oxlint 11 warnings 0 errors（9 条 router lazy fast-refresh + 1 条 SearchPage useEffect 重置态——语义所需已注释 + 1 条基线）。
- 浏览器实测（IAB 1188×742，**禁 page-in 动画后测量=静止态等价**；IAB 文档恒 hidden 动画时钟冻结，属环境特性）：八页对齐纪律零偏差——首页网格 83.1、我的/设置卡 82.9、相册维度行 89.2、数据时段行 89.3、维护/榜单/作者 h2 顶 80.5，横向全部在 96.8 内容线上，搜索框中心与顶栏中心重合 629.2。
- 交互冒烟（DOM 事件派发，IAB 吞合成输入属环境限制）：排行榜 tab→周期行 ✓、相册维度/值/排序/空态 ✓、数据页→榜单子页/作者管理 ✓、作者搜索/排序/关注 toggle/空态 ✓、搜索回车→类型计数/标签池增删级联 ✓、我的三 tab/历史搜索/关注 ✓、主题 toggle+持久化 ✓、保存提示 ✓。
- reviewer 对抗审查：P0 首页 12 卡补回（误删 2 张广告卡——原型删的是卡内元素不是卡片）、P1 index.html/theme.ts 统一、prototype.css 全局污染收窄，全部修复复测。

### 已知差异与记账

- 顶栏 tab 激活态挂在 URL（离开首页即丢，回首页重置"推荐"）——原型 JS 态跨页保留，URL 驱动为 web 语义，已记录待用户裁决。
- 作者页搜索图标复用统一 SearchIcon（r=8 vs 原型 7，差 ≤1px）；sonner toast 主题仍跟系统（next-themes 未接，记账）。
- oxlint：router lazy fast-refresh 警告 ×9 属模式性警告，不修。

---

## M4 播放端协议与服务端基座（2026-09-02）

执行 AI：GLM-5.3-Flash（主代理，ZCode）

播放器接入（Web 用 ArtPlayer / Android 用 Media3）的地基：断点续播 + 编码元数据。播放器 UI 接线不在本条目范围。

### 协议（api/openapi.yaml，redocly 通过 → make sdk 三端重生成）

- 新端点 `PUT /api/v1/assets/{assetId}/progress`：断点续播进度上报（ProgressUpdate.positionSeconds，秒）。语义 = **最新状态而非统计事实**：服务端只保留每资产最新位置，不写 ViewEvent 事件流、不参与 playCount（DOMAIN_RULES §5 播放计数仍走 play 事件 + 会话当日去重）；建议客户端 10s 心跳 + 暂停/离开播放页各补一次。
- AssetSummary 新增 `durationMs`（自 AssetDetail 上移，allOf 展平后 Go 生成物无变化，TS/Kotlin 列表页即取时长）与 `lastPositionSeconds`（nullable；"已看完"口径 = >= durationMs/1000 由客户端推导，服务端不算徽标）。
- AssetDetail 新增 `videoCodec`/`audioCodec`（nullable，ffprobe codec_name）——Web 端据此判断浏览器直链兼容性（null = 尝试播放、失败再兜底）。

### 服务端

- migration `0006_playback`（只加不改，ADR-0011）：assets 加 `last_position_seconds REAL` + `video_codec/audio_codec TEXT` 三列；down 逆序回滚；进度不建独立表、不加事件 kind（挂 assets 行，删除资产即连带清理，无孤儿数据）。
- ffprobe 解析扩展（thumbnail/ffmpeg.go）：ProbeResult 增加 VideoCodec/AudioCodec；ProbeVideo 由"循环内遇首个视频流即 return"改为"全流扫描后构造"——音频流可能排在视频流之后，提前 return 会漏采。
- scanner 接线：入库/重探时把 codec_name 写入新列（空串转 NULL）。**已知限制**：size+mtime 未变的存量视频不触发重探（变更检测跳过 ffprobe），codec 维持 null 直到文件变化或重扫策略扩展。
- 详情查询 GetAssetWithLibrary 补三列；AssetDetail 响应带出 lastPositionSeconds/videoCodec/audioCodec（列表接口本轮不带——消费场景是播放页先取详情；列表带出留待浏览历史端点设计时一起）。
- 新 handler `PutApiV1AssetsAssetIdProgress`（httpapi/playback.go）：UPDATE 影响行数 0 → 404（assets 行删除即回收站/物理删除后，无需先查存在性）；进度上报不 bump updated_at（播放器状态而非内容变化）。

### 测试

- playback_test.go：上报/读回 + 覆盖语义（二次上报替换不累加）+ 未知资产 404 + 不产生事件流行数断言。
- TestMigrateDownThenUp 增补 0006 回退步骤（该测试按设计随 migration 演进扩步）。
- ProbeVideo 集成测试补 codec 断言（lavfi 合成 mp4 = h264/无音轨），新增带音轨用例（h264/aac，验证音频流后置时不漏采）。

---

## UI 原型：榜单改造 + 作者管理页 + 对齐实测动画污染修正（2026-09-02）

执行 AI：GLM-5.3-Flash（主代理，ZCode）

### 数据页排行榜

- 数字徽章全删（15 个含前三名彩色），榜单改纯行式；内容榜独占全宽一行（条目为首页同款封面卡：16:9 封面 + 底部渐变 + 右下数值徽标 + 两行标题，数据页 5 张/子页 15 张网格）。
- 标签榜/作者榜/作者总览三卡等宽并排（3 列轨道，rank-grid 复用）。
- 作者总览卡（终态）：行式列表与左右两卡同构（作者名+作品数 Top 5）+「管理」入口进作者管理页 + 统计行；中间经历过的字徽胶囊流样式已按用户要求删除（author-chips 规则清零）。

### 作者管理页（新子页 #page-authors）

- 「作者总览·管理」进入（不再跳我的页关注作者），显示全部 15 位作者（mock：12 常规 + 3 COS）。
- 体系胶囊（全部/常规/COS，DOMAIN_RULES §6 双体系）+ 旧版排序三项（默认/经常浏览/文件数量，搜索页 sort-pill 同款）+ 名字搜索实时过滤；行 = 作者名 + 关注按钮（内存态 toggle；体系小标与作品数按用户要求删除，体系仅作胶囊筛选用）；列表行字徽头像按用户要求删除。
- **标题行视觉对齐基准修正（用户拍板）**：大标题（page-head h2）的对齐基准由「nav-item 整体中心」改为「侧栏首页图标块顶部」——26px 的 h2 与 26px 图标块顶对齐即视觉对齐（数字上中心对齐但视觉上用户仍判不对齐的根因）。三页 page-head -4.5→-13.8px，实测 h2 顶 80.5 vs 图标顶 80.8（差 0.3px）。

### 数据页作者总览卡

- 卡内实现与标签榜/作者榜统一（rank-card 行式列表：作者名+作品数，Top 5），字徽胶囊流样式删除（author-chips 规则清零）；「管理」入口与统计行保留，三卡等高（grid 拉伸，无固定高度）。

### 对齐实测动画污染修正（HANDOVER_UI §4.5 新增第 5 条）

- 根因：`.page:not([hidden])` 的 page-in 动画（0.18s translateY 4px）污染切页瞬间的实测——此前入档的负 margin 全是动画中间帧假值，静止态普遍偏差 4~5px（用户肉眼发现后揪出）。
- 静止态重校 8 处（终值）：三页标题行 -13.8（视觉基准=图标块顶，见下）、数据 seg-row -5.8、相册 filter-card -5.9、搜索 stype-row -14.9、我的/设置卡 -11.6、**首页网格 -11.5**（卡片顶沿 83.1 与我的/设置卡 82.9 同线）。修后全量复测偏差 ≤0.6px。
- **大标题视觉对齐基准修正（用户拍板）**：page-head h2 的基准由「nav-item 整体中心」改为「侧栏首页图标块顶部」——同高块顶对齐才视觉齐平（数字中心对齐用户仍判不对齐的根因）。
- 流程修正入档 §4.5 第 5 条：此后对齐实测必须等动画结束（300ms）再取 rect。

### reviewer 审查修复（对抗性审查，总评可交付）

- `--elev: var(--elev)` 自引用（深色表层面 token 失效致 .card--cover/.search input 深色背景透明）→ 落实际值 #26262a，.dark .pop-chip 字面量同步收编为 var(--elev)。
- 删 `.a-list b` 死规则（作品数元素已按要求移除）与 `.rank-grid--2` 冗余声明（与 .rank-grid 定义重复）。
- 留档建议（移植 web 前处理）：renderCard/mediaCardHtml 输出结构相同应抽共享；.rank-cards 未纳入窄屏单列回退。已记 HANDOVER_UI §5 第 7 条。

---


## UI 原型对齐修正（2026-09-02）

执行 AI：GLM-5.3-Flash（主代理，ZCode）

### media-ui-prototype（用户逐项反馈修正）

- **顶栏激活横线对齐参考截图**：修复 `.tabs` 类名撞名——「我的」页 `.tabs { border-bottom + padding-bottom:10px }` 污染顶栏 tab 行，致 tab 行下方多出全宽浅色底线、按钮上移 5.8px；我的页规则作用域限定为 `#page-mine .tabs`。激活短横线（`.tab.active::after`）由贴字位置（bottom:8px）下移至文字下方约 0.9 倍字高处（bottom:-6px，实测间隔≈16 CSS px），与桌面客户端截图逐像素测量一致。
- **导航行与侧边栏逐项纵向对齐**（用户拍板，参照客户端热门页结构）：热门态 tab 行保持可见，「综合热门/排行榜」为顶栏下方独立行、行中心对齐侧栏「首页」项中心（subnav padding-top 2px + 侧栏 nav margin-top 14px）；「日/月/周/年」为二级行下方独立行、行中心对齐侧栏「相册」项中心（margin-top 23px）；返回箭头下移 6px 与顶栏 tab 行中心对齐。实测三组中心偏差 ≤0.5px。切页/切 tab 时日月周年行同步收起（防残留）。
- **排行榜导航精简为单行**（用户拍板）：顶栏第三 tab「热门」更名「排行榜」；「综合热门/排行榜」二级行整体删除（subnav/subtab 样式与逻辑清零）；「日/月/周/年」改为「日榜/月榜/周榜/年榜」并上移占据原二级行位置（行中心仍对齐侧栏「首页」项，偏差 0.4px）；切走再切回自动重置日榜。
- **右下角悬浮刷新**：视口右下固定悬浮按钮（白底圆角卡 52px + 深色刷新图标 + 投影，全页面常显、不随内容滚动）；静态原型点击仅图标旋转一圈反馈，未接真实刷新。
- **搜索结果页**（用户拍板）：顶栏搜索框回车或点历史词进入；类型 tabs（综合/视频/图片/音频 + 实时计数徽标，「综合」无徽标参照截图）；排序行只保留「综合排序/最多点击」（删最新发布/最多弹幕/最多收藏）；「更多筛选 ▼」面板按用户反馈精简为六行：顺位/播放次数（观看次数删除）/文件大小/时间范围（点「按年份区间」展开起止年下拉）/标签模式（模糊·精确单列一行）/标签（多选）；分区/作品/角色/作者四行删除（维度筛选保留在相册页）。标签池支持增删（DOMAIN_RULES §7 口径）：「+ 添加」→ 内联输入回车/失焦入池（去重、过滤非法字符、按名称升序展示），标签胶囊 hover 显 × 删除并级联清理筛选选中态；原型为内存态刷新即还原。类型与标签为真实过滤（mock 13 文件，新增 1 条音频），「最多点击」按播放数真排序；区间类（播放/大小/时间/年份）无 mock 数据仅样式切换。搜索框有词显示清除按钮；每次新搜索重置上次筛选。
- **全页面首行贴侧栏节奏**（用户拍板，AskUserQuestion 确认方案）：各页第一行按搜索页同款纪律对齐——相册维度行/数据时段行/维护标题行中心 ↔ 侧栏「首页」项中心（margin-top -10/-11/-9px，实测偏差 0.3/0.8/0.1px），我的资料卡/设置表单卡等高卡片顶沿贴顶栏下缘（-16px，实测齐平）；首页卡片不受影响。锚点定值已写入 HANDOVER_UI.md §4.5 纪律第 3 条。
- **我的页/相册页整改**（用户逐项反馈）：搜索历史「清空」修复（.pop-block 的 display 未补 [hidden] 规则致失效，现整块隐藏）；相册筛选区去卡片盒（边框/底色/内边距清零，行缘与网格 24px 线同线）；我的页删除头像（资料卡+关注列表）并简化关注卡为「作者名 + N 个作品」；关注按钮接通双向切换（已关注灰描边 ↔ 关注主色实底）；浏览历史卡弃缩略占位改真实封面（与首页同款图+徽标浮层）；历史搜索框接通真实过滤（标题/作者子串、不分大小写、空组连标题隐藏）。
- **跨页宽度跳变根治**：.content 加 scrollbar-gutter: stable——有/无滚动条的页面内容等宽（此前我的-关注页比滚动页宽 6px，切页整体跳宽）。全页扫测：五页所有容器 left 96.8 / right 1247.3，spread=0。
- **修复清除按钮未搜索时常驻**（用户反馈）：`.search-clear` 的 display:flex 覆盖 hidden 的 UA 默认 none（与 .page/.tabs 同类坑，第三次踩中），补 `.search-clear[hidden]{display:none}`；HANDOVER_UI.md 踩坑记录泛化为通用规则——带 hidden 用法的类必须补 `[hidden]` 显式规则，新增组件自查。
- **侧栏返回按钮接通历史栈**：此前返回按钮无事件（纯装饰）。现 showPage 记录页面历史（同页不重复压栈），返回按钮回退到上一页——搜索后返回进搜索前所在页（首页/相册等均正确）、tab 显隐与周期行状态随之恢复，无历史时不动（原型内存栈，刷新还原）。
- **搜索页对齐修正 + 对齐纪律入档**（用户拍板"做好对齐、写入 UI 开发规则、不再反复提醒"）：搜索筛选面板 margin 24px→0，与类型行/工具栏/结果网格左右边缘同线（实测五块容器 left spread=0、right spread=0）；类型行/排序行套用侧栏纵向对齐纪律——类型行按钮中心 ↔ 侧栏「首页」项中心（margin-top -20px）、排序行胶囊中心 ↔ 侧栏「相册」项中心（padding-top 2px），实测中心偏差 0.1/0.4px；HANDOVER_UI.md 新增「§4.5 对齐纪律」硬规则——每次 UI 改动交付前必须浏览器实测横向同线/列内对齐/纵向对齐并附数字，目测不交付。
- 文档同步：HANDOVER_UI.md（最后更新行、§4 导航描述、§4.5 对齐纪律、原型踩坑与对齐规则记录）。

---

## UI 原型收尾 + v1 旧壳删除（2026-09-01）

执行 AI：DeepSeek-V4-Flash（主代理，ZCode）

### media-ui-prototype 桌面客户端风格原型（用户拍板的主路线，全部 mock）

- 侧栏（64px 窄栏：返回箭头 + 首页/相册/我的/数据 + 底部 明暗主题/维护/设置）；顶栏「推荐/cos/热门」只在首页显示，搜索框顶栏框内居中 + 搜索下拉面板（搜索历史胶囊可展开 + 推荐搜索占位）；热门二级导航（综合热门/排行榜 → 日/月/周/年，固定贴顶栏不随内容滚动）。
- 六页搬入（自 panel-demo，mock 数据）：相册（两段式胶囊筛选：维度切换/值行计数/超两行收起展开/即时过滤/空态）、我的（头部资料卡 + 关注作者/收藏作品/浏览历史历史流样式）、数据（时间筛选/指标/趋势/双环/排行三卡）、维护（性能圆环+网络曲线+工具卡+日志表）、设置（表单卡+保存提示）。
- 明暗主题切换（月亮按钮，`.dark` 全站深色变量）；媒体卡统一（封面+时长角标+标题+作者·日期，相册/收藏/首页同款）。
- 布局修正：整体放大 10%（`html{zoom:1.1}`）与 `.layout` 高度 100vh→100%（100vh 被 zoom 放大导致侧栏底部溢出）；`.page/.m-pane/.tabs` 的 `[hidden]` 显式 display:none（display:flex 覆盖 hidden 的堆放 bug）。
- 硬编码治理：重复颜色提为设计 token（--accent-2/--accent-3/--faint/--hover-bg/--icon-idle/--icon-faint/--ok）；删除死变量（--up-orange、未用的 --qm-* 项）与死代码（.hint 等）。
- 措辞中性化（用户要求）：原型内移除全部平台命名痕迹（标题/注释/数据字段/任务书），含目录改名 media-ui-prototype。

### v1/v2 旧 UI 删除（用户拍板：参考搬入界面的上一版 UI 全部删除，只保留当前原型）

- 删除 web 端 v1 旧壳（AppShell 五 Tab 用户端）：`web/src/pages/`（8 页 + _shared）、`components/{admin,asset,charts,organize,upload,viewer,misc}/`、`components/layout/AppShell.tsx`、`components/ui/` 中未被保留代码引用的文件、旧 hooks（use-assets/use-asset-detail/use-dirs/use-engagement/use-libraries/use-recommendations/use-sources/use-system-status/use-tags/use-timeline-tags/use-trash/use-upload/shared）。
- **v2（panel-demo）也删除**：`panel-demo/`（五页面板+mock）、`components/tremor/*`、`lib/tremor.ts`、`lib/format.ts`（无引用）、`tailwind-variants`/`recharts`/`@remixicon/react`/`motion`/`@radix-ui/react-*` 单包依赖（npm uninstall）。
- 保留：`components/auth/`、`components/layout/{AuthGate,RootLayout,SseBridge}`、`components/ui/{button,input,sonner}.tsx`、`hooks/{use-session,use-sse-events}`、`lib/{api-client,constants,sse,utils}`、`api/*`、`@immich/ui`（index.css 主题来源，保留）。
- `router.tsx` 重写为占位页 + `/app`（AuthGate 门禁占位）；**`npm --prefix web run build` 通过**（PWA 预缓存 9 项）。


| 文档 | 已更新 |
|---|---|
| docs/HANDOVER_UI.md | 全文重写（原型现状/六页/中性化/v1 删除/待办/工作树） |
| docs/HANDOVER.md | UI 路线现状与待办表述更新（v1 已删，原文参考措辞中性化） |

## Immich 风格主题 token + 开发免密通道（2026-08-31）

执行 AI：DeepSeek-V4-Flash（主代理，ZCode）

### Immich 风格主题移植（用户选型：只换主题 token，不动布局）

按用户要求以 Immich 为 UI 视觉基准（能力地图同布局类项目），从 `@immich/ui@0.86.0`（**MIT 许可**）提取完整色板（oklch 双套 light/dark，primary/success/danger/warning/info 全套 50-950 色阶）落地到 shadcn 变量层：

| 位置 | 改动 |
|---|---|
| `web/src/index.css` | 主色 #4250af（浅）/浅蓝 oklch(0.836 0.074 258.58)（深）；深色背景 #0a0a0a、卡面 #212121 档位；图表色换 Immich 语义色系；圆角 0.625→0.75rem（媒体卡片 12px 口径）；focus ring 跟随主色 |
| `web/src/tokens.css` | 圆角口径注释更新（`--qm-*` 映射层自动联动，组件零改动，符合 ADR-0008） |

字体保留 Geist（Immich 的 Google Sans 许可不开放）。设计规格存档 `dev-tools/immich-ref/DESIGN_SPEC.md`（仓库外参考目录）。

### 开发免密通道（用户约定 1：项目未完成前不要密码流程）

| 位置 | 改动 |
|---|---|
| `api/openapi.yaml` | 新增 POST /api/v1/auth/dev-login（security: []；404=未开启，生产语义上"端点不存在"） |
| `server/internal/config` | `auth_dev_mode`（yaml + env QIMENG_AUTH_DEV_MODE，默认 false） |
| `server/internal/httpapi/authapi.go` | dev-login：未开启恒 404；开启则免密签发 token（未初始化自动建 admin 占位；与 /auth/login 同语义，签发即重铸旧 token 失效） |
| `server/internal/httpapi/server.go` | topRouter 免鉴权清单双同步（dev-login 加入，注释标注协议联动责任） |
| `server/internal/httpapi/auth_dev_test.go` | 3 用例：关闭 404 / 开启自动初始化+签发可用 / 二次调用重铸且旧 token 失效 |
| `web/src/hooks/use-session.ts` | `useDevLogin`（retry: 0；404 是业务预期） |
| `web/src/components/auth/LoginGate.tsx` | 挂载时先试 dev-login，命中直进 UI；404/失败静默回退正常表单（生产零行为变化） |

### 测试库与约定记录

- 测试库：样例相册 `<本地相册目录>\相册\2017 12 9～10  样例`（90 张 JPG，kind=normal）注册进本地开发库（qimeng-data）——用户约定 3。
- 三条用户约定（免密调试/不单拉前端走 8420/测试库）已记录 docs/HANDOVER.md「用户约定」节；SECURITY.md 新增「开发模式」节（红线 5 单点例外边界与禁止组合）；GUIDE_API.md 认证行补 dev-login。

验证：make sdk-validate/sdk-go/sdk-ts 通过；`go test ./...` 全绿（新增 dev-login 3 用例）；web `tsc+build+lint` 全 0 错误；dev 模式实跑免密直达 UI。

---

## Web 适配补丁：/dirs libraryId 必填 + 详情库 ID 接线（2026-08-31，紧随 M3 收尾）

执行 AI：DeepSeek-V4-Flash（主代理，ZCode）

上一条提交把 `/dirs` 的 libraryId 标为必填后，CI 的 web job（先从 openapi.yaml 重建 TS 再 tsc）暴露前端调用点类型不兼容——本地曾因 TS 生成物未随 `make sdk` 重建而假绿（补丁起：本机 tsc 验证改用真实退出码且先重建生成物）。适配：

| 位置 | 改动 |
|---|---|
| `web/src/hooks/use-dirs.ts` | useDirTree 未传 libraryId 时查询禁用（协议必填参数，enabled 保护 + 防御性错误）；useDirCreate 入参 `libraryId` 改必填 |
| `web/src/pages/DetailPage.tsx` | MoveDialog 的 libraryId 改用 `AssetDetail.libraryId`（M3 收尾协议已补字段，替换"取第一库"workaround），库列表加载瞬态仍以第一库兜底 |
| `web/src/components/organize/MoveDialog.tsx` | 文件头接口文档更新（协议缺口的描述移除） |

验证：`tsc --noEmit`/`npm run build`/`npm run lint` 真实退出码全 0；CI web job 复跑可复现通过。

文档：`CHANGELOG.md` 本条。

---

## M3 后端收尾：遗留三项清零 + 协议债修复（2026-08-31）

执行 AI：DeepSeek-V4-Flash（主代理，ZCode；研究子代理摸底 + 主代理实施）

把 M3 完成后挂账的收尾尾巴一次清完，顺带清掉用户指认的测试残留路径：

| 改动 | 位置 | 要点 |
|---|---|---|
| 协议三处 | `api/openapi.yaml` | `/dirs` GET 参数与 POST body 的 `libraryId` 标 required（此前实现强制、协议未标——M2 UI 段记账的协议债）；`AssetDetail` 补 `libraryId`（多库场景客户端定位整理目标）；新增 `GET/PUT /sources/custom`（`CustomSources{names}` schema，整体替换）——make sdk 三端重建 |
| custom_sources 写入端点 | `httpapi/sources.go` + `scanner/enrich.go` | PUT=整体替换：服务端规范化（trim+去空+去重+升序）→ 持久化 kv_settings（`authoring.SettingKeyCustomSources`）→ 运行中 matcher 同步刷新（Scanner.UpdateCustomSources）→ 后台逐库 `RecomputeEnrichment` 存量重算（4 路并发，仅 normal 库；完成发 library.changed）。**存量传导关键**：资产 size+mtime 未变时全量扫描只跳过，不显式重算已入库 source 列永不更新。GET 无记录回空数组（匹配引擎空集即等价） |
| filing cos 库作者映射修正 | `scanner/enrich.go` `EnrichAsset`/`recomputeCosAuthor` + `scanner.go` `applyMoveMerge` | EnrichAsset 去掉 cos 早退：cos 分支按当前 rel 首段目录重算（先删旧关联再挂新作者；库根直放只清不挂）；扫描移动合并（作者目录整体改名场景）在改路径后同样重算。normal 库行为不变（文件名不变不重算是正确语义） |
| 孤立 COS 作者清理 | `scanner/enrich.go` `cleanupOrphanCosAuthors` + 新查询 | 全量扫描收尾与 fsnotify 增量删除后自动清理零关联 cos_ 作者（旧项目 deleteOrphanCosAuthors 语义）；全库范围（隔离口径下 normal 资产不会关联 cos_ 作者）。清空后出现在 /authors 列表的残留作者不再需要人工处理 |
| 新查询 | `store/queries/authors.sql`（sqlc 重建） | `DeleteAssetAuthorsByAssetID`（先删后插覆盖）、`DeleteOrphanCosAuthors`（NOT EXISTS 清理）、`ListAssetsForEnrichmentByLibrary`（存量重算输入） |
| 测试 | `scanner/enrich_test.go` +4、`httpapi/sources_test.go` +1、`browse_test.go` 断言 +1 | cos 单资产移动重算/作者目录改名移动合并重算+旧作者清理/删除目录后孤立清理/自定义出处存量重算；custom 端点闭环（空→PUT 规范化→回读+持久化形态一致→清空）；详情携带 libraryId |
| 环境清理 | `server/data/` 已删除 | 用户指认的"测试用路径"：config 默认 `./data` 的裸启动残留（空库：0 库/0 资产/0 用户 + media-secret），与正式数据目录 `qimeng-data/`（测试媒体库 7347 文件）无关；另清 HANDOVER 中对已不存在临时文件的路径引用 |
| 卫生顺手 | `httpapi/authapi.go` | 上上条 commit 带入的注释 `///` 笔误与 CRLF 行尾修正（gofmt 合规；CI golangci 不查 gofmt 所以漂移至今） |

验证：`go test ./... -count=1` 15 包全绿（新增 ~7 用例）、golangci-lint 0 issues、`gofmt -l` 空、`go vet` 干净、redocly 0 error（make sdk 走完验证链）、web `tsc --noEmit` 通过。

文档：`GUIDE_API.md`（自定义出处机制 + 端点表）、`DOMAIN_RULES.md`（§4 自定义出处/§6 COS 作者清理与目录变更/§9 cos 移动映射 + 最后更新）、`HANDOVER.md`（进度/待办/环境清理）、`CAPABILITY_MAP.md`（M2/M3 三态同步）、`PROJECT_PLAN.md`（M3 收尾勾选）、`CHANGELOG.md` 本条。

---

## 线上实机部署与登录链路修复（2026-08-30）

执行 AI：GLM-5.3-Flash（主代理）

线上实机暴露的死锁缺陷：token 仅 setup 响应明文一次，换浏览器/清缓存/新设备后无任何找回通道（前端 verify 模式要求粘贴当年 token，实际不可用；实机表现为用户浏览器永远无法进入）。修复：

- 协议：新增 `POST /auth/login`（security: []，请求复用 AuthSetupRequest，响应 AuthToken；401=密码错误）——make sdk 三端重建
- 后端：argon2id 比对 + 单用户单 token 重铸（登录成功新 token 覆盖旧哈希，旧 token 失效）；topRouter 免鉴权清单同步（与协议双写的第二处）
- 前端：LoginGate「已初始化」分支从"粘贴 token"改为"密码登录"（useAuthLogin hook），登录成功 token 落 localStorage 后无需再输
- 测试：3 用例（正确密码换新 token 且旧 token 失效/错误密码 401/连续登录各自有效）；go test 全绿、lint 0、web tsc + build 通过

- 第二层修复（commit 3d0d908）：`unwrapSdkResult` 把 hey-api 的 HTTP 状态码并进错误对象——错误体只有 {code,message}，LoginGate 判断 409 永远失败，把"已初始化，请登录"误判成"初始化失败，请重试"（M2 冒烟只测过全新库 setup 成功路径，409 分支从未走到）。此修解释了用户实机看到的全部报错。

**实机数据**：注册「测试收藏库」库（<本地相册目录>\1\HHH）扫描 7347 文件（图 6917/视频 430/20.7GB）；导入 `4 作者` 两个 TXT（122 位作者、824 关联）；出处自动匹配 47 组（守望先锋 232 等）。用户浏览器实测登录进主界面。实机状态与明天待办见 `HANDOVER.md`「线上实机状态」节。

文档：`GUIDE_API.md`（认证行）、`HANDOVER.md`（线上实机状态节）、`CHANGELOG.md` 本条。

---

## M3 后端算法移植全量完成（2026-08-30）

执行 AI：GLM-5.3-Flash（主代理 + 执行子代理 A/B/C 三路并行/串行 + maker-checker 审查）

M3 六项中五项后端完成（推荐偏好设置页属 UI 按用户指示不动），501 stub 全部清零（notImplemented 机制退役）：

| 模块 | 产出 |
|---|---|
| stats 统计 | migration 0005（asset_daily_stats 文件×天物化表 + authors.followed + libraries.kind）；`internal/stats` 趋势分桶纯函数（周一对齐/动态分桶/总和守恒，旧项目 StatsFormatHelperTest 7 例全译+6 补充）；/stats/overview /stats/trends 接线；打点路径 open/play 会话去重（dwell 秒数累加不去重）+ 物化表同步累加 |
| sourcematcher | `internal/sourcematcher` 130 组内置检索表整表翻译（475 变体/1388 角色——**131 系旧文档把 data class 定义行误计，DOMAIN_RULES §4 已勘误**）+ 匹配引擎（长度降序前缀/角色剥离数字保护/多出处"+"分段/结果缓存 8192）；旧 SourceMatcherTest 23 例照译+7 新增（文档化无测试行为） |
| authoring 作者体系 | `internal/authoring` TXT 三格式解析 + 文件名匹配规则（精确/序号括号容错/hasExt 判定）+ authorId 生成（旧 AuthorImportUseCaseTest 13 例照译；分片存储系旧 Android CursorWindow 规避不实现）；authors.sql + GET /authors、POST /authors/import-txt（统一重建：跨 TXT 并集）、PUT /authors/{id}/follow 三端点接线 |
| scanner 富化 | ingestFile 按 libraries.kind 分派：normal 走 SourceMatcher（source+asset_characters 落库）、cos 按目录结构建 cos_ 作者；移动/重命名（filing 写入路径）显式重算 EnrichAsset（文件名变而 size+mtime 不变不会触发重 ingest）；自定义出处从 kv_settings 装载 |
| 迁移端点 | 协议补 LegacyBackupImport 17 段 schema + LegacyImportResult（此前 /import/qimeng-backup 零建模，违反协议先行已修正）+ make sdk 三端重建；实现：文件名匹配（folderName 消歧）/作者 cos_ 前缀保留/标签/时间轴/收藏/关注 upsert + 事件回放（dailyBrowse 全量 + mediaStats 差额 + history 补漏，总量守恒——测试锁定 open=明细+差额+补漏）+ 同 exportedAtMillis 批次幂等锚点 + scanSources/settings/albumRules 不导入进 warnings |
| 协议增量 | Library.kind（normal/cos，注册 COS 作者库）+ LibraryCreate.kind + 迁移请求/响应 schema；openapi 3.1 nullable 写法修正（type: [x, "null"]） |

测试：`go test ./...` 13 包全绿（新增 ~60 用例）；golangci-lint 0 issues；redocly 0 error。文档：PROJECT_PLAN（M3 勾选）、HANDOVER、GUIDE_API（库 kind/统计口径/迁移语义/出处富化）、ARCHITECTURE（§2 图+§5 边界表加 sourcematcher/authoring）、DOMAIN_RULES（§4 勘误 130）。

遗留（记 HANDOVER）：COS 孤立作者清理（作者目录改名/删除后残留，旧项目有对应能力）、custom_sources 写入端点（协议未定义，UI 路线一起做）、filing API 改名的 cos 库作者映射修正（normal 库已覆盖）。

---

## UI 方向定稿：Tremor 风格面板成为正式 UI 基底（2026-08-29/30）

执行 AI：GLM-5.3-Flash（主代理 + 执行子代理多轮迭代，浏览器原型逐版验收）

M2 UI 提交后 UI 方向经用户多轮拍板演进，本条存档方向变化与原型产出：

| 轮次 | 决策/产出 |
|---|---|
| 桌面媒体中心改版（已弃用） | AppShell 双布局（桌面侧边栏/移动底栏）+ 绮梦紫品牌主题（index.css）+ 卡片质感/响应式网格——用户后改为以 Tremor 风格面板为基底，此轮品牌色与质感改法保留在代码中复用 |
| 管理面板原型（panel-demo） | 选型 Tremor Raw（copy-paste 组件，Tailwind v4 + React 19 兼容）+ Recharts；14 组件 + lib/tremor 工具；经 V1→V3.3 五轮浏览器验收迭代 |
| **正式 UI 结构定稿**（用户拍板） | 单人使用，管理面板与用户端**合并为一个界面**。侧栏五项：①首页（桌面 PC 式卡片流：单击右侧详情面板/双击播放占位）②相册（旧版全部页逻辑：**两段式胶囊**——维度行「分区/作品/角色/类型」+ 值行带级联计数、展开收起，维度按条目类型区分）③我的（头部信息卡 + Tabs：数据统计/浏览历史）④维护（性能监控圆环+网络负载 + 文件管理/回收站/日志合并）⑤设置（固定最下） |
| 技术说明 | 官方 PC 客户端闭源；布局参考 PiliPala/tiajinsha 等开源实现的交互语义，组件以自有 Tremor/Tailwind 体系重做；保持 Web 形态（PWA），Electron 客户端方案评估后弃（维护成本高、NAS 自用无必要） |

原型现状：全部 mock 数据；`/panel-demo` 路由独立于现有用户端壳（用户端 AppShell 一并存留，最终以 panel-demo 结构为正式界面逐步替换）。待办：接真实数据（首页/相册接资产列表+签名直链缩略图、维护页接硬件监控 API、数据页依赖 M3 统计）、品牌色统一、提交后逐步替换用户端。

本 commit 同时收录工作树中的并行 M3 后端改动（recommend 十维推荐端点实装、rankings/prefs 端点、settings/daily_shown 存储层、stubs 替换、golangci G404 豁免——go test 全绿），为保进度按用户指示合并存档。

文档：`HANDOVER.md`（UI 方向定稿节）、`CHANGELOG.md` 本条、`.gitignore`（server/data 运行时目录）。

---

## M2 UI 段页面组装与集成验收（2026-08-29）

执行 AI：GLM-5.3-Flash（主代理组装 DetailPage + 修复与集成；Organize/Trash/Stats 三页由执行子代理完成；reviewer 子代理对抗审查通过）

接手前会话因额度暂停的 M2 UI 中间态（executor-C/D 停工：31 个 TS 构建报错 + Detail/Organize/Stats/Trash 四页仍为 6 行占位），本次完成页面组装、集成检查与全链路冒烟：

| 改动 | 位置 | 要点 |
|---|---|---|
| 31 个构建报错清零 | `Dashboard.tsx`/`RecommendPage.tsx`/`labels.ts`/`use-asset-move.ts`/`LibraryManager.tsx`/`AllAssetsPage.tsx` | Dashboard 解构修复（`const { data } = useSystemStatus()`）；RecommendPage 提取 `hotItems/favoriteItems`（`data?.items ?? []`）做 undefined 保护；`LibraryScanState` 改从 `Library['scanState']` 派生（generated 无独立导出类型，协议扩展自动跟随）；3 处 unused 删除 |
| 详情页组装（主代理） | `pages/DetailPage.tsx`（全新约 300 行） | 媒体区双分支（image/animated→ImageViewer；video→thumbUrl 预览封面/VideoPlayer 两态）+ chrome 轻操作层（返回/信息 + 点赞/收藏/标签/移动/删除）+ 批次导航（location.state `DetailBatchState`，navigate replace 防堆栈增长）+ open/dwell 打点（离开 ≥1s 才报）+ TagManager/InfoSheet/MoveDialog/删除确认四弹层；外层 `key={assetId}` 重挂载复位模式（免复位 effect，React Compiler purity 合规）；useLike 无本地基线时以详情 likeCount 显示（likeTouched 区分） |
| 整理/回收站/统计三页组装（执行子代理） | `pages/OrganizePage.tsx`/`TrashPage.tsx`/`StatsPage.tsx` | Organize：库选择 + DirTree + useDirCreate 新建目录（客户端拦路径分隔符）+ DropZone→useUploadQueue→UploadQueue（失败 toast ref 去重）；Trash：useTrash 四 hook + 恢复/单删/清空全部二次确认；Stats：getApiV1StatsOverview（后端 501 stub 期间 retry:false）+ "统计尚未上线"空态 + 库概览卡（扫描状态徽标） |
| MoveDialog 接线 bug 修复 | `components/organize/MoveDialog.tsx` + `DetailPage.tsx` | 冒烟发现：服务端 `/api/v1/dirs` libraryId 业务必填（dirs.go 有测试锁定），而 MoveDialog 无参调用 useDirTree 必 400。修复：MoveDialog 增 `libraryId?` prop 透传；DetailPage 传第一库 id（AssetDetail 协议无 libraryId 字段，多库场景记为协议债） |
| 集成检查与验收 | — | Toaster 挂载/SSE 单连接多实例/error-text 口径/相册跳转 source 参数五项确认；隔离实例（临时数据目录 + ffmpeg 造 2 图 1 视频）browser 冒烟全链路通过：设密→注册库→扫描→列表分组→视频播放（TimelineBar/倍速/默认静音）→点赞收藏标签→新建目录→移动→删除→回收站恢复→统计空态→仪表盘真数据→相册兜底分组；上传 UI 受 IAB 无 file chooser 限制，XHR 通道页面内直调验证 201 |

验证：`npm run build` 0 错误（tsc -b + vite + PWA sw.js 产物）、`npm run lint` 0 错误 10 警告（逐一核对全部为基建层遗留、本次新写文件 0 警告）、`go test ./...` 全绿（确认 M3 并行改动未破坏）、reviewer 子代理全新上下文对抗审查通过（独立重跑三项验证）。

文档：`PROJECT_PLAN.md`（M2 UI 九项勾选）、`HANDOVER.md`（完成记录 + 提交待办）、`CHANGELOG.md` 本条。**git 未提交**——工作树混有并行 M3 后端改动，提交拆分策略待用户确认。

---

## M2 UI 段随附后端四件套（2026-08-29）

执行 AI：DeepSeek-V4-Flash（执行子代理，ZCode）

M2 Web UI 段的随附后端改动——缩略图抽帧策略对齐、标签排序、出处列表端点、服务端 SPA 托管，全部走"协议先行 → 生成 → 接线 → 测试 → 文档"：

| 改动 | 位置 | 要点 |
|---|---|---|
| 缩略图抽帧策略对齐 §11 | `server/internal/thumbnail/`（blackframe.go/ffmpeg.go/generate.go/cachekey.go + 测试） | **内嵌封面优先**（ffprobe `disposition.attached_pic` 探测，`-map 0:<流索引>` 抽封面流）→ 按时长 **35% 代表帧** → **黑白扩散序列**（35%→25%→45%→15%→55%→5%→65%→0ms）：新增白帧判定（全部 >240）、取不到时长短路 0ms、候选点毫秒级去重（"不重复取同一帧"）；旧"0s→1s→2s→3s→5s"逐点序列废弃。**缓存键升版本段**：SHA-256(`"v2:"+assetId+":"+size`)，旧缓存全部自然失效重建（孤儿交给对账清理，符合"永不因数量上限删除"）；golden 向量与注释同步。性能分工（实时用首帧/代表帧仅预生成）**M3 预留**，代码注释标注 |
| 标签排序 | `tags.sql`/`browse.sql` + migration `0004_asset_tag_created_at` + `tags.go` + 测试 | GET /tags 改**名称升序**（筛选面板口径，LEGACY_REQUIREMENTS §A）；详情 tags 改**关联时间倒序**（最新添加置顶）——asset_tags 无关联时间列，0004 补 created_at（只加不改，epoch 回填 + NOT NULL DEFAULT，down 可回滚）；替换式 PUT 写入当前时刻（替换即刷新、重添加天然置顶），同批毫秒内按标签创建时间倒序 tie-break |
| 出处列表 `GET /api/v1/sources` | openapi + `store/queries/sources.sql` + `httpapi/sources.go` + `sources_test.go` | 按出处规范名分组计数（name=null=无出处文件，显示层兜底"其他"），fileCount 降序；`includeCos` 默认 false，复用 browse.sql 的 COS 排除形态（口径与资产列表一致）；三端 SDK 经 `make sdk` 全量重新生成 |
| 服务端 SPA 托管 + /_debug/ | `config`（WebConfig `web.static_dir`/`QIMENG_WEB_STATIC_DIR`，默认 `../web/dist`，空=禁用）+ `httpapi/server.go`/`spa.go` + `Makefile web-build` | 静态目录存在且 index.html 可读 → `/` 托管构建产物（`/assets/**`、`/icons/**` immutable 长缓存、HTML no-cache、非文件路径回退 index.html、`os.Root` 防路径穿越、文件系统错误回退日志留痕）；目录缺失 → 回退内嵌验收页（M1 行为不变，服务不挂）；验收页固定挂 `/_debug/`（SPA 可用时也可访问，免鉴权）；`/api/` 前缀与 `/metrics` 维持原鉴权不吞 404 |

验证：`gofmt -l` 空、`go test ./... -count=1` 全绿（thumbnail 黑白/扩散/去重/封面/缓存键版本化 + httpapi 标签排序/出处/SPA 用例 + store 迁移 0004 down/up）、`go build ./...` 通过、`make sdk` 三端生成无漂移（redocly OK）。

文档：`DOMAIN_RULES.md` §3（sources 端点一句）/§7（标签排序两行）/§11（实现状态=已对齐，性能分工 M3 预留）/「最后更新」加日期；`GUIDE_API.md` 分组表与全局约定；`HANDOVER.md` 进度；`CHANGELOG.md` 本条。`PROJECT_PLAN.md` 未动（主 AI 统一勾选）。

---

## M2 收尾：FTS5 全文搜索 + upload.done SSE 载荷 schema（2026-08-29）

执行 AI：DeepSeek-V4-Flash（主代理，ZCode）

M2 最后一个后端任务——全文搜索落地，全链路（协议→迁移→查询→接口→测试）：

| 改动 | 位置 | 要点 |
|---|---|---|
| 迁移 0002（up/down） | `server/migrations/0002_search_fts` | trigram 分词 FTS5 虚表（为什么不是 unicode61：中文任意子串检索）；聚合 VIEW `asset_search_text`（文件名/文件夹路径/标签/作者/角色/出处六维拼词，空格分隔防跨维度假命中）；11 个同步触发器（assets 主表 3 + 标签/角色/作者关联表 8）——全部写路径（upsert/移动改名/恢复/标签增删改/作者改名）自动维护索引，业务零感知；存量回填语句 |
| 浏览三查询 q 谓词 | `store/queries/browse.sql`（Desc/Asc/Count 同步） | `json_each` 把空格分词结果转 AND 语义；每词 instr 子串匹配任一维度——**关键取舍**：trigram 的 `MATCH` 不支持 2 字短词（'尼尔' 命中 0，SQLite 3.53.3 实测），LIKE 的 `%`/`_` 转义又遇 sqlc v1.31.1 不支持 FTS 虚表列 `LIKE ... ESCAPE`（最小复现定位），定案 `instr(lower(), lower())`——contains 语义 + ASCII 大小写折叠 + 通配符纯字面（'100%完结' 可搜）；3 万行基准每词 <150ms |
| search 包 | `internal/search`（doc.go + search.go） | 职责收敛为关键词解析（ParseQuery，空格分词/多词 AND）与索引重建（RebuildIndex，Clear+Fill 运维兜底）；索引增量维护在迁移触发器，不重复开发 |
| 索引失效/重建 | `store/queries/search.sql`（RebuildAssetsFtsClear/Fill） | 幂等重建路径，测试覆盖"清空索引→RebuildIndex→恢复命中" |
| 协议 | `api/openapi.yaml` | `q` 描述更新（空格分词/多词 AND/六维子串/与筛选叠加）；SSE `upload.done` 载荷 schema 补齐（UploadDoneEvent，代码侧 events.UploadDoneEvent 结构化载荷替换原 assetId 裸字符串） |
| 测试 | search 单测（ParseQuery/RebuildIndex）+ httpapi 端到端 4 用例 | 六维命中/多词 AND/大小写/通配符字面/与筛选叠加/触发器同步（移动、作者改名、删标签级联）/重建兜底/参数共存——13 个新用例；store 回滚测试适配两步迁移 |

验证：go test ./... 全绿（9 包）、go vet 通过、golangci-lint 门禁绿、make sdk 三端生成无漂移、web tsc 通过。

文档：`DOMAIN_RULES.md` §3 新增「全文搜索口径」节（分词/AND/六维/子串/叠加/索引说明，行为唯一权威）；`GUIDE_API.md` 搜索实现状态；`HANDOVER.md` 进度与剩余任务（M2 后端全清）；`PROJECT_PLAN.md` M2 勾选；`CHANGELOG.md` 本条。

---

## 代码卫生排查与硬编码收敛（2026-08-29）

执行 AI：GLM-5.3-Flash（主代理，ZCode）

三端代码卫生排查（Web 端 Explore 子代理 + Go 端主代理逐项 grep），修复发现的 4 类硬编码，并把排查标准沉淀为 AI 编码规范：

| 修复 | 位置 | 要点 |
|---|---|---|
| 媒体直链路径前缀常量化 | httpapi `server.go`（常量定义 + 免鉴权判定）、`assets.go`/`upload.go`/`media.go`（引用点） | `mediaPathPrefix`/`mediaPathOrig`/`mediaPathThumb` 单一来源——签名协议字符串原散落 4 处手抄，一致性原靠人肉保证 |
| 分页语义抽共享 | 新增 httpapi `pagination.go` | `defaultPageLimit`/`maxPageLimit`/`resolvePageLimit` 收敛 assets 与 recommendations 两端点重复的默认值+范围校验+错误消息（第 2 次复制粘贴触发警戒线）；与 openapi.yaml 的双写同步责任入注释 |
| 缩略图缓存头常量化 | `media.go` | `thumbCacheControl`（max-age 一年 + immutable，语义注释） |
| readyz 超时常量化 | `server.go` | `readyzTimeout`（3s，与包内既有命名常量风格对齐） |

验证：gofmt 无差异、go vet 通过、go build 通过、go test ./... 全绿。

文档：`AI_README_FIRST.md` 新增「代码卫生约束」节（魔法值零容忍/第二次即提取/协议双写同步责任/安全字符串单一来源/调度参数禁内联/复用优先级/禁隐式调度耦合），警戒线表增「魔法字面量」行；`AGENTS.md` 警戒线索引同步。Web 端排查结论：手写代码干净，生成 SDK 未接线、baseUrl 配置策略留待 SDK 接线任务。Android 端仅生成物不适用。

## M2 后端先行：六批端点接线（2026-08-27 ~ 2026-08-29）

执行 AI：GLM-5.3-Flash（主代理，ZCode）

用户拍板 M2 拆为"后端先行、UI 后置"（PROJECT_PLAN M2 节记录）。六批端点从 501 占位转真实实现，全部走"协议先行 → make sdk → 接线 → 测试 → 文档"流程：

| 批次 | 内容 | 要点 |
|---|---|---|
| sysmon 接线 | /system/status + /metrics | 协议补 perCore；挂载点动态=DataDir+全部库根（sysStatusAdapter 现查库表）；version 常量单一来源；真机冒烟（20 核差分）通过 |
| 回收站五端点 | DELETE asset / trash CRUD | TrashMeta 扩 LibraryID/MediaType；恢复=UpsertAsset 重建（asset_id 不变，浏览历史保留——ADR-0005 无外键红利）；恢复冲突自动重命名；遍历两遍式 os.Root 防 TOCTOU（gosec 提示真修，未开 nolint）；恢复语义与"绑定不还原"已知限制入 DOMAIN_RULES §9 |
| 移动/重命名 | POST /assets/{id}/move | 冲突 409（移动不覆盖）；失败回滚文件移动（扫描器自愈为兜底）；targetDir 空串=库根（目录语义与资产路径语义区分） |
| 推荐流占位 | GET /recommendations | M2 热度占位（viewCount 降序），seed 忽略，M3 十维算法只换实现 |
| 目录树 | GET/POST /dirs | 遍历磁盘（空目录可作整理目标）；POST 幂等；穿越拒绝 |
| 标签体系 | /tags 三件套 + /assets/{id}/tags + timeline-tags 三件套 | store 新增 tags.sql 十查询（sqlc 注释须纯 ASCII——中文注释使解析器报错，已验证入档）；替换式绑定走事务；跨资产时间轴删除隔离 |
| 上传 | POST /assets/upload | 协议补 libraryId 必填参数（多库定位）；config upload.max_bytes（默认 2GB）；流式接收（Content-Length 预判 + MaxBytesReader 兜底）；冲突自动重命名；media_type 用 scanner.ClassifyMedia 唯一口径；视频探测同 scanner"失败留空"语义；upload.done 最小载荷发布（SSE 载荷 schema 待补协议） |

验证：全部测试绿（本轮新增 30+ 用例）、golangci-lint 0 issues、make sdk 三端重建通过、修复 ineffassign 揭示的 err 遮蔽真 bug（upload.go Copy 错误曾会被吞）。

文档同步：PROJECT_PLAN（勾选+执行顺序调整）、HANDOVER（剩余任务表收敛为 FTS5 一项）、DOMAIN_RULES §9（上传/恢复语义）、GUIDE_API（参数行）、OBSERVABILITY（接线状态）。

已知遗留：本机 redocly 有 Windows libuv 退出崩溃 bug（校验本身通过，CI Linux 不受影响）；migrations/0001 的 trash_items 表未使用（回收站真相源是 meta 文件，filing 包设计），下轮评估是否在 0002+ 清理或启用。

---

## 底层重构与文档体系升级（2026-08-26，c7d4839）

执行 AI：DeepSeek-V4（主代理 + 执行子代理）

三片底层重构（代码侧已落地，本条目连同文档一并归档）：

| 项 | 要点 |
|---|---|
| 探针鉴权定案 | `/readyz` 免鉴权定案：openapi.yaml:380 标 `security: []`；topRouter 注释改为"以 yaml security 元数据为准" |
| Makefile 落地 | server-run / server-test / web-dev / web-test 真实可用；`make lint` = redocly + golangci-lint + TS；docker-build 标 TODO(M5) 如实占位 |
| golangci-lint 门禁 | 新增 `server/.golangci.yml`（v2.13.1，11 linter）；depguard 两条红线：非 httpapi 包禁 import httpapi/gen；业务包与 sysmon 禁 import httpapi（cmd 组合根例外） |
| CI 对齐 | server job 插入 golangci-lint 步骤（golangci-lint-action@v9）；生成物不入库 + CI 重建链保持 |
| 缩略图档位单一来源 | `server/internal/thumbnail/cachekey.go` 常量（SizeSmall/SizeGrid/SizePreview）；config.Thumbnail.LongSide 默认 0=回落 SizeGrid；Config.Token 与 QIMENG_TOKEN 已删 |
| stubs 注释对齐 | httpapi/stubs.go 各端点里程碑注释与 PROJECT_PLAN 对齐（sysmon 接线 = M2） |

文档体系升级（本条目主体）：

- 新增 ADR-0009（SDK 生成物不入库防漂移）、ADR-0010（模块边界三层强制）、ADR-0011（数据库演进式迁移纪律）+ `docs/adr/INDEX.md` 决策索引 + adr 模板升级 Nygard 五段
- 新增 `docs/CAPABILITY_MAP.md`（能力三态表 + 对标 Jellyfin/Immich/Plex 缺口清单，AI 主动提案依据）
- 新增根 `llms.txt`（llms.txt v2 格式文档导航）
- AGENTS.md / AI_README_FIRST.md 增补模块边界、迁移纪律、生成物禁手改、commit scope 约定、Go/React 警戒线
- ARCHITECTURE §5.1 模块边界强制小节、§10 CI 四 job 实况对齐 ci.yml；OBSERVABILITY/GUIDE_API readyz 免鉴权口径统一；HANDOVER search 包现状更正；PROJECT_PLAN 补常驻任务与验收门禁

## M1 收尾：一键启动脚本（2026-08-22，`1ae33eb`）

执行 AI：GLM-5.3（主代理）

双击 `启动服务端.bat` 即可启动服务端（显示局域网 IP 列表 + 防火墙提示 + 全路径调 go 规避 PATH 截断）。三次迭代修复：中文 echo 在 cmd 代码页下破坏后续命令解析 → 全 ASCII；`go` 不在继承 PATH → 全路径调用。已实测 healthz 通。

## M1 服务端核心闭环完成（2026-08-22，`4a42acd`）

执行 AI：GLM-5.3（主代理 + 执行子代理 ×8）

九个模块全部落地、9 包测试全绿、真实二进制集成验收通过（详见 PROJECT_PLAN M1 勾选）。"9 包"口径：9 个有代码包（auth/config/events/filing/httpapi/scanner/store/sysmon/thumbnail）测试全绿；recommend/search/stats 三个 doc.go 空壳包暂无测试；sysmon 属 M2 提前件随 M1 交付。各模块与执行方式：

| 模块 | 执行 | 要点 |
|---|---|---|
| auth 鉴权 | 子代理 | argon2id PHC + token 哈希 + HMAC 签名直链 + chi 中间件（59 用例） |
| store 存储 | 子代理 | SQLite(modernc) 14 表 + golang-migrate + sqlc keyset 分页（9 测试） |
| thumbnail 缩略图 | 子代理 | ffmpeg 封装 + 黑帧检测（全部采样制）+ 工作池 + 真实 ffmpeg 集成测试（16 用例） |
| events 事件 | 子代理 | 进程内总线（慢订阅者丢弃隔离）+ SSE handler（13 用例双轮稳定） |
| filing 文件安全 | 子代理 | 路径穿越全变体/MIME 魔数/上传四道校验/回收站布局（142 子用例） |
| scanner 扫描器 | 子代理 | 全量/fsnotify 增量/轮询兜底/移动合并启发式（12 测试 -count=3 稳定） |
| httpapi 接口层 | 子代理 | 全浏览闭环端点 + 中文极简验收页 + 端到端测试 |
| sysmon 监控 | 子代理 | gopsutil 快照 + prometheus 业务指标（10 用例；M2 提前件） |
| 接线与收尾 | 主代理 | wire.go 扫描适配器、FinishScan/SetScanner、sm=256 档、自噬防御修复与验收 |

**集成验收实测发现并修复**：数据目录配置在库内时缩略图缓存（webp 白名单格式）被扫描器自噬入库——加两道防线（注册互斥校验 DATA_DIR_CONFLICT + scanner SkipDir/watch 过滤）并补测试。移动合并真机验证通过（改名后 asset_id 不变）。

## 协议变更：删除按需缩放副本（2026-08-22，`4a1f3da`）

执行 AI：GLM-5.3（主代理）

用户明确要求"查看永远发原件"：删除 `/media/preview` 端点与 `previewUrl` 字段，三端 SDK 重新生成。同步更新铁律 1（总说明）、ARCHITECTURE §7、GUIDE_API。同 commit 附带：DOMAIN_RULES 黑帧判定语义澄清（全部采样制）、ADR-0005 事件流无外键实现澄清、m4v 白名单补齐。

## CI 与仓库（2026-08-22，`848ef29`/`11a0fdb`）

执行 AI：GLM-5.3（主代理）

GitHub 私有仓库 `Surtr42u/qimeng-media` 建立；Actions 四道门禁（redocly 协议校验 / Go vet+test+build / Web tsc+build / make sdk 三端生成链）首跑全绿；actions 升级 v5/v6 + Go 缓存路径修复。

## M0 地基完成（2026-08-22，`1625db1`）

执行 AI：GLM-5.3（主代理 + 执行子代理 ×3）

| 项 | 执行 | 要点 |
|---|---|---|
| 协议定稿 v0.1.0 | 主代理 | 评审补 9 处缺口（排序/顺位/includeCos/sessionId/标签删除/时间轴删除/作者关注/回收站清空/缩略图尺寸）+ 修复草案 2 处语法错误；redocly 0 error |
| server 骨架 | 子代理 | go.mod + 12 包 doc.go + chi/slog + config（默认 :8420 测试锁定）+ healthz |
| web 骨架 | 子代理 | Vite+React+TS 严格 + Tailwind v4 + shadcn(radix/nova) + TanStack Query + --qm-* 设计 token |
| make sdk 三端链 | 子代理 | oapi-codegen v2.8.0 / @hey-api 0.99.0 / openapi-generator+JDK17（免安装 zip 方案） |
| GUIDE_API.md | 主代理 | 36 端点分组速览 + 关键机制导读 |

环境侧（不入库）：JDK17 免安装（dev-tools/jdk17）、choco make 4.4.1、winget 不可用结论，均记入 TOOLCHAIN_GUIDE。
