# CHANGELOG - 变更历史

本文件归档项目历次功能/修复/决策变更，每条对应 git 提交（commit hash 标注）。
## AI 署名约定（沿用旧项目 QimengMedia 惯例）

- 每个变更条目标注实际执行该改动的 AI 模型（真实命名，品牌-版本），便于追溯每次改动由谁完成。
- 每个条目单独署名；多 AI 协作时各条目自行署名。
- 子代理执行的工作标注"（执行子代理）"，主对话直接完成的标注"（主代理）"。
- 署名自查（2026-09-05 补）：每条变更由执行会话先确认自身实际运行模型的真实名称再署名（GLM-5.3 与 GLM-5.3-Flash 是两个不同模型名），禁止沿用上一会话或上一条目的署名行；历史条目真实署名不动。

> **历史条目拆分说明（2026-10-01）**：为控制 AI 上下文体量，本文件只保留 **2026-09-22 及之后**的条目（第三百八十二笔起）；拆分线之前的全部条目已逐字迁入 `docs/history/CHANGELOG-ARCHIVE.md`（零改写，笔号与本文件连续可查）。引用早于拆分线的旧笔号请去历史档查阅。

## feat(app/web): 批量选取卡顿优化与全域UI/深色模式升级——零延迟点选触觉响应、胶囊展开收起换至右下角、批量抽屉与详情页作者/编辑弹层卡片化及夜间模式打磨（2026-10-08 第五百笔）

执行 AI：Gemini-3.8-Flash（主代理）

- **背景与需求**：
  1. 用户体验第四百九十九笔后反馈：“可以可以,然后我觉得你ui做的不从,你先把这个批量的优化一下然后我记得详情页左下角的也可以点开类似的,一起优化一下,我对你的ui审美很满意,这些优化好再做一下整体优化,图标啥的,以及夜间模式,顺带把相册的胶囊展开的那个按钮从左边换到右下角(桌面的和web的好像也是左下角的展开,一起换到右下角),最后你在对整体做个优化,还有你这个批量的交互选取有点点卡顿不顺手一起优化了”。
- **改动内容**：
  1. **批量点选卡顿与交互手感根本性优化（零延迟响应与 Compose Smart Skipping）**（`android/core/ui/src/main/java/media/qimeng/app/core/ui/component/QimengMediaGrid.kt`）：
     - **卡顿延迟根因**：原网格 `AssetCard` 无论是否在多选模式下均挂载 `combinedClickable`，Android Gesture Pointer 需等待 400~500ms 长按超时以判断单击还是长按，导致连续点选有明显粘滞延迟；且在遍历列表时为每个卡片分配了闭包 lambda `{ onAssetClick(asset) }`，导致每次勾选一个卡片都会强制使视口内所有卡片重新重组。
     - **彻底消除延迟与重组**：多选模式（`isSelectionMode == true`）下**彻底移除 `combinedClickable`，切换为原生极速 `Modifier.clickable`**，并在点击时伴随轻快清脆的微触控反馈（`TextHandleMove`），指尖点按瞬时勾选；直接透传稳定函数引用 `onAssetClick` 与 `onAssetLongClick`，使得未改变选择状态的卡片能被 Compose 编译器直接跳过重组（Smart Skipping），多选连点丝滑不丢帧。
  2. **相册胶囊展开/收起按钮挪至右下角（三端一致）**：
     - **App 端**（`android/core/ui/src/main/java/media/qimeng/app/core/ui/component/QimengPills.kt`）：`QimengValuePillBlock` 的展开/收起按钮改用 `Box(contentAlignment = Alignment.CenterEnd)` 容器，靠右下角对齐；
     - **Web 与桌面端**（`web/src/styles/glass.css`）：`.expand-btn` 样式增加 `display: block; margin-left: auto;`，统一停靠在右下角。
  3. **多选工具栏与批量操作抽屉 UI 升级**（`android/feature/all/src/main/java/media/qimeng/app/feature/all/AlbumViewModel.kt`、`AllScreen.kt`）：
     - `AlbumViewModel` 增加 `clearSelection()`；工具栏支持“全选/清空”自适应切换，已选数量以独立 Primary 圆形徽标呈现；
     - `BatchAuthorSheet` 升级：引入 M3 容器色 `surfaceContainerLow` 与规范拖拽把手；增加操作说明提示卡片；作者选择与来源渠道分别使用独立圆角卡片（`surface`）包装，层次清晰；操作栏提供带有正在写入动画（`CircularProgressIndicator`）的确认按钮，夜间模式对比舒适。
  4. **详情页作者/作品弹层与资产编辑页重构**（`android/feature/detail/src/main/java/media/qimeng/app/feature/detail/DetailSheets.kt`、`AssetEditScreen.kt`、`android/core/ui/src/main/java/media/qimeng/app/core/ui/icon/QimengDetailIcons.kt`）：
     - `QimengDetailIcons` 补充 `EditPencilIcon` 官方矢量；
     - `DetailAuthorSheet`：重构为现代卡片式结构。每位作者封装在独立 `surface` 卡片内，带头像图标、姓名、COS 徽章、关注/取关玻璃按钮与“进入作者主页”跳转行；底部提供独立的“编辑关联作者与来源”快捷操作卡片，明确告知用户支持修改作者和来源并自动写入 NAS 作品 TXT；无作者时展示优雅空态卡片；
     - `AssetEditScreen`：已关联作者与添加作者全模块升级为深浅模式适配的容器卡片，保存按钮圆角与字重加强，排版大方呼吸。
  5. **整体深色/夜间模式打磨**：
     - 统一容器层级色（`surfaceContainer`、`surfaceContainerLow`、`surfaceContainerHigh`），彻底消除深色模式下死黑、刺眼发白或暗灰对比缺失的问题。
- **验证结论**：
  - 单元测试 `:feature:all:testDebugUnitTest`、`:feature:detail:testDebugUnitTest` 全绿通过；
  - `:app:assembleDebug` 打包构建成功；
  - 通过 ADB 成功推送到已连接的 Android 真机设备并成功启动验证。
- **文档**：CHANGELOG.md（本条）。

## feat(app): 相册长按多选与批量关联作者与来源——支持网格卡片长按多选、底部操作胶囊与联想抽屉，保存自动写入 NAS TXT 片段（2026-10-08 第四百九十九笔）

执行 AI：Gemini-3.8-Flash（主代理）

- **背景与需求**：
  1. 用户在体验上传重构后反馈：“杂图这种等上传完再去相册的内置作者页编辑来源和作者是吗,这个编辑后会自动写入txt吗；你加一下把我看看效果”。
  2. 方案确定：杂图/多作者图片直接轻装上传入库；在相册网格（全部媒体列表）中支持长按资产多选，一次性批量关联作者与来源；服务端由 `PUT /assets/{assetId}/authors` 在事务中自动原子写入 NAS 的对应作者 TXT 片段（写入真相源），既不改变底层一致性，又让批量管理顺手舒服。
- **改动内容**：
  1. **共享媒体网格组件支持多选模式与触觉震动反馈**（`android/core/ui/src/main/java/media/qimeng/app/core/ui/component/QimengMediaGrid.kt`）：
     - `QimengMediaGrid` 增加 `onAssetLongClick`、`selectedAssetIds`、`isSelectionMode` 等参数；
     - `AssetCard` 增加 `isSelected`、`isSelectionMode` 与 `onLongClick`；
     - 交互升级：在非多选状态下长按资产卡片，触发 `LocalHapticFeedback` 震动并进入多选模式；多选模式下卡片右上角显示圆形复选标记（已选为 primary 底色对勾 `CheckMarkIcon`，未选为半透明黑底白边框圆圈），已选项外圈带 2.5dp 高亮边框与轻量遮罩；
     - 多选模式下点击卡片直接 toggle 勾选/反选，避免误触发详情页。
  2. **相册状态机支持多选与批量设置编排**（`android/feature/all/src/main/java/media/qimeng/app/feature/all/AlbumViewModel.kt`）：
     - 注入 `AuthorRepository` 与 `UploadRepository`；
     - 增加 `BatchAuthorUiState`、`selectedAssetIds`、`isSelectionMode` 及 `userNoticeMessage`；
     - 暴露多选动作：`startSelection`、`toggleAssetSelection`、`selectAll`、`exitSelectionMode`；
     - 暴露批量设置流程：`openBatchAuthorSheet`、`onBatchAuthorQueryChange`（带防抖联想）、`onPickBatchAuthor`、`onToggleBatchSource`、`onAddBatchCustomSource`、`submitBatchAuthor`；
     - `submitBatchAuthor` 逐个调用 `authorRepository.replaceAssetAuthors`（服务端 DB 事务原子写 NAS TXT），并按需并入来源 `appendAuthorSources`；保存成功后退出多选模式、刷新相册数据并弹出成功通知横幅。
  3. **相册页面接入与视觉动效**（`android/feature/all/src/main/java/media/qimeng/app/feature/all/AllScreen.kt`）：
     - 多选模式顶部工具栏：显示“已选 X 项”以及“全选”、“退出”按钮；
     - 底部悬浮操作按钮：当有选中项时通过 `AnimatedVisibility` 浮现胶囊按钮“🏷️ 批量设置作者与来源 (X)”；
     - 弹出 `ModalBottomSheet` 抽屉：集成 `QimengAuthorSuggestSection`（作者联想与种子选择）与 `QimengSourceSection`（快捷词表与自定义来源），底部带取消与确认保存；
     - 成功提示横幅：保存完成后展示绿色/Tertiary 消息卡片，告知具体作者与自动写入 TXT 状态。
  4. **测试补齐与锁定**（`android/feature/all/src/test/java/media/qimeng/app/feature/all/AlbumViewModelTest.kt` 与 `AlbumFilterPanelTest.kt`）：
     - 接入 `:core:testing` 的 `FakeAuthorRepository` 与 `FakeUploadRepository`；
     - 新增多选状态机测试（长按进入、点击切换、全选、退出）；
     - 新增批量设置作者测试（校验 `replaceAssetAuthorCalls` 遍历挂靠及 `appendSourceCalls` 来源并入，验证状态重置与提示反馈）。
- **验证结论**：
  - `:feature:all:testDebugUnitTest` 单元测试全绿通过；
  - `:app:assembleDebug` 全包打包成功；
  - 成功安装并运行于连接的 Android 真机设备。
- **文档**：CHANGELOG.md（本条）。

## feat(app): 数据管理与上传体验重构——数据管理三级语义分组与图标体系，上传批次作者来源可选折叠卡化（2026-10-08 第四百九十八笔）

执行 AI：Gemini-3.8-Flash（主代理）

- **背景与痛点**：
  1. 用户反馈：“看一下app的数据管理内容,我认为随着项目逐渐完善,这个中有太多功能和实现了,交互逻辑我不知道好不好,怎么合理交互逻辑以及ui,你给点建议；然后我对这个上传和修改文件的比较迷茫怎么实现比较好,现在在一起的,就是上传完会写入txt的数据等等关联的这个功能,我觉得在一起太繁重了,但是也不知道怎么实现比较好,以及这个修改的比较简陋,再同时上传多个作者的时候,或者多批量的时候,较难管理和使用,但是我也没啥优秀的思路和设计方案,你看看怎么优化,其他的也是,怎么让功能顺手合理交互舒服等等；你开一个分支做把,我看看效果”。
  2. 根因剖析：
     - **数据管理 Hub 扁平堆叠**：历次功能演进向后追加（最初只有上传/库管理，随后加入作者 TXT、备份、缩略图缓存、归档文件夹、词表维护），7 个完全相同尺寸的白底文本卡片垂直平铺，日常高频操作被淹没在低频配置中，缺少语义分组、缺乏图标指示与视觉呼吸感。
     - **上传与元数据强绑定**：上传页原先将“批次作者/来源联想”常驻在页面中央，强迫用户在上传前把作者信息配好。当用户上传多作者混合文件或日常杂图时，不得不反复分批操作或放弃配置，导致“上传”这一轻快行为变得极其繁重压抑。
- **改动内容**：
  1. **新建分支**：切至新特性分支 `feat/app-manage-upload-redesign`。
  2. **矢量图标体系扩充**（`android/core/ui/src/main/java/media/qimeng/app/core/ui/icon/QimengManageIcons.kt`）：
     - 按需引入 Material Icons 官方 24px 矢量（零依赖纯 ImageVector 构建，QimengIcons 先例）：`CloudUploadIcon`、`FolderManagedIcon`、`VocabularyBookIcon`、`BackupRestoreIcon`、`StorageCacheIcon`、`ArchiveBoxIcon`、`TxtImportFileIcon`。
  3. **数据管理 Hub 结构与视觉重构**（`android/feature/manage/src/main/java/media/qimeng/app/feature/manage/DataManageScreen.kt`）：
     - 重组为三大语义分组（Grouped Sections）：
       ① **媒体与内容**：上传文件、库管理；
       ② **规则与同步**：词表维护、备份导入导出；
       ③ **本地存储与工具**：缩略图缓存、上传归档文件夹、作者 TXT 导入。
     - 入口行升级：增加圆角轻色前缀图标容器（`primaryContainer` 底色 + `primary` 图标）+ 主标题（`FontWeight.Medium`）+ 副标题（12sp 弱色）+ 尾部导航细箭头（`ChevronRightIcon`），大幅提升界面可读性与信息层次感。
  4. **上传页批次预设可选折叠卡化**（`android/feature/upload/src/main/java/media/qimeng/app/feature/upload/UploadPendingSection.kt` 与 `UploadScreen.kt`）：
     - 将原本霸屏的 `BatchDefaultSection` 包装为可折叠卡片（默认收起为轻量入口，带“多张图属同一作者时可展开预设；杂图无需设置，直接上传即可”辅助说明）；
     - 已设置作者时显示高亮摘要（作者名 + 来源列表）与一键清除/展开修改；
     - 更新直传引导文案，明确多作者或杂图无需预设，消除用户心理负担。
- **验证结论**：
  - `:feature:manage:compileDebugKotlin`、`:feature:upload:compileDebugKotlin` 编译通过；
  - `:app:assembleDebug` 全包构建成功；
  - 全套单元测试（`testDebugUnitTest :core:model:test`，438 tasks）全部绿。
- **文档**：CHANGELOG.md（本条）。

## feat(app): 上传文件名编辑加回——SAF 选文件先进预览可改落库基名（扩展名锁定、UploadNaming 单源拼装），系统分享保持选完即传（2026-10-07 第四百九十七笔）

执行 AI：GLM-5.3-Flash（主代理；接手前会话 Gemini-3.8-Flash 留在 review_ui_and_branches 工作树的未提交 WIP——模型/UI/VM 主体为其所写，本会话补齐单测、KDoc 同步、文档与全量验证）

- **背景与痛点**：
  1. 2026-09-29 直传化（暂存区整体退役，用户拍板「暂存了好像没意义啊，去掉吧」）后，App 端上传失去「上传前改文件名」能力，用户要求把文件名编辑加回；
  2. 前会话在 review_ui_and_branches 工作树完成主体实现（`UploadModels`/`UploadScreen`/`UploadUiState`/`UploadViewModel` 四文件未提交改动），但未验证、未同步文档、页面与 VM 头注释仍残留「无逐项编辑、无开始上传按钮」旧表述；本会话接手收尾。
- **改动内容**：
  1. **基名/扩展名拆装单源**（`android/core/model/.../UploadModels.kt`，前会话）：
     - `UploadItem` 新增 `uploadBaseName`（编辑后落库基名，null/blank=未编辑）与派生属性 `extension`/`defaultBaseName`/`currentBaseName`；
     - `effectiveUploadName` 升级三段优先级：`uploadBaseName`（基名+锁定扩展名经 composeUploadName 拼装）→ 旧 `uploadFileName` 全名 → 展示名，末端仍经 `UploadRules.sanitizeFileName` 规范化；
     - 新增 `UploadNaming` 纯规则对象（baseNameOf/extensionOf/composeUploadName）：扩展名锁定口径单一实现，UI 只许编辑基名、完整落库名一律经拼装禁止散拼，对齐 Web 端 `web/src/lib/upload-naming.ts`（三端同一口径）。
  2. **VM 双管道拆分**（`android/feature/upload/.../UploadViewModel.kt`，前会话主体）：
     - SAF 多选改走 `onFilesSelected`：describe 解元数据 → 填充 `selectedFiles` 预览列表（不入队）；新增 `updateSelectedFileName`（基名编辑即时同步，空白=未编辑回退展示名）、`removeSelectedFile`、`uploadSelectedFiles`（未选库门禁提示不传 → 现取 GET /config 超限拦截不出网 → 入队继承批次默认快照：库/目录/作者/来源 → 成功清空选中列表；入队失败列表保留可重试）；
     - 系统分享接收仍走 `submitUris` 选完即传（分享不带编辑场景），门禁/超限/入队口径两管道完全同源。
  3. **UI 预览卡**（`android/feature/upload/.../UploadScreen.kt`，前会话）：新增 `SelectedFilesCard`/`SelectedFileRow`——逐项 `QimengCapsuleTextField` 基名输入（回显输入原文）+ 锁定扩展名「锁定」角标 + 移除 + 「开始上传（N 项）」按钮；`UploadUiState` 新增 `selectedFiles`/`describing`；SAF launcher 回调从 submitUris 切到 onFilesSelected。
  4. **单测补齐**（本会话）：
     - `UploadModelsTest` 新增 14 例：基名口径 8 例（拆分/隐藏文件/多点文件/拼装锁定扩展名/基名含点不可换后缀/空白回退/无扩展名/对旧全名字段优先级）+ `UploadNaming` 6 例（用例对齐 Web `upload-naming.test.ts`）；
     - `UploadViewModelTest` 新增 11 例：预览填充且不入队/空列表忽略/describe 失败横幅/基名编辑生效于入队载荷且列表清空/基名清空回退展示名/移除后只入剩余/未选库拦截/全部超限拦截且列表保留/部分超限照常入队/空列表与重复触发不产生入队/入队失败列表保留可重试。
  5. **KDoc 与文档同步**（本会话）：`UploadScreen`/`UploadViewModel`/测试类头注释中直传化旧表述全部更新为双管道现状；`HANDOVER.md` Android 上传口径行同步。
- **验证结论**：
  - `:core:model:test` 174 例全部通过（基线 160 + 新增 14）；
  - `:feature:upload:testDebugUnitTest` 47 例全部通过（基线 36 + 新增 11）。
- **文档**：CHANGELOG.md（本条），HANDOVER.md。

## fix(app): 上传文件名安全规范化与自动补齐——消除 INVALID_FILENAME 400 失败（2026-10-07 第四百九十六笔）

执行 AI：Gemini-3.8-Flash（主代理）

- **背景与痛点**：
  1. 用户反馈：“看一下手机端的代码,现在手机端上传会出现失败:INVALID_FILENAME文件名不合法的问题,修复一下”。
  2. 根因剖析：
     - **服务端校验硬红线**（`server/internal/httpapi/upload.go` 与 `uploads.go`）：服务端在直传与分片建会话时均执行 `filing.SanitizeFilename(filename)`，剥离 `/`、`\`、控制字符、Windows 非法字符（`:*?"<>|`）并修剪首尾点号与空格；若结果为空串（`ErrFilenameEmpty`）或命中 Windows 保留设备名（`CON`, `PRN`, `AUX`, `NUL`, `COM1-9`, `LPT1-9`），服务端直接抛出 `HTTP 400 INVALID_FILENAME: 文件名不合法`。
     - **手机端解析与清洗防线缺失**（`SdkUploadRepository.describeOne`）：
       ① 空白/空文件名未过滤：特定 ContentProvider 或外部应用分享时返回空字符串或纯空格，原代码未做过滤直接采用，导致向服务端发送 `filename=""`；
       ② 包含路径前缀或特殊符号：部分应用返回的 `DISPLAY_NAME` 是完整文件路径（如 `/storage/...`）或带冒号/特殊字符；
       ③ 兜底无合法扩展名：原代码在元数据异常时简单回退 `"未命名"`（无扩展名），既无法匹配 MIME 也容易在过滤后变空；
       ④ 出网与落库名（`UploadItem.effectiveUploadName` / `UploadWorkSpec.specFromInputData`）未做客户端安全清洗，保留设备名等非法名直传服务端。
- **改动内容**：
  1. **纯函数安全清洗机制**（`android/core/model/src/main/java/media/qimeng/app/core/model/UploadModels.kt`）：
     - `UploadRules.sanitizeFileName`：剥离路径前缀与反斜杠，过滤控制字符与 Windows 非法字符，修剪两端点号与空格；全空或非法名自动以 `fallbackBaseName`（默认 `upload`）兜底；缺少扩展名时自动追加 `fallbackExtension`；
     - `UploadRules.isWindowsReservedName`：识别 Windows 保留设备名（CON, PRN, AUX, NUL, COM1-9, LPT1-9），自动加 `file_` 前缀保护；
     - `UploadItem.effectiveUploadName`：无论编辑文件名还是展示名，出网取值一律经 `UploadRules.sanitizeFileName` 规范化。
  2. **完善手机端元数据提取与兜底**（`android/core/data/src/main/java/media/qimeng/app/core/data/repository/SdkUploadRepository.kt`）：
     - `describeOne` 提取 `contentResolver.getType(uri)` 解析真实 MIME 类型与合法扩展名；
     - `DISPLAY_NAME` 异常或为空时，依次尝试 `uri.lastPathSegment` 与本地文件属性；
     - 最终经 `UploadRules.sanitizeFileName` 产出合规文件名，并在缺失大小时回退探测 `AssetFileDescriptor`。
  3. **WorkManager 反解反向清洗保障**（`android/core/data/src/main/java/media/qimeng/app/core/data/upload/UploadWorkSpec.kt`）：
     - `specFromInputData` 在读取落库文件名时同样经过 `UploadRules.sanitizeFileName`，保证历史在途任务与重试任务同样受到安全保护。
  4. **单测全量锁定**（`android/core/model/src/test/java/media/qimeng/app/core/model/UploadModelsTest.kt`）：
     - 覆盖普通文件名、路径前缀、特殊字符、点文件、无后缀补齐、保留设备名前缀保护、全非法/空白兜底等 12+ 种边界用例。
- **验证结论**：
  - `:core:model:test` 160 个测试全部通过；
  - `:core:data:testDebugUnitTest` 全部通过；
  - `:feature:upload:testDebugUnitTest` 全部通过。

## feat(web): 遵循 ArtPlayer 官方规范重构播控体系——消除右侧菜单错位、恢复 GitHub 原始舞台尺寸、消除左上角闪烁提示、根除底部背景视频变暗闪烁与实现选完即关主流交互、清理历史冗余分支、根治叠层冲突与桌面端画面上移 + 缩略图时长去胶囊与修复夜间反黑 + 视频封面元数据固化 + 修复设置面板浮层定位与对标 B 站标准播控交互（2026-10-07 第四百九十五笔）

执行 AI：Gemini-3.8-Flash（主代理）+ Antigravity（执行子代理：设置面板定位与顶部阴影消除、B 站标准播控交互对标）

- **背景与痛点**：
  1. 用户明确指示：“你可以看看官方的技术文档来实现.现在右侧的那几个点开错位.你看看官方文档和github看看怎么实现兼容或者其他方法,依旧你做的这个新的缩水了高度显得小”。
  2. 用户追加反馈：“播放时移动到下方的组件弹出选项时会导致视频变暗闪一下”、“然后这个组件交互逻辑不对,你看看主流的交互,好像是选完自动消失?”。
  3. 根因剖析：
     - **右侧菜单严重错位根因**：此前自绘控制条在外层包裹了一个 `justify-content: flex-end` 的竖向托盘容器，清晰度和倍速菜单全部被强制推到播放器最右边，与底部的触发按钮完全脱节错位；
     - **播放器缩水矮小根因**：`.asset-stage` 最小高度仅 360px，外层嵌套了固定高度的占位容器，导致宽屏下舞台局促矮小；
     - **桌面端画面上移根因**：此前给 `.asset-stage video` 强加了 `margin: 0 auto; display: block;`，覆盖了 ArtPlayer 依赖的绝对居中定位；
     - **移动到底部组件视频变暗闪烁根因**：`.art-video-player .art-bottom` 占满视口 100% 区域，鼠标滑入底部触发淡入时整个大面积暗色渐变笼罩大半视频画面，造成发暗闪烁；
     - **选项选完留在屏幕不消失根因**：ArtPlayer 原生依赖 hover 样式，且点击选项时鼠标停留在该区域，hover 状态导致菜单死死悬停无法自动收起。
  4. 用户追加反馈及截图 `media_1791360204314.png`：点击齿轮设置按钮后屏幕无设置 UI，视频最上方边缘出现细暗阴影；播控组件需全面对标 B 站 Web 端交互标准（Toggle 开关、移出离界收起、选完即关、空白处即关）。
  5. **设置面板丢失与顶部阴影根因**：在 `web/src/styles/glass.css` 中，`.art-video-player .art-settings` 曾被写入 `bottom: calc(100% + 10px)`。在 ArtPlayer DOM 结构中，`.art-settings` 是整个播放器容器（高度 100%）的直接子元素，而非底栏按钮子元素。该定位导致整个设置面板被直接推至播放器外部上方，仅底部 32px 盒子阴影渗入视频顶部边缘，造成用户看不到设置 UI 却看到顶部黑影。
- **改动内容**：
  1. **严格遵循 ArtPlayer 官方技术规范重构（消除菜单错位）**（`web/src/components/media/video-player.tsx`）：
     - 清晰度（`quality`）、倍速（`playbackRate`）、字幕（`subtitle`）完全走官方 `controls: [{ selector: [...] }]` 注册；
     - 在 DOM 中，每个 selector 列表（`.art-selector-list`）均挂载在对应按钮内部，配合 CSS `left: 50%; transform: translateX(-50%); bottom: calc(100% + 10px);`，在任何分辨率下**绝对垂直居中对齐触发按钮正上方**，彻底根除右偏错位；
     - 齿轮设置面板（`settings`）标准接入画面比例调节（默认/16:9/4:3/拉伸铺满）与单片循环；
     - 监听 `loadedmetadata` 动态更新清晰度标签（如 720P 高清、1280×720 分辨率）及外挂/内置字幕轨；
     - 交互增强：兼顾鼠标悬停（hover）与点击弹出（click），带 12px 防抖透明桥，滑行动作平滑不闪退。
  2. **严格保持与旧版（GitHub）一致的经典舞台尺寸**（`web/src/styles/glass.css`）：
     - 严格恢复 GitHub 原版规格：`.asset-stage` 保持 `min-height: 360px; max-height: 68vh; --stage-max-h: 68vh;`，`.video-player-box` 保持 `width: 100%; height: var(--stage-max-h);`，保持原汁原味的舞台长宽比与适中视野；
     - 移除干扰 ArtPlayer 的 video 全局样式，避免桌面端 Tauri 壳及浏览器画面上移；
     - 保持屏蔽暂停时中央大播放按钮：`.art-video-player .art-state { display: none !important; }`；
     - **彻底消除左上角一闪而过的 seek / 时间气泡提示**：`.art-video-player .art-notice { display: none !important; }` 配合 `art.notice.show` 拦截，彻底消灭视频起播续播或拖拽进度时左上角浮现的提示。
  3. **暗色极光毛玻璃美学注入与消除底部变暗闪烁**（`web/src/styles/glass.css`）：
     - 严格收敛 `.art-bottom` 背景渐变遮罩高度至仅底部 90px 播控区（`background-size: 100% 90px !important; background-repeat: no-repeat !important; background-position: bottom !important;`），彻底消除滑入底栏时视频画面大面积发暗闪烁，视频主体 100% 纯净清晰；
     - 弹出面板使用苹果同款深色磨砂材质（`rgba(22, 22, 22, 0.94)`, `backdrop-filter: blur(24px)`, `border-radius: 10px`, `box-shadow: 0 12px 32px rgba(0, 0, 0, 0.7)`）；
     - 纯白高对比度文字与等宽数字防抖排版，当前选中项高亮主题色。
  4. **彻底实现主流播放器“选项选完自动消失”交互体验**（`web/src/components/media/video-player.tsx`, `web/src/styles/glass.css`）：
     - 增加 `.art-control-selector.art-selector-closed .art-selector-list { display: none !important; ... }` 强制隐藏规则；
     - 在用户点击清晰度/倍速/字幕选项时即时加上 `.art-selector-closed` 并清理 `.art-selector-show`，实现选完即关的丝滑体验（主流 Bilibili / YouTube 体验）；
     - 监听 mouseleave / mouseout、mouseover 及按钮再次点击，自然清理 closed 标志，保证下次鼠标移入或点击能够自然重新呼出。
  5. **全屏与叠层根治保障**：
     - 使用 ArtPlayer 原生 `fullscreenWeb: true` 与 `fullscreen: !isTauriShell`；
     - 全屏态注入最高层级 `position: fixed !important; inset: 0 !important; z-index: 99999 !important;`，无论网页全屏还是 HTML5 全屏均彻底超越 Header（z-index 20）与侧栏。
  6. **全站时长角标去胶囊与日夜反黑修复**（`web/src/styles/glass.css`）：
     - 统一收敛普通卡片（`.card--duration`）、浏览历史卡片（`.hc-duration`）、详情页推荐行（`.upnext-dur`）：去胶囊背景，改用恒白 `color: var(--qm-on-cover);` + 双层阴影 + 等宽数字。
  7. **历史遗留临时分支与工作树彻底清理**：
     - 清理并注销历史遗留子代理临时工作树（`mighty_meteor_hovers_00h31` 与 `untitled-worktree`），彻底删除对应的死分支；
     - 全仓分支收敛干净：仅保留主分支 `master` 与当前 UI 优化专属分支 `feat/web-ui-optimization`（工作树 `review_ui_and_branches`）。
  8. **视频封面元数据固化与缩略图缓存重置**：
     - PC 端与手机端（ADB: `真机`）《守望先锋  法鸡.mp4》均封装 1.12208s 帧为 MP4 内嵌封面（Stream #0:2 attached_pic），原文件修改时间戳保全不变；
     - 清理并重置两端缩略图缓存。
  9. **设置面板定位修复与顶部阴影彻底消除**（`web/src/styles/glass.css`）：
     - 将 `.art-video-player .art-settings` 定位修正为底栏上方 `bottom: calc(var(--art-control-height, 46px) + 12px) !important; right: 16px !important;`；
     - 设置面板精准悬浮于右下角齿轮按钮正上方，顶部阴影彻底消除；补充 `.art-setting-item` 磨砂交互高亮、字阶（13px/500）与主题色激活态（`var(--qm-primary)`）。
  10. **完全对标 B 站 Web 播控组件交互标准**（`web/src/components/media/video-player.tsx`, `web/src/styles/glass.css`）：
     - **Toggle 开关**：点击清晰度/倍速/字幕按钮自身，若已处于打开状态（包含 hover 展开态），再次点击立即收起；关闭态点击则展开当前项并收起其他项与设置面板；
     - **移出自然收起**：鼠标离开 selector 按钮及弹出列表区域（`mouseleave`/`mouseout`）时，同时清理 `art-selector-show` 与 `art-selector-closed`，菜单平滑收起；再次移入自然通过 hover 展开；
     - **选项选完即关**：点击选项后触发选择并立即收起菜单，杜绝 hover 残留；并在比例等设置项选定后自动收起设置面板；
     - **空白与外部收起**：点击视频画面、播放器外部页面时，所有打开的 selector 浮层及设置面板即刻全部收起；
     - **互斥联动**：点击设置齿轮按钮时自动关闭已展开的 selector 浮层。
- **验证**：
  - 前端 28 个测试套件 287 项单元测试全部通过（PASS）；
  - `npm run build` 打包成功（零类型警告、零错误）；
  - `npm run lint` 检查通过（零错误）；
  - 全过程后台静默执行，零前台弹窗、零截图。
- **文档同步**：本笔。

## feat(web): 极光骨架屏体系与通用空态升维——消除列表首屏跳闪（2026-10-06 第四百九十四笔）

执行 AI：Gemini-3.8-Flash（主代理）

- **背景与痛点**：
  Web / 桌面端列表页（首页推荐/COS/热榜、相册页、合集页、搜索页、我的收藏等）在数据在途（pending/loading）期间，网格区域为空，仅在底部或容器内挂载单个居中文本 `<LoadingHint>`。数据返回后几十张卡片瞬间插入，引发剧烈的 Cumulative Layout Shift (CLS) 布局跳闪，且等待感枯燥。
- **改动内容**：
  1. **极光骨架屏组件 `SkeletonGrid`**（`web/src/components/ui/skeleton-grid.tsx`）：
     - 几何尺寸、16:9 比例、圆角、标题行与元信息行完全对齐真实 `MediaCard`；
     - 纯 CSS 实现 Shimmer 极光流光扫光（`--qm-primary` 14% + `--qm-surface-strong` 18%），相邻卡片 90ms 相位微延迟，产生微波流光质感；零额外 JS 依赖，`prefers-reduced-motion` 自动归零；
  2. **多页面接入消除 CLS**：
     - `HomePage.tsx`：`StreamCards` 当 `isLoading && items.length === 0` 时以 `SkeletonGrid` 垫底 12 卡，平滑过渡；
     - `AlbumsPage.tsx`：首屏加载在途且数据为空时由 `SkeletonGrid` 支撑，消除大块空白；
     - `CollectionPage.tsx`：`SkeletonGrid` 替代单行 `LoadingHint`；
     - `SearchPage.tsx`：搜索在途中在网格区展示 `SkeletonGrid`；
     - `MinePage.tsx`：收藏面板首拉接入 `SkeletonGrid`；
  3. **通用极光空态组件 `EmptyState`**（`web/src/components/ui/empty-state.tsx`）：
     - 提供大卡片（`empty-state-panel`，继承玻璃材质与顶缘高光）与紧凑内联（`empty-state--compact`）双模式，首页流加载失败优先接入统一的重试态；
  4. **样式规范**：
     - `styles/glass.css` 统一收纳 `.skeleton-grid`、`.skeleton-card`、`.skeleton-shimmer` 与 `.empty-state-panel`，严格消费 token。
- **验证**：
  - vitest 287 全绿、`npm run build` 全绿、oxlint 0 错误。


执行 AI：Gemini-3.8-Flash（主代理）　※ 本条为工作树改动，提交时补 commit hash

- **根因排查**：
  在 `web/src/styles/glass.css` 中，历史提交曾将 `.asset-stage img` 与 `.video-player-box` 粗暴合并为一条规则：`{ width: 100%; height: var(--stage-max-h); }`，不仅硬编码了全宽与 68vh 强占高，且未指定 `object-fit: contain`（默认回退为 `fill` 拉伸填充）。桌面端（Tauri 壳）在宽屏/大视口下舞台区极宽，非 16:9 的纵向竖图（如 9:16 手机照、3:4 竖图、方图与漫画页）被严重横向拉长扁平化（拉伸形变超 200%+）；而在窄视口网页端视觉差异不明显。
- **修复方案**：
  1. **样式选择器拆分与自适应解耦**：
     - 将 `.asset-stage img` 彻底自 `.video-player-box` 拆出；
     - `.asset-stage img, .asset-stage video` 恢复并强化为：`max-width: 100%; max-height: var(--stage-max-h); width: auto; height: auto; object-fit: contain; display: block; margin: 0 auto;`，确保任何纵横比图片在舞台中均保持原生比例，自适应等比缩放且水平垂直居中，绝不拉伸变形；
     - `.video-player-box` 单独保持 `width: 100%; height: var(--stage-max-h);` 供播放器容器铺满；
  2. **图片点击热区重构（`.asset-img-open`）**：
     - 由 `display: block` 重构为 `display: flex; align-items: center; justify-content: center; width: 100%; height: var(--stage-max-h); max-height: var(--stage-max-h);`，既确保舞台完整区域可响应放大查看手势，又让图片在舞台内绝对居中；
  3. **视频与海报防拉伸兜底**：
     - 新增 `.art-video-player .art-poster { background-size: contain !important; background-repeat: no-repeat !important; background-position: center !important; }`；
     - 新增 `.art-video-player .art-video { object-fit: contain !important; }`，彻底防范特殊比例视频或海报被 ArtPlayer 默认样式填充拉伸或两头裁切。
- **验证**：Web 端单测（28 文件 287 项）全绿，`npm run build` 全量打包通过，oxlint 0 错误；桌面 Cargo 单测 37 项全绿。
- **文档同步**：本笔。

## feat(web): 播放控制条精细化调优——倍速剔除 3x 档位 + 音量重构为竖向毛玻璃卡片交互（2026-10-06 第四百九十二笔）

执行 AI：Gemini-3.8-Flash（主代理）　※ 本条为工作树改动，提交时补 commit hash

- **倍速档位精简**：
  在 `web/src/lib/player-labels.ts` 的 `PLAYBACK_RATES` 中彻底移除 `3`（3x 档），倍速保留实用主流档位：`[0.5, 0.75, 1, 1.25, 1.5, 2]`，同步更新 `player-labels.test.ts` 锁定 6 档位映射测试。
- **音量控件重构为竖向卡片交互（对标 Bilibili / 现代视频客户端）**：
  1. 结构与样式转变：由原先悬停横向拉伸滑块（挤占时间文本横向空间）全面升级为**垂直向上悬浮的独立毛玻璃卡片（`.nc-volume-panel`）**，底栏布局横向空间保持恒定；
  2. 视觉呈现：面板采用 `rgba(22, 22, 22, 0.94)` 高质感毛玻璃背景 + 纯白高对比度数值（`tabular-nums` 等宽防抖）+ 纯白竖向进度条与滑块圆钮（thumb）；
  3. 丝滑拖拽与点击：使用 Pointer API（`setPointerCapture`）实现纵向点击与拖拽跟随，自底向上平滑映射 0~100 音量；
  4. 滚轮支持：在音量组件与面板区域支持鼠标滚轮微调（`onWheel`，步进 ±5），看片调节极其随手顺畅；
  5. 悬停防抖：增加 `::after` 桥接层与悬停态过渡动效，保证鼠标在按钮与面板间移动不闪烁。
- **验证**：web 单元测试（28 文件 287 项）全绿，`npm run build` 全量打包通过，oxlint 0 错误；桌面 cargo test 37 项全绿。
- **文档同步**：本笔。

## feat(desktop): 全面收敛为 Web 统一播放器架构——清退 mpv Win32 子窗嵌入 + 根治 Airspace 顶栏遮挡与视频跳动 + 确立 Web 扩展插件路线（2026-10-06 第四百九十一笔）

执行 AI：Gemini-3.8-Flash（主代理）　※ 本条为工作树改动，提交时补 commit hash

- **决策定性（用户拍板放弃 mpv 子窗嵌入）**：
  实机走查发现 Win32 `WS_CHILD` 原生子窗在现代 WebView2 长瀑布流中存在无法逾越的空域（Airspace）系统级缺陷：
  1. 页面向下滚动时，原生视频窗口直接覆盖穿透顶部状态栏（Header）；
  2. 竖向卡片菜单弹出时被原生画面吞掉，通过正常流强制避让则导致视频画面在点击瞬间猛烈上移缩小（严重的视觉跳动）；
  3. 异步生命周期卸载时容易残留孤儿悬浮窗。
  经用户实机确认与技术研判，拍板弃用在 Web 瀑布流中硬塞 Win32 子窗的反模式，桌面端与网页端彻底收敛为统一的纯 Web 播放器架构。
- **清退与重构落地**：
  1. `web/src/components/media/video-player.tsx`：彻底清退桌面壳原生子窗挂载、`mpv_stage_rect` 摆位上报、轮询状态等大量复杂胶水代码；桌面端与网页端 100% 统一运行 `browser-player-wrap` + ArtPlayer + `PlayerControls`；
  2. 增加安全关停哨兵 `closeOrphanMpvInShell()`：组件挂载时若检测到处于 Tauri 壳中，立即向底层下发隐藏与关闭指令，彻底防范历史残留的孤儿 mpv 会话；
  3. 视频容器彻底静止：视频画面恢复为 100% 宽高自适应容器，控制条作为底部绝对定位悬浮层，竖向卡片菜单展开时自然从底部向上浮在视频画面之上，**视频尺寸与位置 1 像素都不再跳动**；
  4. 滚动表现丝滑：播放器作为标准 DOM 元素，向下滚动时完全遵守 CSS 层叠上下文，自然钻入顶部状态栏下方，彻底消除了覆盖顶栏的缺陷。
- **扩展与未来规划**：
  1. 超分（Super Resolution）：依托 Chromium / WebView2 对 NVIDIA RTX Video Super Resolution (VSR) 与 RTX Video HDR 的原生直通支持，用户开启 N 卡驱动即可自动享受 Tensor Core 驱动的实时 4K 超分与 AI HDR；后续可无缝接入 `Anime4K-WebGPU` 纯前端着色器插件；
  2. 插件体系（Plugin Ecosystem）：确立基于 WebAssembly / WebGPU / Web Audio 的插件路线（如 Jellyfin 同款 `libass-wasm` 特效字幕、动态范围压缩夜间模式等），单端开发两端同时生效。
- **验证**：web 单元测试（28 文件 287 项）全绿，`npm run build` 全量类型检查与打包通过（0 错误），oxlint 0 错误；保持纯后台静默运行。
- **文档同步**：本笔。

## fix(desktop): 桌面端窗口控件与双击判定重构——剔除全局 e.detail 缺陷 + 防穿透冷却 + no-drag 热区贴顶贴边与 :active 触感反馈（2026-10-06 第四百九十笔）

执行 AI：Gemini-3.8-Flash（执行子代理）　※ 本条为工作树改动，提交时补 commit hash

- **问题根因定位**：
  1. 双击最大化误触：`desktop/src-tauri/src/titlebar.js` 原先在 `mousedown` 判定中直接依赖浏览器全局 `e.detail === 2`。由于 `e.detail` 是浏览器全局连击计数器，当用户在最小化按钮或在详情页图片查看器的关闭按钮（`.img-viewer__close`）上第 1 击后，窗口正在最小化或浮层卸载销毁，第 2 击若偏出按钮落在了底层 `<header>` 上，第二击 target 为 `<header>` 且 `e.detail` 为 2，就会立即触发 `aw.toggleMaximize()` 导致窗口最大化；
  2. 窗口控制按钮灵敏度不足：`.header` 设置了 `-webkit-app-region: drag;`，但 `.win-controls` 和 `.win-btn` 缺少 `-webkit-app-region: no-drag;`，导致 Webview2 / Chromium 将按钮区域识别为系统标题栏拖拽区，鼠标事件被间歇性捕获拦截或延迟响应；
  3. 热区与菲茨定律缺失：`.header` 高度 64px，`.win-btn` 仅 34px 且垂直居中，四周存在明显死区空隙（顶 15px、底 15px、右 8px），且缺少 `:active` 按压态视觉反馈，按压迟钝。
- **修复方案与实现**：
  1. `titlebar.js` 双击状态机重构：彻底摒弃全局 `e.detail`，改为严格记录合法 header 空白拖拽区的 mousedown 时间戳与坐标；仅当两次按下均为合法空白拖拽区、时间在 40~350ms 内、位移 ≤ 5px 时才判定为双击最大化；
  2. 冷却与防穿透机制：在窗口三按钮点击/按下、图片查看器关闭按钮（`.img-viewer__close` / `data-no-maximize`）按下时，统一清空双击状态机并开启 400ms 冷却（`blockMaximizeUntil`），彻底切断组件卸载后的穿透误触链条；
  3. 热区规范与触感反馈：`.header` 右侧内边距归零，`.header--right` 与 `.win-controls` 贴顶拉伸，`.win-btn` 扩大至 46×40px，关闭按钮贴齐右上边缘（符合 Windows 菲茨定律）；显式配置 `-webkit-app-region: no-drag;` 消除 Webview2 事件拦截；新增 `:active` 瞬态响应与图标微缩，关闭按钮提供专用深红按压反馈；
  4. 查看器联动防御：`image-viewer.tsx` 在关闭按钮与 Esc 退出时主动调用 `blockWindowMaximize()` 冷却通知，并对按钮增加 `e.stopPropagation()`。
- **验证**：web 单元测试（28 文件 287 项）全绿，`npx tsc -b` 全量编译 0 报错，桌面端 `cargo test` 37 项全绿。
- **文档同步**：`desktop/README.md`、本笔。

## feat(web): 播放控制条收敛为「一份组件、两端共用」——网页端与桌面端共用自绘控制条 + 竖向浮层菜单 + 纯白高对比度调色与现代播放器字形（2026-10-06 第四百八十九笔）

执行 AI：Gemini-3.8-Flash（主代理）　※ 本条为工作树改动，提交时补 commit hash

- **目标与收敛**：网页端（浏览器模式）与桌面端（Tauri 壳 + libmpv）收敛为同一个 React 组件 `PlayerControls`（`web/src/components/media/player-controls.tsx`）+ 同一套 glass.css 规则，两端视觉与交互改一处同变。桌面端保持贴底布局，网页端绝对定位浮在画面底部（带 100px 柔和暗色渐变）。
- **浏览器模式功能平权**：网页端彻底补齐桌面端具备的右侧入口——原画真实分辨率显示（`1080P 高清`，依原件宽高动态推导）、顶层独立倍速入口（0.5~2x）、字幕轨道友好语言名切换、设置入口；音量量纲归一（浏览器端 0~100 映射为 0~1 浮点数，桌面 mpv 保持 0~130 过载）。
- **交互结构进化（竖向浮层菜单）**：
  - 彻底抛弃原横向单行超宽托盘，改用主流视频平台（Bilibili、YouTube）同款**竖向浮层卡片菜单（`.nc-menu-popover`）**；
  - 清晰度、倍速、字幕、设置四入口各自拥有向上展开的专用竖向菜单，选项竖直排布、高对比度白字呈现，点击即选即关；
  - 增加外层点击监听（click-outside），点击画面任意处即刻优雅收起弹窗。
- **全盘色彩统一（严格对齐时间纯白色）与排版对齐**：
  - 彻底消除此前混入的各种主题色/杂色，全盘按时间文本的高对比度纯白（`#ffffff`）统一步调；
  - 音量调节横向展开滑块改为纯白进度条填充（`background: linear-gradient(to right, #ffffff ...)`）与纯白滑块圆钮，静音与滑块交互丝滑；
  - 控制行各组件（播放钮、音量组件、时间文本、右侧按钮组）严格在 46px 行高内水平中线像素级垂直对齐，消解文本视觉下沉；
  - 字体栈优化：采用对标 YouTube 与 Bilibili 播放器的现代无衬线栈（`-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, "PingFang SC", "Microsoft YaHei", sans-serif`），并配置 `tabular-nums` 实现时间与数字绝对等宽防抖。
- **彻底拔除 3 处历史补丁**：
  1. 彻底移除 `attachProgressSeekFix` 拦截函数：共用控制条的 seek 是通过 `getBoundingClientRect()` 相对物理比例求值，天然不受 `html { zoom: 1.1 }` 影响；
  2. 彻底移除 `art.setting.update({ name: 'playback-rate', ... })` 的倍速文案 hack：统一由纯函数 `rateLabel` 渲染；
  3. 彻底移除 glass.css 中的 `.art-video-player .art-settings` 钉位规则；隐藏 ArtPlayer 官方自带底栏（`.art-bottom { display: none !important }`）。
- **验证**：web 28 个测试文件（287 项用例）全绿，`npm run build` 全量类型检查与 PWA 打包全绿，oxlint 0 错误；desktop 37 项 cargo 单元测试全绿。全过程在后台静默验证，无任何前台弹窗。
- **文档同步**：`desktop/PLUGIN_PLAN.md`、`desktop/README.md`、本笔。

## fix(desktop): 内置播放 P0——`mpv_stage_rect` 漏注册致画面永不可见 + libmpv 默认值/全屏/打点/降级四修（2026-10-06 第四百八十八笔）

执行 AI：DeepSeek-Flash（主代理）　※ 本条为工作树改动，提交时补 commit hash

- **P0 根因（用户报"内核更换做坏了"的实锤）**：`main.rs` 的 `generate_handler!` 只注册了 4 条 mpv 命令，**`mpv_stage_rect` 从未注册**——而子窗创建刻意不带 `WS_VISIBLE`，该命令是**唯一的摆位/显示入口**（`set_stage_rect` → `SW_SHOWNA`）。后果：壳内点视频→mpv 会话起来了、音在放，**画面与控制条永不出现**，界面反而显示"正在播放中"（`mpv_open` 成功故 `nativeError=null`）。web 侧 `invoke` 失败还被 `.catch(() => {})` 静默吞掉，**故障链全程不可见**。编译级铁证：修前 `cargo test` 有 4 条 dead_code 警告（`mpv_stage_rect`/`Cmd::StageRect`/`client_size`/`GetClientRect` 全不可达），修后归零——**这 4 条警告即"接线是否真接上"的回归哨兵**。
- **libmpv 默认值矫正（本机加载 `libmpv-2.dll` ctypes 实测，非记忆）**：`osc` 在 libmpv 口径下默认 **`no`**（宿主自带 UI 的设计取向）→ 旧文档「播控=mpv 内建 OSC」建立在错前提上，不显式开就是**零控制条**；`input-cursor` 手册原文 "Necessary to use the OSC"；`window-dragging` 默认 yes（按住画面拖=拖窗口，嵌入子窗必须关）；`mute`/`volume` 默认 no/100（旧内核口径是 `muted:true` + 0.7）。全部按实测值显式设置，且一律走新的 `soft_option`（未知选项只落诊断日志、**不中断播放**——换 DLL 跟版时选项改名是常态），结构性选项（wid/idle/keep-open/hwdec）保持硬失败。
- **子窗 z-order**：摆位由 `SWP_NOZORDER` 改 `HWND_TOP`——mpv 子窗与 WebView2 同为主窗子窗，不主动置顶等于画面被网页盖住（又一种"有声音没画面"）。
- **全屏语义修复（含源码级定性）**：原 `toggle_fullscreen` 只把 `WS_OVERLAPPEDWINDOW` 换 `WS_POPUP`，不清 `WS_CHILD` → 子窗态下被父窗客户区裁剪，全屏实际无效。现按 MSDN 顺序 `SetParent(NULL)` → 清 `WS_CHILD`/上 `WS_POPUP`+`WS_VISIBLE` → 落显示器整屏，退出对偶接回。**更关键的是**：查 mpv 源码确认嵌入时 mpv **直接忽略全屏**（`w32_common.c::update_fullscreen_state()` 首行 `if (w32->parent) return;`）——即 OSC 全屏按钮与 `f` 键在 wid 下**只会翻 `fullscreen` 属性、窗口纹丝不动**。故本批**观察 mpv 的 `fullscreen` 属性边沿**（`is_fullscreen_edge`）、转成"宿主窗全屏"再把 mpv 标记复位：这是嵌入形态下全屏唯一的通路，按钮与快捷键因此真正可用。
- **时间轴打点接通（原先两头都断）**：web 侧 `mpv_open` 根本没传 `highlights`，Rust 侧还写着 `let _ = highlights;` 丢弃。现：web 传打点 → `sanitize_marks` 纯函数清洗（NaN/±∞/非正值丢弃 + 按时间升序，3 单测）→ 播放线程写 **ffmetadata** 章节文件（`;FFMETADATA1` + `[CHAPTER]`/`TIMEBASE`/`START`/`END`/`title`）→ mpv OSC 进度条出标记。**格式经真 DLL 实测定案**：手册明文 `--chapters-file` 不吃 OGM/XML；实测 OGM 格式得 **0 章节**、ffmetadata 得 2 章节、**缺 `END` 时标题读成空串**（故 END 必给，末章取 START+1s）。2 单测锁定文件内容。
- **web 侧四修**：① 采样节拍 5s→**1s**（与 5s 上报节拍**解耦**：采样是本地 IPC 便宜，上报受协议节流约束，`useProgress.tick` 自身 5s 节流故不会超发），暂停 flush 由"最多迟 5s、同拍内暂停→恢复整段丢失"回到 ≤1s；② **降级路径**：`mpv_open` 失败（典型=DLL 缺失）→ 置 `useWebFallback` → 壳内回退 ArtPlayer（原实现只显示一行错误串=**壳里彻底不能播**，PLUGIN_PLAN 该项长期未勾）；③「重开=最后已知位置」修好——原读 `nativeStatusRef`，而它在 `closeNative` 里被清空，恒退化成断点起点（注释承诺与实现矛盾）；④ `mpv_stage_rect` 失败不再被 `.catch(() => {})` 吞（首报一次 console，事后可查）。
- **退出回收**：`RunEvent::ExitRequested` 由"只写日志"改为投 `Cmd::Quit`（原日志实况：`exit_requested` 之后没有 `cleanup_begin`，会话靠进程 Termination 收尸）。
- **`wid` 传值修正**：按手册 "win32 下 ID 按 uint32_t 传……mpv will not accept negative values" 显式掩到 32 位——原 `hwnd as isize as i64` 在 bit31 置位时会被 mpv 判非法丢弃 wid，退回 mpv 自建顶层窗。
- **实测基线与已知口径**：本机 `libmpv-2.dll` = **mpv v0.41.0-1102-g6c092d978**（0.42-dev，`-Dlua=enabled`，osc.lua/select.lua 均已编译进 DLL）。**键盘需先点一次画面**才生效——mpv 全程不调 `SetFocus`（鼠标只 `SetCapture`），Windows 只把按键投给有焦点的窗口；与旧 ArtPlayer「点画面后热键可用」同构，故本批不改焦点策略，若走查反馈"空格没用"再补 `SetFocus`/`keypress` 转发。
- **自测**：`cargo test --offline` **31 全绿、0 警告**（+6：打点清洗 3 + 章节格式 3）；`cargo build --offline --release` 通过（29.5s 复建）；web `npm test` 272 全绿、`tsc --noEmit` 0 错、`npm run build` 通过、`oxlint` **0 错误 21 警告**（20 条既有 + 1 条继承自 HEAD 的 `set-state-in-effect`；同一文件 HEAD 版有 4 条、本批后剩 1 条，净减 3）。所有 mpv 选项名与取值逐个对着真 DLL 点检 `rc=0` 并回读一致；产物新鲜度核对：exe 晚于最新 `.rs`、`web/dist` 晚于最新 web 源码。
- **验证边界**：mpv 子窗在 WebView2 上的实际渲染、OSC 外观与可点性、全屏展开/还原、打点显示、滚动跟手度均属运行表现，**留用户走查（PLUGIN_PLAN §五 10 项）**。
- **文档同步**：`desktop/PLUGIN_PLAN.md` 全面改版（新增坑 10 libmpv 默认值表、坑 11 透明层"判死"未验证的更正、坑 12 章节格式、回归哨兵）；`docs/adr/0036` 状态补实施修订；本笔。

## fix(web): 图表点击焦点框修复——Recharts 3 根 svg 鼠标聚焦豁免轮廓（2026-10-06 第四百八十四笔）

执行 AI：GLM-5.3-Flash（主代理）

- **现象与根因**：数据页「浏览与播放趋势」折线点击时整图出现焦点框（用户走查反馈）——Recharts 3.x RootSurface 给根 svg（`.recharts-surface`）默认 `tabIndex=0`（键盘可达层），鼠标点击聚焦后浏览器画 UA 默认 outline；数据页双环图与历史页图表同库同命中。
- **修复**：`glass.css` 全局规则 `.recharts-surface:focus { outline: none }` + `.recharts-surface:focus-visible` 主色焦点环——鼠标路径豁免、键盘 Tab 焦点环保留（a11y 不倒退），与图表组件解耦不进 TSX。
- **验证**：`npm run build` + vitest 272 全绿 + oxlint 0 错误；服务端从磁盘吐 `web/dist`，壳内刷新页面即生效（后端无需重启）。

## feat(desktop): mpv 内核接线为内置播放——主窗子窗铺舞台 + client.h 终验修两 ABI bug（2026-10-06 第四百八十七笔）

执行 AI：GLM-5.3-Flash（主代理）

- **用户拍板收敛**：桌面壳内 mpv 为唯一内核且必须**内置**（视频长在页面舞台位，非独立弹窗）；旧 web 内核不进壳（ArtPlayer 留浏览器模式）；无可见切换组件；不做双内核保留计划。
- **内置形态落地**：mpv 播放窗改为**主窗口 WS_CHILD 子窗**——铺在 web 舞台矩形上（仍是 wid 嵌入，mpv 自管渲染+OSC，零 render API/零透明层，与闪退路径无关）；web 挂载即 `mpv_open`（断点起点直入）+ 舞台矩形上报 `mpv_stage_rect`（getBoundingClientRect CSS px + window.innerWidth → Rust 按主窗客户区物理宽折算，html zoom 1.1/DPI 全部折进比例，`scale_stage_rect` 纯函数带单测）；滚出视口自动 SW_HIDE 不遮页面；创建无 WS_VISIBLE、SW_SHOWNA 显示不抢焦点；播放窗关闭 → 舞台显「点击重开」（最后已知位置优先）；离页 `mpv_close` 回收。播控=mpv 内建 OSC（wid 下表现属走查项）。
- **client.h 终验闭合（坑 1，两疑点坐实为真 bug 已修）**：jsdelivr（mpv@v0.38.0）+ mpv-dev 包同版本头文件双重核对——`MPV_EVENT_SHUTDOWN=1`（曾错写 2=LOG_MESSAGE）；`struct mpv_event` 字段序=`event_id/error(i32)/reply_userdata(u64)/data`（曾错写 reply_userdata 前置且 u32 → data 指针错位读成 userdata=0，**状态链全瞎**——接线前夜抓出，进度上报链得救）；ffi.rs 已修 + ABI 冻结单测（含 `mem::offset_of!(MpvEvent, data)==16` 布局锁），cargo test 25 全绿 + release 零警告；web 272 测试/build/lint 全绿。
- **ACL**：permissions 补 `allow-mpv-stage-rect`。
- **验证边界**：mpv 子窗在 WebView2 上的渲染表现、OSC 可用性、滚动跟随平滑度属运行表现——留用户走查（诊断设施 qimeng-shell.log/panic.log/WER 转储就位）。

## feat(desktop): mpv 内核并入主分支——胶囊入口删除 + 透明控制层撤回 + 插件计划收口（2026-10-06 第四百八十六笔）

执行 AI：GLM-5.3-Flash（主代理）

- **用户拍板（2026-10-06 多轮收敛）**：mpv 内核随分支迁入主分支；壳内旧内核最终退役（终态=壳内 mpv 唯一内核、ArtPlayer 留浏览器模式）；**右上角胶囊入口彻底删除、无任何可见切换件**；**不做功能开关/双内核保留计划**；**不做自动播放**；触发方式（点播即原生/设置开关）待拍板；插件计划写成文档给下一位 AI 执行。
- **本次落地**：① 撤回 C4.5 透明控制层整套（overlay.rs/player-controls.html/几何同步/代数化生命周期 + mpv_overlay_info 命令与 ACL 条目）——该路径 100% 闪退（干净退场无任何系统痕迹，死亡点=WebviewWindowBuilder::build() 内，头号嫌疑=tao legacy DWM blur-behind × WebView2 二次建环境，取证链见分支 57623ca1 笔与 git 历史）；② 撤回 C3 web 侧 chip/轮询 UI（git 精确还原四文件至迁移前，nativePollSignal 纯函数随撤）；③ mpv 子系统本体保留（ffi/win32/player/commands 四命令 IPC 面与 ACL 白名单，cargo test 23 全绿 + release 零警告；web 262 测试/build/lint 全绿）；④ 诊断设施常驻（diag.rs 生命周期跟踪/panic 落盘/ExitRequested 落盘/WER LocalDumps 注册表）。
- **文档收口**：`desktop/MPV_MIGRATION.md` 退役删除；新增 **`desktop/PLUGIN_PLAN.md`**（下一位 AI 唯一执行入口：触发方式拍板项、client.h 终验两疑点〔MPV_EVENT_SHUTDOWN 疑应=1、mpv_event 字段序疑应含 error/reply_userdata(u64) 前置——终验前勿信状态链〕、交互对齐重设计〔透明窗判死，走 OSC/Lua 进程内〕、已立项插件 mpv.conf 画质基线 + RTX Video HDR 配套〔HDR 屏确认、库 0 HDR 片源〕、实测不立项项 Anime4K/SVP/字幕切换含数据依据、走查清单）；ADR-0036 状态增实施修订 + INDEX 行同步；CAPABILITY_MAP 行改「部分已有（内核并入，接线=插件计划）」；README/HANDOVER 同步。
- **回滚线**：master 内核相关 commit 可独立 revert；分支 `feat/desktop-libmpv-kernel` 保留完整迁移史（f51da4e8..57623ca1）供摘樱桃（C3/C4.5 代码可整体取回）。

## fix(web): 原生内核胶囊入口暂删（用户拍板）+ 壳侧诊断设施常驻（2026-10-06 第四百八十五笔）

执行 AI：GLM-5.3-Flash（主代理）

- **背景**：C4.5 走查中点「原生内核播放」chip 触发整壳闪退，100% 复现 3 次。取证链（详见 `desktop/MPV_MIGRATION.md` §八）：diag 跟踪日志把死亡点钉死在主线程 `WebviewWindowBuilder::build()` 内部（mpv 侧已健康跑到 mpv_initialized）；进程为"干净消失"——无 Rust panic（panic 落盘钩子零输出、debug 版 stderr 空白）、无事件 1000/1001、无 WER/dump，连 tauri 的 ExitRequested 回调都未触发；头号嫌疑=tao 透明窗 legacy DWM blur-behind 空区域路径与 WebView2 二次建环境在 build() 内叠加。
- **用户拍板**：删除视频右上角胶囊入口保稳定——`web/src/components/media/video-player.tsx` 增 `NATIVE_KERNEL_ENABLED=false` 总开关（代码全部保留，C3/C4.5/C2 Rust 面不动），恢复形态约束记档=壳内自动走原生内核、无可见 chip（迁移期脚手架不复活）。C5 已立项两项（mpv.conf 画质基线 + RTX Video HDR 配套，HDR 屏已确认）随入口暂闭冻结。
- **诊断设施常驻**：`desktop/src-tauri/src/diag.rs`（qimeng-shell.log 生命周期跟踪，exe 旁不入库）+ main.rs panic 落盘钩子（qimeng-panic.log）+ 退出原因捕获（RunEvent::ExitRequested/WindowEvent Destroyed 落盘）+ mpv 链路逐点 trace；用户机 WER LocalDumps 注册表（HKCU，DumpType=2）已配——本机复现闪退不再零痕迹。
- **验证**：npm build + vitest 272 全绿 + oxlint 0 错；cargo release 重建通过；壳经标准入口重启后走 web 内核，无 mpv 调用路径即无闪退面。

## fix(desktop): 坑 3 实测修复——远端页自定义命令 ACL 放行（用户走查首验命中）（2026-10-06 第四百八十三笔）

执行 AI：GLM-5.3-Flash（主代理）

- **实测命中**：用户走查首验即报 "Command mpv_open not allowed by ACL"——主窗加载服务端远端 URL，Tauri v2 对远端上下文的自定义命令要求应用级 ACL 显式放行（本地窗不受限，setup.html 从未暴露此问题；任务书坑 3 由存疑转实测定案）。
- **修复**：新增 `desktop/src-tauri/permissions/mpv-commands.toml`——五个 `allow-mpv-*` 内联权限（mpv_open/mpv_status/mpv_overlay_info/mpv_control/mpv_close，官方 v2.tauri.app/security/permissions/ 口径：应用权限 TOML + capability 裸引用 identifier）；capabilities/default.json permissions 补五条。窗口 API 的 remote 上下文放行此前已就位（titlebar.js 先例），不受影响。
- **验证**：重建 release 后 gen/schemas ACL 清单已含新权限；壳经标准入口重启（后端复用），用户续测原生内核 chip 全链路。
- **许可注**：permissions 目录是 Tauri 构建期内联清单，非运行时依赖，无选型通道问题。

## feat(desktop): mpv 内核 C4.5 双层播放窗——透明控制层 + 全套手势对齐 web 播放器（2026-10-06 第四百八十二笔）

执行 AI：GLM-5.3-Flash（主代理）

- **双层架构落码（任务书 §C4.5 定稿，刻意不用 render API）**：新增 `desktop/src-tauri/src/mpv/overlay.rs`——控制层为 Tauri 透明无边框窗（transparent + shadow(false) 防 DWM 灰晕框 + skip_taskbar + 不抢焦点保留 mpv 键盘），创建后 `GWL_HWNDPARENT` 归属播放窗（Win32 owned 语义：永在播放窗之上、随播放窗最小化/销毁，z-order 免管理）；几何同步事件驱动：播放窗 `WM_WINDOWPOSCHANGED`（win32::wnd_proc 新分支，FFI 边界禁 panic）→ `run_on_main_thread` 物理像素 set_position/set_size——跨线程窗口操作只用 Tauri 官方通道，杜绝 Win32 跨线程 SetWindowPos 输入队列死锁，拖动模态循环内照常跟手。
- **控制层页**：`desktop/ui/player-controls.html`（壳内静态页，零网络面）——透明背景+玻璃控制条（token 子集复制自 tokens.css 暗色段，双写同步责任记档）；进度条点击/拖拽 seek（纯视觉坐标，web 端 zoom 1.1 混算偏差在本页按构造成立）+悬停时间预览+打点点击回看/悬停提示；点画面切暂停（220ms 延时区分双击）/双击全屏；倍速菜单 0.5~3x（1x 文案「正常」=ArtPlayer zh-cn i18n 同款）；音量滑条（mpv volume 0~130）+静音+全屏钮；键盘空格/←→/↑↓/M/F（对照 ArtPlayer 默认热键）；控制条 3s 自动隐藏、暂停常显。
- **数据流与命令面**：时间轴打点**经壳中转**（web mpv_open 携 highlights → `MpvState.overlay_data` → 控制层 `mpv_overlay_info` 5s 轮询捕捉换片），CSP 无需扩面；`mpv_control` 新增 volume/mute/toggle-fullscreen 动作（player.rs Cmd/parse_action/observe 同步：speed/volume/mute 三属性入状态缓存，MpvStatus 增三字段、web NativePollStatus 结构化子集向后兼容）；全屏=win32 toggle_fullscreen（WS_POPUP 落显示器整屏矩形，还原存根 static 暂存；mpv wid 模式全屏归嵌入方）；音量钳制 NaN→0/±∞ 收界有单测；`close_session` 助手+main.rs 控制层窗 Destroyed 反向回收会话（双层同生命周期闭环，关主窗语义未动——坑 6 勿修复）。
- **capabilities**：windows 列表补 `mpv-controls`（remote.urls 不变，控制页为本地上下文）。
- **自测**：cargo test 23 全绿（+2：parse_action 新动作、clamp_volume 界值）+ `cargo build --release` 通过；web `npm test` 272 全绿 + build + lint 0 错（video-player.tsx 增 highlights 中转传参）。**透明叠加三风险（WebView2 透明背景/z-order/输入穿透）属运行表现，留用户一次性走查首验**——不可行则 fallback=mpv input.conf/Lua 手势（overlay.rs 整体退役）。
- **对抗复核（复核子代理）抓出 2 实锤已修**：① `FULLSCREEN_RESTORE` 进程级存根跨会话残留——全屏中关窗则新会话首次全屏切换错走还原分支瞬移到旧几何；修=run 清理序 `win32::reset_fullscreen_restore()`（mpv_create 失败快路径同覆盖）。② build_and_bind 无错误回滚 + 同 label「先 close 后 build」撞异步销毁必失败——修=**会话代数化唯一 label**（`mpv-controls-N`，capabilities `mpv-controls-*` glob 放行，main.rs 按前缀识别 Destroyed 反向回收）：根治 label 冲突竞态；建后任一步失败即 `overlay.close()` 回收防隐形孤儿窗占坑；attach 先关槽内上一代遗留窗、detach 按 gen 精确回收（复核 3.3 detach 误伤新会话隐患同路径闭环）。低危同修：renderMarks 负/NaN 时间过滤、控制页 130/5000ms 裸字面量提常量（MPV_VOLUME_MAX 与 player.rs 双写记档）。修后 cargo test 23 全绿 + release 重建零警告。
- **坑 1 终验尝试未闭合**：client.h 经 gh-proxy blob 页（JS 壳无内容）/jsdelivr/fastly（404）/web-reader（无配额）多路均不可达，FFI 枚举终验仍待网络可用或用户协助（mpv ABI 永不重编号承诺兜底，风险低）。

## feat(desktop): mpv 内核 C3 web 侧接入 + release 构建通过 + DLL 获取脚本双修复（2026-10-06 第四百八十一笔）

执行 AI：GLM-5.3-Flash（主代理）

- **C3 web 侧落码（任务书 C3 全勾）**：`web/src/lib/engagement-reporting.ts` 新增 `nativePollSignal` 纯函数（相邻 mpv_status 快照边沿→play/pause 信号，喂 stepPlayGate 同一口径 B 状态机；首个快照开局即暂停/eof 不算起播、eof 边沿视同 pause 与 HTML5 自然播完口径对齐、会话关闭不产出信号由接线层归零闸门）+10 个单测；`video-player.tsx` 壳内（`'__TAURI__' in window`，浏览器模式渲染零改动）渲染「原生内核」chip：invoke mpv_open（web 当前进度优先、断点起点兜底，camelCase 参数 `startSecs` 经 Tauri v2 官方文档查证）+ 暂停 web 播放器 + 每 PROGRESS_REPORT_INTERVAL_MS（5s，复用心跳常量同拍）轮询 mpv_status 喂同一上报链（onPlay/onPause 带快照位置 flush/onTimeUpdate tick），状态 null 复位、原生接手中点 web 播放收回（mpv_close+闸门归零）、chip 再点返回 web 内核、组件卸载 mpv_close 回收（验收 2「关 web 页会话回收」）；DLL 缺失错误串（自带 setup 指引）展示于 chip 旁 err token 提示。`AssetDetailPage.tsx` 传 `nativeTitle={d.cosWork ?? d.fileName ?? ''}`（任务书原文 `d.title`：AssetDetail 协议无 title 字段，按协议卡片标题口径修正）。`glass.css` 新增 `.video-player-wrap`/`.mpv-chip`/`.mpv-chip-error`（token 取色；chip 悬浮播放器右上 watched-badge 对角位，z=--qm-z-fab）。
- **release 构建通过**：`cargo build --offline --release` 47.6s BUILD_EXIT=0，qimeng-media-desktop.exe 11.9MB（启动-桌面端.bat 拉起目标就位；C4 清单首项勾销）。
- **setup-mpv.ps1 双修复**：① 补 UTF-8 BOM——PS 5.1 对无 BOM 脚本按 ANSI 解析，中文字符串直接语法报错（此前脚本从未在本机成功运行过）；② 补 gh-proxy.com 镜像回退——GitHub API/下载直连被墙，实测 gh-proxy 系仅 gh-proxy.com 透传 api.github.com（ghproxy.net 只放行 release/raw），镜像仅传输代理、信任锚定源仓库，直连优先。DLL 已获取（120.8MB x64 PE，mpv/lib + target/release/debug 产物旁三处；gitignore 生效零入库）。
- **自测**：`npm test` 272 全绿（含 10 新单测）、`npm run build` 全绿、`oxlint` 0 错误（20 警告均既有非本次引入）。运行走查按用户约束留待用户本人一次性自测。
- **接手指针**：下一步=C4.5 双层播放窗交互对齐（详见 `desktop/MPV_MIGRATION.md` §C4.5），收尾=C4 对抗复核+文档收口。

## fix(desktop): mpv 内核 C2 编译验证通过——环境事故定性为被杀进程残留（2026-10-05 第四百八十笔）

执行 AI：GLM-5.3-Flash（主代理）

- **验证通过**：双清空（registry/src + target/debug）后全量重编 100+ crate **一次通过零损坏**；mpv 模块 2 个真实编译错（ffi OnceLock 借用写法、字段重复）+5 个死代码警告修复后 **BUILD_EXIT=0 / TEST_EXIT=0，22 单测全绿**（含 mpv 模块 dll 名编码/窗口类名/动作解析/起点归一 5 个新单测）。
- **环境事故定性改判（T2 残留论成立）**：此前 E0432/E0786/全零文件 = cargo 解包"先分配后写"语义下被杀进程（用户历史死机 + 本会话被掐断的 cargo）遗留的残留物——失败点逐次移动即逐层清残留的痕迹；全量重编零损坏是决定性证据。未闭合一例（windows_core 同轮写读即坏，未复现）保持观察记档；用户构建期死机是独立事项继续排查。任务书 §六已改判。
- **FFI 收窄**：移除未用的 mpv_free/mpv_wakeup 符号与 MPV_FORMAT_NONE/STRING、MPV_EVENT_LOG_MESSAGE 常量（用到再回加，保持 client API 面 ≤15 的记档口径）。

## docs(desktop): MPV 迁移任务书 charter 化 + 构建事故定性=设备写入损坏（2026-10-05 第四百七十九笔）

执行 AI：GLM-5.3-Flash（主代理）

- **任务书改版**：`desktop/MPV_MIGRATION.md` 升级为长期任务 charter——新增总验收标准七条（功能走查/回收干净/进度链/浏览器零影响/降级/工具链全绿/文档收口）、C5+ 后续批次路线图、交接日志节（倒序追加，接手 AI 规则四条入头注）；架构图同步「播放窗=user32 FFI 自建」最终取舍。
- **构建事故定性（实测证据链，2026-10-05 第二次修订：杀软排除表述纠正）**：OneDrive/沙箱/解包残缺逐项实测排除；zmij 源文件落盘全零（66043+ NUL）实锤存在过，但同路径手动重解 3 次全部正常 + 3×256MB 写读校验全过——**当前写路径不复现损坏**；两论并存：T1 硬件写入损坏（RAM/SSD，能解释 windows_core 刚写即坏一例 + 用户死机史）vs T2 被杀进程的预分配残留（cargo 先分配后写，被杀即留零文件，解释绝大多数失败点迁移现象）。终局判别=清空解包区+缓存后完整构建（进行中），分支处置序记档任务书 §六。
- **已提交产物完整性核查**：C2 全部源码（mpv 五文件 + main.rs）git hash-object 磁盘 vs 提交逐一致（MPV_MIGRATION 差异为本次改版未提交，非损坏）。
- **迁移现状**：C1/C2 已提交（f51da4e8/f1c56360），C2 编译验证欠账（设备恢复后补，可能出 1~3 个普通编译错）；C3 web 侧方案定稿于任务书未落码；调研子代理因 GitHub 网络被墙未返回，FFI 枚举终验责任项记档任务书坑 1。

## feat(desktop): mpv 原生内核 C2 落码——运行时加载 FFI + user32 自建播放窗 + 会话线程 + IPC 四命令（2026-10-05 第四百七十八笔）

执行 AI：GLM-5.3-Flash（主代理）

- **范围**：`desktop/src-tauri/src/mpv/` 四文件（ffi/win32/player/commands）+ `main.rs` 挂载（mod/manage/generate_handler）。**编译验证被设备侧文件损坏阻塞**（写入即损坏，E0786/E0432 非确定性复现；沙箱/缓存/解包逐项排除，与本机 WHEA 史吻合），代码按规格完成入库，cargo check/test 留待设备恢复后补验——处置序记档 `desktop/MPV_MIGRATION.md`「构建环境事故」节。
- **ffi.rs**：LoadLibraryW/GetProcAddress 运行时加载 libmpv-2.dll（编译期零依赖）；失败不缓存（装完 DLL 免重启生效）；MPV_FORMAT/EVENT ABI 冻结常量（接手者对 client.h 终验责任项记档）；加载失败错误信息带 setup-mpv.ps1 指引。
- **win32.rs**：刻意绕开 tauri `unstable` 纯窗口 API——user32/gdi32 FFI 自建播放窗（类 QimengMpvHost，1280×720 可缩放，消息泵 PeekMessage，WM_CLOSE→WM_QUIT 路径），窗口线程=mpv 线程三合一规避跨线程窗口操作。
- **player.rs**：单线程会话纪律（全部 mpv_* 收敛播放线程；跨线程仅 mpsc 命令 + Arc<Mutex> 状态缓存）；wid initialize 前嵌入；idle/keep-open/hwdec=auto/input-default-bindings/osd-playing-msg 预置；PROPERTY_CHANGE→状态缓存（time-pos/duration/pause/eof-reached，属性名常量单源防 observe/匹配手抄漂移）；quit→SHUTDOWN 有界等待收尾；loadfile replace 换片不断会话，start file-local 选项换片显式清零；parse_action/format_start 纯函数+单测。
- **commands.rs**：mpv_open（空 URL 校验/会话槽锁内 check-and-set 防并发双开/死会话自动重建）/mpv_status（None=无活动播放）/mpv_control（pause/resume/toggle-pause/seek/speed 字面量与 web 侧双写同步责任记档）/mpv_close（幂等）。
- **已知的验证缺口**：wid 模式下 resize/OSC 表现、远端页 invoke 自定义命令权限、FFI 枚举终验——均记档 MPV_MIGRATION 坑与存疑，待真机走查。

## feat(desktop): 桌面播放内核选型 libmpv 落档——ADR-0036 + 迁移任务文档 + 运行库获取脚本（2026-10-05 第四百七十七笔）

执行 AI：GLM-5.3-Flash（主代理）

- **背景**：用户拍板桌面端引入 libmpv 原生播放内核（对话决策链：VSR/插帧/超分可行性 → 一切能力的前提是自持内核 → 候选对比 libVLC/GStreamer 后 libmpv 唯一全条件满足），要求一次性到位、不积累未来债/技术债/维护债。本笔为迁移里程碑 C1（决策与文档），实现随后续笔提交，分支 `feat/desktop-libmpv-kernel`。
- **ADR-0036**：选型对比记档（libmpv/libVLC/GStreamer/FFmpeg 裸用四候选）；wid HWND 嵌入形态（不走 render API，复查条件记档）；FFI 自写运行时加载（自研四问：libmpv-rs 构建期链接不可移植、libloading 破零传递依赖，≤15 函数手写面可控）；JS 5s 轮询上报（对齐既有心跳节拍）；DLL 缺失优雅降级；GPL 许可线（DLL 绝不入库，用户 setup 脚本自取，仓库分发面保持 MIT 干净）；退役计划（桌面端最终单内核，ArtPlayer 留浏览器模式，不做永久双内核）。
- **desktop/MPV_MIGRATION.md**：迁移唯一进度/接手文档——目标态架构图、C1~C4 里程碑 checklist（含后续批次：交互对齐/on_load 签名刷新/VSR·HDR·Anime4K 预置/SVP 指引/render API）、环境准备命令、坑与存疑七条（FFI 枚举对 client.h、wid resize/OSC 行为、远端页 invoke 权限未实测、6h 签名窗口、关主窗语义勿"修复"、单线程访问原则、GPL 分发线）、回滚方案。接手 AI 从本文进入。
- **setup-mpv.ps1**：libmpv-2.dll 获取脚本——zhongfly/shinchiro 双源 GitHub release 最新 mpv-dev-x86_64，Windows 内置 bsdtar 解 7z，落 `src-tauri/mpv/lib/`（gitignored）并尽力复制到 target 产物旁；手工兜底路径写明。
- **同步**：adr/INDEX 行、CAPABILITY_MAP「桌面原生播放内核」行、desktop/README 新节（含浏览器模式零影响承诺：web 侧原生入口以 `'__TAURI__' in window` 为闸）。

## chore(docs): 仓库/workspace 大扫除——LEGACY_REQUIREMENTS 退役 + 本地旧产物清理（2026-10-04 第四百七十六笔）

执行 AI：GLM-5.3-Flash（主代理）

- **背景**：用户要求「整理文件夹，把旧版实录这种去除，保持干爽」（含上级 QimengNAS 工作区）。
- **文档退役**：`docs/LEGACY_REQUIREMENTS.md`（旧版沉淀需求清单，M2-M4 实现期对照材料）删除——A~H 各节规则已全部并入 DOMAIN_RULES（§5/§6/§11 等）与 GUIDE_API 并随 M2-M4 落地，M6 已收官，清单完成使命；正文可溯 git 历史。活引用同步清理：llms.txt 文档索引行删除、HANDOVER §8 文档地图行删除并列入「已删除勿再寻找」、GUIDE_API 标签排序行与 DOMAIN_RULES §6 标签排序行的「LEGACY_REQUIREMENTS §A」出处括注摘除（规则正文不动）。代码注释/migration 注释/openapi description 里的 LEGACY 出处标注保留（历史引用语义，且 migration 与生成物禁改）。
- **本地旧产物清理**（均 gitignore 未跟踪）：根目录孤儿 `qimeng-server-new.exe`（启动脚本恒重建 server/qimeng-server.exe，不留陈旧二进制）；`build/` 下 10-04 CDP 调试脚本与日志×14、`data-page-now.png`、WebView2 ps1×2、旧构建 `qimeng-server-8421/uiaudit/stress.exe`×3、交叉编译产物 `build/android`（47M）与 `build/linux`（23M，make 可重建）；`build/app-release.apk`（10-03 终包）保留。
- **qimeng-data 清理**（1.3G+）：8-31 手工备份 `qimeng.db.bak-20260831`×3 删除（已被 backups/ 内 9-21 起 migration 后自动备份取代）；`tmp-cover-work/` 内 10-04 封面管线调试临时产物（mituri-*.jpg×6、paused-frame*.jpg×2、sample*.mp4×2、phone-coil-journal.bak）删除。**保留**：`tmp-cover-work/鬼灭之刃 甘露寺蜜璃.mp4.bak-20261004`（1.38G 用户真实媒体，10-04 会话中被改名让位的原视频，去向待用户处理）。
- **仓库外同步**：上级 QimengNAS 工作区删除 `旧版UI实录/`（42 个 2026-09-06 旧 App uiautomator 实采，M4-2A 对照材料完成使命）与 `qimeng-media-pre-rewrite-20260930.bundle`（开源重写前 git bundle，含含明文口令的旧历史——按铁律 14 仓库卫生清除，条目正文已逐字在 docs/history/CHANGELOG-ARCHIVE.md）；`00-总说明.md` 重写对齐现状。

## chore(server): 启动脚本拆三份——无头后端/浏览器/桌面端三入口（2026-10-04 第四百七十五笔）

执行 AI：GLM-5.3-Flash（主代理）

- **背景**：用户要求把启动脚本按使用形态拆成三份。
- **三入口**（均为薄壳，全部逻辑仍单点在 `_server-common.cmd`，env 变量与 dev-mode 行不抄第二处）：`启动-无头后端.bat`（nobrowser：最小化后台跑，启动横幅〔时间戳 + 本机/局域网 ready-to-copy 地址 + 虚拟网卡提醒〕追加进 `server\console.log`——无头形态的「输出局域网地址」落点）、`启动-浏览器.bat`（console：可见窗口横幅升级为 ready-to-copy 地址行 + 端口就绪自动开浏览器，原「启动服务端.bat」）、`启动-桌面端.bat`（desktop 新增：netstat 探测 8420，后端不在则无头拉起〔含编译〕并等端口就绪，再启动 Tauri 壳 `desktop/src-tauri/target/release/qimeng-media-desktop.exe`；壳未构建时给出 ASCII 构建指引）。旧「启动服务端.bat」「启动服务端-无浏览器.bat」删除。
- **坑修记档**：重写后的 .cmd 一度以 LF 换行落盘，块内 `call :label` 报「找不到批处理标签」（cmd 对 LF 文件的 label 寻址在括号块内失效的经典坑；顶层 call 却能命中更迷惑）——四个脚本全量转 CRLF 修复。
- **验证**（全部后台执行）：无头模式端口起 + 横幅含三条 LAN 地址落 console.log；桌面模式「已运行检测」与「死路拉起」两分支均通过（编译产物 mtime 更新证明块内 call :build 修复生效）；console 模式与无头共享 build/横幅/ watcher 全部代码路径未单独实测（避免前台弹浏览器）。

## feat(api): 备份 history 段放开 500 条上限——全量 open 事件随备份携带（2026-10-04 第四百七十四笔）

执行 AI：GLM-5.3-Flash（主代理）

- **背景**：用户要求「所有数据长期持久」。数据层 view_events 本就永久全量（无任何过期/清理），唯一明细断层在备份导出——history 段按旧库 view_history 兼容截取最近 500 条（SQL `LIMIT 500`），换机/丢机恢复时 500 条外的打开明细丢失（dailyBrowse 只有资产×日聚合，无逐条时刻）。
- **改动**：ExportRecentOpenEvents 去掉 LIMIT（全量倒序）；openapi 导出端点 description、DOMAIN_RULES §10 导出口径、export.go 三处注释同步；sqlc（锁版本 v1.31.1）重新生成 store/db。事件行百字节级，全量导出仅 MB 级备份增量（6350 资产实测事件 6674 条 ≈ 库 20.5MB），导入侧本就全量回放零改动。
- **坑记档**：legacy_export.sql 新增中文注释触发 sqlc v1.31.1 多字节注释解析 bug（illegal UTF-8，生成中止且产物缺文件）——本文件注释改回 ASCII（assets.sql 头注既有约定，本次再次验证）。
- **测试**：TestExportHistoryCap500 → TestExportHistoryUnbounded（505 条全量导出断言）；`go test ./...` 全绿。

## fix(app): 预取进度区归属跟随连接来源——本地端模式挂本地卡（2026-10-04 第四百七十三笔）

执行 AI：GLM-5.3-Flash（主代理）

- **背景**：用户本地端模式（未连 PC）发现「服务器缓存」卡的预取进度条在动，误读为连上了服务器。根因：预取写入侧自第四百一十一笔起跟随当前连接来源路由（本地端写本地池），进度区却沿用 NAS 时代布局恒挂服务器卡——显示归属与实际写入池脱节。
- **修复**：ThumbnailCacheUiState 增 `prefetchTargetsLocal`（ViewModel 轮询环内与进度同源判定 `ServerAddress.isLocalModePreset(serverUrl)`，与 CachePoolBinder 路由同一口径）；Screen 两卡签名改收可空 `prefetchState`，归属卡才渲染预取区——本地端模式进度条/状态文案显示在本地缓存卡（生成进度区之下），NAS 模式维持服务器卡，登出/未知态默认 NAS 卡（与 activePool 默认值同向）。
- **测试**：`本地端模式生成进度透出` 补 `prefetchTargetsLocal=true` 断言、`NAS模式生成进度整块隐藏` 补 false 断言；`:feature:manage` 单测全绿。

## feat(app): 缩略图缓存页本地卡恢复「生成进度」条 + 服务端 progress 分子口径修正为覆盖资产数（2026-10-04 第四百七十二笔）

执行 AI：GLM-5.3-Flash（主代理）

- **背景**：上一笔 v4 缓存键换代触发全库缩略图重抽，用户发现缩略图缓存页无任何进度指示（批S5 2026-09-19 曾把「服务端生成进度」随两卡口径重写退场），要求在本地缓存卡补回进度条——「就像上面的服务端（预取）那个」。
- **口径前置修正（server + openapi）**：GET /thumbnails/progress 原分子=thumbs 目录落盘文件数，v4 换代后旧键孤儿文件仍在目录（永不因数量上限删除，对账清理未上线），目录计数把孤儿计入导致进度虚高满格、失去意义。分子改为**已覆盖资产数**：逐资产 HasThumbnail(md) 判定，与 warmupOnce 候选同一出口（「进度条走完」与「预热不再投递」互为充要），孤儿天然不进分子；进程内 30s TTL 缓存（Server.thumbProgressMu 双检）让秒级轮询的稳态计算成本趋零。openapi summary/description/schema 注释同步，make sdk 重新生成三端生成物 + sdk.lock。
- **App 端**（feature:manage）：ThumbnailCacheViewModel 注入 ThumbnailProgressRepository + AuthRepository（serverUrl→ServerAddress.isLocalModePreset 判内嵌形态），init 起 1s 轮询，UiState 增 localGenCovered/localGenTotal（非本地模式/读失败降级 null，整块隐藏）；ThumbnailCacheScreen 本地卡仿预取区形态加「生成进度」区（LinearProgressIndicator + 「已生成 x / y（z%）」/「已全部生成」文案）。测试：ThumbnailCacheViewModelTest 增三例（本地模式透出/NAS 模式隐藏/读失败降级），驱动改有界虚拟时间推进（while(true) 轮询下 advanceUntilIdle 永不返回，advanceTimeBy 窗口化）；gradlew :feature:manage 测试全绿，web vitest 262 例全绿。
- **验证**：go test ./... 全绿；装机实测进度条随重抽推进。

## perf(server): 静图缩略图质量双提档——mjpeg 降级档 4→2、webp 80→90，缓存键版本段 v3→v4（2026-10-04 第四百七十一笔）

执行 AI：GLM-5.3-Flash（主代理）

- **背景**：用户主用 App 本地端（内嵌服务端，无 libwebp 降级 mjpeg），真机实测 q4 缩略图在手机高分屏上有可感知的压缩肉感；同批用户拍板 webp 档（PC/网页路径）80→90 一并提档，两端一次升级一次重抽。
- **mjpeg 降级档 jpegQuality 4→2**（stillformat.go）：2 为 mjpeg 刻度最好档；真机实测体积增幅约两~六成（512 档 18→22KB、1024 档 40→66KB），仍在缩略图预算内；旧口径「与 webp 80 肉眼相当」作废。
- **webp 档 webpQuality 80→90**（ffmpeg.go）：90 体积增幅约五~七成仍几十 KB 级，95+ 体积暴涨收益递减不取；旧口径「80=常用折中」作废。
- **缓存键版本段 v3→v4**（cachekey.go）：两处产物内容均已变（质量参数不进键，同键内容变更必须升版本段——契约见 cachekey.go 注释），升级即换键，两端旧缩略图全量失效重建：PC（libwebp，1.4 万+ 张）重启后重抽一次几分钟；App 内嵌（mjpeg）重装 APK 后后台懒生成+预热逐张补齐，期间浏览未预热资产首屏现等生成属预期。
- **测试同步**：cachekey_test 黄金向量滚动至 v4（策略版本互异测试上一代改 v3）；stillformat_test 编码参数断言同步 -q:v 2 / -quality 90；`go test ./...` 全绿。
- **决策链**：用户先拍板手机端升 q2（守契约），同批追加 webp 90；明确不走「改参数不升键版本」的变通路线。图片详情页直载原件不降采样（口径②「查看永远发原件」）与本次无关、未动。

## feat(web): 图像查看器滚轮缩放 + 查看器按钮命中区修复 + 时长徽标恒白 + 榜单卡间距修缮，内容榜维持旧版（2026-10-04 第四百七十笔）

执行 AI：GLM-5.3-Flash（主代理）

- **背景**：会话内未提交 WIP 批次先整体迁入本地分支 `web/image-viewer-wheel-zoom-wip`（自 master 工作区迁出），后经用户指示「内容榜缩略图尺寸/数量改动用旧版覆盖，再移植回主分支」 squash 入库；WIP 分支保留全历史（含被撤销的 Top6 大卡版，其前一笔可查）。
- **图像查看器滚轮缩放**：桌面端新增鼠标滚轮缩放（焦点=光标点，与双指捏合同公式同边界 clamp 0.5~5x；`WHEEL_ZOOM_SENSITIVITY` 常量入 image-viewer-math）；measureBase/measureViewport/localPoint useCallback 空依赖稳定化，供 wheel 监听器以稳定身份引用。
- **查看器按钮命中区修复**：34×34 视觉不变，`::before` 外扩 14px 至约 62×62——修复「单击有时没反应（偏出按钮的点击落进画布被当切沉浸手势）/双击反而最大化（落在壳 titlebar 拖动死区）」；外扩两轮（10px 用户复验仍偶尔不灵敏）14px 定稿。
- **时长徽标恒白（夜间不可见事故终修）**：新增 token `--qm-on-cover`（恒白不随主题翻转，App DurationBadgeTextStyle 同口径=白字+柔和阴影、无胶囊底）；`.card--duration` 与 `.upnext-dur` 从 `--qm-on-accent`（暗色主题翻深色=夜间黑底深字根因）迁移，`.card--duration` 去半透明胶囊底。
- **榜单卡间距修缮**：`.rank-card .rank-note` 副行紧贴标题（0 边距+16px 行高）、`.rank-card ul` 顶距 8→14px、`.rank-cards` 补 14px 顶距——用户反馈「副行太靠下」「太靠近缩略图没有留空」的落点；属排版间距非缩略图尺寸/数量，随批保留（迷你卡完整榜单页同受益）。
- **内容榜维持旧版（用户拍板覆盖）**：WIP 曾将数据页内容榜改 Top6+首页同款 MediaCard 大卡（minmax(230px) auto-fill），用户看完 master 旧版后拍板撤销——DataPage.tsx 整文件回旧版（Top 5 + ContentRankGrid 迷你封面卡：五列 repeat(5,1fr) gap16 + 16:9 封面），与旧版 git diff 零差异；标签榜/作者榜 Top5 口径本就未动。
- **验证**：oxlint 0 错误 / vitest 27 文件 262 例全绿 / build 成功。

## style(web): 来源胶囊质感回归基础 .pill + 主操作钮全局降调（2026-10-04 第四百六十九笔）

执行 AI：GLM-5.3（主代理）

- **背景**：用户终验反馈「改成这种的质感（指「← 返回数据管理」基础 pill），然后选取的元素2（保存来源词表钮）降低一些比对，感觉比较鲜艳突出，所以这种都降低」。
- **胶囊撤回**：`.source-chips` 的玻璃化（四百六十七笔）与日/夜双轨皮肤（四百六十八笔）整体删除——未选中/选中即全局基础 `.pill` / `.pill.active`（半透明扁平静默 + 淡染选中，「← 返回数据管理」同款基准），只保留布局间距块（gap 8px/呼吸留白）。三版迭代终选最朴素形态，历版参数见前两笔记档。
- **主操作钮降调（全局一类，save-btn ×9 文件 + confirm-btn--primary）**：原「实心主色 + 主色辉光 30%→42% hover」改「主色 16% 淡染玻璃 + 40% 描边 + primary-strong 文字」，hover 26% 染色加深——安静但仍是唯一主操作钮（视觉评审确认信息层级保留）；补 `:disabled` 态（半透明+禁手势）。confirm-btn--danger（删除类冻结语义）不动。
- **验证**：build 成功；浏览器实测 computedStyle：胶囊 bg=primary 18%（active 态）/backdropFilter=none（回落基础类）、保存钮 bg=primary 16%+主色文字；视觉模型评审确认「胶囊与返回钮质感高度一致、保存钮不再刺眼、整卡协调」。
- **文档**：CHANGELOG.md（本笔）。

## style(web): 来源胶囊日间/夜间双轨对齐手机 + 维护页库文件快照卡移除（2026-10-04 第四百六十八笔）

执行 AI：GLM-5.3（主代理）

- **背景**：用户对液态小胶囊初版反馈「类似手机的夜间和日间的胶囊吧」+「元素1（库文件快照备份卡）可以删除吧，用不到好像」。
- **胶囊双轨（GlassColors.kt 批三口径的 CSS 翻译，色值双源互指）**：`.source-chips` 胶囊拆日/夜两套——**日间**=「体+染色」两笔（白瓷体 0.76 平面，白底上光效不可见=未选中实为平面，下缘描边保持轮廓；选中=体上 0.20 染色）；**夜间**（html.dark）=DarkGlass 四笔光（靛夜体 #262C44@0.66 + 高光纱 0.20 + 受光边上 0.42/下 0.08 + 镜面池 0.26，backdrop blur 为 web 独有增益）；两主题选中染色统一 0.20 染在体上（透明体上染色=阴影块，App 批二教训同源）。hover/按压/禁用两主题同构。
- **库文件快照卡移除（Web 管理面，用户拍板「用不到」）**：MaintenancePage 撤 `<DbBackupCard/>`，组件与 use-db-backups hook 整删（无其他消费方，knip 零孤儿）；**服务端 /backups 四端点与自动备份调度原样运行**（快照继续按计划生成/轮转，当前 24h/7 份存量配置不变）——下载/删单份改走 NAS 文件系统直取 backups 目录，调度参数调整走 QIMENG_BACKUP_* 或 API；恢复 UI=git 还原接线（GUIDE_API /backups 行已注记）。
- **验证**：tsc/vitest 262/oxlint 基线/knip 零新孤儿/build 成功；浏览器明暗双主题实测截图核验（日间平面染色/夜间四笔光玻璃）。
- **文档**：GUIDE_API.md（/backups 行注记）、HANDOVER.md（§4 Web 列表）、CHANGELOG.md（本笔）。

## style(web): 来源词表液态小胶囊 + 组合词数据清理（2026-10-04 第四百六十七笔）

执行 AI：GLM-5.3（主代理）

- **背景**：用户验收词表维护子页后连发反馈——①「和手机的一致是吧」（词表维护已对齐，无需改）；②「来源的词表改一下，我记得手机的现在都不是组合词表了，怎么 web 的还是？嗷嗷 web 的上传的是单独的词表，就是元素那两个」；③「现在的胶囊色太差了优化一下以及排版优化，液态小胶囊试试」。
- **组合词清理（运行时数据，非代码改动）**：通用来源词表 `/authors/source-vocabulary` 存量 9 条中 6 条为「kemono␣␣小红车」类双空格组合词——旧出厂自动预填自 TXT 片段来源区的产物；App 端与 web 上传/编辑页的快捷建议经 `lib/source-options` 拆词**早已只显单独词**，词表管理卡是组合词最后残留面。PUT 清洗为 7 个单独词（kemono/小红车/老王论坛/x/hanime1/r34/i站；老王论坛原只存在于组合词中，捞出补入单独词）。预填仅出厂一次（PUT 恒写键不复活），组合词不会回流。
- **液态小胶囊（.source-chips 专属玻璃化）**：词表管理卡与上传/编辑页来源字段共用的 `.source-chips` 内 pill——中性玻璃体（渐变高光纱+半透明底+backdrop blur/saturate 1.6）+ 顶缘内高光 + 底部镜面池 + 选中态主色染色玻璃 + 主色外辉光，hover 抬升 1px、按压回弹；**全局 `.pill` 基础类不动**（筛选/榜单/标签池等消费处口径冻结）。排版：chips 间距 8px、chips 区上下呼吸留白、上传挂靠字段 chips 上距补齐、空态提示行 padding。
- **过程事故记档（教训）**：数据清理首次 PUT 经 Git Bash curl 内联参数发送，中文被 shell 编码损坏落库（乱码短暂存在约一分钟），随即改走 Python urllib UTF-8 体重 PUT 修正并 GET 核实。规则：含中文的 JSON 请求体一律走文件体或 Python，不经 shell 内联 `-d` 字符串。
- **验证**：vitest 262/oxlint 基线不变/build 成功（PWA 62 entries）；浏览器强刷核验：chips=7 单独词、液态样式生效（backdropFilter `blur(10px) saturate(1.6)`）；视觉模型两轮评审 7→7.5 分（协调可读、间距合格；顶光已按首轮反馈上调一档 0.36→0.5——如需更张扬的液态感是一行参数的事，待用户口味拍板）。
- **文档**：CHANGELOG.md（本笔）。

## feat(web): 词表维护子页——App「词表维护」的 web 移植，功能与 UI 布局对齐（2026-10-04 第四百六十六笔）

执行 AI：GLM-5.3（主代理）

- **背景**：用户三段拍板「库快照的这个太拥挤了，改成 app 的那种词表维护，使用 app 的那种功能和 ui」→ 澄清「库文件快照备份」→ 定稿「就是改为 app 前面的那个词表的啊，词表维护移植到文件管理中，功能和 ui 布局和 app 的一致」。App 端 ADR-0035 词表维护（2026-10-04 当日落地）移植 web，挂数据管理 hub（文件管理域）。协议零改动——GET/PUT /sources/custom-groups 早在 ADR-0033 落地，web SDK 已生成但此前无消费方。
- **件**：hub「词表维护」入口卡（紧随「来源词表」；两张表各自独立：本页=匹配引擎检索词层出处组/停用词，来源词表=/authors/source-vocabulary 上传挂靠建议词）→ 子页 `/app/maintenance/files/vocabulary-edit`：停用词卡 + 自定义出处组卡（组行互斥展开 → 变体写法/角色检索表小节 → 角色展开别名小节，词条计数副行/空态注记/文案逐字对齐 App）+ 单输入框弹窗（rune 字数回显 100 封顶、空白禁确定）+ 整体保存（PUT 恒显式 groups+stopWords）+ 规则说明四条（内置组数取 133 权威口径——App 规则行的 130 系固化前陈旧文案，已记档待 App 顺手勘误）。
- **架构（铁律 7/ADR-0008）**：编辑状态机 = `lib/vocabulary-edit.ts` 纯函数族（新增/改名 trim 拒空白、越界静默 no-op、可省列表空表归一 undefined、载荷恒带 stopWords；14 例单测=App VocabularyEditViewModelTest 对位）；数据 = `hooks/use-source-groups`（TanStack Query；保存成功同步写缓存+失效重拉=App「静默回读」web 等价，规避重拉间隙闪旧值）；组件零业务规则；限额常量 VOCABULARY_LIMITS 注释与 openapi.yaml 双同步责任（rune 计数口径，增补面字符按 1 计）。
- **离开防线**：useBlocker 拦 SPA 内一切导航（返回钮/浏览器返回同口径，App BackHandler 对位；createBrowserRouter 数据路由下可用）+ beforeunload 拦标签页关闭；文案逐字取 App VocabularyDiscardDialog。
- **验证**：tsc/vitest 262（+14）/oxlint 新文件 0 警告（基线 20 警告不变）/knip 零新孤儿（前后 findings 逐字一致）/build 成功（PWA 61→62 entries）；浏览器实测：真实词表 6 组回显、组/角色两级展开、别名小节、弹窗录入置脏（保存钮激活）、离开拦截「继续编辑/放弃离开」双通道、放弃后零 PUT（编辑不落库）；视觉截图核验布局（卡片/两端对齐行/两级缩进 20px/分隔线/无溢出重叠）。
- **文档**：GUIDE_API.md（custom-groups 行补 Web 端消费）、adr/0035（Web 移植补记）、HANDOVER.md（§4 Web 条目）、CHANGELOG.md（本笔）。

## refactor(all): 全仓重构优化批——四端性能/代码卫生/手造轮子排查，零功能零 UI 变化（2026-10-04 第四百六十五笔）

执行 AI：GLM-5.3（主代理；三执行代理+四审查代理多开协作）

- **背景与方法**：用户令「对整个项目执行重构优化，不破坏功能和 UI；排查 AI 自研小方案走主流，判断维护债/升级风险后执行；最后多开代理全量审查+本地构建验证」。执行序=四路只读调研（server/web/android/desktop+文档一致性）→ 主代理逐项拍板（收益/风险/主流性）→ 三执行代理分端实施 → 四对抗审查代理复核 → P1 返工修复 → 四端全量本地构建。**DOMAIN_RULES 公式、协议面（api/ 零改动）、可见 UI 三冻结。**
- **服务端性能五件**：①DSN 补 `synchronous(NORMAL)`（WAL+NORMAL 官方推荐组合，写吞吐主升点——每事务免 fsync，断电最多丢最近若干已提交事务不损库，注释记档取舍，busy_timeout 同款实证调参先例）；②扫描富化单资产事务化（ingestNormalFile/recomputeNormalEnrichment 原 2+N 条语句逐条 autocommit → runAssetTx 单事务，原子性只强不弱；scanner_test 接线 SetConn 覆盖事务分支——对抗审查抓出的测试缺口）；③备份导入五段段级事务化（对齐 import_replay 先例；importTxtFragments 不包=其内部自开事务嵌套即死锁）；④GET /libraries 与指标刷新 N+1 合并（新 sqlc 查询 CountAllLibrariesMedia 一次往返装配全部库，死查询 CountLibraryMedia 移除，sqlc v1.31.1 再生零漂移）；⑤facets 六聚合 errgroup 并行（装配后置纯函数化）。
- **服务端卫生与轮子排查**：sourcematcher exactAlias 线性扫 → rebuild 预构建 O(1) 查表（取舍序=canonical 字典序最小，与旧首中语义逐字节等价）；UNIQUE/FK 错误文本匹配 → errors.As 判 sqlite.Error Code（官方常量表，1555 主键边界记档）；扫描态魔法串 → gen 常量；多值筛选解析三处复制 → 共享 helper（history↔assets_filters，facets 形状不同不并）；matcher.go/scanner.go 超线理由注释补档。**手造轮子裁定**：detachedGroup（缩略图单飞）**保留**——其测试直接观测内部回收时序（2026-10-03 CI -race 实撞 flake 的观测手段），换 singleflight 三条路（删字段破测试/留死字段/重建簿记）全部违背约束，收益不敌 CI flake 风险，论证记档；WorkerPool/SniffMagic/authLimiter 为已记档合理自研维持。
- **Web 性能与卫生**：上传进度 patchProgress 守卫（percent 不变跳过写——XHR 每秒数十次 onprogress 不再触发整个工作台重渲染，终态路径不经守卫）；MinePage HistCardItem 补 memo+稳定回调（对齐 MediaCard 2026-09-20 既有口径）；批量执行胶水四处 → lib/batch-run.ts 单源；LoadingHint 加 className/children，9 处「加载中…」手抄收敛；package.json 卫生（shadcn CLI/tailwindcss/@tailwindcss/vite/vite-plugin-pwa/tw-animate-css/@fontsource 六构建期包归 devDependencies——index.css 四 @import 包同口径统一，lock 同步重生零版本漂移）；CollectionPage 过期注释修正、--danger 别名口径统一。**对抗审查纠错**：调研称 LoginGate eslint-disable 注释惰性——实测 oxlint 已启用 exhaustive-deps 且该注释是必需的，正确回退不改。
- **Android 性能与卫生**：分组 O(n) 计算包 remember ×4 补齐（收藏/历史/作者合集/搜索四页漏修点——相册页修复D-1 的同型问题，四维胶囊点选不再全列表重算重扁平化）；HomeScreen 三页 sections remember（筛选草稿点选不再触发三网格全量重扁平化）；**Compose 稳定性配置**（stabilityConfigurationFiles + compose_compiler_config.conf 33 类逐类核验收录，core:model 值对象 List 字段不再判 unstable——UiState.copy 不再拖垮订阅子树 skippable；单数 DSL 在 Kotlin 2.4.20 已废弃按错误拦截，首次构建失败后换 NIA 同款复数形态）；僵尸代码清除（LocalMediaRepository 族 3 文件+DI 绑定=2026-09-29 相册选择器退役漏删孤儿，QimengLoadingState+专属 token=被骨架屏取代）；LifecycleEventEffect 官方件替换 ×4（双事件+onDispose 必达的 2 处有据保留）；AUTHOR_SUGGEST_DEBOUNCE_MS 单源化。**复议维持原判**：分页状态机六 VM 平行（362-367 记档裁决）、pinch 列数/dispatchPill 重复（横跨模块收益为负）、VideoStage 拆分（播放器回归风险）——均记档不动。
- **文档对齐批（对抗审查核过的事实修正）**：GUIDE_API 补记漏收的 POST /auth/logout 与 GET /thumbnails/progress、端点计数 73→74（校正实数非新增）；ARCHITECTURE §5.1 编排层现状补记（orchestration 包未建、localsync_runner 落 httpapi 属 ADR-0019 决策 2 既成违背，增迁口径=新流程按 authorattach 先例落业务包）+ ADR-0019/0020 补记同口径；ARCHITECTURE §2 图补 7 包注记；desktop/README 目录树补全（titlebar.js/ui 图标）+「跨仓契约：Web TopBar ↔ titlebar.js」节（防 TopBar 重构静默打断桌面壳窗口操作）+ 已知限制三条记档；GUIDE_UI 三处引用标注「在旧项目仓库内」。
- **验证**（全绿）：Go build/vet/test 全量+golangci-lint 0 issue+gofmt 干净；Web tsc/oxlint(基线同 warnings)/vitest 248/knip 零孤儿/build 成功；Android assembleDebug+testDebugUnitTest+:core:model:test；desktop cargo test 17+build。存量 CRLF 行尾伪 diff（custom.go 等）随 .gitattributes eol=lf 清偿。文档：HANDOVER.md（本笔+记档级更新）。
- **记档级新增**（详见 HANDOVER §5）：browse.sql viewCount/playCount 排序相关子查询预分组化待办（需 EXPLAIN 计划锁另批）、Android strings.xml 硬编码中文维持现状（单语应用、无 l10n 规划，非项目口径违例）。

## fix(app): 夜间玻璃件液态感微调——按日间逻辑同构定稿（2026-10-04 第四百六十四笔）

执行 AI：GLM-5.3-Flash（主代理）

- **背景**：用户连续三轮反馈夜间胶囊观感（「夜间ui的胶囊不太行」→「我说的是首页上方的胶囊，相册上方的胶囊，要和液态玻璃一样」→「底色能做到透明吗」→「还是不太行，不需要为选取的时候有阴影啥的，你看看日间的逻辑」）。三轮试错定位：夜间胶囊的问题不在单点参数，而在**夜间没有按日间的结构逻辑走**——日间选中态成立的前提是「胶囊先有一层实体玻璃体（白 76%），选中染色 0.20 只是染在体上的一层色」；批二的「compact 近全透体 0.18+夜间加重染色 0.32」让染色直接落在透明体上，视觉上就是一块灰色阴影（用户反馈「选取的时候有阴影啥的」的根因）。
- **批三定稿（回归日间同构，撤回夜间特调）**：①撤回 fillCompact 小件透明档（GlassColors 数据类与 GlassSurface 分派还原两档结构，compact 与普通档共用玻璃体，与日间一致）；②撤回夜间选中染色 0.32 分档（QimengSegPill 回归统一 QIMENG_GLASS_TINT_ALPHA=0.20，深浅主题同一染色档染在玻璃体上）；③保留批一的夜间提档（体 0.80→0.66 稍透、高光纱 0.14→0.20、受光边 0.32→0.42、镜面池 0.22→0.26——夜间 compact 靠这几笔光立玻璃感）与 BackdropGlassPanel 夜间 scrim 0.62→0.30（真采样胶囊透出采样内容，浅色保持 0.62）。
- **批四定稿（用户反馈「非选区为什么还有立体光影」）**：compact 小件档改**扁平玻璃**——drawGlass 只画 体+染色 两笔即返回，光效四笔（内影/镜面池/高光纱/受光边）全部不参与。依据=日间可见效果对齐：高光纱与受光边在日间白底上本就不可见（日间未选中胶囊实为平面），夜间白线落黑底才显出立体描边；至此小胶囊=「体+选中染色」的平面玻璃，与日间观感结构完全一致。大面板（搜索胶囊/卡片/坞）光效保留。
- **落点**：core/ui/glass/GlassColors.kt、core/ui/glass/GlassSurface.kt、core/ui/glass/BackdropGlassPanel.kt、core/ui/component/QimengSegPill.kt。视觉参数微调零行为变化。

## feat(app): App 词表维护（本机词表直接编辑）与词表合并同步（2026-10-04 第四百六十三笔）

执行 AI：GLM-5.3-Flash（主代理）

- **背景**：ADR-0034 上线当天用户三段拍板定稿（ADR-0035）——①「没有手动维护功能啊，只允许走 NAS 同步过来？？双向的手机也可以同步到 NAS，以及输入出处的维护手机也可以，加上去」；②「添加框太复古了，统一设计元素」；③「直接走备份那边一起吧，这样明确一点。词表和其他的不一样，只会多加不会删除，所以两边合并是增量不会出奇怪的问题」。**服务端/openapi/SDK 零改动**：GET/PUT /sources/custom-groups 既有两端点已同时承载读写，合并同步纯客户端编排。
- **词表合并同步（取代 0034 覆盖式下发，语义定稿）**：备份页新增「词表合并同步」卡（与「同步浏览数据」并排=数据流转集中一页，用户拍板「直接走备份那边一起」），一键触发、无方向选择、无两段式确认防线（合并无损即无需防线）——远端 GET + 本机 GET → VocabularyMerger 并集合并（core:data 纯函数+单测锁定：折叠键=trim+忽略大小写；出处组按折叠键并集〔远端在前、本机独有按原序追加；同键组并入=规范名取先出现方写法、变体并集、角色按折叠键并集+别名并集〕；变体/别名去重含「不与规范名自身重复」〔GET 回读的 canonical 自并入形态合并滤除、PUT 后服务端重新补上〕；停用词并集；空表回 null）→ 合并结果显式 PUT 回两端，两端收敛同一并集。结果提示带增量数字（合并后组数/停用词数+远端补入 N 组·本机补入 M 组）。词表只增不删故合并无损；任一端 PUT 失败两端短暂不一致，合并幂等重跑即收敛。 VocabularySyncScreen 子页/词表同步 hub 行/路由随入口迁移退役。
- **词表维护子页**：数据管理 hub 新增「词表维护」入口行 → 编辑子页。无门禁（编辑对象恒为本机内嵌库，不涉远端；远程登录态内嵌服务端按需拉起、收尾停回）。进页 GET 全量 → 内存编辑（组改名/移除、变体增删、角色增删/改名、别名增删、停用词增删，全部纯内存列表操作 + dirty 置位，越界静默 no-op）→ 保存按钮整体 PUT（恒显式 groups+stopWords）→ 成功后静默回读抹平服务端规范化差异。输入按协议容量投影封顶（VocabularyLimits：组 256/变体 64/角色 128/别名 32/停用词 256/单串 100，标注与 openapi.yaml 双同步责任）；新增/改名 trim 拒空白；有未保存修改时返回走放弃确认（系统返回 BackHandler 同口径）。**输入面统一 QimengCapsuleTextField**（用户反馈「添加框太复古」定稿：不再用 M3 OutlinedTextField 描边框，胶囊软底+placeholder+字数回显，全仓输入面统一语言）；**弹窗容器显式落 surface（卡片面）**（用户反馈「夜间胶囊不太行」根因修复：M3 AlertDialog 默认容器 surfaceContainerHigh 与本主题夜间胶囊底 surfaceVariant 同档合并 #2E2E2E，胶囊在夜间弹窗里完全隐形——落 surface 恢复「页面底 1A<弹窗 24<胶囊 2E」抬升梯度，浅色同构弹窗纯白=卡片语言）。UI 文件拆分（Screen/GroupsCard/Dialogs）守住渲染逻辑警戒线。
- **本机通道抽取共享（代码卫生约束：相似逻辑第 2 次出现即抽单源）**：0034 的「拉起内嵌服务端→端口就绪→dev-login→接线→用完停回」编排抽 LocalVocabularyChannel（core:data/embedded），词表同步/维护两仓库共用；401 陷阱防线整体继承（@LocalDirectClient 零拦截器客户端、实例级 accessTokenProvider、本地 token 与全局 NAS 会话互不接触）；错误分类不另立平行枚举（维护复用 VocabularySyncError 本地两支）。
- **落点**：core:data（VocabularyMerger 纯函数新件、LocalVocabularyChannel 新件、VocabularyEditRepository 接口+Impl、VocabularySyncRepository 接口+Impl 合并化、DataModule @Binds）；feature:manage（VocabularyEditViewModel/Screen/GroupsCard/Dialogs、VocabularySyncCard 新件、VocabularySyncViewModel 一键化、VocabularySyncScreen 退役、DataManageScreen 词表维护行+备份行副文案改述）；app（QimengNavHost VOCABULARY_EDIT 路由新增、VOCABULARY_SYNC 退役）。测试：VocabularyMergerTest（并集保序/同键并入/角色别名并集/停用词折叠去重/canonical 自并入滤除/空表归一）、VocabularySyncViewModelTest（一键同步/忙态防重/五支错误映射/失败重试）、VocabularyEditViewModelTest（9 用例），全部绿；:app:assembleDebug 编译通过。UI 零业务规则零直调 API（铁律 7）。
- **文档**：adr/0035（新，含被否决方案记档：0034 单向覆盖与本批一度实施的方向化双向覆盖均被用户「只增不删合并增量」拍板取代）、adr/INDEX.md、GUIDE_API.md（custom-groups 消费面改述）、HANDOVER.md、CHANGELOG.md（本条）。

## feat(app): App 词表同步——远程登录态一键把 NAS/电脑端词表单向下发覆盖本机内嵌库（2026-10-04 第四百六十二笔）

执行 AI：GLM-5.3-Flash（执行子代理）

- **背景**：ADR-0033 补记三记档的结构缺口转正实施——词表数据层（kv）各部署各自一份，内嵌端点受一次性密钥保护外部不可写，手机本地库拿不到电脑端词条；固化基线（第四百六十一笔）是一次性止血，跨端一致的常态正解即本笔「词表同步」（用户拍板「为什么不和电脑一致」，ADR-0034）。**服务端/openapi/SDK 零改动**：只消费既有 GET/PUT /sources/custom-groups 两端点与既有 SDK 方法。
- **交互**（两段式）：数据管理 hub 新增「词表同步」入口行 → 子页进页自动预览（远端 GET → 拉起内嵌服务端 ensureStartedIfLocalMode+warmup → 本地 dev-login → 本地 GET → 对照：远端 N 组/本机 M 组/本机独有 K 组〔canonical 不在远端，trim+忽略大小写〕，独有>0 警示色提示同步后丢失）→ 确认按钮执行本地 PUT，body **恒显式携带 groups+stopWords**（停用词远端无追加层传空数组=清空，不用协议缺省的「保持现值」语义，保证本机=远端）；PUT 成功即完成（服务端自动后台全库重算），结果提示下发组数/停用词数。本机模式进入即提示「单机模式无权威词表源，请先登录 NAS/电脑端」，门禁在 repository。
- **401 登出陷阱防线（本笔核心）**：全局 OkHttp 挂 AuthInterceptor，401 会 clearToken+广播登出把用户 NAS 会话踢回登录页——对 127.0.0.1:18430 的 dev-login/GET/PUT 全部走新 `@LocalDirectClient` 独立客户端（NetworkModule 全新 builder 零拦截器，刻意不用 newBuilder 派生）；本地 Bearer 经 SDK 实例级 accessTokenProvider 注入，与全局 ServerConfig token 两套会话互不接触。内嵌服务端按需拉起、操作收尾停回（恢复壳层「主键非本机预设=停服」不变量，换端守卫防误停）。
- **落点**：core:network NetworkModule（@LocalDirectClient 客户端/DefaultApi/AuthApi 三装配，SdkAuthApi 密钥内存槽现取同款姿势）；core:data VocabularySyncRepository 接口+Impl（编排单点，业务错误四分类 NoAuthoritativeSource/RemoteFetchFailed/LocalServerUnavailable/LocalApplyFailed）+ DataModule @Binds；feature:manage VocabularySyncViewModel（UiState 防重复提交+错误横幅+结果提示三件套，BackupViewModel 范式）/VocabularySyncScreen/DataManageScreen 入口行；app QimengNavHost 路由接线。UI 零业务规则零直调 API（铁律 7）；feature:manage 对 core:data/core:network 依赖既有，零 build.gradle 改动。
- **验证**：按用户拍板本笔零本地构建/测试命令，全部静态核对（SDK 方法签名/生成物 ApiClient accessTokenProvider 实例级语义/import/DI/路由/文档笔数），编译与行为验证走 GitHub 云端 CI。
- **已知限制记档（ADR-0034）**：覆盖式下发本机独有词条会丢（预览明示+确认防线，拍板语义）；dev-login 在本机 auth_sessions 留会话行（随过期策略消亡，无实害）；本机通道只认内嵌预设地址（自定义端口 Termux 形态 A 不在语义内）。
- **文档**：ADR-0034 新建 + INDEX 登记；GUIDE_API custom-groups 段补 App 消费一句；HANDOVER §4 Android 与文头最后更新同步。

## feat(server): 停用词层——内容备注词（触手/白丝类）不进角色胶囊（2026-10-03 第四百五十九笔）

执行 AI：GLM-5.3（主代理；引擎/接线/测试由执行子代理完成）

- **背景**：用户拍板备注口径（二次修正）：序号之后无论加什么都=纯备注（`安燃 2 婚纱` 的婚纱只留文件名显示）；角色位上的内容描述词（「原创角色 触手」「无限暖暖 白丝」）同备注对待——不是角色名，不进胶囊。此类词与真角色名同形，位置规则不可区分，需词表。用户同时评估并否决了「物理改名统一文件名」方案（两个特例本来就没有角色名可改，其余文件引擎已正确，改名纯风险零收益）与「单独停用词端点」方案（复用现有端点，见 ADR-0033 补记二）。
- **协议**：CustomSourceGroups 新增可选 `stopWords` 字段（maxItems 256/单串 100，make sdk 三端再生成 + sdk.lock）；语义与 groups 不同——**PUT 缺省=保持现值、显式空数组=清空回内置基线**（防 AI 忘带字段误清空）；GET 恒返回用户层（空数组=无）。存储 kv_settings 新键 `custom_stop_words`。
- **引擎**（sourcematcher 新文件 stop_words.go + matcher.go）：停用词集合 = 内置冻结基线（触手/白丝/婚纱/多角色/多角色酒吧）∪ 用户追加层（UpdateStopWords，updateMu 串行化、重建索引+清缓存，风格对齐两层既有自定义）；**只作用于兜底提取层**（extractTokens 命中即跳过、不终止收集），别名表层完全不受影响。scanner 构造期 loadStopWords 装载（降级容忍同 loadCustomSources）+ UpdateStopWords 包装；httpapi PUT/GET 接线 + wire 适配器 + 两处测试夹具补齐。
- **测试**：sourcematcher stop_words_test.go 4 组（基线跳过+对照组/追加层与清空/跳过不终止/别名表层不受影响）；httpapi 端到端 4 组（trim+引擎记录+kv 一致/缺省保持/空数组清空/乱码 400）。`go vet ./...` + `go test ./... -count=1` 全绿，既有断言零改动。
- **实测**（8420 重建重启 + PUT 触发重算）：触手/白丝/多角色/多角色酒吧桶全部消失（290 桶，-4），对应文件角色行为空、文件名原样显示可搜索；天使触手→天使（57）、司霆惊蛰 触手→司霆惊蛰（10）、艾什 1 婚纱→艾什、D.Mon/风间准/风间飞鸟/杰玛/战斗修女/阿拉尼雅 全部不变；KDA/Lawa/Melody 留桶（未拍板为备注词，随时可经 stopWords 追加）。App 内嵌后端双 ABI 已重建入 jniLibs（8421 运行实例未动）。
- **排查记档**：验证时曾见「司霆惊蛰 触手」行短暂为空——系重算未完成时抢先查库，非回归（重算完成后 10/10 正确）；后台重算完成前查库要留时序余量。

## feat(server): 首批审定词条固化进内置基线（130→133）——手机本地/桌面/NAS 与电脑端引擎级一致（2026-10-04 第四百六十一笔）

执行 AI：GLM-5.3（主代理；数据固化与测试由执行子代理完成）

- **背景**：用户切 App 单机模式实测后拍板「为什么不和电脑一致」——词表数据层（kv）各部署各自一份，内嵌形态端点受一次性密钥保护外部不可写，手机本地库拿不到电脑端词条（D.Mon 改名、怪物猎人/战锤40k 新组）。经评估：一次性固化进内置基线是当前最小正确解（改 App 加同步功能更大，留待提案；不构建的通道已被安全设计全部关闭）。
- **固化内容**（source_groups_data.go）：守望先锋+D.Mon(Dmon)；铁拳·风间飞鸟 扩别名 风间明日香 + 新角色 风间准(Jun Kazama)；最终幻想+阿拉尼雅(Aranea/Aranea Highwind)；新组 怪物猎人(Monster Hunter/MHWilds/MHW)·杰玛(Gemma)、战锤40k(Warhammer 40K 等)·战斗修女(Battle Sisters)、初音未来(Hatsune Miku/Miku)。评估退回 MH 两字母变体（任何 mh 开头文件名误命中，如 MHA）。头注释计数与记档同步，custom.go 过时计数顺带修正。
- **引擎版本 1→2**：自愈重算使各部署在启动时自动用新基线重算存量（手机本地库无鉴权自动生效——正是第四百六十笔机制的第一次实战收益）。PC 端 kv 同名组合并结果与基线一致（零行为变化）。
- **测试**：新增 builtin_words_test.go（8 组纯内置匹配用例 + canonical 全表唯一性断言）；受固化影响的 3 处既有断言按新语义修正（custom_test/fallback_test 的 Dmon 兜底用例改用未收录名 Reinhardt/索杰恩 保留判别力，并新增清空词层后 Dmon→D.Mon 的基线锁定断言；表完整性断言 130→133）。vet + 全量 test 全绿。
- **部署**：PC 8420 重建重启（自愈 v2 重算落标记）；App release 重装+拉起，内嵌服务端自愈 v2 使本地库与电脑一致。文档：DOMAIN_RULES §4 冻结口径修订、ADR-0033 补记三。

## feat(server): 引擎版本自愈重算——引擎升级后存量富化开箱自愈，不依赖端点与鉴权（2026-10-04 第四百六十笔）

执行 AI：GLM-5.3（主代理；实现与测试由执行子代理完成）

- **背景**：用户切到 App 单机（本地）模式实测，暴露结构缺口——引擎升级（兜底提取/停用词）后存量资产的富化不会自然刷新，此前唯一触发重算的路径是词表端点 PUT，而内嵌形态的 dev 登录受一次性共享密钥保护（2026-09-30 批A 防同机越权，密钥只在 App 内存）、外部不可调；release 包不可调试，DB 亦不可直读，存量数据无法传导。
- **方案**（DOMAIN_RULES §4 新增「引擎版本自愈重算」）：服务端启动时比对 kv 标记 `enrichment_engine_version` 与代码常量 `scanner.EnrichmentEngineVersion`（第 1 代=兜底提取+停用词层；匹配语义再变才 +1）；标记落后（含无标记存量部署）→ 后台对全部常规库 RecomputeEnrichment 一次并落新标记，持平则零开销跳过；幂等，单库失败 warn 继续（下版启动再补）。cmd 启动 goroutine 接线，不阻塞监听。
- **测试**：scanner selfheal_test.go 三用例（无标记触发重算+落标记/持平跳过/cos 库不报错），`go vet` + `go test ./... -count=1` 全绿。
- **部署验证**：PC 8420 重启即自愈（stored=0 → 两常规库重算 3.4s → 标记=1，日志留痕）；App release 重装+拉起，内嵌服务端（libqimeng.so 00:25 重建）启动自愈本地库，18430 监听、App 与服务端通信正常。
- **记档（App 单机模式词表边界）**：自愈只传导**引擎规则**（兜底提取/停用词基线/数字终止）；词表数据层（D.Mon 改名、怪物猎人/战锤40k 新组）仍存于各部署自己的 kv——内嵌形态因一次性密钥外部不可写，如需同步属 App 端「词表同步」功能提案（未做，待用户拍板）。手机本地模式下冷门角色将以原名进胶囊（如 Dmon 而非 D.Mon），出处未收录组（怪物猎人等）无出处无角色——与设计一致。

## feat(server): 角色匹配第二层——命名规约兜底提取 + 词表入口乱码防线（2026-10-03 第四百五十八笔）

执行 AI：GLM-5.3（主代理）

- **背景**：ADR-0033 词表接口落地后复盘，用户提出真实诉求——「方便维护 + 准确，降低角色表依赖」：收录一半是热门角色、一半是新/冷门角色，但文件命名高度统一（`出处  角色名 序号.扩展名`）。逐角色补词条永远追不上收录速度。
- **兜底提取层**（sourcematcher matcher.go，DOMAIN_RULES §4 新增「命名规约兜底提取」）：别名表命中非空时结果完全以表为准（既有资产零回归，含子串命中场景测试锁定）；表零命中时在原串（保留大小写与空格——折叠域无法分词）剥离开头出处后按规约提取——空格分词、词内 +/& 拆分、裸 x 作分隔词；自左向右收集到首个纯数字/括号序号词终止（序号后是「小长篇」「婚纱」类描述词）；普通词命中本出处别名表取 canonical（改名归一），否则按原词入库；数字开头后跟 ASCII 字母的词（"2B" 形）仅当表认识才保留（"8K"/"1080p" 画质词防线）。"+" 多出处文件按分段提取且只吃本出处段与无主段（`恶魔战士+铁拳8 莫妮卡` 不得产出「铁拳8」角色，既有测试锁定）。自定义裸名出处同样享受兜底（`我的分区_某角色.jpg` 自动得「某角色」胶囊）。
- **事故加固①——词表入口乱码 400**：非 UTF-8 客户端载荷（如 GBK 终端里的 curl）经 JSON 解码器把坏字节静默替换成 U+FFFD 后落库 = 永不命中的死词条，且整体替换语义会覆盖掉此前的正确词表——这正是本日「PUT 后重算不生效」排查的实际根因（引擎与端点本身无 bug，干净 UTF-8 载荷全链路实测即通）。sources/custom 与 sources/custom-groups 两入口对含替换符词条显式 400 INVALID_PARAM。
- **事故加固②——启动脚本跳编译陷阱**：_server-common.cmd nobrowser 模式原「exe 存在即跳过编译」改为两模式统一总是构建（秒级 go build 缓存换正确性）——排查曾因旧二进制持续服务、新加日志永不出现而原地打转。
- **实测**（8420 重建重启 + 词表端点触发全库重算）：此前 27 个缺角色资产全部就位——D.Mon（词条改名）/杰玛/战斗修女/阿拉尼雅/风间飞鸟（词条命中）+ 触手 11/白丝 3/KDA 2/多角色/Lawa+Melody（兜底原名提取，大小写保留）；`守望先锋.jpg`、`生化危机 1.jpg` 等文件名里本无角色的正确留空；facets 角色桶 294 个。已知取舍记档：同文件表部分命中时其余未认识名不再兜底（零回归优先，如 `风间飞鸟+风间准` 的风间准需补一条词条）。
- **测试**：sourcematcher fallback_test.go 新增 7 组用例（单名提取/序号终止/画质词防线/分隔符与无主段/部分命中即止/表零回归/未知出处不提取）；custom_test.go ③ 与 scanner enrich_test.go 自定义裸名断言随新语义更新。`go vet ./...` + `go test ./...` 全绿。
- **App 内嵌后端**：`make app-embedded` 双 ABI（arm64 23.9MB / x86_64 25.2MB）重建入 jniLibs（含兜底层与乱码防线；jniLibs 按既有口径不入 git，8421 运行实例未动，下次 App 装机生效）。
- **同批入库**：ADR-0033 词表接口本体的协议/引擎/存储/接线/文档（openapi + make sdk 三端 + sdk.lock、sourcematcher custom.go、httpapi source_groups.go、wire 适配器、GUIDE_API/adr 记档）——同会话前段完成，与本笔合并提交。

## feat(server): 检索词表维护接口（ADR-0033）+ 本批角色修复——自定义出处组运行期增补词条，内置 130 组冻结为基线（2026-10-03 第四百五十七笔）

执行 AI：GLM-5.3-Flash（主代理）

- **背景**：用户库持续收录「单一角色多文件」内容，新角色/冷门资源不断出现（守望先锋 Dmon、怪物猎人杰玛等），内置检索表冻结在代码里跟不上——文件入库后角色恒空白，App 四维筛选的角色胶囊行永远看不到。用户拍板做「检索词表 + 方便维护的接口（让 AI 补词条）」。性能前提澄清：匹配只发生在扫描/富化时（索引构造期建好 + 8192 缓存），检索表大小不进请求热路径，本决策动机是维护性。
- **协议先行**：openapi 新增 `GET/PUT /api/v1/sources/custom-groups` + CustomSourceGroups/CustomSourceGroup/CustomSourceCharacter 三 schema（整体替换语义、maxItems/maxLength 上限、204、复用 /sources/custom 的「保存→引擎生效→后台全常规库重算存量」闭环描述）；`make sdk` 三端再生成 + api/sdk.lock 同 commit（245→251 条目）。
- **引擎合并层**（sourcematcher 新文件 custom.go，纯函数 MergeGroups）：内置表 → 裸名层 → 出处组层按 canonical 深拷贝合并——同名=并入（变体追加、同名角色扩别名）、新名=追加组、canonical 自并入自身变体/别名兜底；顺带修复旧 rebuild 的 map 覆盖行为（裸名与内置组同名时角色表不再丢失）；Matcher 增出处组层字段 + updateMu 串行化两层自定义更新（防并发 PUT 交错重建）。MergeGroups 不写穿入参（base 是包级内置表）由测试锁定。
- **内置表只修 bug 不扩表**：删除第一后裔组复合变体 `"第一后裔 邦尼"`——长度降序前缀命中即停的 strip 规则下它把 `第一后裔 邦尼 1.mp4` 的角色名整体吃掉（剩余串只剩 "1"），角色匹配落空；全表审计确认这是唯一吞角色的复合变体；回归测试锁定。
- **存储与接线**：kv_settings 新键 `custom_source_groups`（常量单源 authoring 包，与裸名 `custom_sources` 语义隔离）；scanner 构造期 loadCustomGroups 装载（损坏降级空集同 loadCustomSources）+ Scanner.UpdateCustomGroups 同步方法；httpapi 新文件 source_groups.go（normalize：trim/去空/去重/重复 canonical 取首个/超限 400 INVALID_PARAM，上限与 openapi maxItems 双写同步）+ server.go Scanner 接口与 noScanner 占位 + cmd/qimeng/wire.go 适配器；测试环境 testEnv 暴露 fscan 断言「引擎收到的 = 持久化的」。
- **测试**：sourcematcher custom_test.go（合并语义/不写穿基线/并入内置组+新组+清空/裸名同名保角色/邦尼回归）；httpapi source_groups_test.go（E2E：初始空→混合载荷规范化→GET 回读=persisted=fakeScanner 收到→清空→组数超限 400→单串超长 400）。`go vet ./...` + `go test ./... -count=1` 全绿；`make sdk` 三端生成+锁同 commit。
- **落地种子词表**（重建重启后经新端点 PUT）：守望先锋+Dmon｜铁拳·风间飞鸟+别名风间明日香｜最终幻想+阿拉尼雅(Aranea)｜怪物猎人(新组)+杰玛(Gemma)｜战锤40k(新组)+战斗修女(Battle Sisters)｜初音未来(新组)+初音未来(miku)；邦尼靠内置修复+重算自动出现。
- **记档**：云曦天泪/VAM 赵灵儿经用户拍板不加；原神 Melody x Lawa 为原创角色不动词表（想进「原创角色」行需改文件名前缀）。


## perf(app): 掉帧全链路定位与构建税根修——材质无罪，debug 构建税是主因；日常装机切换 release 通道（2026-10-03 第四百五十六笔）

执行 AI：GLM-5.3（主代理）

- **背景**：用户反馈液态玻璃下滚动卡顿，追问「不是材质的问题吧，全链路优化、不得降低材质」。四档对照测量（液态 10.4–12.6%/磨砂 9.6%/纯色 7.4%/经典 7.1%，真实使用与匀速滑两口径）初判采样层有责，一度默认档改 SOLID（455 笔）。
- **方法升级**：改用 `input swipe` 脚本化滑动（可复现、免协调）+ 查明设备为 **120Hz 屏（帧预算 8.3ms，一切开销被放大）**。脚本匀速滑下 debug 构建+液态仅 3.95%——真实使用的 10-12% 主要来自高速甩动/图片加载风暴叠加 debug 构建 CPU 税，玻璃采样管线的稳态成本被高估。
- **根修**：启用既有 release 构建类型（本就配 debug 签名+R8+baseline profile AOT，零配置改动）——**同脚本液态掉帧 3.95%→1.45%，p50 10→8ms**，材质全保留。默认材质档撤回 LIQUID（455 笔的 SOLID 改判依据为 debug 数据，予以修正；SOLID/CLASSIC 留作低端/省电手动档）。首页顶行采样随材质门控（455 笔）保留——纯色/经典档仍真零捕获，防御性正收益。
- **口径记档**：日常装机/性能评估一律 release 通道（app-release.apk，debug 签名本地实测档）；debug 包仅开发调试用。库版本核查：backdrop 2.0.1 已是最新，无升级红利。
- **验证**：真机 release+液态装机，脚本化滑动 1.45% 掉帧/757 帧；用户手感复验待确认。

## feat(app): 动效系统 v2 全新重制——spring 转场退役，M3 emphasized 曲线族驱动（2026-10-03 第四百五十一笔）

执行 AI：GLM-5.3（主代理）

- **背景**：用户目验 450 批转场「过于生硬」，拍板深度调研后全新重制、不要草草了事。
- **研究结论**（M3 tokens-specs / material-components-android theming·Motion / 移动端转场时长研究）：①页面级转场质感区 300–400ms（>500 拖沓、<200 生硬）；②进场曲线 emphasized decelerate = (0.05,0.7,0.1,1)（首帧即速度→长缓收尾，iOS 手感来源），退场 emphasized accelerate = (0.3,0,0.8,0.15)，成对即 M3 共享轴标准编排；③进出不对称（进 350/退 200：来者为主去者让位）；④v1 生硬三根因=低刚度 spring 起步迟滞 + 0.9 阻尼过冲晃动 + 140ms 线性快闪淡入；⑤spring 只配触摸微交互（按压回弹=触觉语言），空间导航用曲线（位移回弹读作「页面漂」）。
- **落地**：`QimengMotion` v2 整体重写——覆盖页=30% 屏滑入（350ms 减速曲线）+被覆盖页 8% 让位（200ms 加速曲线），pop 镜像；详情页=0.94 缩放走来（320ms）；Tab 间 snap 排除项不变；API 签名零改动（NavHost 分派不动）；常量全具名并注释研究出处。
- **验证**：本地 `:app:assembleDebug` 全绿；真机拔线中，装机目验待回连。

## feat(app): 动效统一批——NavHost 全路由转场接入 QimengMotion 单源（2026-10-03 第四百五十笔）

执行 AI：GLM-5.3（主代理）

- **背景**：用户拍板补交互动效过渡并统一设计语言（参照 Material 3 共享轴/Motion 规范）。排查发现 `QimengMotion`（ADR-0031 动效规范单源：覆盖页滑入/详情缩放/Tab 排除）在 dock-only 减法批被压成全路由 snap，规范空挂。
- **落地**：NavHost 四个转场 lambda 按路由分派——Tab 间 snap（常驻层防叠影拍板，规范明确排除项）；进详情=0.92 缩放「走来」；进其余子页（服务器/数据管理/作者/上传/搜索等）=1/4 屏 spring 滑入推开内容，返回反向；`QimengMotion` 补 `overlayExit`/`overlayPopEnter`（M3 共享轴 outgoing 侧 1/8 屏）。按压反馈（spring 缩放）+ 转场自此同属 glass/Motion.kt 单源。
- **验证**：本地 `:app:assembleDebug` 全绿；真机已拔线，装机目验待设备回连。

## feat(app): 液态感强化批——玻璃材料升级（渐变体/镜面高光池/底部内影/染色层）+ 全仓胶囊玻璃单源化 + 我的页行卡玻璃化与「主体色彩」子页（2026-10-03 第四百四十九笔）

执行 AI：GLM-5.3（主代理）

- **背景**：用户反馈 448 批后「其他胶囊液态感不明显」，并拍板整批 UI 质感优化：胶囊推广全 App（含详情页）、设置页「外观模式+底栏材质」合并为「主体色彩」点进去的子页、「我的」行卡与背景对比不足无立体感；方向=借鉴大厂液态玻璃语言、不大改结构。
- **玻璃材料升级（单源）**：`GlassColors` 新增 `specular`（顶部镜面高光池色）/`innerShade`（底部内影色）双 token；`GlassSurface` 绘制从三笔升为六笔（体→染色→底部内影→镜面高光池→高光纱→受光描边），新增 `tintOverlay` 参数承载「有色玻璃」（染在体上、光影笔照常叠加）。所有 GlassSurface 消费方（顶行搜索胶囊/玻璃钮族）即刻受益。
- **全仓胶囊玻璃单源化**：`QimengSegPill`（全仓单枚胶囊唯一渲染源——首页三胶囊/相册芯片/搜索词丸/详情值丸/收藏历史值区块/设置统计档位全部经它收敛）从 M3 FilterChip 换 GlassSurface 玻璃体：未选=素玻璃+onSurface 文案，选中=主色染色玻璃（tintOverlay 0.92）+onPrimary SemiBold；几何逐项保留（32dp 高/胶囊圆角/12sp）；按下 spring 缩放与玻璃族同语言；语义对齐 FilterChip（Role.Checkbox+selected）。详情页值胶囊经此自动玻璃化。
- **我的页**：EntryRow 纯白 surface 卡 → GlassCard（用户反馈根因：纯白卡浮 #FAFAFA 近乎不可辨；几何 16dp 圆角/72dp 节奏不变，点击附按压缩放）；外观模式+底栏材质两张选择卡从主列表撤出，合并为单入口行「主体色彩」→ `ThemeColorPage` 行内子页（BackHandler 系统返回、rememberSaveable 跨重建），主列表减两卡。
- **质感对齐返工（同批内）**：用户目验「胶囊与坞质感差距大」——流内胶囊无 backdrop 采样（下方无滚过内容，采样无意义），观感对齐改走「坞级磨砂配方」材料要素：GlassColors 遮盖提至 0.85/0.86 档（对标坞 FROSTED scrim 0.62 以上再加静态补偿）+ specular 提档；GlassIconButton 与我的页 EntryRow 加 2dp 投影（悬浮景深与坞同语言；密集小胶囊不投影防叠印）；GlassCard 透传 elevation 参数。
- **验证**：本地 `:app:assembleDebug` 全绿；真机覆盖安装启动，效果待用户目验。

## feat(app): 玻璃语言首批推广——坞未选中档对比度修复 + 首页顶行/页头胶囊玻璃化（2026-10-03 第四百四十八笔）

执行 AI：GLM-5.3（主代理）

- **背景**：用户目验移植批通过（下沉已修），随拍两件：①浅色主题下玻璃坞未选中 tab 图案/文字发虚（首页浅背景上尤甚）；②把玻璃胶囊风格推广到其他胶囊元素。
- **坞对比度**：`DockNavItem` 未选中 tint `onSurfaceVariant`→`onSurface`（浅/深主题各自的最强文本色；选中态 primary + SemiBold + 点亮缩放维持层级区分）。
- **胶囊玻璃化**（配方沿 443 批 ac4c994e 适配移植，几何逐项零改动只换面材质；不用真 backdrop 采样——胶囊下方无滚过内容，443 批同口径）：首页顶行搜索胶囊 `Surface(surfaceVariant)`→`GlassSurface(pill)` + pressScale 按压反馈；首页筛选/列数两颗 40dp 胶囊钮本地自绘 `HomeTopIconButton` 退役→`:core:ui` 单源 `GlassIconButton`；`QimengTitleRow` 筛选钮激活态→`GlassIconButton`、未激活态透明底 + pressScale（U10-2b「不常亮」语义逐字保留）。
- **验证**：本地 `:app:assembleDebug` 全绿；真机覆盖安装冷启动，效果待用户目验（试验性推广，观感不合意可整批单 commit 回退）。

## fix(server): 缩略图单飞测试时序双解修复——detachedGroup「完成即回收」语义勘正锁定（2026-10-03 第四百四十八笔；与玻璃语言推广批重号——PR #18 已合入历史保留，时序在后）

执行 AI：GLM-5.3（主代理；445 批 CI -race 实撞触发）

- **背景**：审查修复批（445）二次 rebase 后 CI 首红：`TestDetachedGroup等待者取消不杀生成` 报「fn 总执行次数应为 1，实得 2」。排查=实现固有行为而非回归：fn 完成后后台 goroutine `close(done) → delete(terms)`，测试第三阶段「后来者直接取已完成结果」只在 do 抢到 delete 之前的锁时成立，另一半时序后来者重跑 fn——而同 key 合并测试的注释早已写明正确契约（「单飞是进行中合并语义，完成即忘却，迟到者走重新执行，真链路由缓存快路径兜住」）。
- **修复（测试与文档对齐真实契约，实现零改动）**：①取消测试第三阶段改为确定性断言——新增 waitTermsRecycled 白盒轮询等回收完成后，断言迟到者重新执行 fn（runs=2）且成功；②错误传播测试改名「错误传播与回收后迟到者重跑」——旧版第二断言在回收时序下空转通过（新 fn 返回 nil 恰好满足 err==nil 检查），一并勘正为真实重跑断言；③detached.go do() 文档补「去重只覆盖进行中，完成即回收（x/sync/singleflight 同款语义），真链路由 EnsureDetached 磁盘快路径兜底不重跑 ffmpeg」；测试头注同步。
- **验证**：gofmt/vet 零输出；TestDetachedGroup 全组 -count=30 压测通过（Windows 本机无 gcc 不跑 -race，-race 语义由 CI 锁定）。
- **涉及文档**：`docs/CHANGELOG.md`（本条）。

## feat(app): 悬浮玻璃坞移植批——回滚后 master（老 UI 基线）+ ui/app-dock-only 玻璃坞，顶部布局零改动（2026-10-03 第四百四十七笔）

执行 AI：GLM-5.3（主代理）

- **背景**：PR #16 全量玻璃化合入后用户回滚 master 至老 UI（3af64c87），目验老 UI 形态正确后拍板执行移植。移植源=评估线 ui/app-dock-only（d6f22a4c，「老 UI+纯玻璃坞」减法形态）；d10e096f 携带的 QimengTitleRow/Color/Type/主题大改等「顶部空白挤压 UI」疑似元凶文件全部不带。
- **移植方式**：`git diff 67e5db07 d6f22a4c -- android/` 全量补丁打到回滚后 master（其 android/ 树与 67e5db07 逐字节一致，补丁零冲突落地，19 文件）。内容=悬浮玻璃坞本体（FloatingTabDock）+ glass 件（GlassButtons/Colors/Surface/Motion/PressScale/QimengBackdrop）+ 坞材质偏好持久化链（UiAppearance/ClientPrefsRepositories/AppearanceViewModel/设置页材质切换档）+ 选中态透镜胶囊（Kyant0 LiquidBottomTabs 配方）+ 三页底部让位（Home/All/Stats 的 bottomContentPadding 走 TabDockDefaults 单源，内容从坞身后滚过）+ MainActivity 外观模式三档解析。NavHost 顶部 inset 范式逐像素对齐老语义（「双重留白清偿」注释链保留）——Home/All/Stats 三页 diff 逐行核查确认全部为底部让位，无任何顶部改动。
- **真机内嵌服务端修复（顺带）**：本地 jniLibs 缓存的 libqimeng.so 停在 10-01，早于 0016 迁移（10-02 dc79b00d），真机内嵌服务端启动秒退（「no migration found for version 16」），死亡通知的「端口被占——Termux 形态 A 在跑请先停」静态常见原因文案造成「形态 A 复现」误判；此前叠加占用者为极光探索线装机 media.qimeng.app.aurora 的内嵌服务端（已 force-stop + pm disable-user 冻结，pm enable 可恢复）。修复=以当前源码重新交叉编译 arm64 服务端（GOOS=android CGO_ENABLED=0）刷新主仓与移植 worktree 两处 jniLibs 缓存。遗留建议：死亡通知文案按形态 A 退役后口径改写（列为后续待办，未随本笔）。
- **下沉返工（同批内修复）**：移植首装真机复现「上方多一块空白、内容下沉」——根因=悬浮坞改造新增的内容层外 Box 自带 `padding+consume(topSidePadding)`，内层 NavHost 非 detail 分支与常驻层再各自消费同一份，Tab 屏顶部吃**双份状态栏留白**（d10e096f 与评估线同构同病，即主线时代「首页布局下坠」的真身，此前归嫌疑于 QimengTitleRow/Color/Type 属误判——本次移植不带这些文件仍复现，锁定嵌套双份 padding）。修复=外层 Box 摘除 padding/consume（backdrop 捕获保留，全屏采样不影响坞观感），让位范式回到老主线同构：仅 NavHost 非 detail 分支与常驻层消费，detail 沉浸式全屏不受影响。
- **验证**：本地 `:app:assembleDebug` + `:feature:settings:testDebugUnitTest` 全绿；真机覆盖安装启动后内嵌服务端存活（libqimeng.so 进程在册、server.log 无迁移错误、推荐流冷算正常出数）；下沉修复后冷启动复装待用户目验（重点：顶部无新增空白、坞悬浮正常）。

## docs: 云端构建强制要求废止——「夜间/无人值守执行纪律」整节删除、本地产物构建恢复（2026-10-03 第四百四十六笔）

执行 AI：GLM-5.3（主代理）

- **背景**：第四百三十七笔（96ddcc1d）今晨定案「禁本地产物构建、验证与产物一律云端 CI」（动机=本机高负载 WHEA 史 + CI 已有云端 APK 装配链）。同日用户验证 master UI 回滚时嫌云端装配链等待慢，拍板废止：速度优先，本地产物构建（Gradle assemble、Go 交叉编译、docker、前端打包）恢复，云端 CI 降为可选通道不再强制。
- **变更**：`AI_README_FIRST.md` 删除「夜间/无人值守执行纪律」整节（本地产物构建禁令、云端 CI 强制、轻量本地操作白名单、夜间设备操作禁令指针四条一并撤除），「最后更新」行同步。WHEA 史仍记档于 HANDOVER 不动；ADR-0031 设备纪律本体不动（仅撤本文件指针，其正文仍是有效决策）。
- **笔号说明**：本笔取 446——跨线笔号已用至 445（ui/app-dock-only d6f22a4c），master 本文件当前止于 440，取 446 避免玻璃坞线条目日后回迁 master 时撞号。

## fix(server/web/app/desktop): 全项目摸底审查修复批——安全面零 P0 通过记档 + 规范清偿 + CI 第六道门禁 + 仓库卫生脱敏（2026-10-03 第四百四十五笔）

执行 AI：GLM-5.3（主代理统筹+5 研究子代理分域审查+4 执行子代理修复+对抗审查子代理复核）

- **笔号说明**：第四百四十四笔已被未合并分支 ui/app-dock-only（311964ec）占用，为防撞号本批跳用 445。rebase 适配记档（本批产出期间 master 三次前进，两次人工合冲突）：①原基于玻璃坞时代 master（d10e096f）产出，revert 3af64c87 落地后重放（MediaRepository/QimengNavHost 按「revert 后基线+本批意图」手工合）；②447 玻璃坞移植批（4a66e533）落地后二次 rebase，FloatingTabDock 回归 master，其超线 KDoc 补回、超线注记恢复 7 文件口径。
- **背景**：用户授权全项目大摸底排查与修复（代码规范+实现安全+必要重构）。本批开工时生效的「夜间/无人值守执行纪律」（禁本地产物构建）在批次进行中被第四百四十六笔废止（速度优先），Android 改动先静态收口、后按新口径本地补验。
- **审查面与结论**：五路并行研究子代理分域（服务端安全/服务端规范/Web/Android/横切面基建）。**安全面零 P0**：路径穿越（NormalizeRelPath/PathWithinRoot 双闸全热点核查）、SQL 全参数化、上传四道校验三通道同函数、鉴权豁免清单与 openapi 一致、HMAC 签名消息定界、并发锁面、备份快照三道防线、migration 只加不改、sdk.lock 未过期、密钥零入库——各排除项已在审查报告逐条记档；问题集中在规范缺口与三处真隐患。
- **server（纯注释零逻辑）**：4 个超 100 行函数补「超线理由」注释（newAssetFilters/PostApiV1EventsView/PostApiV1AssetsAssetIdMove/PostApiV1TrashTrashIdRestore——参数归一直线展开/事务骨架等真实理由）+ 5 处忽略返回值补论证注释（localsync_runner 复核错误已内记 Warn、json.Marshal 入参定形无失败路径、authorattach 显示名可重建 ×2；另有 3 处 `w.Write` 经核已有合规注释免改）。
- **web**：非空断言 8 处收敛清零（守卫内取局部常量替代闭包 `x.id!`，全部可收敛点已收，仅剩 main.tsx React 入口惯例断言）；「暂无数据」空态 3 处逐字重复收敛为新组件 EmptyNote；upload-queue-store.ts（664 行）补超线注记；TopBar 窗口按钮 title 文案与桌面壳 titlebar.js 的隐式耦合补互指注释（titlebar.js 侧原有记档，本批补齐 Web 侧）。tsc + vitest 26 文件 248 测试全绿。
- **android（重头）**：①feature:manage 撤 `:sdk` 直依赖——BackupValidator 连测试整体下沉 core:data/backup（逻辑逐字搬迁），SDK 模型经 `ValidatedBackupPayload` 不透明句柄（internal）与 `LegacyImportSummary`/`TxtImportSummary` 域投影隔离，BackupRepository.exportJson()/importBackup() 与 AuthorRepository txt 族签名域类型化，AutoBackupRunner 改调 exportJson（原 BackupViewModelTest 的 format 断言弱化为 size，覆盖转移至 core:data 侧测试锁定）；②协议枚举单源——core:model 新建 MediaTypeKeys/SourceKeys（KDoc 记与 openapi.yaml 双写同步责任），4 消费点（StatsDetailViewModel/StatsDetailScreen/FourDimPills/SdkStatsRepositories）收口；③路由常量单源——TopLevelDestination 改引 HOME_ROUTE/ALBUM_ROUTE，KEY_ASSET_ID 收口 DetailRoutes；④调试残留 Log.d("QimengM42") 清除；⑤18430 端口文案改引 ServerAddress.LOCAL_MODE_PORT（feature:manage 补 core:network 依赖，login/settings 先例同款）；⑥EmbeddedServerService 轮询粒度常量化；⑦RankingEntry 自 core:data 下沉 core:model（7 文件 import 收口）；⑧7 个超线文件补 KDoc 理由（QimengNavHost/StatsDetailScreen 1026/VideoStage 898/HomeViewModel 863/DetailScreen 855/DetailViewModel 793/FloatingTabDock 737）；⑨StatsDetailScreen 图表色板 5 个字面量命名化（值逐字节不变）；⑩备份排除面补 Room 事件库 qimeng_events.db 三文件（行为隐私外带面关闭，口径见 SECURITY.md）。
- **desktop/CI（横切面清偿）**：ci.yml 新增第六道门禁 desktop job（windows-latest + rust stable + rust-cache + cargo test/build——图标已入库、frontendDist 为源内静态页故无前端构建前置）；全部 job 补 timeout-minutes（20~60）；web job 补 npm 缓存；Makefile sdk-lock 写锁前断言 Kotlin 生成物非空（消「无 Java 静默跳过→残锁本地绿 CI 红」）；ARCHITECTURE §10 门禁清单 5→6 同步。
- **docs/仓库卫生**：CHANGELOG-ARCHIVE 中被明文记档为「真实局域网 IP」的 192.0.2.2/.8 共 8 处脱敏为 192.168.1.x（文件头加脱敏注记，铁律 14 优先于「逐字存档」声明）；裁决记档——全库其余 192.168.1.x 命中均为 RFC1918 合成测试/示例值（尾号混用，无「此为真实地址」记档语境），不构成拓扑指纹，维持现状不扩大清洗；SECURITY.md 补 Termux 形态 A dev-login 为共享密钥缓解漏网路径的边界记档（缓解需 App 跨沙箱取密钥的产品决策，暂记边界待立项）+ deploy/termux/qimeng-start.sh 误导注释修正（127.0.0.1 绑定不等于同机 App 不可达）+ App 备份排除口径更新。
- **验证**：本地——go test 20 包全绿（含 httpapi 53s 全量集成）、gofmt/vet 零输出、web tsc+vitest 248 全绿（rebase 后复验）、Android 本地 Gradle 补验（纪律废止后按 446 笔新口径）testDebugUnitTest + :core:model:test 全绿（BUILD SUCCESSFUL，revert 重放后全模块编译+单测通过；首次运行遇 Windows 文件锁中断，停守护进程重跑即绿，非代码问题）、ci.yml js-yaml 校验、Makefile make -n 干跑、backup XML 良构校验；独立对抗审查子代理逐文件复核（含 Kotlin const 链/可见性/依赖方向/搬迁逐字等价比对）总评「可提交、零必修」。desktop job 首跑由 PR 云端 CI 实跑验证。
- **涉及文档**：`docs/CHANGELOG.md`（本条）、`docs/SECURITY.md`（Termux 形态A 边界 + 备份排除口径）、`docs/ARCHITECTURE.md` §10（门禁清单）、`docs/history/CHANGELOG-ARCHIVE.md`（脱敏注记）。

## feat(api/web/server): 备份调度参数编辑热生效 + Web 缩略图档位偏好——HANDOVER §5 建议立项④两件能力窄缺口落地（2026-10-03 第四百四十笔）

执行 AI：GLM-5.3-Flash（执行子代理）

- **背景（HANDOVER §5 建议立项④）**：备份调度参数此前「仅可看」（GET /backups 的 schedule 只读回显，调整走 yaml/env + 重启）；Web 缩略图档位（sm/md/lg）无用户选择面。两件都属窄缺口：协议事实已具备（size 查询参数=缓存键组成部分且不参与签名），只差编辑入口与接入面。
- **任务①协议先行（铁律 1）**：openapi.yaml 新增 `PUT /api/v1/backups/schedule`（全量提交三键 `{enabled, intervalHours, retention}`，与 BackupSchedule 同形；校验 1–8760 小时 / 1–365 份，越界 400 INVALID_PARAM 两步都不做——持久化与热生效合败不拆，无「看似生效、重启回退」半态），BackupSchedule 描述从「只读回显」改为「当前生效值」并补 minimum/maximum；make sdk 三端生成物再生 + api/sdk.lock 同 commit（245 条目）；GUIDE_API 备份热备行同步（路径 72→73）。
- **任务①服务端（热生效路线，调研后选定的原因）**：调度器原为 Manager.Start 固定 ticker + retention 装配期固定——但改造面收敛在 backup 包内部（新增 schedMu 锁域 + applyScheduleLocked 单点：停旧循环/起新循环/retention 即时改），持久化复用 kv_settings 既有机制（migrations/0003 文件头「后续设置项加一行键即可」惯例的直接兑现，键 backup_schedule，值 JSON 三键；不另造配置系统），**无需为热生效动任何无关架构**，故取热生效而非「持久化+重启生效」降级版。Manager 三入口：StartScheduling（装配根挂 ctx+初始三键）/ApplySchedule（PUT 持久化成功后调，interval 变更重置周期=从保存时刻重新起算、enabled=false 只停定时面、retention 即时作用下次轮转）/Schedule（GET 回显单点）；调度循环 Ticker→Timer（每轮完成重置，慢快照不背靠背，注释记档语义差异）。启动三级裁决在 main 装配根：kv 覆盖值（UI 保存）> env QIMENG_BACKUP_* > yaml > 默认，覆盖值损坏回落启动配置不锁死调度；httpapi GET 回显从 s.cfg 切到 Manager.Schedule()（消灭双真相）。
- **任务①Web（组件零业务规则，铁律 7）**：校验逻辑层 lib/backup.ts（BACKUP_SCHEDULE_BOUNDS 三处同值注释：openapi/服务端常量/此处）+ use-db-backups 增 useUpdateDbBackupSchedule；维护页 DbBackupCard 快照卡增编辑区（开关+间隔+保留三字段草稿态，保存即生效 toast 明示「无需重启·周期从保存时刻重新起算」），样式全走既有 settings-grid/settings-field/save-btn 类。
- **任务②（调研结论：纯客户端可解，零协议改动）**：档位语义=openapi GET /media/thumb/{assetId} 的 size 查询参数（sm/md/lg 三档=缓存键组成部分）；签名只锚定路径（assets_media_url.go 明文「size 属于缓存选择而非授权面」），客户端改写服务端下发 thumbUrl 的 size 参数即可按偏好取图，切换=换缓存键无混存，不发明新缓存层。逻辑层 lib/thumb-size.ts（auto/sm/lg 三档，auto=跟随服务端下发零变化；applyThumbSize 纯字符串函数；localStorage 键 qimeng_thumb_size 同 theme.ts 口径；不设显式 md 档——列表下发本就是 md、显式 md 会静默降详情大图，记档）；hooks/use-thumb-size.ts（useSyncExternalStore 单例 store）；接入六消费点单点化：MediaCard（全站列表卡单点）/ContentRankGrid/UpNextList/MinePage 历史卡/详情页海报帧/编辑页缩略图；设置页「界面」卡增档位 Pill（点击即存，交互同推荐偏好）。
- **测试**：server 新增 backup/schedule_test.go（解析合法/坏 JSON/越界七用例）+ manager_test.go 增热生效行为锁定（interval 缩短生效、enabled 停启冻结、retention 即时轮转收敛到 2 份、Schedule() 回读）+ httpapi backups_test.go 增 PUT 端点全链（200 回显+kv 落库可还原+Manager 即时可见+GET 切换+缺键/越界 400 且 kv 不变）；web 新增 lib/thumb-size.test.ts 六用例（auto/空 URL 原样、sm/lg 改写保留 exp/sig、无 size 追加、持久化+订阅通知、非法值归一）+ backup.test.ts 增调度校验三用例。
- **验证**：`go vet ./...` + `go test ./... -count=1` 全绿（19 包）；web `tsc -b` 零错误、oxlint 0 error（19 warning 全为 router/button/chart-shared 既有存量，不涉本批文件）、vitest 26 文件 247/247 全绿；`make sdk` 三端生成+锁 245 条目；夜间纪律合规（零产物构建/零模拟器操作），最终以 draft PR 云端 CI 为准。
- **涉及文档**：`api/openapi.yaml`、`api/sdk.lock`、`docs/GUIDE_API.md`、`docs/HANDOVER.md` §5（建议立项④勾销）、`docs/CHANGELOG.md`（本条）。

## feat(api/web/server): 数据溯源字段（origin/createdAtMillis）在资产详情响应与 Web 详情页可见——ADR-0032 保留的「详情页 UI 展示独立提案」落地（2026-10-03 第四百三十九笔）

执行 AI：GLM-5.3-Flash（执行子代理）

- **背景**：ADR-0032（第四百三十五笔）当时拍板「详情/列表响应模型不动，溯源读取走备份导出已全覆盖，详情页 UI 展示留待独立提案」——本批即该独立提案：资产详情响应透出关联行级溯源，Web 详情页作者/标签区呈现来历，跨端核对不再需要导备份。Android 端溯源 **UI 消费**本批不做（ui/app-redesign 分支未合并，避免冲突；SDK 已再生待其消费），留待后续批；但 SDK 类型变更引发的既有代码编译适配照做（协议先行第三步「按编译错误适配各端」，见下）。
- **协议先行（铁律 1）**：openapi.yaml 新增 `AssetDetailTag`/`AssetDetailAuthor` 两 schema（allOf 叠加 Tag/Author，**既有字段不动**），AssetDetail 的 `tags`/`authors` 条目改引之——增可空 `origin`（ADR-0032 受控词表 client/txt/import/local-sync/scanner/legacy，与备份导出 LegacyMediaTagRef/LegacyAuthorMediaRef.origin **同名同义**）+ 可空 `createdAtMillis`（关联成立毫秒）；null/缺省=不可考。详情条目专属视图：标签池 `GET /tags` 与作者列表 `GET /authors` 仍返回原 Tag/Author 不带溯源（溯源展示只属详情面）。`make sdk` 三端生成物再生 + api/sdk.lock 同 commit 更新（245 条目）。**不新增查询参数、不动既有字段**。
- **服务端（纯透传、口径零变化）**：browse.sql 的 ListAssetTagRefs/ListAssetAuthorRefs 两查询补带 `at.origin/at.created_at`、`aa.origin/aa.created_at`（sqlc v1.31.1 重新生成，仅 browse.sql.go 变化）；httpapi fetchAssetRefs 装配 `gen.AssetDetailTag/AssetDetailAuthor`，新增 `provenanceMillis` 单点归一不可考哨兵（asset_authors.created_at NULL / asset_tags.created_at epoch → 字段缺省，≤0 同待遇，绝不落 1970 纪元字面量——口径对齐 §10「≤0=缺省」）；upload.go 上传 201 响应同模型空集装配。origin 含 legacy 哨兵恒透传（不伪造词表值），是否展示由前端词表映射决定。DOMAIN_RULES §10 补「读取面」一句（纯读零裁决，补证/keep 语义不涉读取面），裁决语义零变化。
- **Web 详情页（UI 组件零业务规则，ADR-0008）**：新增逻辑层 `lib/provenance.ts`——origin→中文标签映射（客户端/TXT 导入/备份导入/本机同步/扫描器；legacy=不可考不产出）+ 注记组装「客户端 · 10-1 12:30」（时间复用 formatDateTime 口径），词表外值不显示（宁不可考不造假），完全不可考返回 null；组件只渲染其返回值。AssetTagRow 标签胶囊内缀小字、AuthorCard 作者名下次行小字（不可考不渲染任何标记），glass.css 增 `.prov-note`/`.author-main` 三行（颜色全走 --qm-text-muted token，字号沿用全站 12px 惯例）；改动最小化不重设计页面。
- **Android 编译适配（非功能，CI 首轮拦出）**：Kotlin 生成器对 allOf 产出独立 data class（不继承 Tag/Author），master 既有 `core:data SdkDetailMappers` 的 `toDetailTag(Tag)`/`toDetailAuthor(Author)` 对详情响应新条目类型 Inapplicable——两函数重定型为 `AssetDetailTag`/`AssetDetailAuthor`（全仓仅详情映射一个调用面，无第三调用方；`toTagChip(Tag)` 标签池端点不受影响）。溯源字段零消费，UI 留待 ui/app-redesign 合并后批次；本机 gradle 构建夜间禁做，编译验证以云端 CI 为准。
- **测试**：新增 `assets_detail_provenance_test.go`（详情响应装配面，与 store/provenance_test.go 写入面互补）——三情形锁定：client 盖章行可见（origin+毫秒）、legacy 行 origin=legacy 原样透传、时间戳可空（NULL/epoch 哨兵→字段缺省）；新增 `web/src/lib/provenance.test.ts` 六用例（组合/仅词表/仅时间/词表外不显示/legacy 与 null 同待遇/≤0 哨兵）。
- **已知边界（记档）**：① 角色关联不入详情溯源面——asset_characters 行 origin 恒 scanner、created_at=重算时刻（携带零信息量），且 AssetSummary 的 `characters` 是 string[] 共享列表面，改条目形状即破坏列表端点契约与既有客户端，维持现状；② upload 201 的 AssetDetail 空集不带溯源（新资产零关联，无信息可带）；③ browse.sql 文件头注新增「注释必须纯 ASCII」约束——sqlc v1.31.1 对多字节注释会错乱查询边界（实测：中文注释致下一查询报 ":one without RETURNING"），中文理由写在 Go 装配层注释。
- **验证**：`go vet ./...` + `go test ./... -count=1` 全绿（server 全包，含新详情溯源测试）；web `tsc -b` 零错误、oxlint 零错误（既有 19 条 warning 不涉本文件）、vitest 222/222 全绿（含新增 6 用例）；`make sdk` 三端生成+锁 245 条目；夜间纪律合规（零产物构建/零模拟器操作，禁触项未触碰），最终以本 draft PR 云端 CI 为准。
- **涉及文档**：`api/openapi.yaml`、`api/sdk.lock`、`docs/DOMAIN_RULES.md` §10（读取面）、`docs/GUIDE_API.md`、`docs/CHANGELOG.md`（本条）。
## feat(web): 上传队列刷新持久化——未终态条目入 IndexedDB，刷新恢复「需重新选择文件」态+重选同名文件按既有协议续传（2026-10-03 第四百三十八笔）

执行 AI：GLM-5.3-Flash（执行子代理）

- **背景（HANDOVER §5 待办#2）**：大文件上传中页面刷新造成整条丢失已实际发生一次（2026-10-01 拆 store 批记档「页面刷新仍会丢队列——另立项」，本笔即该立项落地）。File 句柄不跨页面存活是平台客观限制，落地方案取「最小诚实版」：恢复的是队列条目与断点事实，不伪造可续传假象。
- **持久化（原生 IndexedDB，零新依赖）**：选型决策序第 1 级平台机制命中即停（既有先例=打点账本 qimeng_event_ledger），不引 idb-keyval；新库 `qimeng_upload_queue`（store queue_items，keyPath=id，version 递增只加不改）。持久化字段=条目 id/落库名/原文件名/大小/目标库与目录/挂靠快照/分片会话 id/已传分片偏移/入队与推进时间戳；**只持久化未终态条目**（排队/在传），终态（done/failed/attach-failed/canceled）即删记录，移除/清除已完成兜底同删，不留孤儿。存储失败静默降级（同打点账本口径：只丢「刷新恢复」增强能力，不阻断传输主链路）。建库/单事务 promise 化抽共享件 `web/src/lib/idb.ts`（第 2 个 IndexedDB 消费方出现，代码卫生约束 6，ledger-instance 同批收敛复用）。
- **恢复与续传（严格既有协议，零新端点零新参数，openapi 不动）**：工作台挂载即 restorePersisted，记录还原为新增 `needs-file` 态——UI 明确标记「需重新选择文件」（重选/移除行内动作+提示文案），因为现有拾取链路是 input[type=file]+DataTransfer（非 File System Access API 句柄），按行为目标不做句柄恢复。用户重选文件后经纯函数 decideResume 判定：名称（编辑过基名按 originalName）或大小不符=mismatch 留在原态并给文案（防把别的文件传进原目标位）；命中则补句柄转 queued 重新入泵——分片条目（≥16MB）带持久化会话 id 走既有 GET 探测→PATCH→complete 续传（探测 404 会话已失效时通道内既有 rebuild 路径自动新建从 0；服务端 offset 唯一真相源，持久化偏移只做恢复展示与续传资格判定），直传条目与无会话分片从头重传。分片通道（lib/upload-chunked）为此增三个可选回调参数：sessionId（续传既有会话）/onSession（会话建立回写）/onOffset（权威断点回写持久化），传输状态机零改动。
- **架构边界（ADR-0008）**：纯核心 `lib/upload-queue-persist.ts`（decideResume/toRestoredEntry/存储端口，零 IO 可单测）+ 浏览器装配 `lib/upload-queue-persist-instance.ts`（IndexedDB 实现+内存降级+单例，模式同 ledger-instance）+ store 编排（upload-queue-store）；UploadQueueTable/UploadWorkbench 只渲染与转发回调，零业务规则零 API 直调；样式复用既有类与 token，零新增样式。
- **行为锁定（vitest 216→232，+16 用例）**：①持久化写读往返（存储端口契约 put/getAll 覆盖/删）；②刷新恢复态判定（needs-file 映射、断点推导进度、越界夹取、幂等去重、恢复 id 序号让位防冲突）；③同名续传决策（同/异名、同/异大小、originalName 基准、分片会话续传 GET→PATCH→complete 全链、直传从头重传全链）；④完成清理（入队即写、终态/取消/清除已完成/移除四路删除，清除已完成不替用户丢 needs-file 条目）。
- **验证**：`tsc -b` 零错误；oxlint 0 error（19 warning 全为既有存量）；vitest 24 文件 232 用例全绿；knip 门禁六类零孤儿（全量报告 25/10/2 与基线逐项相同，新文件零未用导出）；本批遵守夜间纪律零产物构建，最终以 draft PR 云端 CI 为准。
- **涉及文档**：`docs/CHANGELOG.md`（本条）、`docs/HANDOVER.md` §5（待办#2 勾销记档）。


## docs: 夜间/无人值守执行纪律入册——禁本地产物构建、验证与产物一律云端 CI（2026-10-03 第四百三十七笔）

执行 AI：GLM-5.3（主代理）

- **背景（用户定案）**：2026-10-02 夜为预览装机做过一次本地 Gradle 构建，用户随后拍板：夜间（无人值守批次）不允许本地构建，一律走云端。动因=本机高负载不稳（WHEA 断电史，HANDOVER 记档）+ CI 已具备 android job 云端 APK 装配链（app-embedded-arm64：oapi-codegen → fetch-ffmpeg → assembleDebug → assert），云端取件路径本就齐备。
- **内容**：AI_README_FIRST 新增「夜间/无人值守执行纪律」节：禁本地产物构建（APK/AAB/Go 交叉编译/docker/前端产物包），允许单测/类型检查/lint/`make sdk` 轻量前置验证；验证与产物获取一律 GitHub Actions（gh 盯门禁/artifact 取件）；夜间禁模拟器与真机操作，装机验收留到用户在场时段。
- **涉及文档**：`AI_README_FIRST.md`（本条记档）。


## fix(web): 「我的」页历史/收藏 pane 首拉加载态——服务端慢查询期间整 pane 空白无反馈修复（2026-10-02 第四百三十六笔）

执行 AI：GLM-5.3-Flash（执行子代理）

- **背景（web 端「浏览历史不显示」排查，第四百三十二笔同症状的 web 侧收尾）**：用户报新版 Web（Aurora Glass）浏览历史页无内容。取证链：PC 预览栈 8420 服务端 /api/v1/history 协议与数据全正常（641 条 11 页游标分页全通），但**单页实测 9.1s、第 3 页 47.8s**——该预览实例跑的是当天 16:00 构建的旧二进制，早于同日 17:25/17:31 两个服务端历史查询修复（第四百三十二笔 0015 覆盖索引 + 第四百三十三笔主流化改写）；用 master HEAD 重建同数据根重启后实测 3–48ms/页。**web 前端数据链路零缺陷**（headless 渲染取证：请求正常发出、响应到达后 60 卡正常渲染、零控制台异常），「没有内容」的观感=首拉 9s+ 期间 pane 内空无一物、无任何加载反馈。
- **修复（本 commit 代码改动，最小化）**：MinePage 历史/收藏两 pane 首拉期间补加载提示——原空态判定 `items.length === 0 && !isFetching` 在「无数据且在拉取」时渲染 null（整 pane 空白），改判 `isLoading`（无数据且在拉取，TanStack v5 语义）优先渲染共享件 LoadingHint（`grid-empty` 加载行唯一来源，卫生约束 6 复用既有共享件）；缓存命中后切 tab 瞬时显示，isLoading 恒 false 不闪提示。UI 组件零业务规则（ADR-0008），数据链路零改动；收藏 pane 同型观感同批修（两 pane 结构对称）。
- **验证**：headless（CDP）目验——点击「浏览历史」tab 即现「加载中…」，落定后「今天 · 9 / 更早 · 51」60 卡正常渲染；收藏 pane「收藏 · 0」与服务端 totalMatched=0 一致（非回归）；`tsc -b` 零错误、oxlint 零错误（既有 19 条 warning 不涉本文件）、vitest 216 用例全绿。
- **运维侧事实（非本 commit 代码，记档防复发）**：预览栈 8420 已用 master HEAD 二进制同数据根重启并实测恢复毫秒级；数据零改动（迁移仅服务端启动自管）。凡「服务端修完性能、预览实例没跟着换二进制」都会复演本观感，预览实例二进制需与服务端修复同批更新。
- **涉及文档**：`docs/CHANGELOG.md`（本条）。


## feat(server): 关联/作者类记录溯源体系——migration 0016 补 created_at+origin 双列、备份载荷透传来历、导入补证+首写优先行级裁决（2026-10-02 第四百三十五笔）

执行 AI：GLM-5.3-Flash（执行子代理）

- **背景（ADR-0032）**：2026-10-02 三方数据取证——asset_authors/asset_tags 等关联表无时间戳无来源，PC 多出的 152 条关联「无时间戳可考」无法判定真伪，跨端合并只能靠猜；同批取证另见 view_events 三代回放叠加（1×/3×/6× 每代键不同，09-18 内容键幂等拦不住跨代并存）——该问题不在本批范围（事件流已有 session_id/client_event_id 行级溯源，根因是键空间不同属导入回放专项，ADR-0032 边界记档）。用户拍板：给关联类记录建立专业溯源体系。
- **migration 0016（只加不改，ADR-0011）**：asset_authors/asset_characters 补 `created_at TEXT`（可空）+`origin TEXT NOT NULL DEFAULT 'legacy'`；asset_tags/authors 补 `origin`（created_at 已有不动：0004 的 epoch=既有「不可考→最旧」哨兵）。存量行 origin 默认 'legacy'（如实记录不可考）、新列时间 NULL=不可考——**禁止伪造时间**；有意不建索引（无新查询族，ADR-0011 修订5 计划横向复查不适用）。时间戳格式沿用库内统一口径 RFC3339 固定毫秒文本（store.FormatTimestamp 单源；非整数毫秒——一致性优先，ADR 记档）。
- **origin 受控词表**（常量单源 `server/internal/store/provenance.go`，TEXT 存储+Go 校验、不设 SQL CHECK=扩枚举零迁移）：`client`=HTTP 客户端端点（PUT tags/PUT authors/上传挂靠；web/app 共用端点服务端不做 UA 猜测，设备级区分留待协议级客户端标识）、`txt`=TXT 片段统一重建手动导入、`import`=备份导入、`local-sync`=本机同步自动导入（ADR-0030）、`scanner`=扫描器派生（角色行/COS 作者）、`legacy`=0016 回填哨兵（写入端永不主动写）。
- **写入路径全覆盖盖章**：httpapi/tags.go（替换式 PUT）、authorattach/attach.go（上传挂靠 Apply）、authorattach/edit.go（PUT /assets/{id}/authors swap）→ client；httpapi/authors.go（importTxt 全链——签名增 origin 参数贯穿 rebuildFromAllSources/rebuildAll/upsertMergedAuthors/insertLinks，格式 C/rebuild/删除重建三路径同盖）、removeTxtSource → txt；localsync_runner.go → local-sync；httpapi/import.go（ImportUpsertAuthor/ImportAddAssetAuthor/ImportAddAssetTag+备份 TXT 片段段）→ import；scanner/enrich.go（ingest/refresh/COS 三路径 6 处）→ scanner。asset_characters 的 created_at 语义=当前重算发生时刻（先删后插派生数据，重算即刷新，如实记档）。
- **合并裁决语义升级（先改 DOMAIN_RULES §10「关联溯源与行级合并裁决」再改代码，铁律 3）**：备份载荷 authors/authorMediaRefs/mediaTagRefs 三段增可空 `origin`（authorMediaRefs 另带 `createdAtMillis`）——**导出透传库内原始来历**（A 端手工创建的关联到 B 端仍是 client，来历不因搬运失真）；导入端词表校验（非法/缺省兜底 import；时间缺省回退导入时刻=「入账时刻」真实事件）+ **补证 heal**（既有行不可考〔legacy/NULL/epoch〕被透传值升级——不可考不等于永久不可知）+ **首写优先 keep**（可考行绝不覆盖，重复导入幂等）。标签组**集级**「谁新听谁」裁决维持 0012 tag_set_updated_at 口径不变，升级的是行级溯源。
- **协议先行**：openapi.yaml 三 schema 增字段 → `make sdk` 三端再生成 + api/sdk.lock 同 commit 更新；详情/列表响应模型**不动**（溯源读取走 GET /export/qimeng-backup 已全覆盖，详情页 UI 展示留待独立提案）；web/app 对新字段零消费零改动。
- **测试**：store/provenance_test.go 四组新测试（词表校验/业务盖章+作者行补证/存量行哨兵/导入三分支裁决）；store_test.go TestMigrateDownThenUp 链首插 0016 验证步（六列 down+0015 对象保留+既有列不动）；export_test.go 回环测试增溯源透传断言。**顺带修复一个测试脆弱性**：import_test.go authorRefAsset 裸 `LIMIT 1`（无 ORDER BY）依赖 planner 扫描选择——0016 加列使表行变大、planner 转向覆盖索引扫描、返回序翻转——补 `ORDER BY rowid` 锁定「首个插入」确定语义（ADR-0011 修订5「加列也重排计划」在测试面的微缩重演；全库生产查询均带 ORDER BY，grep 复核零同类暴露）。
- **对抗审查返工（同笔记档，笔号自第四百三十四笔让位于治理批总记）**：① import.go nowOrMillis 补 ≤0 边界——备份携带 createdAtMillis=0/负值与缺省同义回退导入时刻，杜绝 1970 纪元字面量与 asset_tags.created_at epoch「不可考」哨兵混淆成「可考章+纪元时间」自相矛盾行（违反本笔写进 DOMAIN_RULES §10 / ADR-0032 的裁决①），新增 TestImport_nonPositiveMillisFallbacksToNow 锁定；② DOMAIN_RULES §10 补明「TXT 重建/tags PUT 等替换式路径先删后插，行章随重建通道刷新、不参与补证与 keep」，防「可考行绝不覆盖」被误读为适用于重建路径。
- **边缘知悉项（审查确认，不改）**：备份含 TXT 片段时片段覆盖式重建会把该作者关联章盖为 import、压过 authorMediaRefs 段的透传来历——现状与 ADR-0032 自洽（重建=通道重挂语义），留待后续提案。
- **验证**：`go vet ./...` + `go test ./... -count=1` 全绿（server 全包）；sqlc@v1.31.1 重新生成（4 查询文件）；`make sdk` 三端生成物+锁 241 条目；禁触项（gradle/npm 生产构建、模拟器、手机）未触碰，最终以本 draft PR 云端 CI 为准。
- **涉及文档**：`docs/CHANGELOG.md`（本条）、`docs/adr/0032`（新）、`docs/adr/INDEX.md`、`docs/DOMAIN_RULES.md` §10、`docs/GUIDE_API.md`。



## chore(governance): 手搓治理方案全量落地——选型铁律入协作规则 + CI 三道横切门禁（产物完整性/孤儿依赖/版本穿越回放）+ 自研点改造（2026-10-02 第四百三十四笔）

执行 AI：GLM-5.3-Flash（执行子代理）

- **背景**：元排查定案根因 =「规则不对称」（引依赖程序成本高、手写零成本）导致系统性手搓，用户拍板全量落地。本批 = 规则文档 + 三道 CI 门禁（第四道查询计划锁已于 1703304 落地）+ P2 自研点改造，按类分 commit（规则/两道 CI/测试/重构/记档文档）。
- **规则文档（决策序入法）**：`AI_README_FIRST.md` 新增「选型约束」节（与「代码卫生约束」同级同风格）——五级决策序（平台机制 > 标准库 > golang.org/x 官方扩展 > 零传递依赖小库 > 自研）、自研四问记档（业界方案是什么/为什么不用〔可证伪理由〕/复查条件/退役触发；轻量件头注、重大件 ADR）、依赖分级通道（大框架/运行时重依赖=ADR+用户拍板不变；**运行时依赖**=x/ 官方扩展与零传递依赖小库走**轻量通道**：头注或 ADR 简记+CHANGELOG 同 commit，无需拍板；**开发/检查工具链条款**=devDependency/CLI 允许传递依赖但绝不进运行时产物，同走轻量通道记档；**Android 运行时库不适用轻量通道**=维持 ADR-0014 白名单+用户拍板惯例〔Vico/Coil 先例〕；不豁免先读官方文档与协议/安全红线）；**反向禁令**明文化「禁止对依赖树/标准库/平台已有的成熟能力手写实现」；`AGENTS.md`「警戒线」节加索引行；「禁止行为」引依赖条目措辞对称化（保留原禁令+补反向句）。
- **CI 门禁①产物完整性（android job）**：内嵌形态可装机产物上 CI——`make app-embedded-arm64` 等价链（GOOS=android arm64 交叉编译 + jniLibs 装配 + ffmpeg 预构建下载，与 Makefile 目标双向注释互指）；`deploy/embedded/fetch-ffmpeg-arm64.sh` 为 `.ps1` 的同锁 bash 孪生（commit/SHA-256 与 README 逐字一致；供应链同步注记升级为四处：.ps1/.sh/README/发布说明）；新增 `deploy/embedded/assert-apk-embedded.sh` 断言 APK 内 `lib/arm64-v8a/{libqimeng,libffmpeg_cli,libffprobe_cli}.so` 齐全（unzip -l 文本断言，缺失 `::error::` fail）；artifact 命名 `app-debug-apk-embedded`（retention 7 天，验装机用途）。取舍：CI 只装配 arm64 链（x86_64 需 NDK 不进 runner），以 debug 变体验证打包语义（release 同 jniLibs 路径）；断言脚本经正反例治具实测（治具侧踩过 PowerShell Compress-Archive 反斜杠条目陷阱，脚本按 ZIP 规范正斜杠匹配——真实 APK 由 AGP 生成不受影响）。
- **CI 门禁②孤儿依赖（web job）**：knip（devDependency，按轻量通道**开发/检查工具链条款**记档——允许传递依赖、绝不进运行时产物；非零传递依赖口径）+ `npx knip --include dependencies,devDependencies,unlisted,binaries,unresolved,files` 六类零孤儿为过；必须在 openapi-ts 生成后跑（`@/api/generated` 不入库，生成前检全量误报 Unresolved，与 tsc 同一前置约束）；`web/knip.ts` 头注记档口径——openapi-ts.config.ts 列入口（CLI 消费件非 import 可达），零 ignoreDependencies 豁免；「未用导出/重复导出」35 项存量属代码卫生面**不入门禁拦截口径**（本地 `npx knip` 全量可见，清偿另行批次）。
- **CI 门禁③版本穿越回放（server）**：`import_traversal_test.go` 两用例——①旧代库态（内容键诞生前形态：事件 `client_event_id` 恒 NULL、批次锚 `legacy_import_batch` 已写、时间戳按 §10 确定性〔正午偏移+逐秒错开〕复刻）→ 新代码同批次重导：批次锚快速路径整体跳过、事件计数守恒（5/5）、无键行不被触碰、锚不改写；②新代码内容键路径三代链（T1→T2→T3 同载荷）：每代零新增（内容键唯一索引拦重）、dwell 秒数取首写值、内容键覆盖 5/5——「不三代叠加」。**已知边界记档（不拦截）**：旧代无键行**换新批次**重导同内容会重放（唯一索引拦不住 NULL 键行；§10「换批次只增量补写」隐含前代已走内容键路径）——同批次场景有批次锚兜底，真实 M6 迁移链（2026-09-20）在内容键落地（2026-09-18）之后执行不受影响；**已知边界已随本笔审查返工批记入 DOMAIN_RULES §10「版本穿越已知边界」**（风险接受，待数据清洗移除存量无键行后复评），本批只锁定现状不扩语义。
- **P2 改造① recommend_cache 手写单飞退役改 `golang.org/x/sync/singleflight`**（x/sync 已是直接依赖，零 go.mod 变化；轻量通道四问记档入结构体头注）：业界方案=x/sync（决策序第 3 级），替代 2026-09-18 手写 inflight map + 席位 goroutine 簿记；**为什么仍要包装**——上游 panic 语义与本缓存契约不兼容（v0.23.0 doCall 实证：DoChan 路径 panic 兜底是 `go panic(e)`+`select{}` 常驻=裸崩进程+泄漏 goroutine，Do 路径等待方跟随 panic），故 `safeCompute` 在进入 Group 前就地转译，Group 恒收正常返回；复查条件=上游提供 panic 可选转译官方面；退役触发=包装层成为死代码。**panic 契约改写记档**：2026-09-18「席位方 re-panic 交 net/http recover」契约退役 → panic 就地转译错误 + slog 带堆栈（日志职责收编、handler 走正常 500 而非连接中断），等待方仍拿转译错误不阻塞；并发/panic/预热/键契约由既有测试锁定，断言零修改（TestRecommendCacheSingleflight / PanicInComputeReleasesWaiters / PrewarmKeyMatchesAppFirstScreen 等全绿）。单飞键 `sfKeyString` 逐字段 %q 转义（禁位置化拼接——mediaType 来自查询参数，含分隔符可碰撞）。
- **P2 改造② ADR-0028 修订**：增补「分片通道三端绕行点清单」修订记录——web `upload-chunked.ts` XHR 直连（hey-api 生成 client 无 abort/signal、全局 401/403 拦截器把 create 403 UPLOAD_DISABLED 误广播为鉴权失效）、Android `OkHttpUploadSessionClient`（@UploadClient 直连：content:// 无文件路径双倍 IO 不可接受 / 生成端丢弃 4xx 响应体 / 409 权威 offset 须自解析）、生成器面背景（hey-api/oapi-codegen 请求面无取消信号与流式载荷钩子=1/2 两条的共同上游）；绕行点定位为「选型约束」反向禁令的**记档例外面**，唯一记账处在本清单，新增绕行须同批追加。`adr/INDEX.md` 状态列同步。
- **P2 改造③ browse/facets 手抄同型面清点记档**（`HANDOVER.md` §5「记档级」）：sqlc v1.31.1 解析器 4 条限制（browse.sql 文件头）迫使同型谓词手抄——browse.sql `ListAssetsFilteredDesc/Asc/CountAssetsFiltered` 三联体（同 WHERE 收敛家族三份、排序方向烘焙）+ facets.sql 六聚合查询「排除自身维度+应用其余维度」谓词家族（favorite/history 子集 EXISTS 12 处）；退役条件=sqlc 修复解析限制或 CTE 预聚合同款主流化改写（第四百三十三笔先例），改前必跑 EXPLAIN 计划锁（ADR-0011 修订第 5/6 条；子集探测已被 history_plan_test.go 锁定）。
- **验证**：`go vet` / `go test ./...` 全绿（`-race` 本机 Windows 需 cgo 不可跑，以 CI Linux 同款 `-race` 为准）；web knip 门禁 / `tsc --noEmit` / vitest 216/216 全绿（package-lock 差异=knip 新增+同版本重排，逐项核过）；fetch 脚本真下载 SHA-256 与 README 逐字一致 + skip 路径实测；断言脚本正反例实测（正=exit 0 三件套齐全，反=exit 1 缺件 `::error::`）；CI YAML js-yaml 解析通过；**协议零改动**（openapi.yaml / sdk.lock 无涉）。
- **对抗审查返工批（同日，全部文档/注释级、代码零返工）**：①计数勘误——facets.sql 子集 EXISTS 探测实测 12 处（原记 13 处混入注释行与聚合），HANDOVER §5 与本条目更正；②孪生注记同步——`fetch-ffmpeg-arm64.sh` 头注 THREE→FOUR places（.ps1/README 已升四处口径，.sh 自指遗漏）；③口径勘误——knip 门禁 include 实为六类（dependencies/devDependencies/unlisted/binaries/unresolved/files），ci.yml/knip.ts/本条目"五类"更正；④轻量通道边界定案（用户拍板）——运行时依赖维持零传递依赖严格口径，新增**开发/检查工具链条款**（devDependency/CLI 允许传递依赖、绝不进运行时产物，knip 为先例），**Android 运行时库不适用轻量通道**（ADR-0014 白名单+拍板惯例），`AI_README_FIRST.md`「选型约束」节与本条目 knip 归路表述同步；⑤**DOMAIN_RULES §10 增补「版本穿越已知边界」记档**——旧代（2026-09-18 内容键幂等落地前）备份换 exportedAtMillis 批次重导会叠加 viewing 统计（唯一索引拦不住无键行），已知风险接受、待数据清洗移除存量无键行后复评，`DOMAIN_RULES.md` 头部更新链同步。
- **涉及文档**：`AI_README_FIRST.md`、`AGENTS.md`、`deploy/embedded/README.md`、`docs/DOMAIN_RULES.md`（§10 版本穿越已知边界）、`docs/HANDOVER.md`、`docs/adr/0028`、`docs/adr/INDEX.md`、`docs/CHANGELOG.md`（本条）。

## refactor(server): 浏览历史查询主流化改写——ListHistory 相关标量子查询退役改 CTE 预聚合 JOIN + 计划锁定测试上 CI + ADR-0011 增补索引迁移计划复查工序（2026-10-02 第四百三十三笔）

执行 AI：GLM-5.3-Flash（执行子代理）

- **背景（治本批，叠在应急修复 PR #4 之上）**：第四百二十九笔以 0015 覆盖索引止血后，用户定性根因=手搓非主流形态（ListHistory 用每资产相关标量子查询取 MAX(started_at)，cursor 分支再手抄两份同款），要求主流化改写。本批查询改写 + 计划锁定测试 + 迁移纪律补工序三件套同 commit。
- **sqlc 限制核实（铁律 8 先验，官方出处 + 锁定版本实证）**：仓库锁定 sqlc v1.31.1（`server/sqlc.yaml` 禁随手升级）。其 SQLite 解析器限制**确凿存在但边界比 history.sql 旧注释「parser rule 3」更窄**：WHERE 引用 CTE/派生表别名报 `table alias does not exist`（上游 [sqlc-dev/sqlc#3639](https://github.com/sqlc-dev/sqlc/issues/3639)，2024-10 报告至今 **Open 无修复**，v1.25 可用、v1.26 起回归；本机以锁定版本对真实 migrations 复现确认）；SELECT/ON 引用别名正常。另实证发现更隐蔽一档：**HAVING 里的 `sqlc.arg`/`sqlc.narg` 宏被静默原样保留且对应参数从生成结构体丢弃（运行时必炸，无告警）**——两 discovered facts 已写入 history.sql 文件头与生成物注释。
- **改写（recommend.sql 先例同款主流形态）**：`latest` CTE 对 view_events 做**无 WHERE 的 `MAX(CASE WHEN kind='open')` 按 asset_id 预聚合**（单趟顺序索引扫描，GROUP BY 由索引序满足）+ INNER JOIN assets（join 即锚——latest 只含有事件的资产，原 `WHERE EXISTS` 锚冗余删除，孤儿事件照旧被 join 天然排除）+ **游标谓词移入 JOIN 的 ON 子句**（INNER JOIN 下 ON 与 WHERE 逻辑等价；WHERE/HAVING 均因上述解析器限制不可用）。**语义零变化**：kind='open' 口径、每资产 MAX、`(last_viewed_at DESC, asset_id DESC)` 排序、keyset 游标严格续读、字段集与生成类型全同（ListHistoryParams 仅字段顺序变化，httpapi 零改动编译通过）；DOMAIN_RULES §8 为展示层分组口径，对照确认不受影响。
- **截胡实证（改写过程中的关键取舍）**：首版 CTE 保留 `WHERE kind='open'` 时，无 ANALYZE 的启发式 planner 实测被 0013 索引截胡（`SEARCH ve USING INDEX idx_view_events_kind_started (kind=?)` + `USE TEMP B-TREE FOR GROUP BY`）——印证「CTE 内只要有 seekable kind 约束就会被 kind 前导索引劫持」；终版去掉 CTE 内 WHERE（kind 过滤改由 CASE 聚合承担）**结构性消除该截胡向量**，实测计划变为 `SCAN ve USING COVERING INDEX idx_view_events_asset_kind_started`（0015）单趟顺序扫、无临时树、无任何 view_events 逐行探测。
- **语义等价佐证（新测试）**：`store/history_test.go` TestListHistorySemantics——每资产一条取 MAX(open)、play/dwell 不计入、孤儿事件与未打开资产不出现、同毫秒 asset_id 决胜、游标两分支严格续读、is_favorite 投影流转；测试还反向验证了 `source_is_other` 恒传 0/1 的调用方契约（NULL 三值逻辑会静默排除全部行，与 handler 行为一致）。
- **计划锁定测试（新，F1 facets 同型隐患并入）**：`store/db/history_plan_test.go`（必须住 package db——被断言 SQL 常量未导出且生成物禁手改，ADR-0009）——① ListHistory 首屏/游标两分支断言 EXPLAIN QUERY PLAN 文本：覆盖索引单趟扫描、无 GROUP BY 临时树、无 view_events 逐行探测、0013 陷阱索引（kind 前导）禁现；② facets 六查询 history_subset EXISTS 探测（与事故子查询逐字节同型、`GET /assets/facets?history=1` 可达，F1 扫描发现）以 FacetPartitionCounts 为代表断言双等值前缀 `SEARCH veh USING COVERING INDEX …(asset_id=? AND kind=?)`——**实测未被 0013 截胡**（asset_id+kind 双等值天然走 0015 前缀），无需升级处理。断言全部为确定性计划文本比对（全仓无 ANALYZE，同 schema 同驱动必出同计划），零耗时断言；未来任何翻坏计划的索引迁移会被 CI 拦截。
- **ADR-0011 修订（迁移纪律补工序）**：索引类迁移必须附**同表全部查询族**的 EXPLAIN QUERY PLAN 前后横向复查（结论入迁移头注；有真机只读副本时附规模化耗时对比），关键查询族计划以文本断言入 CI 锁；动机=0013→history 事故（验收只看目标查询族、planner 无统计纯启发式、索引增删静默重排同表全部计划）。`docs/adr/INDEX.md` 同步。
- **验证**：`go vet ./...` + `go test ./...` 全绿（server 全包，含 httpapi 全链路与迁移链 down/up 测试）；`sqlc@v1.31.1` 重新生成仅 `history.sql.go` 变化；协议零改动（openapi.yaml/sdk.lock 无涉）；禁触项（gradle/npm/vite 构建、模拟器、手机、内嵌打包）未触碰，最终以本 draft PR 云端 CI 为准。
- **涉及文档**：`docs/CHANGELOG.md`（本条）、`docs/adr/0011`（修订记录：计划影响横向复查）、`docs/adr/INDEX.md`；history.sql 文件头注释重写（sqlc 解析器两 discovered facts + 改写依据）。


## fix(server): 观看历史查询计划退化根治——0015 覆盖索引 (asset_id, kind, started_at)，/history 从 8.85s 回到毫秒级（2026-10-02 第四百三十二笔）

执行 AI：GLM-5.3-Flash（执行子代理）

- **背景（真机事故）**：手机 App（内嵌服务端形态）浏览历史页空白。取证链：内嵌服务端 log 两次「查询观看历史失败: context canceled」（App 侧取消）→ PC 侧直测 GET /history 200 但单页 8.85s → App 主客户端读超时恰为 10s（NetworkModule），冷启动负载下必超时 → OkHttp SocketTimeout 取消请求 → 页面空白。**数据零丢失**（接口正常回 60 条/页，最近浏览 2026-09-30），是纯查询性能问题。
- **根因（EXPLAIN 实证）**：0013 的 (kind, started_at) 索引（审计 R4，为 stats 族引入）把 ListHistory 的每资产 `MAX(started_at)` 相关子查询带进坏计划——该索引只能服务 `kind=?` 前缀约束（asset_id 不在索引列），每个资产要扫过全部 open 事件（真机库 6350 资产 × ~6.8k open 事件 ≈ 4300 万索引步/请求）。真机库只读副本实测：坏计划下查询 0.16s（PC）≈ 8.85s（手机），叠加冷启动并发与大 WAL 即穿 10s 超时线。
- **修复**：新迁移 `0015_view_events_asset_kind_started` 加覆盖索引 (asset_id, kind, started_at)——EXISTS 探测与 MAX 聚合都变 `(asset_id=? AND kind=?)` 双等值前缀查找，副本实测 0.16s→0.00s。**查询零改动**（history.sql 逐字节不动），游标分支同型子查询与 browse.sql 同型的每资产聚合同批受益；DOMAIN_RULES §5/§8 口径零变化，纯索引迁移只加不改（ADR-0011），旧索引按纪律保留不删。
- **测试**：`store_test.go` TestMigrateDownThenUp 按该测试自身约定「新增迁移在链首插入对应验证步」补 0015 down 验证步（索引删除 + 0014 对象保留断言）；首版 PR 漏补此步被 CI 拦下（down 链链首错位一位全链雪崩）——本仓迁移必须同 commit 配测试步的活例。
- **验证边界（夜间零构建约束）**：诊断全程只读（adb forward 只 GET、真机库只读副本分析、token 仅内存变量未落盘）；本地禁止编译/测试，修复经分支 `fix/history-display` draft PR 走云端 CI 验证，**待晨间构建装机后真机复测浏览历史页**。
- **涉及文档**：`docs/CHANGELOG.md`（本条目）。迁移文件自身注释含完整根因与取舍（0015 up/down）。


## fix(web): 「手搓方案」排查批——窗口事件总线退役改 React context、sonner 主题接线归位并退役孤儿依赖 next-themes（2026-10-02 第四百三十一笔）

执行 AI：GLM-5.3-Flash（执行子代理）

- **背景**：App/服务端侧曾发现「AI 自写小方案而不用成熟主流方案」一类问题，本批对 web 端做同口径全量排查（判定标准：重复实现依赖树已有成熟能力/绕过 ADR-0008 分层与 token 纪律/绕工具链限制的手写 hack/自造事件总线替代 React 已有机制；有注释理由的取舍不构成问题）。全量扫描 `web/src` 结论：**无 P0**；组件直调 API/硬编码颜色/手写防抖/手写弹窗（radix 已封装 select/popover/switch/confirm-dialog/ sonner 接入）均无违例，P1 两处、P2 记档七处（详见 PR 正文排查清单）。
- **P1-1 事件总线**：AppShell 刷新 FAB → 首页 tab 的「全量重排」信号原走 `window.dispatchEvent('qm:refresh')` 手搓事件总线（constants.ts 存 `QM_REFRESH_EVENT`、HomePage 手写 add/removeEventListener 订阅）——判定标准「自造状态/事件总线替代 React 已有机制」逐字命中。改 React context：新增 `components/shell/refresh-context.tsx`（`ShellRefreshContext` + `useShellRefresh`，prevTickRef 比对保证挂载/StrictMode 双跑不误触发，时序语义与原事件一致），AppShell 持 Provider 递增 tick，HomePage 三 tab 改消费 hook；`QM_REFRESH_EVENT` 常量删除，use-stats/use-assets 相关注释同步。行为等价：刷新仍 = invalidateQueries（全页面）+ 首页推荐/cos 换 seed 回第一页、热榜重置分页。
- **P1-2 sonner 主题脱钩**：`components/ui/sonner.tsx` 经 `next-themes` 的 `useTheme()` 取主题，但项目从未挂 ThemeProvider（ADR-0031 主题机制自持：index.html 内联脚本 + lib/theme.ts）——`useTheme()` 恒返回缺省 context，sonner.tsx 回退 `"system"` 跟随 OS 的 prefers-color-scheme，与应用 `html.dark`（暗色优先）脱钩：浅色系统 + 默认暗色应用时 richColors 色板按亮色出、浮在暗玻璃上（@immich/ui 退役遗留的半迁移状态，next-themes 在依赖树内无其他消费方）。修复：新增 `hooks/use-is-dark.ts`（MutationObserver 订阅 `.dark` class → React 状态，video-player 主题色观察器同款先例），Toaster `theme` 直读应用主题；`next-themes` 从 package.json/lockfile 退役（净删一个依赖，零新增）。
- **测试**：`npx tsc --noEmit` 零错；`npx vitest run` 216 用例全绿；oxlint 警告数与改前基线持平（19 条全为存量）；vite dev 按需转换冒烟（改动五模块 + 首页全部 200，无转换错误）。新接线（context/hook）按 ADR-0017 口径不做 React 层单测（vitest 只测 src/lib 纯函数，未装 @testing-library），行为靠 dev 冒烟 + 既有 216 用例回归兜底。
- **涉及文档**：本条目。代码侧：`web/src/components/shell/refresh-context.tsx`（新增）、`web/src/components/shell/AppShell.tsx`、`web/src/pages/HomePage.tsx`、`web/src/lib/constants.ts`、`web/src/hooks/use-is-dark.ts`（新增）、`web/src/components/ui/sonner.tsx`、`web/src/hooks/use-stats.ts`、`web/src/hooks/use-assets.ts`、`web/package.json`、`web/package-lock.json`。

## feat(api/server)+docs: 备份导出/导入并入 TXT 作者片段——txtFragments 段随备份全量迁移，跨端迁移最后一公里补齐（2026-10-01 第四百二十八笔）

执行 AI：GLM-5.3-Flash（执行子代理）

- **背景**：用户跨端迁移工作流「本机攒 → 登 NAS 导入」最后一公里补齐——媒体文件走归档一键上传/本机同步、行为数据走备份，作者 TXT 片段此前还需再手动走 import-txt 导出/导入往返；本批把 TXT 作者片段并入备份载荷，一次导入全量到位。
- **协议（api）**：`api/openapi.yaml` `LegacyBackupData` 增可选 `txtFragments` 段 + 新 schema `LegacyTxtFragment`（filename/content/importedAtMillis）、`LegacyImportResult` 增 `txtFragmentsImported`/`txtFragmentsSkipped` 两计数；纯增量扩展、无端点签名变化；**Web 裸字节透传零改动，App 侧备份经 SDK 模型 Moshi 往返（导出重序列化落盘/导入重序列化上传）——旧装机 App 双向静默丢 txtFragments，须升级含新 SDK 的 APK 后 App 端备份链才携带片段**；三端 SDK 重生成、`api/sdk.lock` 同 commit 更新。
- **服务端（server）**：导出（export.go）全量已导入 TXT 片段逐字导出（kv `imported_txt_sources`，不截断不转换）；导入（import.go）该段在 authors/authorMediaRefs 段**之前**逐片段处理（片段导入触发统一重建，若后处理会冲掉备份携带的作者关联）——目标库无同名片段直接导入；同名且内容相同跳过（幂等）；同名但内容不同按 import-txt **keep** 语义（目标端上传写入条目并回后替换重建，绝不 remove）；旧备份无该段零处理（向后兼容）；响应计数 `txtFragmentsImported`（新增+替换）/ `txtFragmentsSkipped`（内容相同跳过）。
- **权威口径**：`docs/DOMAIN_RULES.md` §10 新增「TXT 片段（txtFragments 段）」节（导出/导入合并/响应计数的唯一权威），同节「未匹配与载荷边界」TXT 单独通道旧表述同步废止。
- **测试**：`server/internal/httpapi/import_txtfrag_test.go` 六集成用例全绿（导出全量/新增/幂等跳过/keep 保护上传写入条目/旧备份无段向后兼容/txtFragments 先于 authors 段的顺序保护）。
- **涉及文档**：`docs/DOMAIN_RULES.md`（§10 新节+载荷边界改口+头部）、`docs/GUIDE_API.md`（「迁移」行+「跨端迁移工作流」小节+头部）、`docs/ARCHITECTURE.md`（§11 备份端点段）、`docs/CAPABILITY_MAP.md`（备份行+头部）、`docs/HANDOVER.md`（§4 服务端行+头部）、本条目。

## fix(web): 对抗审查返工批——#root 高度链断裂（长页无滚动）等六处（2026-10-02 第四百三十笔）

执行 AI：GLM-5.3-Flash（执行子代理）

- **P0 滚动断裂**：`styles/glass.css` 补回 `#root { height: 100% }`（v1 prototype.css 同款规则，重写时漏带）——React 多包一层 #root，html/body{height:100%} 传不到 .layout 时长页（首页网格/相册/榜单/设置）被 body overflow:hidden 裁切无滚动；本地 dev server 滚动验证通过（滚到底可达折叠内容）。
- **P1 死变量**：`AppErrorBoundary.tsx` 错误屏主按钮 `var(--qm-primary-foreground)`（v2 已删）→ `var(--qm-on-accent)`，修复浅色主题紫底深字低对比。
- **P2 如实记档**：glass.css 头注释「零颜色字面量」改为如实（12 处 oklch：3 处纯黑 α 混合/投影锚点 + 降级块 9 处，均有刻意理由就地注释）；tokens.css 别名块注释改为如实（43 个中在用 10 个，其余 33 个零引用暂不裁剪、清理后整块删）。
- **P2 行为**：theme.ts 的 startViewTransition 外包 prefers-reduced-motion 短路（减动效用户明暗切换不再收全屏溶解）；vite.config.ts 代理覆盖注释示例泛化（去具体端口）。
- **文档**：ADR-0031 决策 2/3 同步（别名块在用清单、#root 高度链与 reduced-motion 短路入决策记录）。
- **涉及文件**：`web/src/styles/glass.css`、`web/src/components/layout/AppErrorBoundary.tsx`、`web/src/lib/theme.ts`、`web/vite.config.ts`、`web/src/tokens.css`（仅注释）、`docs/adr/0031`、本条目。

## feat(web): Web 全新设计语言「绮梦流光 · Aurora Glass」——暗色优先液态玻璃 + token 体系 v2（2026-10-02 第四百二十九笔）

执行 AI：GLM-5.3-Flash（执行子代理）

- **背景**：Web 视觉层是 M2 原型移植（亮色优先白底实心卡，色彩挂在 @immich/ui 色板上）；媒体全存 NAS 的桌面/大屏消费场景需要暗色优先的现代设计语言。约束：功能守恒（20 路由页/数据流/类名契约不动）、样式仍只许 token、不引重型新依赖。
- **设计语言（ADR-0031）**：三层结构——画布层（深靛底 + 三团低饱和极光 radial-gradient 固定氛围层）、玻璃层（半透明表面 + backdrop-filter blur(22px) saturate(1.5) + 发丝描边 + 顶缘内高光 + 轻落影；浮层用 strong 档）、内容层（文字四级灰阶 + 极光紫主色 + 图表五色）。暗色为设计主角，浅色为完整度相同的「晨雾玻璃」变体；**主题默认从「跟随系统」改为「默认深色」**（index.html 首帧内联脚本与 lib/theme.ts 双写同语义，月亮按钮切换持久化不变）。
- **token 体系 v2（web/src/tokens.css 重写）**：`--qm-*` 语义层全量重构（画布/极光/玻璃材质组/阴影四级/圆角四档/动效三时长三缓动〔新增 spring〕/z 阶梯七档）；**shadcn 桥接反向**（v1 `--qm-*`←immich，v2 shadcn 变量←`--qm-*`，index.css 桥接层）——`--qm-*` 成唯一色彩事实源；旧名兼容别名块（`--text-main` 等存量 TSX 引用指向单源，新代码禁用）；`@immich/ui` 主题包 CSS import 与依赖一并退役。
- **样式层整体替换**：`styles/prototype.css` 删除 → 新 `styles/glass.css` 按相同类名契约全量重写（约 400 选择器 13 分节；组件 DOM/逻辑零改动）；v1「首行贴侧栏」负 margin 像素锚位体系退役改统一节奏；**保留** zoom 1.1 及三处配套补偿（radix popper/图片查看器 --zoom-inverse/:fullscreen）、reduced-motion 归零层、radix data-state 进出场机制。
- **降级策略**：`@supports not (backdrop-filter…)` 时 surface 组变量整组替换为同色不透明等价值 + 叠加层/查看器/遮罩转实底——布局/层级/圆角零变化（Safari 走 -webkit- 双写）。
- **动效**：卡片进场 stagger、详情叠加层入场、点赞回弹沿用机制换 token 档位；新增明暗切换 `document.startViewTransition` 全屏交叉溶解（不支持的浏览器同步直切，渐进增强）；动效零 JS 库。
- **组件/配置同步（非视觉逻辑）**：SettingsPage 主题模式文案对齐暗色默认；PWA manifest 与 theme-color meta 三处双写改随暗色画布（#0b0c16/#f3f3f9）；vite 开发代理目标支持 `QIMENG_DEV_PROXY_TARGET` 环境变量覆盖（默认 8420 行为不变，隔离调试实例用）。
- **功能守恒自查**：20 个路由页（home/albums/mine/data/maintenance/settings/search/ranks/authors/collection/asset/asset-edit/maintenance 六子页/trash）CDP 截图走查全部可达且渲染正常（暗/浅双主题）；登录门/搜索面板/上传工作台/确认弹窗类名契约未动。
- **涉及文档**：`docs/adr/0031`（新增）、`docs/adr/INDEX.md`、`docs/HANDOVER.md`（头部+§4 Web 首行）、`web/README.md`（设计语言节+分层纪律+代理覆盖）、本条目。


## docs(repo): 文档结构优化批——CHANGELOG 拆档 + 现状文档瘦身（2026-10-01 第四百二十七笔）

执行 AI：GLM-5.3-Flash（执行子代理×2 批次；对抗性审查子代理复核零丢失）

- **背景**：用户要求文档「准确清晰、减少繁琐」，控制 AI 上下文负担。全库文档约 6900 行中 CHANGELOG 一个文件占 5187 行（75%）。
- **CHANGELOG 拆档**：新建 `docs/history/`；2026-09-22（第三百八十二笔）之前的 405 笔**逐字**迁入 `docs/history/CHANGELOG-ARCHIVE.md`（字节级 diff 验证零改写，笔号连续可查）；主文件保留 45 笔近期条目（463 行）+ 头部拆分说明；`REVIEW-20260922.md` 同步归档至 `docs/history/`（git mv，100% 相似度纯移动）。
- **引用扫尾**：全仓早于拆分线的笔号引用 20 处补「（历史档）」标注（DOMAIN_RULES/GUIDE_API/PROJECT_PLAN/ADR-0016·0022·0027）；HANDOVER 两处 REVIEW 路径更新；主文件保留段内的旧引用按「不改写正文」红线不动（头部拆分说明统一兜底）。
- **现状文档瘦身**：`docs/HANDOVER.md` §4 按端重写为短句清单（字节 -44%，四端能力点逐一核对缺项=0、超长行清零、批次流水账与 commit 哈希清零，ADR 引用与「防再提案」退役注记保留）、§5 待办/记档级/用户节点/建议立项/已知问题全保留、§8 文档地图补 LEGACY_REQUIREMENTS 与 history/；`docs/CAPABILITY_MAP.md` 头部压短、表行删 commit 哈希（能力事实全保留）；`docs/PROJECT_PLAN.md` 151→63 行（M0-M6 收官叙事压缩，M7+ 储备与常驻任务节逐字未动）；GUIDE_API/ARCHITECTURE/OBSERVABILITY/SECURITY 仅头部「最后更新」行收敛（正文零改动）；DOMAIN_RULES 除引用标注外零改动；`AI_README_FIRST.md` 接手行补历史档指引。
- **审查结论**：PASS——拆档逐字节可复现、HANDOVER 待办与事实零实质丢失（6 个子级细节压缩均在他处活文档有档）、引用扫尾全覆盖无漏网、M7+ 与常驻任务逐字一致、无敏感信息引入。
- **涉及文件**：`docs/CHANGELOG.md`、`docs/history/CHANGELOG-ARCHIVE.md`（新）、`docs/history/REVIEW-20260922.md`（移入）、`docs/HANDOVER.md`、`docs/CAPABILITY_MAP.md`、`docs/PROJECT_PLAN.md`、`docs/GUIDE_API.md`、`docs/ARCHITECTURE.md`、`docs/OBSERVABILITY.md`、`docs/SECURITY.md`、`docs/DOMAIN_RULES.md`、`docs/adr/0016`、`docs/adr/0022`、`docs/adr/0027`、`AI_README_FIRST.md`、本条目。

## docs(repo): 全库文档漂移排查批——三路对照代码取证，修正 22+ 处（2026-10-01 第四百二十六笔）

执行 AI：GLM-5.3-Flash（执行子代理）

- **背景**：三路只读排查对照代码取证后的全库文档漂移修正批：误导级 5（暂存区/收件箱/相册选择器仍以现状语态描述×3、ADR-0029「第二半待落地」、PROJECT_PLAN progress/dwell「待协议批修」）+ 键名/路径/模块表/计数等十余处；附 server 一处代码注释对齐。DOMAIN_RULES 公式与口径句、CHANGELOG 既有正文、ADR 正文一律未动。
- **误导级修正**：① `docs/GUIDE_API.md`「关键机制」作者挂靠编辑段暂存列表/逐项编辑现状语态改为退役注记（批次默认持久化+入队快照继承、选完即传），name-suggestions 行尾注改「协议保留、当前无端调用方」；② `docs/DOMAIN_RULES.md` §6 同口径替换暂存列表句；③ `docs/adr/INDEX.md` ADR-0029 行「第二半待落地」改「已同日落地（Android ServerEventConsumer 消费+三层门、Web SseBridge 失效映射）」，ADR-0030 状态行 Accepted→已接受（口径统一）；④ `docs/PROJECT_PLAN.md` M4-3 progress/dwell seconds 序列化 400 补销账（已于 2026-09-09 N2 协议批根修，format: double 三端落位）、M6 形态 A 补「T2 已随 2026-09-20 M6 收官」、头注回填「2026-09-22（M5 真机收官回填）」；⑤ `docs/CAPABILITY_MAP.md` 相册式选择器退役销账（现唯一『系统文件』SAF 入口+系统分享单管道）、#21 拍板放弃对齐、图例「规划中」改 M7+ 口径、缺口列「双端已接入」移入现状列（缺口清空）。
- **键名/路径/注记类**：`docs/OBSERVABILITY.md` 日志等级键 `QM_LOG_LEVEL`→`QIMENG_LOG_LEVEL`（config.go 实际键名）、thumb_queue_depth 注记 6 改已随缩略图预热出数、健康检查句改「deploy 样例实际用 /api/v1/healthz」；`docs/DOMAIN_RULES.md` §10 上报路径 POST view-event→POST /events/view、§11 孤儿缓存句补「待对账任务兜底，规划项」、§11 实现状态句改 md 档预生成已落地（thumbnail_warmup.go：开机回填/扫描后补齐/10min 周期兜底）+ 双管拆分仍未做；`docs/SECURITY.md` 红线 5 补认证端点豁免（setup/login/dev-login）；`deploy/README.md` 环境变量表补 QIMENG_BACKUP_ENABLED/INTERVAL/RETENTION 一行三键；`docs/ARCHITECTURE.md` §5 模块边界表补 0025-0030 批次四包（backup/libraryrevision/uploadsess/localsync）、authorattach 行职责改「作者挂靠编辑与本地镜像编排（上传挂靠参数已随 ADR-0024 退役）」；`docs/HANDOVER.md` §4 Web 行「刷新存活/断点续传待另立项」改断点续传已接入、§4 资产编辑段暂存区/收件箱/相册选择器/浏览文件入口历史叙事收敛为退役注记（保留仍活能力原文，协议路径 63→65 补「时点数，现 72」）。
- **README**：`android/README.md` 上传职责行对齐直传化现状（系统文件 SAF 唯一入口+直传/分片双通道+归档一键上传）；`web/README.md` 分层纪律补例外注（lib/upload-chunked.ts 传输层 XHR 直连不走生成 SDK，ADR-0028 记档）。
- **代码注释（server）**：`server/internal/filing/upload.go` allowedExtensions 清单依据注释对齐现行 DOMAIN_RULES §9（两处视频清单一致均含 m4v，原「§9 无 m4v 取 SECURITY 超集」表述过时）；`gofmt -l internal/filing` 为空。
- **涉及文档**：`docs/GUIDE_API.md`、`docs/DOMAIN_RULES.md`、`docs/OBSERVABILITY.md`、`docs/SECURITY.md`、`docs/CAPABILITY_MAP.md`、`docs/HANDOVER.md`、`docs/adr/INDEX.md`、`docs/PROJECT_PLAN.md`、`docs/ARCHITECTURE.md`、`deploy/README.md`、`android/README.md`、`web/README.md`、`server/internal/filing/upload.go`、本条目。

## feat(app)+docs: 归档文件夹一键上传 + 备份导入语义文档澄清（2026-10-01 第四百二十五笔）

执行 AI：GLM-5.3-Flash（执行子代理）

- **App（feat(app)）**：上传页新增「从归档文件夹一键上传」区块——扫描归档根一级子文件夹，文件夹名=库名自动匹配（精确或 sanitize——trim + `\/:*?"<>|` 九字符→`_`，复用 `InboxFileStore.sanitizeLibraryDirName` 单一事实源，与服务端 ADR-0030 本机同步通道同一规范）；递归收集媒体文件（子目录相对路径映射库内子目录），确认后逐条带条目级库目标走既有上传队列入队；`alreadyArchived` 旗标让 Worker 跳过再次归档移动（源文件已归档过）；防重门禁（入队成功后置位，重扫文件数变化才复位+常驻警示文案）、本地超限拦截与服务端 413 双兜底、库列表加载失败时仍诚实扫描。新增 24 个单测（`ArchiveBatchScanTest` 14 + `UploadViewModelTest` 新增 8 + `UploadWorkSpecTest` 新增 2）。
- **文档（docs）**：备份导入语义澄清（用户实测推动；经代码考证 import.go/import_replay.go/export.go，备份导入是**幂等合并**而非整库替换）——`docs/GUIDE_API.md`「迁移」行过时警告「导入只用于全新实例/迁移场景」整句替换为幂等合并准确表述（不建资产不删数据、按文件名匹配既有资产并入、内容键跨批去重、未匹配跳过、文件本体与 TXT 片段不在备份内），「关键机制」新增「跨端迁移工作流」小节；`docs/DOMAIN_RULES.md` §10 mediaFiles 映射行措辞修正（按文件名匹配目标库既有资产建立 asset_id 映射，不创建资产）+ 新增「未匹配与载荷边界」段（未匹配文件行为数据静默跳过、作者/标签关联计 skipped；备份不含媒体文件本体与 TXT 片段）；同批澄清跨端工作流：本机模式导出（手机 SAF 目录）→ App 登 NAS → 导入即合并，文件本体走上传通道/ADR-0030，TXT 片段单独走 import-txt 导出/导入。
- **涉及文档**：`docs/GUIDE_API.md`、`docs/DOMAIN_RULES.md`、`docs/HANDOVER.md`（头部最后更新 + §4 Android 行末）、`docs/CAPABILITY_MAP.md`（头部最后更新 + 上传整理行 + 备份行）、本条目。

## feat(api/server/web): 本机文件夹自动同步通道——服务端轮询监测同步根，库名文件夹自动匹配入库（2026-10-01 第四百二十四笔）

执行 AI：GLM-5.3-Flash（执行子代理）

- **协议（api）**：`api/openapi.yaml` 新增 `GET /api/v1/local-sync/status`（通道状态只读：enabled/root/intervalSeconds/paused/lastError/lastCycleAt/syncedTotal/counts{waitingStable,failed,ignored}/items 上限 500/recentSynced 最近 20）与 `POST /api/v1/local-sync/trigger`（202 异步触发一轮扫描；未启用 409 code=`LOCAL_SYNC_DISABLED`）两端点 + `LocalSyncStatus`/`LocalSyncItem` 两 schema；路径计数 70→72；三端 SDK 重生成、`api/sdk.lock` 同 commit 更新。
- **服务端（server）**：① 纯逻辑包 `server/internal/localsync/`（match.go 库名 sanitize 匹配〔trim + `\/:*?"<>|` 九字符→`_`，与 Android 归档 `InboxFileStore.sanitizeLibraryDirName` 逐字对齐，大小写敏感；0 命中/多命中/停用/COS 库=失败保留〕、scan.go 目录扫描〔稳定门槛=_mtime 年龄≥门槛且跨轮 size/mtime 不变、隐藏条目与符号链接跳过、NormalizeRelPath+PathWithinRoot 根内限制〕+ doc.go + 单测 11 用例）；② 编排与运行态 `server/internal/httpapi/localsync.go` + `localsync_runner.go`（trash_sweeper 同型先例：轮询 daemon + trigger 闸 + status 运行态）：媒体走直传完全同款链路——`filing.ValidateUpload` 四道校验 + `upload.autoAccept` 闸 + `upload.max_bytes` 上限 + `ResolveConflict` 同名自动改名永不 409 + `WithLibraryGate` 库锁落位 + 落位 size 复核（对齐第四百一十九笔硬化）+ `ingestPlacedUpload`（sysmon/upload.done/library.changed/修订号/富化全同款），成功后源文件 move 入库根；同步根直接下 `*.txt` 走 importTxt 统一重建（conflictResolution 恒 keep、仅 UTF-8 剥 BOM、10MB 护栏），成功后移 `<同步根>/.synced/`（点前缀内部目录，同名加序号）；失败=原地保留每轮重试；安全=同步根与库根/DataDir 双向重叠禁令（含相等）、根不存在=通道级错误不建目录；`server/internal/config/config.go` 增 `LocalSyncConfig` 三键（yaml `local_sync.root/interval/stable_age` / env `QIMENG_LOCAL_SYNC_ROOT`〔空=关〕/`QIMENG_LOCAL_SYNC_INTERVAL` 默认 30s/`QIMENG_LOCAL_SYNC_STABLE_AGE` 默认 60s）；httpapi 集成测试 12 用例（成功/清理对齐 E2E/TXT/未命中/非媒体忽略/根级媒体忽略/稳定门/根重叠/两端点行为/未启用 409/COS 拒绝/autoAccept 暂停）。
- **Web（web）**：维护页新增「本机同步」卡（`web/src/components/manage/LocalSyncCard.tsx` + `web/src/hooks/use-local-sync.ts`，渲染于 `DbBackupCard` 之后）：状态徽标/信息行/lastError 警示/失败·忽略·最近成功三段列表/立即同步按钮。
- **边界**：无 DB 迁移、无新依赖；App 端零改动（手动上传保留为备选安全通道）；已开放通道级已知权衡（运行态内存持有重启重建/syncedTotal 归零、move 与注册之间崩溃窗口由扫描器 ≤5min 兜底、GBK 不支持、autoAccept 关闭期媒体暂停）——记档见 ADR-0030。
- **文档**：新增 `docs/adr/0030-local-folder-sync.md` + `docs/adr/INDEX.md` 索引行；`docs/GUIDE_API.md`（路径计数 70→72、速览补行、「关键机制」新增「本机自动同步通道」小节）；`docs/SECURITY.md`（新增「本机同步通道」节）；`docs/DOMAIN_RULES.md`（§9 上传口径段末一句括注，其余一字未动）；`docs/HANDOVER.md`；`deploy/README.md`（环境变量表补三键）。

## chore(server): gofmt 格式对齐六文件——CI 服务端格式门禁清偿（2026-10-01 第四百二十三笔）

执行 AI：GLM-5.3（主代理，executor+reviewer 子代理协作）

- 执行子代理本地验证只跑了 golangci-lint（不含 gofmt），六个新文件（uploads/uploads_test/orientation/orientation_test/uploadsess 两件）存在 gofmt 格式偏差，CI 的 `test -z "$(gofmt -l .)"` 门禁拦截。本笔纯格式对齐（零逻辑改动，build/vet 复验通过）。（GLM-5.3 主代理）

## chore(ci): make sdk 生成前清理三端旧产物+Kotlin 生成链锁版本+锁校验失败打印差异——CI 指纹锁从未绿的根因修复（2026-10-01 第四百二十二笔）

执行 AI：GLM-5.3（主代理，executor+reviewer 子代理协作）

- **根因（CI 历史从未绿，含本仓库首推 GitHub 的每一轮）**：`make sdk` 的三个生成器只覆写不删除——本地三端生成树里累积了历次协议演进中被移除端点的陈旧文件（本次清理实证 2 个），它们一并被记进 `api/sdk.lock`；而 CI 全新 checkout 只产出当前 openapi 的文件集，两边文件集合恒不等、锁校验恒炸。本地重算与已提交锁一致（230 秒内可复现），坐实"脏树入锁"而非生成器版本漂移。
- **修法三件**：① `make sdk` 新增 `sdk-clean` 前置步骤（rm 三端生成树后全量重生成）——生成结果与树的历史状态无关，锁自此跨机可重现；② Kotlin 生成链锁版本 `@openapitools/openapi-generator-cli@2.41.0`（内嵌 generator 7.24.0；此前是三端唯一未锁版本的生成器，`npx -y` 永拉最新，随发布漂移必致锁失配）；③ ci.yml 锁校验失败时打印 diff 摘要（stat+前 40 行），下次失配直接看到漂移文件，不再盲猜。
- **验证**：本地 `make sdk` 全链通过（validate/go build OK/TS/Kotlin 2.41.0），锁 235→233 条（恰为 2 个陈旧文件），Go/TS 两端重生成逐字节一致（确定性良好）；已推送由 CI 五门禁终审。（GLM-5.3 主代理）

## fix(server): 缩略图 EXIF 方向显式处理——竖拍图不再横躺，缓存键 v2→v3 全量重建（2026-10-01 第四百二十一笔）

执行 AI：GLM-5.3（主代理，executor+reviewer 子代理协作）

- **动机（轨迹审计实证缺口）**：服务端全库无任何 EXIF/orientation 处理，`thumbnail/ffmpeg.go` 的 scaleStill（静图缩放）无 transpose/autorotate 保障——竖拍手机照（EXIF Orientation=6 等）的缩略图方向取决于部署 ffmpeg 版本的隐式行为，且缓存键不含方向语义，错误方向的缩略图按 immutable 一年缓存。实测补充关键事实（任务背景"版本隐式行为"的实证）：ffmpeg 对静图 JPEG 的 EXIF 处理**随版本漂移双向存在**——本仓 ffmpeg 9.0.1 解码期**已隐式按 EXIF 转向**（Orientation=6 默认解出已转正的竖帧），旧版则不转；单纯补显式滤镜会叠在隐式行为之上双重变换（镜像/180° 抵消、90° 变 180°，集成测试首跑全部实测复现）。浏览器显示原图自行按 EXIF 转向（CSS image-orientation 默认 from-image），故只有服务端生成的缩略图/海报帧需要处理；视频 display matrix 由 ffmpeg autorotate 常态可靠，不在本批范围。
- **修法（显式、跨版本恒定）**：① 新增 `thumbnail/orientation.go`——纯手写解析 JPEG APP1 EXIF 的 IFD0 Orientation tag（零新依赖，只认 SHORT+count=1 的单一 tag，TIFF II/MM 双字节序都支持；HEIF/解析失败/非 JPEG/值越界一律回落 1 不转，方向解析失败不阻断生成）。② `orientationFilterChain` 映射表：1=无、2=hflip、3=hflip,vflip、4=vflip、5=transpose=0（主对角线翻转）、6=transpose=1（90°CW）、7=transpose=3（副对角线翻转）、8=transpose=2（90°CCW）——依据 EXIF 规范 0th row/column 语义 × ffmpeg transpose 滤镜官方语义（`-h filter=transpose`），**每档经真实 ffmpeg 四象限像素级夹具实测锁定**（任务拟稿中 5=transpose=1 的猜测被实测证伪，5 实为 transpose=0）；scaleStill 输入侧恒带 `-noautorotate` 关闭解码期隐式 EXIF 转向，显式滤镜成为唯一方向权威（新旧 ffmpeg 行为一致；对无方向元数据的视频中转帧 PNG 是 no-op，视频抽帧侧 display-matrix autorotate 不受影响）。方向读取只在 `ensureOne` 的 KindImage 分支（用户原图是唯一带 EXIF 的输入），动图/视频中转帧传 orientationNormal 零开销跳过。③ 缓存键策略版本段 v2→v3（cachekey.go）——v2 键下的缩略图未按方向旋转，全量失效重建，旧文件变孤儿由对账清理（版本段既定语义，迁移零新增、orig 直链不动）。
- **测试**：`go test -count=1 ./internal/thumbnail/ ./internal/httpapi/` 全绿；`golangci-lint run` 0 issues。新增 `orientation_test.go`——解析层（8 档小端 II 逐档读回/大端 MM/无 EXIF/PNG 字节流/空·截断·垃圾字节兜底/值越界·类型错·XMP 段·无 tag 畸形兜底/前导 FF 填充穿透）、映射表逐档文本锁定、磁盘入口吞错语义；真实 ffmpeg 集成测试 9 组（1-8 档 + 无 EXIF 对照）：Go 标准库手造四象限 JPEG 夹具（32×16，红绿蓝白四象限）+ SOI 后插 APP1 EXIF 段，强制 jpeg 降级档使产物可逐像素解码，断言输出尺寸换维（5-8 档 32×16→16×32）与四象限中心采样色逐一吻合 EXIF 规范独立推导的显示变换（longSide 恰等源长边使 scale 恒等，断言纯方向语义）。cachekey_test 黄金向量更新为 sha256("v3:…") 三条 + 版本段语义测试扩"当前≠上一代 v2≠裸键"双断言。
- **协作记录**：主代理定稿改动清单（含映射表"以夹具实测为准修正"的指令），executor 执行（DOMAIN_RULES 先行 + 代码 + 测试 + CHANGELOG/HANDOVER 同步；DOMAIN_RULES §11 方向策略与 `-noautorotate` 口径、缓存键 v3 公式、头部"最后更新"行随批更新）。（GLM-5.3 主代理，executor 子代理）

## fix(server): 备份快照 quick_check 校验+Docker 日志轮转+上传会话 Seal 落位复核——三项硬化（2026-10-01 第四百一十九笔）

执行 AI：GLM-5.3（主代理，executor+reviewer 子代理协作）

- **① 备份快照完整性校验（quick_check）**：`store.VacuumInto` 写完快照随即对**快照文件**做 `PRAGMA quick_check`（modernc 驱动以 `file:…?mode=ro&_query_only=1` 只读打开——缺文件直接报错而非静默建空库，空库的 quick_check 恰好也是 "ok"，不锁只读会把「快照丢了」误判成「快照完好」）；结果非 "ok" 视为损坏：slog Warn 记异常行头部（上限 5 行）、**删除损坏快照文件**、本轮快照报错失败（经 backup.Manager.Create 既有错误路径自然上抛，不留「快照存在但坏」静默留存——备份是唯一灾备手段，坏快照要等恢复日才暴露）。不做配置开关（主流备份工具默认校验）；性能口径：quick_check 全量读扫与 VACUUM INTO 全量写同量级，个人库数十 MB 级可接受。落位 store 包而非 backup 包（SQL 属 store 边界，backup 对 SQLite 零感知），`VacuumInto` 签名不变、main/backup 零改动；Windows 细节：删损坏文件前必须先关校验连接（句柄未释放时 Remove 撞 sharing violation）。测试（`store/vacuum_test.go` 新增）：VACUUM INTO 全链一次通过、完好快照独立复检通过、截断损坏→报错+文件被删、缺失文件→报错（防空库误判防线）。
- **② Docker 日志轮转**：`deploy/docker-compose.yml` 给唯一长驻服务 qimeng-media 补 `logging: json-file / max-size 10m / max-file 3`（默认 json-file 驱动无上限，长跑写满宿主盘）；`docs/OBSERVABILITY.md` §日志首条「按大小滚动为规划项」改判已实现（容器层），头部「最后更新」行同步。
- **③ 上传会话 Seal 落位 size 复核（上批审查 P2-4 遗留收口）**：`httpapi/placeSealedInLibrary` 在库锁 gate 内、rename 前对 staging 文件 `os.Stat` 复核实际字节数 == `seal.Size`——complete 的 staging/rename 段不持会话锁，违规客户端可在 Seal 后并发 PATCH（offset==size 恰好过 Append 首道比对）在 rename 间隙塞字节，且 Append 自身的回滚截断可能落在 rename 之后（截的是已被移走的旧路径）拦不住，落库文件就与声明 size 不符；不等则按会话已损坏处理：拒绝落位 + cleanup 清掉损坏 staging + complete 映射 **400**（协议侧 complete 明文「永不 409」、400「校验失败且会话保留」为既有语义，size 复核是四道终检里「最终大小」的加固延伸，零协议/SDK 改动）。stat 与 rename 相邻执行把竞态窗压缩到两条系统调用之间（绝对串行须把落位整体搬进 session.mu，会话锁横跨库锁与跨卷拷贝，不值得）。测试：`TestUploadSessionTamperedAfterSeal`——Seal 后向成品尾追加 1 字节走真实落位函数，断言 errSealedSizeMismatch + 库内无坏文件 + staging 已清理（HTTP 层无法确定性插入该间隙：complete 入口的 Seal 兜底截断会治愈落位前的篡改，测试注释已记档）。
- **验证**：`go test -count=1 ./...` 全绿（含新用例）；`golangci-lint run` 零告警；compose YAML 语法校验通过。

## feat(api): SSE 补 favorite.changed/like.changed 事件——跨端数据新鲜度收敛端到端（ADR-0029）（2026-10-01 第四百二十笔）

执行 AI：GLM-5.3（主代理，executor+reviewer 子代理协作）

- **动机（轨迹审计结论）**：跨端改收藏/点赞后其它端零感知——服务端对 favorite/like 本来就不发事件（Android 目前靠 5min TTL 兜底自愈〔第四百一十五笔〕，Web 靠窗口聚焦重取），陈旧窗口以分钟计且各端各自绕弯。收敛方案 = 服务端补两类轻事件（本批，ADR-0029 第一半）+ 双端消费（第二半，Android okhttp-sse 接入 + 门收敛、Web SseBridge 失效映射，归并行批次）。
- **修法**：① `events` 包新增 `favorite.changed` / `like.changed` 两主题常量与同构载荷 `EngagementChangedEvent{assetId}`（轻载荷取舍记档：写路径全为单资产操作无批量接口，单 id 即全部信息；不带变更后状态——事件是变更信号不是状态面，消费方重拉权威状态，避免载荷与 handler 形成第二份真相）；`allTopics` 增两枚举，SSE 通道零改动——事件经总线自然到达既有订阅者，未接线的客户端按 SSE 规范忽略未知事件名零害。② `engagement.go` 两 handler 写成功后发布（发布在写响应之前的同步路径上），404 路径零发布（行为测试锁定；like 的 count 回读失败 500 时事件已发但变更本身为真，无实害）；重复收藏的幂等 no-op（ON CONFLICT 0 行）照发——多刷幂等无害，发布点与写成功点一一对应更可审计；发布失败只告警不改变请求成功语义。③ **不 bump 修订号**（ADR-0026 语义边界不变：revision 只保证资产集合面）——零新增机制即成立：修订号自增订阅只挂 library.changed，新事件天然绕开；该约束写进 topic 常量注释、载荷 KDoc 与 handler 注释，防止未来有人拿 favorite 事件去 bump revision。④ 协议面零改动：SSE 运行时事件面不入 openapi.yaml（事件清单以 GUIDE_API「实时推送」行为准），bus.go 既有「主题与 openapi 对齐」注释同步修订并记档此例外；migration 零新增。
- **测试**：`go test -count=1 ./internal/events/ ./internal/httpapi/` 两包全绿（events 1.35s / httpapi 29.3s）；`golangci-lint run` 0 issues。新增两组——events 侧 `TestSSEEngagementEventsPassthrough`（总线→SSE 帧逐字节断言：event 名 + `{"assetId"}` 载荷 + id 递增，客户端批次按此实现解析）；httpapi 侧 `TestEngagementChangeEventsPublished`（收藏设置/取消、点赞 toggle 开/关各恰发一条对应事件〔topic+载荷 assetId〕、重复收藏两次各发一条、不存在资产 404 路径零发布、全程零混发 library.changed 且 GET /library/revision 前后相等——ADR-0026 边界行为级钉死）。
- **协作记录**：主代理定稿改动清单，executor 执行（代码+测试+CHANGELOG/HANDOVER 文档同步）。（GLM-5.3 主代理）
- **Web 接入（第二半，同日并行批次补齐）**：SseBridge 为两新事件注册处理器（事件名常量入 `web/src/lib/constants.ts`，与 server `events/bus.go` Topic* 注释互指双同步；载荷型 `EngagementChangedEvent` 本地最小声明——运行时事件面不入 openapi，生成 SDK 无此型）。失效面按数据实际依赖选最小正确集（键全部取 query-keys 根键常量）：`favorite.changed` → 失效 `ASSETS` 根键一根（覆盖收藏筛选流〔我的页收藏 tab favorite=true 子键〕+ AssetDetail.isFavorite + AssetSummary.isFavorite 列表字段面；推荐流不失效——十维公式无收藏维度、卡片不展示收藏态）；`like.changed` → 失效 `ASSETS` + `RANKINGS` + `RECOMMENDATIONS` 三根（详情 likedToday/likeCount、列表点赞字段面、liked=true 筛选流挂 ASSETS；排行热度含窗口内点赞计数〔DOMAIN_RULES §2〕故失效 RANKINGS；推荐 likeScore 维度〔DOMAIN_RULES §1.1〕故失效 RECOMMENDATIONS；stats/history 无点赞字段不失效）。两映射抽具名函数对齐 invalidateLibraryContent 风格、逐键注释为什么，重连补偿（onOpen）一并覆盖两新面（断线窗口错过的事件同样不可追，多拉幂等无害）。**载荷不消费**（ADR-0029 决策口径）：单 assetId 粒度精确失效收益不值复杂度，事件只当"收藏/点赞面数据可能过期"信号整面失效重拉权威状态；不碰 library.changed 的库内容根键（ADR-0026 语义边界，favorite/like 不改资产集合）。**验证**：`npx tsc -b` 零错误、`npm test` 23 文件 216 用例全绿、`npm run build` 通过；web 无组件渲染测试基建（既有口径），失效键形态契约由既有 query-keys.test.ts 锁定（本批键形态零改动），use-sse-events 派发为模块私有函数且首订阅者即建真实 SSE 连接，无可单测注册机制，接线正确性经类型+构建验证（如实记档）。
- **Android 接入（第二半，同日并行批次补齐）**：①**依赖**：新增 `com.squareup.okhttp3:okhttp-sse` 5.4.0（OkHttp 官方 SSE 工件，与既有 okhttp 同 family 同版本收口〔version catalog 单版本原则，版本注释记核查来源〕，Maven Central 实存核查 + 官方 sources jar 实读 API 面；零其它新依赖）。②**连接生命周期**：`core/data/events/ServerEventConsumer` 单例（QimengApplication.onCreate 接线，ThumbnailPrefetcher.onAppCreate 同款登录态观察范式）——登录即连 `GET /api/v1/events`、断线指数退避重连（3s 起 ×2 爬升 30s 封顶，连接成功重置；okhttp-sse 忽略服务端 retry 提示帧〔RealEventSource.onRetryChange 空实现，sources jar 实读确认〕，重连节奏客户端自带防风暴）、登出即断（取消连接与循环）、后台不断开（ROM 杀进程由既有事实接受，重登录/重启自愈，ADR-0026 口径记档）；鉴权零第二份逻辑——@SseClient 客户端从全局 OkHttpClient 派生共享 AuthInterceptor（token 读取逻辑仍只有拦截器一份），仅禁读超时防心跳掐流（服务端心跳 15s > 主客户端 10s 读超时），地址走 ServerConfigDataSource 单点。③**信号汇**：`DataFreshnessSignal` 三计数（library/favorite/like，AtomicLong 快照单读）——事件按名分发计数、载荷 `{"assetId"}` 不解析（变更信号非状态面）、hello 首帧与未知事件忽略零害；onOpen（含每次重连成功）三计数一起 bump，对齐 Web 端「重连后重新校验」语义（断线窗口错过的事件以门整体失效一次补偿）。④**门收敛**：`FavoriteMutationTracker`/`LikeMutationTracker` 镜像同构——`noteListFetchCompleted()` 同时采样信号快照为基线，`isListFetchFresh()` 扩为「TTL 未过 && 关联信号计数未变」（VM 消费点指纹半边不变）：收藏门关联 favorite.changed+library.changed 两计数、首页门关联 like.changed+library.changed 两计数（按列表数据面定并 KDoc 写明：收藏列表内容随资产集合变化、首页打分/榜单随点赞与资产集合变化，互不关联的第三类信号不触发）；5 分钟 TTL 自此降级为 SSE 断线/离线窗口的兜底（ADR-0029「事件丢失窗口=回到 TTL 兜底」，事件驱动为主 TTL 兜底为辅）。⑤**验证**：`:core:data:testDebugUnitTest` 235 用例、`:feature:favorite:testDebugUnitTest` 13 用例、`:feature:home:testDebugUnitTest` 30 用例全绿，`:app:assembleDebug` 通过。新增：DataFreshnessSignal 4 用例（三计数互不串扰/onOpen bump/快照稳定）；ServerEventConsumer 4 用例（帧解析经 `EventSources.processResponse` 直驱真实 ServerSentEventReader——hello/scan.progress 忽略、三类接线事件计数、onOpen bump、EOF onClosed；生命周期 fake Factory + 虚拟时间——登录即连 URL 断言/退避序列 3s→6s→12s→24s→30s 封顶/连接成功重置/登出取消连接且不再重连/地址未配置循环退出不空转）；两 Tracker 各 2 信号用例（关联计数增长即不新鲜/重新拉取重采样恢复/无关计数不触发/TTL 与信号双兜底独立）；两 VM 各 1 信号消费用例（信号到达后返回即静默重拉/换 seed）。UI 层零改动。（执行子代理 GLM-5.3-Flash；主代理补记：`feature:detail` 的 `DetailViewModelTest` 构造点漏适配 tracker 信号参数，本地未跑该模块测试未揭、CI 首跑编译拦截，`771dc2f` 补齐——教训入档：跨模块签名变更须 grep 全部构造点，不只改过的模块。）

## fix(server): 扫描变更失效缩略图+跨卷移动失败清理半截文件——外部换图自愈与幽灵残缺资产根治（2026-10-01 第四百一十八笔）

执行 AI：GLM-5.3（主代理，executor+reviewer 子代理协作）

- **动机（第三轮排查确认两个服务端缺陷）**：① 缩略图缓存键 = SHA-256("v2:assetID:size") 不含内容信号，生成侧 `os.Stat` 存在即命中、响应头 immutable 一年，而失效联动只覆盖资产"永久消失"四入口——用户在 NAS 文件管理器**原地替换文件**（外部直改是 ADR-0004 支持的工作流）后，扫描器更新了资产元数据（size/mtime 变化重入库），三端却永远拿到旧内容的缩略图/海报帧，永不自愈。② `filing.MoveFile` 跨卷复制回落（EXDEV→copy+删源）中途失败时目标端留半截文件，回收站三个搬运点均不清理——删除入站失败留**无 meta 的隐形残缺件**（列表/清扫/指标全按 `*.meta.json` 遍历，对它不可见，只有清空回收站才带走）；恢复出站失败把半截文件落**库内目标路径**（重试恢复时目标名被占自动改名，残缺件与完整件并存，扫描器把残缺件注册成坏资产）；meta 写失败回滚再失败同款残留。
- **修法**：① 扫描器两条重入库路径（全量扫描重探测 Updated 分支 + watch 增量 processFile 更新分支）在 UpsertAsset 成功后调 `thumbnail.DeleteAssetThumbs(assetID)`（用 RETURNING 的身份保留 asset_id；新增入库不失效——新 asset_id 名下不可能有历史缓存），接线经 `scanner.SetThumbsInvalidator`（main 单点；后置 setter 而非 New 参数：保持既有五参签名稳定，与装配层既有 SetScanner 同款模式）。缓存键公式/immutable 响应头/迁移零改动——只把"变更"信号接到既有"失效"上。② `move.go` copyFile 在 dst 已落盘后的任何失败（复制中断/收尾写失败）当场 `os.Remove(dst)`（best-effort，失败仅默认 logger 警告，KDoc"最坏残留不完整副本"表述同步废止）；导出 `filing.RemovePartialCopy(src, dst)` 供调用点兜底——只删**严格小于源**的确定残缺副本，"复制已完整、仅删源失败"形态下 dst 是完好副本宁留勿删（盲删=销毁已成功搬运的用户数据）；`trash.go` 三搬运点失败路径各接 `cleanupMoveResidue`（入站清 trashFile、恢复清 target、回滚清库内原路径；回滚场景 trashFile 本体故意保留——彼时唯一完好拷贝），清理失败只 warn 不改变错误上抛语义。
- **测试**：`go test -count=1 ./...` server 17 包全绿；`golangci-lint run` 0 issues。新增七组——scanner 侧 `TestScanChangeInvalidatesThumbs`（真 Generator+临时目录假缩略图全档位×webp/jpg：无变更复扫不误失效、变更重扫后磁盘条目真被删）与 `TestWatchChangeInvalidatesThumbs`（增量路径同联动且失效 ID 恰为变更资产，新增/删除不误触发）；filing 侧 `TestCopyFileFailureCleansDst`（copyBody 包内替身注入"复制中断/全写字节仍报错"两形态：源原状、dst 被清；EXDEV 完整 MoveFile 链路维持文件头既有的"真实双挂载点端到端验证"立场）与 `TestRemovePartialCopy`（半截删/等大小疑似完整留/目标不存在视为已清理）；httpapi 侧三搬运点各一组（`moveFile` 包内替身注入"目标端写半截后失败"：入站 500 且回收站零残留+库行保留；恢复 500 且库内目标无残留+meta 与回收站本体保留可重试；回滚 500 且库内半截被清+trashFile 完整副本保留+库行未删）。
- **协作记录**：主代理定稿改动清单，executor 执行（代码+测试+CHANGELOG/HANDOVER 文档同步）。（GLM-5.3 主代理）

## fix(web): 上传队列模块级单例化——切路由不再取消整队 + SSE 重连失效根查询（2026-10-01 第四百一十六笔）

执行 AI：GLM-5.3（主代理，executor+reviewer 子代理协作）

- **动机（Web 端生命周期与数据新鲜度两缺陷）**：① 上传队列与 XHR 句柄全活在 `use-upload.ts` 组件级 `useRef`，切路由（组件卸载）自动 abort 整队——用户传大文件中途点去其它页，在传 abort + 排队全标 canceled，几 GB 白传；原「W-1 冻结：上传是次要功能」的论证已随上传成为主通道失效（直传化/自动挂靠/目录递归连续多批加强），冻结解除。② SSE 断线窗口（服务重启/代理超时）错过的 `library.changed` 等事件在重连成功后不失效任何查询，列表静默陈旧。
- **修法**：① 队列提升为 React 生命周期之外的模块级单例 store（react-query client 同款思路）——新建 `web/src/lib/upload-queue-store.ts` 持有全部队列状态（items/各条 XHR 句柄/串行泵状态机/运行选项），暴露 enqueue/cancelAll/clearFinished/subscribe/setOptions/attachQueryClient，订阅-通知纯 TS 零新依赖；`use-upload.ts` 改为订阅层（`useSyncExternalStore`，React 18+ 标准 API，api-client token store 同款模式），返回签名不变 + 类型与 `BYTES_PER_MB` 原 re-export，调用方（UploadWorkbench/UploadQueueTable）零适配。**abort 只发生在用户显式「全部取消」**；401/登出处置保持既有语义不变（XHR 401 条目逐个失败落终态、服务端 message 透传、泵继续拾取下一条，不整队 abort；挂靠走生成 SDK，其 401 经既有全局拦截器广播 onAuthFailed 由 AuthGate 接管）。上传入库的本地失效（资产/库/目录/作者四根键）改经 store 持有的 QueryClient 引用执行——后台在传队列的失效不再依赖工作台挂载。页面刷新仍会丢队列（浏览器语义，XHR 句柄不跨页面存活）；刷新存活/断点续传走 IndexedDB 持久化 + 服务端 tus 分片端点（ADR-0028 已就绪）——另立项记档，本批刻意不做。② SseBridge 的 `library.changed` 八根键失效映射抽成单源函数 `invalidateLibraryContent`，`use-sse-events` 新增 `onOpen` 通道（lib/sse 既有 onOpen：每次 HTTP 200 且开始读流都触发，含首次与每次重连成功），重连成功即以同一映射失效根查询重新校验本地缓存（主流语义；首次连接也触发=多拉一次，幂等无害）。
- **测试**：web vitest 206 用例全绿（新增 `upload-queue-store.test.ts` 四组：未选库整批拦截零出网+逐条回调 / 大小上限前置拦截零出网 / cancelAll 在传 abort+排队 canceled+订阅通知到达且 cancel 不触发 onItemSettled / 2xx 落 done+回调触发；XHR 用可编程假件不 mock）；`npx tsc -b` 零错误；`npm run build` 通过；oxlint 0 errors（改动文件零警告）。行为说明：切路由后后台传完的 toast 照常全局弹出（sonner 全局挂载，onItemSettled 闭包仅引用模块级依赖）。
- **协作记录**：主代理定稿改动清单，executor 执行（代码+测试+CHANGELOG/HANDOVER 文档同步）。（GLM-5.3 主代理）

## feat(api): 断点续传上传端点（tus 风格分片，ADR-0028）——弱网大文件续传地基（2026-10-01 第四百一十七笔）

执行 AI：GLM-5.3（主代理，executor+reviewer 子代理协作）

- **动机**：现状上传 = 整文件单发 `POST /assets/upload`（octet-stream 流式），弱网传大文件（NAS 场景主力：手机直传 2GB 视频）中断即从零重传——Web 端失败不重试，Android 端 WorkManager 重试也是整文件重来。主流方案 = tus/分片续传；本批落协议与服务端，并同批完成 **Android 端按阈值分流接入**（见下方 Android bullet）与 **Web 端按阈值分流接入**（见下方 Web bullet；直传通道保留）。**同批补齐（dir 入协议收口）**：`CreateUploadRequest` 增可选 `dir`（与直传 dir 参数逐字同语义：库内相对路径、空/缺省 = 库根；create 经与直传同一入口 resolveUploadDir 校验规范化后存入会话、complete 按其落位子目录）——初版"协议无 dir、恒落库根"迫使子目录大文件整通道放弃续传（Android 被迫"有 dir 一律直传"），而 Web 拖拽目录上传与 Android 带目录目标都是真实主链路，故协议补齐、Android 分流回归纯 size 阈值（演变记档 ADR-0028 决策 7）。
- **协议（5 操作 3 路径模板，全局 bearerAuth 无豁免）**：`POST /api/v1/uploads` 创建会话（body {libraryId, fileName, size 必填 + contentType/dir 可选——dir 同批补齐，与直传 dir 参数逐字同语义，见动机 bullet}，创建即做可前置校验：文件名清洗〔直传同规则〕/dir 目标目录校验规范化〔直传同一入口 resolveUploadDir，穿越形态 create 即 400〕/扩展名白名单/size ≤ 上限/库存在且启用/autoAccept 同闸，201 回 `UploadSession{id, offset, size}`）；`GET /api/v1/uploads/{id}` 断点探测权威 offset（未知/过期 404）；`PATCH /api/v1/uploads/{id}?offset=` 追加 octet-stream 分片（offset 不等权威值 **409 响应体即权威 UploadSession**、累计超声明 size 400、单片超 32MB 413〔选型记档：载荷过大用 413，声明不符用 400〕、磁盘写满/读流中断 500 且会话保留可重试）；`POST /api/v1/uploads/{id}/complete` 对**拼装完成的整文件**复跑直传同一套终检（filing.ValidateUpload 四道：扩展名/魔数嗅探/最终大小/文件名 + 目标路径穿越）后入库，201 回与直传同构 AssetDetail；`DELETE /api/v1/uploads/{id}` 放弃 204。**同名冲突语义与直传通道逐字一致：ResolveConflict 自动重命名 "基名 (2).ext"、永不 409**（DOMAIN_RULES §9 既有口径——基线任务书所述「conflictMode 同名二选一」经核实全仓不存在，执行时经确认按现状落，记档 ADR-0028 决策 3）。
- **服务端**：新包 `server/internal/uploadsess/`（doc.go 记职责）——内存会话注册表（每会话一把锁串行 PATCH 追加）、分片临时文件读写（**DataDir/uploads-tmp** 具名常量，不入库目录：未过四道校验的半成品绝不进媒体面；.part 后缀在媒体白名单外）、过期清扫（无活动 ≥24h〔具名常量〕连临时文件回收 + 孤儿 .part 清理，**清扫不 bump 修订号**——未产生资产）。complete 走与直传同构的关键段：库锁外预置同卷临时名（跨卷回退全量拷贝）、锁内 ResolveConflict+rename 原子落位；成功后删临时文件与会话，失败保留可重试（complete 尝试计入过期时钟）。**入库复用既有 ingest 管线**：从 upload.go 抽共享 `ingestPlacedUpload`/`assembleUploadDetail`/`resolveUploadPolicy`/`writeUploadValidationError` 四件（直传 handler 改为调用，行为零变化）——library.changed 照发（修订号经订阅照 bump，ADR-0026）、upload.done 照发、富化照跑、sysmon 计量同口径。清扫 daemon 与关停清理接线 main（StartUploadSweeper/CloseUploadSessions，仿回收站清扫生命周期模式）。会话只在内存：服务端重启全部失效、客户端重建（刻意不持久化，ADR-0028 记档；同批刻意不做：并行分片乱序上传、校验和去重；分片通道目标目录初版刻意不做、同日随 dir 入协议补齐，见动机 bullet）。
- **Android 端接入（feat(app)，同批补记；执行：GLM-5.3-Flash 执行子代理）**：UploadWorker 入口阈值分流（具名常量 [UploadRouting]：≥16MB 走分片会话流——dir 入协议后分流回归纯 size 判定，子目录目标同样续传、create 透传 dir；<16MB 走既有直传一字节未动。初版「有 dir 一律直传」〔协议无 dir 期临时限制，ADR-0028 决策 6〕随协议补齐同日撤销，演变记档决策 7）；分片 8MB，每轮 WorkManager 重试（含首次）先 GET 探测服务端权威 offset 再续传——会话 id 记进程内 UploadSessionRegistry〔本地不存 offset；进程死亡丢表 = 下轮重建会话从 0，服务端孤儿 24h TTL 清扫兜底〕；PATCH 409 用响应体权威 offset 立即重同步继续（不算失败、不重探测）；会话 404（过期清扫/服务端重启，探测/分片/完结三处）自动重建会话从 0 续传（日志记档 + 单次运行上限 2 次防死循环）；4xx 校验类失败落既有 Permanent 文案透传不重试；用户取消 DELETE 会话（best-effort）+ 既有取消终态；complete 资产响应与直传同构，挂靠/归档后处理管线与终态状态机零改动复用；进度按分片粒度推进（每片一跳，UI 语义粗粒度化记档）。传输层沿用直传冻结口径的 @UploadClient OkHttp 直连（SDK `apiV1UploadsIdPatch` 只收 java.io.File 与 content:// 源冲突、SDK 4xx 异常不带 Error 响应体、409 权威 offset 需自行解析，KDoc 记档）。测试：新增 `UploadRoutingTest` 5 用例（阈值两分支含恰达边界/子目录不改变判定）与 `ChunkedUploadSessionTest` 12 用例（全链分片序列与分片粒度进度/中断续传先探测/409 立即重同步/探测·分片·完结三处 404 重建/重建超限不死循环/校验与创建 4xx Permanent/循环顶与写流两处取消 abandon/dir 透传 create 含重建路径），`:core:data:testDebugUnitTest` 223 用例全绿；`:app:assembleDebug` 构建通过。
- **Web 端接入（feat(web)，同批补记；执行：GLM-5.3-Flash 执行子代理）**：`upload-queue-store` 发送入口按 size 阈值分流（具名常量 `CHUNKED_THRESHOLD_BYTES`/`CHUNK_BYTES`，与 Android UploadRouting 注释互指双同步）：≥16MB 走新文件 `web/src/lib/upload-chunked.ts` 分片会话流（8MB 分片）——create 透传入队快照 dir（空串=库根，与直传 dir 参数逐字同语义；拖拽目录条目同享续传）→ 每轮（首次/每次重试/重建后）先 GET 探测服务端权威 offset → `File.slice` 按片 PATCH、用响应权威 offset 推进（进度每片一跳映射既有 percent 字段）→ offset 达 size 后 complete，201 资产响应交与直传完全相同的后处理（资产/库/目录/作者四根键入库失效 + authors 单项/sources append 挂靠序列，零改动复用）；PATCH 409 用响应体权威 offset 立即重同步继续（不算失败）；会话 404（过期被清扫/服务端重启）重建会话从 0 续传（单条上限 2 次防死循环）；网络/5xx/超时指数退避自动重试（1s/2s/4s，同条上限 3 次，每次重试先探测续传；超限终态失败带服务端文案、不自动重新入队）；「offset 连续 8 轮未推进」防御中止（服务端异常实现兜底）；「全部取消」abort 在途请求 + best-effort DELETE 会话（取消旗标同时覆盖退避等待等请求间隙）；<16MB 直传通道一字未动，组件层零改动（分流对 UploadWorkbench/UploadQueueTable 透明）。传输层选型记档（`upload-chunked.ts` 文件头，参照 Android @UploadClient 直连前例）：整通道五操作 XHR 直连不走生成 SDK——① hey-api 生成 client 请求选项无 abort/signal 支持（取消在途分片做不到）；② 全局 client 响应拦截器把一切非 auth 端点 401/403 广播为鉴权失效（AuthGate 清 token），而本通道 create 403 UPLOAD_DISABLED 是正常业务响应，直传通道既有口径为条目级失败透传 message，走全局 SDK 会误踢登录。测试：新增 `upload-chunked.test.ts` 10 用例（阈值纯函数与入队实流两分支含恰达边界/全链 create 带 dir→探测→三片 PATCH offset 序列+片长+分片粒度进度→complete→挂靠后处理断言/409 权威重同步/网络中断退避重试先探测续传/重试上限 4 次 PATCH 全败终态不再出网/404 重建成功路径/404 重建上限防死循环/4xx 立即终态透传 message 零重试/取消 abort+DELETE 会话）；web vitest 216 用例全绿（原 206 + 新增 10）；`tsc -b` 与 `npm run build` 通过；oxlint 改动文件 0 warnings 0 errors。
- **测试**：新增 `uploads_test.go` 十一组（dir 批补齐 dir 落位子目录/穿越五形态拒绝两组）——分片全链（create→PATCH×3 跨 offset 续传含 GET 探测→complete 201 资产可见+磁盘字节一致+修订号 bump+临时目录清空+会话终结）、offset 陈旧 409 权威回滚与重同步续传、累计超 size 400（会话保留 offset 回滚）、单片 32MB+1 → 413、create 关口四连拒（白名单外扩展名/size 超限 413/库不存在/size<1）、complete 终检（未传完 400；.jpg 装文本 400 且会话保留、库无落盘）、complete 同名自动重命名 "same (2).jpg"（与直传 TestUploadConflictRename 同断言口径）、DELETE 后 GET 404 + 注入时钟过期清扫、401；顺手清偿 ADR-0027 审查遗留 P2——`media_test.go` 补 TestMediaOrigCacheControl（orig 直链 200 成功响应必须带 `Cache-Control: private, max-age=<窗口秒数>`）。`go test -count=1 ./...` server 17 包全绿；golangci-lint run 0 issues。协议先行：openapi.yaml 先行，`make sdk` 三端重生成（api/sdk.lock 235 条），web tsc -b 与 android :sdk:compileKotlin 编译验证通过（纯新增操作，web/android 业务代码零改动零连锁）。
- **协作记录**：主代理定稿改动清单（同名冲突语义经核实后按现状拍板：自动重命名），executor 执行（协议+服务端+SDK 重生成+测试+文档七处同步：ADR-0028/INDEX/GUIDE_API/SECURITY/CAPABILITY_MAP/HANDOVER/CHANGELOG）。（GLM-5.3 主代理，executor 子代理）

## fix(app): 收藏/点赞 ON_RESUME 跳过门 TTL 化——跨端改动陈旧至多 5 分钟自愈（2026-10-01 第四百一十五笔）

执行 AI：GLM-5.3（主代理，executor+reviewer 子代理协作）

- **动机**：Android 收藏页/首页在 ON_RESUME 时依据进程内指纹决定是否跳过重拉列表（FavoriteMutationTracker/LikeMutationTracker，任务V V1/任务I I1），但指纹只统计**本进程本端**的收藏/点赞改动——服务端对收藏/点赞不发事件（/api/v1/events 只有 scan.progress/library.changed/thumbnail.progress/upload.done），Android 端也不消费 SSE，Web 端或另一设备改了收藏后 Android 指纹永不变化，ON_RESUME 一直跳过重拉，列表与库不符直到手动下拉或杀进程。
- **修法（主流 staleTime 语义，TanStack Query refetchOnWindowFocus 同款思路）**：跳过条件从「指纹未变」扩为「指纹未变 且 距上次成功列表拉取 < STALE_AFTER_MS（具名常量 5 分钟，core:data 包级单源）」——两个 Tracker 新增 noteListFetchCompleted（列表成功落地打点，含翻页追加，失败不打点）与 isListFetchFresh（0 哨兵=从未成功拉取恒不新鲜，放行兜一次重拉；严格小于，恰越界即陈旧），时钟经 tracker 公开 clockMs 缝隙注入可测（Hilt @Inject constructor 无法提供函数类型绑定，HomeViewModel.clockMs 同款裁决）；消费点 FavoriteViewModel.onResumed / HomeViewModel.onHomeResumed 相与判定，历史 KDoc（SSE 无 like/favorite 事件、本地感知是唯一路径）保留原陈述并追加 TTL 兜底语义。行为变化：跨端改动后至多 5 分钟 + 一次 ON_RESUME 自动纠正；TTL 内仍跳过，「纯浏览返回零网络零重组」优化保留；本端刚改过立即刷新语义不变；UI 零变化（纯数据新鲜度行为）。协议/服务端/Web 零改动。
- **测试**：`:core:data:testDebugUnitTest` 206 用例全绿（两 Tracker 各 +2 TTL 用例：从未拉取不新鲜/TTL 内新鲜/恰越 STALE_AFTER_MS 转陈旧/重新打点重置计时，假钟全确定）；`:feature:favorite:testDebugUnitTest` 12 与 `:feature:home:testDebugUnitTest` 29 全绿（各 +1 跳过门 TTL 集成用例：指纹未变 TTL 内返回不重拉/恰越过 STALE_AFTER_MS 静默重拉自愈/重拉落地重新打点恢复跳过；「指纹变了→立即重拉」由既有指纹门控用例继续锁定）；`:app:assembleDebug` 构建通过。
- **协作记录**：主代理定稿改动清单，executor 执行（代码+测试+CHANGELOG/HANDOVER 文档同步）。（GLM-5.3 主代理）

## fix(server): 签名直链 exp 窗口对齐——同窗 URL 恒定，浏览器缓存/ETag 生效，根治轮换击穿（2026-10-01 第四百一十四笔）

执行 AI：GLM-5.3（主代理，executor+reviewer 子代理协作）

- **动机**：服务端对每个列表/详情响应把全部媒体 URL 重新签名（`exp=now+TTL` 精确到秒），同一资产同一尺寸的 URL 字符串随每次响应变化——浏览器 HTTP 缓存按完整 URL 做键，URL 每次都变 → `/media/**` 缓存永久不命中：Web 端零补偿全量重下，服务端早已实现的 ETag/If-None-Match 因缓存键不存在永远走不到 304；Android 端被迫自建「剥签名键栈」（`SignedMediaCacheKeys` 剥 exp/sig、`SplitDiskCache` 分池、`PrefetchDiskProbe` 探测），历史上已引发三轮真实 bug（第二百六十六/三百六十五/四百一十一笔）。修法 = S3/CloudFront 签名 URL 的「窗口取整」同款思路（ADR-0027）。
- **改动（ADR-0027）**：exp 不再是 now+TTL，而是「下一个窗口上界 + 一个 TTL」（`mediaURLExpiry` 纯函数，`assets_media_url.go`；窗长=token_ttl 缺省 6h，对齐 Unix 纪元整点，一个旋钮）——同一时间窗内所有响应生成**逐字节相同**的 URL，浏览器缓存与 ETag/304 自然生效；有效期恒 ∈ (TTL, 2×TTL]（最短仍一个完整 TTL，绝不出现取到即近过期的 URL——纯取窗口上界的备选方案在窗口尾会产出几分钟内过期的 URL，已否决）。验签侧（auth 包签名消息构造/校验顺序）零改动；URL 格式与参数未变，**协议面零改动**（openapi.yaml 与三端生成物未动）。`/media/orig/**` 此前完全无缓存头，补 `private, max-age=<窗口秒数>`（max-age 取实际生效 ttl——缓存条目寿命不超过 URL 有效期下界）；缩略图直链既有 immutable 长缓存不动。**Android 剥签名键栈保留不退役**：窗口轮换下改用全 URL 键每日 4 次全量失键，剥 exp/sig 稳定键严格更优（键不含 exp 值天然无感），三端零适配。
- **测试**：新增 `assets_media_url_window_test.go` 四组——同窗恒定（10:23 与 11:59 同 exp）/跨窗轮换（11:59 与 12:01 异 exp）/有效期界（全天 15 分钟步进含边界端点 + 亚秒尾时刻，恒 (ttl, 2*ttl]）/URL 级恒定（同窗 origUrl/thumbUrl 逐字节相同、跨窗必变）；存量适配 1 处——`browse_test` 过期 403 用例时钟前移 7h→2*DefaultTokenTTL+1min（窗口语义下有效期上限 2*TTL，越过上界才保证任意窗口签发的直链已过期，403 行为本身不变）；`go test ./...` server 17 包全绿；golangci-lint 0 issues。
- **协作记录**：主代理定稿改动清单，executor 执行（代码+测试+文档六处同步：ADR-0027/INDEX/HANDOVER/SECURITY/GUIDE_API/CHANGELOG）。（GLM-5.3 主代理）

## fix(app): 预取跳过记录捆绑服务器标识+SKIP 前抽样核对——换服务器撞号与缓存漂移双修（2026-09-30 第四百一十三笔）

执行 AI：GLM-5.3（主代理，executor+reviewer 子代理协作）

- **动机（第四百一十二笔复查确认两个真实缺陷）**：① 换端撞号——预取修订号记录只有 Long 值、不记是哪台服务器，本地端与 NAS 是独立计数器且播种基线同为 `COUNT(assets)+1`，两端库规模相近时修订号可相等，切服务器后被错误 SKIP，新服务器一张图没缓存过也永远不预取（旧文档「换服务端不区分多拉无害」论证失实：真实风险是错误 SKIP 而非多拉）；② 缓存漂移——Coil 磁盘缓存池 maxSize LRU 驱逐/系统存储紧张清整个 cache 目录/备份恢复把服务端 `library_revision` 一起回滚，都不动客户端记录，「记录说全量、实际缺一片」且修订号不变永不自愈。修法失败方向全部落在「多拉一轮无害」，协议与服务端零改动。
- **改动（ADR-0026 修订节，决策 7/8）**：`PrefetchRevisionStore` 记录升级三元组（`last_prefetch_revision` 修订号 + 新增 `last_prefetch_server` 服务器 base URL 原串 + 新增 `last_prefetch_sample` 随机抽样），三键同一次 DataStore edit 原子写入；原串精确比较不规范化（同实例换地址多拉一轮无害）；存量只有旧版修订号键的记录按无记录迁移（下轮 FULL 后写入完整三元组）。`PrefetchRevisionGate` 扩为五分支（serverRev null→FULL / record null→FULL / **serverKey 不等→FULL 换端必走全量** / 相等→SKIP / 其余→FULL），缺省永远偏 FULL 口径不变。新增 `PrefetchSampleGate` 纯函数（无 IO）：`SAMPLE_SIZE=50`（用户拍板）、`MISSING_THRESHOLD_PCT=20` 整数运算、空样本恒维持 SKIP。`ThumbnailPrefetcher` 接线：门判 SKIP 后先用记录端同事务落盘的随机样本经 `PrefetchDiskProbe` 逐条本地磁盘探测（stableKey 剥 exp/sig，URL 带过期签名不影响），缺失达阈值降级全量补拉并轮末写回新样本自愈；`recordRound` 同事务记「修订号+服务器标识+抽样」（空库轮照记空样本）；空样本/样本读失败维持 SKIP（revision 读成功说明 DataStore 基本健康，取舍入注释）。
- **测试**：`:core:data:testDebugUnitTest` 202 用例全绿（gate 6 分支含换端撞号回归锁 / store 6 含三键同写、样本回读、clear 三键全删、旧版 Long 键迁移为 null / sampleGate 7 含 20% 整除边界与固定种子确定性）；`:feature:manage:testDebugUnitTest` 54 全绿（清池失效接线不回归，桩随接口机械适配）；`:app:assembleDebug` 构建通过。
- **协作记录**：主代理定稿改动清单，executor 执行（含 feature:manage 测试桩因接口扩签名的连带最小适配），文档四处同步（ADR-0026 修订节/INDEX/HANDOVER/CHANGELOG）。（GLM-5.3 主代理）

## feat(api): 库内容修订号端点+客户端预取整轮跳过（ADR-0026）——大库预取零列表请求（2026-09-30 第四百一十二笔）

执行 AI：GLM-5.3-Flash（主代理，executor×2 + reviewer 子代理协作）

- **动机（用户拍板"未雨绸缪"扩规模）**：第四百一十一笔后预取链路唯一剩的大头 = 每轮冷启动全量分页拉资产列表（10 万资产 = 500 页请求），且 `ALL_THUMBS_MAX_PAGES` 4 万硬上限会静默截断。引入全局库 revision：库没变 → 整轮零列表请求；变了才拉列表+探测补缺。**刻意不做** since 增量清单/墓碑/游标（磁盘探测短路已把变更轮成本压到毫秒级，状态机级复杂度买到的是边际收益——规模到十万级或多端同步需求出现再立项）与 SSE 推送预取（进程存活不可靠）。
- **服务端**：`GET /api/v1/library/revision` → `{revision: int64}`（Bearer 鉴权，路径 66→67，三端 SDK 重生成 + sdk.lock 更新）。全局单计数器持久化 kv_settings 键 `library_revision`（复用 migration 0003 表，无新 migration；键缺失时 `COUNT(assets)+1` 原子播种）；自增 = SQL 单条 UPDATE 原子 + 应用层互斥串行（`server/internal/libraryrevision/`）。bump 收口：发 `library.changed` 的全部写路径（扫描全链/上传/回收站移入与恢复/整理移动/标签/库删除/库开关/手动扫描）在事件订阅一处 bump；不发事件的 4 条路径（物删单条/清空回收站/到期清扫 removed>0/qimeng-backup 导入）显式 bump；`bumpLibraryRevision` 全仓唯一出口、失败只告警（弱失败=客户端至多多拉一轮）。语义边界：只保证资产集合面（收藏/进度/作者展示字段不保证——当前唯一消费方只用 thumbUrl）。
- **Android**：`PrefetchRevisionGate` 纯函数四分支（serverRev null→FULL 含旧服务端 404 降级 / lastDone null→FULL / 相等→SKIP / 不等→FULL）+ `PrefetchRevisionStore`（DataStore 键 `last_prefetch_revision`，接口化可打桩）+ `LibraryRevisionRepository` 端口（`revision(): Long?` 永不抛出）。`ThumbnailPrefetcher` 接线：就绪探针+计费网络门后拉 revision 比对，SKIP 置新终态 `PrefetchUiState.Skipped`（UI 显示"缓存已是最新，本轮无需同步"，不调 allThumbUrls）；FULL 轮完成写回轮首 revision（null 不写保旧值，空库轮也写）。**清空任一缓存池必须失效本地 revision 记录**（`ThumbnailCacheViewModel.clearPool` → `store.clear()`，reviewer P1 修复：否则清空后库静态即永久 SKIP 预取失效）。降级全部"多做无害"方向。
- **测试**：server 17 包全绿（libraryrevision 4 用例含 32 并发自增零丢失；httpapi 全链精确计数「上传→移入→物删→清空」base+1..+6；401 鉴权锁定）；Android core:data 192 / feature:manage 54 / `:app:assembleDebug` 全绿（gate 5 分支 + store 4 含 clear 失效 + VM 清空接线桩）；golangci-lint 0 issues（depguard 红线未触碰）；生成物哈希与 sdk.lock 逐一比对一致（reviewer 亲跑复核）。
- **协作记录**：executor-A（协议+服务端+生成物）、executor-B（客户端+UI）、reviewer（对抗审查亲跑复核，打回 P1 清缓存失效缺失 + P2 注释失实，均已修复；P2 记档：一次扫描可能双发布使 revision 跳 2 系既有行为方向安全）。真机端到端验证因设备断开未跑成（代码层验证全闭环），用户重连后首次打开 App：库未变时预取状态应显示"缓存已是最新，本轮无需同步"。（GLM-5.3-Flash 主代理）

## fix(app): 缩略图预取磁盘探测短路+分池路由来源化——反复缓存根治与本地池架空修复（2026-09-30 第四百一十一笔）

执行 AI：GLM-5.3-Flash（主代理）

- **根因（真机取证）**：用户报「App 反复缓存」。真机（真机）数据实锤：缓存主池 6371 条全部当日写入（凌晨 04 时 1534 条 + 傍晚 18 时 4827 条 + 白天零星），journal 累计 CLEAN 18353 ≈ 现存条目 2.9 倍、REMOVE=0（无 LRU 驱逐，远未满 1GB 档位），当前进程启动后 journal 尾部全是 READ（预取轮再次触发）。结论：`ThumbnailPrefetcher` 登录后自动全库预取**无断点游标**（KDoc 有意简化口径），每次冷启动/重新登录都从头全库重扫，且单轮频繁中断（进程被杀后重开 App 即重触发）——已缓存条目虽不重复下载（键稳定，历史两轮修复有效），但每轮仍做全量列表拉取 + 全库逐条磁盘打开与位图解码，缩略图缓存页进度条反复从头跑全库，观感即「反复缓存」。**后续 datastore 取证补正**：用户日常连接为本地端模式（`server_url=http://127.0.0.1:18430`），该池条目实为本地端来源经分池 bug 错放（见第三条），连本地端时回环下载速度快（18 时轮 4827 条约 3 分钟），全程真实重复下载发生过多轮。
- **主修：预取磁盘探测短路**（新增 `prefetch/PrefetchDiskProbe`）：单条预取前按 `SignedMediaCacheKeys.stableKey` 直查 `SplitDiskCache.openSnapshot`（键与写入侧同源，即取即闭；Coil DiskLruCache `Entry.snapshot()` 源码核实——物理文件缺失的幽灵条目会正确返回 null 并 REMOVE，探测语义安全），命中即跳过 execute（不发请求、不解码、只计入进度）；探测失败按未缓存退化为原路径，正确性不受影响。中断重跑/缓存齐全轮次从「全库重扫」退化为「只补缺口」，齐全轮次秒级收口；轮终在 logcat `QimengCache` 记档 `total/磁盘命中/网络下载` 汇总。`ThumbnailPrefetcher` 注入 `dagger.Lazy<SplitDiskCache>`（保持惰性装配拍板）。
- **顺手发现并修复：分池路由被剥 host 键架空（本单最关键一笔）**：U10-5（第三百六十五笔）剥 host 后签名直链稳定键形如 `/media/thumb/<uuid>?size=md` 不含来源信息，`resolveCachePool` 原「非 http 键兜底 NAS」口径把**本地端来源**的稳定键全部错路由进 NAS 池（分池机制自剥 host 起形同虚设：真机实证 `image_cache_local` 仅 1 文件而主池 6371 条；若两端资产 id 重叠即触发批S5 拍板要防的串图）。修复：非 http 键跟随「当前连接来源」路由——`SplitDiskCache.updateActivePool`（AtomicReference，默认 NAS）+ 新增 `coil/CachePoolBinder`（EventSyncBootstrapper 同款接线：QimengApplication.onCreate 观察 `AuthRepository.serverUrl`，本地端预设→LOCAL 其余→NAS，实时跟随切换）；http 键（含 host 的完整 URL 键族）仍按键内 host 判定。
- **测试**：`SplitDiskCacheTest` 新增 3 组（剥 host 稳定键跟随来源/http 键优先按键判/族外非 http 键跟随来源，16 全绿）；新增 `PrefetchDiskProbeTest` 4 组（真实 RealDiskCache + TemporaryFolder：命中/未命中/非签名家族恒 false/探测跟随当前来源池切换）。`:core:data:testDebugUnitTest` 与 `:app:assembleDebug` 全绿。
- **真机部署验证（真机，debug 覆盖装）**：① 分池来源化生效——重启后本轮预取 6350 条全部正确落入本地池（`image_cache_local` 0→6350 条/230MB，回环下载约 4 分钟），UI 缩略图缓存页报「本轮完成 6350/6350」；② 探测短路「只补缺口」实锤——本地池删 1 条重启后 CLEAN 6350→6351、文件数复原、新条目 mtime 即重启时刻，其余 6349 条零动作零下载；③ 全命中轮次零下载（NAS 池 journal 全程无新写入）。（设备 logcat 被厂商 ROM 限制，验证证据全部取自 run-as 文件系统 + UI dump）
- **遗留（待用户拍板）**：NAS 池 6370 条/474MB 为历史混合数据（本地端来源错放为主，可能混少量真 NAS 来源缓存），磁盘条目无法事后区分来源——建议用户连 NAS 前在缩略图缓存页手动「清空服务器缓存」一次（消除两端资产 id 重叠时的低概率串图风险 + 释放空间），重连后预取自动补齐；每轮开头 ~32 页（200/页）全量列表拉取仍在（`allThumbUrls` 口径不变）；若日后全库缩略图总量超过缓存档位，LRU 驱逐与全库预取会互相追赶，届时再议「上轮完成时刻门槛」或协议层库 revision。（GLM-5.3-Flash 主代理）

## chore(repo): 任务书总纲出库——docs 工作文档清空（2026-09-30 第四百一十笔）

执行 AI：GLM-5.3（主代理）

- **任务书总纲出库**（用户拍板「一起删除了」）：《docs/任务书-安全与UI升级总纲.md》删除——与 REQ 同属面向执行 AI 的工作文档，不随开源发布；全库零引用（HANDOVER 无指向），零连带。
- **防御扩盖**：`.gitignore` 任务书模式 `任务书*.md`（任意层级，覆盖根目录与 docs/）。
- 备注：后续升级工作若仍需总纲编排，按 v1.2 惯例在仓库外重建即可（gitignore 已保证不再入库）。（GLM-5.3 主代理）

## chore(repo): 开源工作文档退役——REQ 需求书出库+gitignore 防御加盖（2026-09-30 第四百零九笔）

执行 AI：GLM-5.3（主代理）

- **REQ 需求书出库**（用户拍板）：根目录《REQ-上传指定作者与来源.md》删除——功能已随 ADR-0023/0024 全部落地收官（上传直传+资产编辑页挂靠+通用来源词表），需求书属面向执行 AI 的工作文档不随开源发布；全库无其他文件引用，零连带。
- **防御加盖**：`.gitignore` 补 `/REQ-*.md`（与 `/交接报告*.md` 同组），后续任务书/需求书不再误入公开历史。
- 备注：`docs/任务书-安全与UI升级总纲.md` 为进行中的活跃编排文档暂留（是否出库待用户拍板）。（GLM-5.3 主代理）

## docs(repo): README 去个人化表述——反馈区中性开场+顶部定位句去作者信息（2026-09-30 第四百零八笔）

执行 AI：GLM-5.3（主代理）

- **反馈与讨论区**：删「这是一个个人自用项目的开源」个人化开场，改为中性交流邀请（AI 协作工作流 + 三端 UI/交互两个主题、Issue 渠道与截图/录屏建议保留）。
- **顶部定位句**：「作者无编程基础」作者个人信息移除，改为项目性质 + 100% AI 制作的机制描述（协议先行 + 文档驱动 + ADR 可追溯）；前身注记保留。
- 复核：README 其余命中均为功能术语（「作者管理」媒体库概念、Issue 脱敏提醒），无个人信息残留。（GLM-5.3 主代理）

## chore(repo): 开源隐私收口——出厂词表与全库站点词中性化+仓库外目录指代中性化+交接文档防御（2026-09-30 第四百零七笔）

执行 AI：GLM-5.3（主代理）

- **背景**（2026-09-30 开源后审计第二笔）：脱敏首笔清除了口令/序列号/本机路径，但复审发现两类残留——①「通用来源词表出厂预填」及关联测试/文档含真实平台词（个人获取渠道偏好，36 文件约 300 处，含 server `vocabulary_prefill.go`/`parse.go`、web `source-options.ts`、android `SourceWords.kt` 等源码与三端测试）；②58 处仓库外工作区目录名指代（CHANGELOG/REVIEW/ADR-0017）。
- **站点词中性化**：统一映射为合成词（site-a~g/forum-c 系），源码出厂预填语义不变（仅示例词面变化）；`api/openapi.yaml` description 示例同步、`make sdk` 三端重生成+`api/sdk.lock` 指纹更新（229 条）；DOMAIN_RULES 多值筛选示例与来源口径、GUIDE_API 作者来源节、REQ 需求书示例词表同步。唯一行为适配：`vocabulary_prefill_test.go` 域名排除用例期望序随新词字典位次修正（排序口径 count 降序+字典序不变）。
- **目录指代中性化**：仓库外工作区目录名 →「工作区」（CHANGELOG 58 处、REVIEW 标题改 qimeng-media、ADR-0017 一处）；`llms.txt`「十条铁律」→「十四条」计数漂移修正。
- **交接文档防御**：仓库根《交接报告-作者联想弹层.md》（含真机序列号+本机路径，铁律14红线内容，未入库）按用户拍板删除；`.gitignore` 加盖 `/交接报告*.md` 防再次误入。
- 验证：server `go test ./...` 16 包全绿；web vitest 21 文件 202 测试全绿；android `:core:model:test` + `:core:data`/`:feature:upload`/`:feature:detail` testDebugUnitTest 全部 0 失败；全树复扫站点词/目录名零残留（生成物经重生成清除）。（GLM-5.3 主代理）

## feat(api): 批A 安全卫生收口——内嵌形态 dev-login 共享密钥门禁 + 维护页开发模式提醒条（2026-09-30 第四百零六笔）

- **dev-login 共享密钥门禁（SECURITY.md 规划项落地，防同机越权）**：Android loopback 端口全设备共享，同机其他 App 可连 `127.0.0.1:18430` 走免密登录拿管理员 token（App 持 MANAGE_EXTERNAL_STORAGE 放大后果）。三端落地：①协议——`POST /auth/dev-login` 新增可选 header `X-Qimeng-Dev-Secret` + 401 响应（`api/openapi.yaml`，三端 SDK 重生成 + sdk.lock 指纹更新）；②server——config 新键 `auth_dev_shared_secret`（env `QIMENG_AUTH_DEV_SHARED_SECRET`），配置后校验请求头（`subtle.ConstantTimeCompare` 恒时比对；顺序=限流→dev 404→密钥 401，401 在自动建户之前），未配置零行为变化（Web bat 免密/日常开发完全不变）（执行子代理）；③App——`EmbeddedServerConfig.generateDevSharedSecret()`（SecureRandom 32 字节 URL-safe）每次拉起子进程生成，注入 env + ServerConfigDataSource 纯内存槽（不落盘不进日志），`SdkAuthApi.devLogin` 同源带出（执行子代理）。
- **维护页「开发模式未关」提醒条（A3）**：`GET /system/status` 响应新增 `devMode` 布尔字段（cfg.AuthDevMode 透出）；Web 维护页顶部 dev 模式开启时显示警示条（严格 `=== true`，纯可见性不动免密机制；token 全用 --log-warn-* 既有变量）（执行子代理）。
- **文档修正（A2）**：HANDOVER「bat/compose 二选一」过时句改写（根 compose 已删、生产样例唯一权威 = deploy/docker-compose.yml）；SECURITY dev 模式位置指向修正 `_server-common.cmd` 单点；GUIDE_API dev-login 行补密钥门禁、system 行补 devMode；SECURITY「开发模式」节补门禁机制说明、:104 规划项标记已实现。
- 验证：server `go build`+`go test ./internal/httpapi/ ./internal/config/` 全绿（含密钥三分支/devMode 两态/env 三态新测试，取证代理 -count=1 复跑）；web build+test 全绿（202 测试）；android `make app-test` 全绿（取证代理复跑）。（GLM-5.3 主代理：协议/SDK/文档收口+取证裁决；执行子代理×3 施工）

## feat(app/web): 上传直传化收窄——暂存区整体退役+收件箱入口退役+维护页观感修复（2026-09-30 第四百零五笔）

- **上传直传化（2026-09-29 用户拍板「暂存了好像没意义啊，去掉吧」）**：双端暂存区退役，选完文件即传。App 侧 StagedUpload 暂存条目模型 / StagedItemEditor 逐项编辑器 / UploadStagingIngestor 摄取器整体删除，系统文件 SAF 多选与系统分享共用 submitUris 单管道（describe 解元数据 → 未选库提示不传 → 逐项判超限（拦截不出网，blockText 文案）→ 其余继承批次默认快照直接入队）；Web 侧 lib/staged-upload.ts 与 StagedUploadList.tsx 退役，工作台「暂存列表/逐项编辑/开始上传按钮」删除，添加文件（点击/拖拽）后立即上传。批次默认（目标库/作者/来源）双端保留持久化（core:model `StagingBatchConfig`，跨进程/隔天不丢），入队时刻快照继承——之后改默认只影响下一批；201 后自动挂靠口径不变（mode=append 不覆盖、失败不重试）。
- **收件箱入口退役（直传化连带）**：上传页「从收件箱导入」入口删除（收件箱自动进暂存区随暂存区失去意义）；「上传收件箱与归档」设置页收窄为「上传归档文件夹」单目标页（InboxSettings 收件箱卡与持久槽退役）；上传成功源文件归档维持 `<归档根>/<库名>/<原文件名>` 口径。
- **维护页观感修复（2026-09-29 用户反馈）**：客户端日志卡默认折叠只显最近 5 条（防同质错误刷屏淹没页面）；gauge/metric 两排网格列宽统一 210px（原 150/190 两套基准同视口下列数错位）。
- 备注：GET /assets/name-suggestions 协议面不动（业务侧随逐项编辑退役暂无调用方）；验证=web build+lint+test 全绿（21 文件 202 测试）+ android testDebugUnitTest BUILD SUCCESSFUL（2026-09-30 复跑）。（GLM-5.3 主代理收口提交；改动主体为 2026-09-29 执行会话遗留工作区）

## fix(server): COS 文件漏进首页推荐根治——零关联资产扫描收尾自愈（2026-09-29 第四百零四笔）

执行 AI：GLM-5.3-Flash（主代理：API 交叉取证/根因定位/自愈手术/测试/装机终验）

- **需求**（用户现场报告）：推荐页出现 COS 文件（DOMAIN_RULES §6 口径=推荐流恒排除 COS 作者关联文件），用户正在看该条目要求抓后台数据。
- **取证（服务端 API 交叉比对）**：推荐流候选全集 783 项 × COS 关联集合（cosOnly=1，5567 项）比对——恰 1 漏网者 `蠢沫沫/水色/蠢沫沫 -水色138.jpg`：cosWork='水色' 已赋（确认扫自 COS 库作品目录）、**作者关联为零**；不在 COS 集合（COS tab 同步消失）。`addedAt=23:52:20.791`＝上一服务会话被杀（装新包）前 8 秒——用户当时正整理文件夹，增量 watch 入库中途进程死亡。
- **根因**：`ingestCosFile` 的 `UpsertAsset` 与 `AddAssetAuthor` 是两条独立语句，半途失败/进程死亡留下「有资产无关联」漏网者；隔离判定按作者关联走（§6），无关联即漏进常规流；且轮询扫描对已存在文件跳过 re-ingest、永不自愈——`applyMoveMerge`「重扫自愈」注释对该形态不成立（重算只挂载在移动合并路径）。
- **修复（扫描收尾自愈网）**：新查询 `ListCosAssetsWithoutAuthor`（零关联 COS 资产；`instr(rel_path,'/')>0` 排除库根直放文件的合法无关联）+ `relinkOrphanCosAssets`（按当前 rel 首段重挂作者，失败 warn 下轮重试、幂等；成功广播 library.changed 作废推荐缓存——隔离判定变化对客户端是结构性变更）；挂载点＝`Scan` 收尾 cos 库分支（孤立作者清理之后）。事务加固（入库+关联单事务）评估后**不做**：需改 Scanner 构造器注入 conn，收益被自愈网完全覆盖（任何半途形态最迟下轮扫描自愈），记档备查。
- **验证**：新增 `TestScanRelinksOrphanCosAssets`（复现漏网形态→文件未变跳过 re-ingest 的复扫→收尾自愈重挂 cos_ 作者）；go test ./... 全绿；装机后手动触发 COS 库扫描，日志实证 `零关联 COS 资产已重挂作者 relinked=1 candidates=1`，推荐候选 783→782、带 cosWork 的推荐项归零。

## fix(app/server): 首页四症结实机取证根治——常驻层死 owner 闪退+推荐冷算 25 倍提速+下拉反馈与到底告知（2026-09-29 第四百零三笔）

执行 AI：GLM-5.3-Flash（主代理：双通道实机取证/根因定位/两端手术/测试/装机冒烟）

- **需求**（用户四条+实机两崩）：② App 下滑不会继续加载 ③ 刷新半天才加载新内容 + COS 下拉无效 + 排行榜显示不对；操作中两次闪退。用户授权全程实机取证（dropbox 崩溃记录 + 内嵌服务端日志 + 接口只读探测）。
- **闪退（两次 data_app_crash 同签名 `IllegalStateException: NavBackStackEntry destroyed`，全在相册页）**：常驻层 per-tab owner=NavBackStackEntry，切 Tab 走 `popUpTo(start){saveState}` 即销毁旧 entry 对象，空壳跳板只在组合时登记、销毁不清理——`tabEntries` 从此挂死 entry 给隐藏屏当 owner，隐藏屏任意一次重组（返回该 Tab 的同帧竞速等）都让 `hiltViewModel()` 在死 owner 上解析 VM 直接崩。修复（QimengNavHost）：DisposableEffect 挂 LifecycleEventObserver 把「entry 死亡」翻转成快照状态，死亡当帧即把该 Tab 真身撤出组合（SaveableStateHolder 保 rememberSaveable，重访经跳板重登记新 entry 原样复活；VM store 由 NavController saveState 保留）；为什么必须观察者而非重组时读 currentState——lifecycle 状态不是快照状态，不加观察者门控恰好漏掉致崩的那次重组。真机冒烟：切 Tab 往返×3+双击回顶+统计页，PID 恒定、dropbox 零新增。
- **刷新慢（服务端，实测冷算 3.6~4.5s）**：/recommendations 每新 seed 全量重算，`ListAssetsRecommendInput` 782 行×~10 关联子查询在手机 SoC 上秒级——页缓存默认 ~2MiB 放不下 17MB 库，冷下探全走闪存。热缓存命中 0.02s 证明装配/计数回写不贵。修复三件套：① view_events 五个子查询改单遍预聚合派生表 LEFT JOIN（GROUP BY 保行数不变=无扇出；WHERE 仍不触碰派生别名——sqlc v1.31.1 解析限制铁律，cos 过滤分支保持关联 EXISTS 原形）② store DSN 加 `cache_size(-32000)` 32MiB 页缓存（负值=KiB；整库进缓存后热路径 seek 全走内存）③ 冷算分段耗时日志（query/algorithm/assemble/total，OBSERVABILITY 同步）。**实测真机：冷算 4.5s → 70~153ms（~25-40×）**。原拟「缓存键改内容池+seed 只重排」方案在深读算法后否决：randomFactor=FNV1a(assetID)⊕seed 直接参与打分（DOMAIN_RULES §1.1），拆缓存必改算法行为——改为把计算本身提速，行为零变化。
- **排行榜下拉显示不对**：RankPage 的 `QimengPullToRefresh(isRefreshing=false)` 写死——下拉真发刷新但转圈永远不出现。接线 `state.rank.isRefreshing`（字段本就存在）。
- **COS/排行下拉「无效」**：实测定案——刷新确实执行且成功（0.14s），服务端返回同页同序数据、界面零变化，用户无法区分「没反应」和「没新的」。修复：刷新成功但内容与刷新前完全一致时亮「已是最新」轻提示（HomeUiState.infoMessage + InfoBanner 中性横幅，2.5s 自动消退；推荐流换 seed 重排自带可见反馈不接此路径）；新增两例单测锁定（同内容亮+超时消退、新内容不亮）。
- **下滑到底无告知**：此前穷尽后界面静默，「到底了」和「坏了」无法区分。QimengMediaGrid 新增 `endFooterText` 跨全列尾标（不参与去重/哨兵计数），推荐/COS 流 exhausted 时亮「已到底」。
- **附带闭环**：COS 库旧路径 `…/3  cos图集` 已不存在（用户改过文件夹名、登记早于跟随改名特性）——用户自行在库管理重指向后实测扫描器 `no such file` 报错归零。
- **测试**：go test ./... 16 包全绿；:feature:home 新增两例+既有 24 例全绿；:core:ui/:core:model/:app 编译+测试全绿。

## feat(app): 上传选文件精简为唯一「系统文件」入口+收件箱设置回显修复（2026-09-29 第四百零二笔）

执行 AI：GLM-5.3（主代理：需求调研/根因定位/入口精简手术/测试迁移/收口）+ 执行子代理（SAF 入口实现与收件箱回显修复，中途按用户指令叫停后由主代理续完并重定义范围）

- **需求**（用户四条）：① App 上传「选项太多」——只保留「系统文件」一个入口（相册/收件箱导入/浏览文件全撤）② 收件箱设置页选完再次进入仍显示「选择文件夹」（应显示当前值）③ 上传选文件要能像收件箱那样看到点前缀隐藏目录（不开「所有文件访问」）④ 隐私约束：无头测试只允许截上传/设置页。
- **「系统文件」唯一入口（①③，SAF ACTION_OPEN_DOCUMENT 多选）**：系统文档选择器天然能见点前缀隐藏目录、多选、免任何存储权限（manifest 零改动）；逐 URI `takePersistableUriPermission`（暂存条目跨进程重启持久，真正读流在 worker 上传时刻，DocumentsUI 临时授权撑不到）；选完走 acceptUris 暂存管道（与系统分享同源）。
- **入口精简手术（①）**：相册选择器（MediaPickerScreen/ViewModel + 媒体读权限运行时申请）、浏览文件弹层（FileBrowserScreen/ViewModel + 「所有文件访问」闸门）、收件箱导入（VM importFromInbox + Ingestor 三管道）**整体退役删除**（5 文件删 + UploadScreen/UploadViewModel/UploadUiState/UploadStagingIngestor 同步瘦身，死代码零残留）；StagingRepository 收件箱数据面保留（收件箱设置页仍读写，归档文件夹共用）；UploadViewModelTest 收件箱导入 5 用例删、相册管道用例改写为 acceptUris 版（describe 元数据单源 fake）、17 处调用批量迁移，48 用例全绿。
- **收件箱设置回显修复（②，执行子代理实现）**：根因 = `browserVisible` 默认展开导致每次进页无视已持久化选定值仍显示目录浏览器——改为 init 回放后**收件箱与归档文件夹两者都未设置才默认展开**（首用直达），任一已设置则显示当前值卡 + 「重新选择」（既有 reopenBrowser 流程不动）；含 InboxSettingsViewModelTest 用例更新。
- **测试与门禁**：`:feature:settings:testDebugUnitTest` + `:feature:upload:testDebugUnitTest` + 两模块编译 + assembleDebug 全绿；真机 <真机序列号> 装机实测入口精简后冒烟通过。
- **文档**：本笔。协议零改动（SAF 是端内选文件方式，不涉服务端）。

## feat(web): 上传文件页拆两页——上传工作台对齐App动线+来源词表独立页（2026-09-29 第四百零一笔）

执行 AI：GLM-5.3（主代理：需求调研/方案/审查/收口）+ 执行子代理（结构重排与拆页实现/门禁自测）

- **需求**（用户在 Web 文件管理页反馈三条）：① 上传工作台「太混乱复杂」，结构对齐 App 端；② 拆成两个页面；③ 通用词表做单独页面。成因：上传文件页 = UploadWorkbench + SourceVocabularyCard + AuthorMirrorCard 三块堆叠一页，工作台内「暂存拖放区与列表混节、开始上传常驻置灰、队列表内联」信息密度高。
- **上传工作台重排（对齐 App UploadForm 动线）**：横幅（动线文案压短「选库 → 放文件 → 逐项校对 → 开始上传」）→ 目标库（必选）→ 目标目录树 → 批次挂靠默认 → **「添加文件」独立成节**（点击/拖拽统一入口，常驻）→ **「暂存列表」独立成节**（空态给引导、有项逐项展开校对）→ **开始上传按钮有暂存项才出现**（App 同口径，替换常驻置灰）→ 上传队列（拆 UploadQueueTable 纯渲染子组件，工作台渲染段 ≤300 行红线内）。上传/挂靠/编辑/队列管道逻辑零改动，纯结构观感重排。
- **来源词表独立页（拆分第二页）**：新路由 `maintenance/files/vocabulary`（LibraryVocabularyPage，两卡整卡迁入零改动）+ 双入口（数据管理 hub 第二张入口卡「来源词表」；上传页页头指引链接）；路由常量收进 route-keys.ts（禁散写字面量纪律）。
- **测试与门禁**：vitest 202 全绿（21 文件，基线无回退）、build 通过（PWA 59 entries）、lint 0 error（19 warning 均既有 fast-refresh 基线）。
- **文档**：本笔。协议零改动（纯前端信息架构拆分）。

## fix(app): 作者联想浮层三轮定位缺陷根治——弃 DropdownMenu 换自锚 Popup+一体式观感（2026-09-29 第四百笔）

执行 AI：GLM-5.3（主代理：真机取证/根因定位/自锚定位与键盘修复/性能优化/文档收口）+ 执行子代理（夸克式一体化视觉实现与真机截图验证；子代理中途被用户叫停，收尾由主代理完成）

- **需求**（用户复测三轮未根治 + 本轮四条追加反馈）：作者联想浮层应贴输入框正下方（搜索式）；追加①不得遮挡输入法②观感要一体式③一体式要「夸克浏览器那种点击后整个扩大」——直角拼接版仍被感知为「两个元素」④夸克式定稿后「有点卡卡的」。
- **根因（真机取证，非猜测）**：material3 1.5.0-alpha28 的 `DropdownMenu` 在 `verticalScroll` 容器内锚定坐标 **y 丢失**（x 正常）——uiautomator dump 实证：输入框 bounds y=647，浮层却出现在 y≈140（窗口顶部），两轮「Box 包裹锚定」修复无效坐实非锚容器问题、非旧包残留（每次装机均复现）。alpha28 坐标链不可信，`Popup` 原生锚（positionProvider 收到的 anchorBounds）同一管线同样不可信。
- **修复（自锚 Popup，绕开内部坐标链）**：`AuthorSuggestionMenu` 从 DropdownMenu 重写为裸 `Popup` + 自定义 `PopupPositionProvider`——输入框 `onGloballyPositioned` 自抓 `boundsInWindow()` 作为唯一坐标源，provider **忽略**框架传入的内部锚；State 整只传 provider（经 snapshot 读取链），滚动/键盘移动锚时浮层实时跟随重定位。`focusable=false` 不抢输入框焦点，敲字连续联想不闪断；点外部关闭、继续输入自动重开（既有 `menuOpen` 逻辑保留）。
- **键盘遮挡修复（追加①）**：下方可用空间 = 窗口可视底 − 键盘高——可视底按 `WindowInsets.ime` 实时扣（adjustResize 的窗口收缩是过渡动画，`rootView.height` 滞后会把下方空间高估导致浮层伸进键盘区，首轮装机用户实测复现）。下方容不下且上方更宽裕时上翻（让位区盖输入框上半），展开方向组合期定死、与限高/顺序/定位三处同源。
- **夸克式点击扩大一体化（追加②③，执行子代理实现，用户以夸克浏览器实拍定稿）**：浮层定位 y 改到输入框**中线**、自中线起覆盖输入框下半部——真输入框的顶部弧充当容器顶部，浮层补齐下半部与列表，两层拼成单一背景色大圆角容器：让位区（高=胶囊半径 17dp）中间透明（Popup 窗口局部透明，露出真输入框文字下半与光标）、两侧 `Canvas`+`Path` 弧形补块填平胶囊底弧缺口使容器边缘垂直连续；列表区矩形 Surface 衔接边直角、外侧两角 12dp 圆角、底色同聚焦态输入框（surfaceContainerHigh）、零阴影。上翻方向全镜像。已知代价（用户接受）：浮层窗口无法局部穿透触摸，输入框下半 17dp 条带点击被浮层吃掉（打字联想场景输入框必已聚焦，影响可忽略）。
- **卡顿治理（追加④）**：键盘弹出动画期 ime insets 逐帧变 → 浮层整棵每帧重组，且每帧新建 provider 实例触发 Popup updateParameters→updateViewLayout 与位置跟随的 updateViewLayout 叠加成每帧双重窗口重排——两处优化：①定位器实例 `remember(anchorBounds, expandUp)` 稳定化（仅方向翻转时重建）；②下方/上方空间值量化到整数 dp 再用（限高多数帧同值免 Surface 逐帧重测，方向判定阈值附近不抖动翻转）。
- **影响面**：仅 `core/ui` `QimengAuthorSourceSection.kt` 单文件；上传页批次作者、逐项编辑、资产编辑页添加作者三处共用组件同批受益；资产编辑页空输入种子全显行为不动。协议/服务端/Web 零改动。
- **测试与门禁**：`:core:ui:compileDebugKotlin` + `:feature:detail:compileDebugKotlin` + `:feature:upload:testDebugUnitTest` + `:core:model:test` 全绿。
- **文档**：本笔。协议零改动。

## fix(app/web): 来源建议拆词提取补漏词+App作者联想改浮层下拉（2026-09-29 第三百九十九笔）

执行 AI：GLM-5.3-Flash（主代理直接实现：定位/双端修改/测试/文档收口，无子代理）

- **需求**（用户复测反馈两项）：① 批次来源建议「少了好几个单独的」——上一笔只过滤组合条目，但 site-d/site-f/site-g/forum-c 这些平台词**只存在于组合条目里**（真库词表 9 条中 6 条是组合），被连词过滤掉了；② App 作者联想「提取太卡」且弹层观感是个内联胶囊 Card——每敲一字建议卡把整张长表单往下顶、全表单重排。
- **来源建议拆词提取（①，双端同口径）**：`individualSourceWords` 语义从「过滤组合」改为「**按空白拆词+去重**」——组合条目拆出的平台词全部纳入建议、首现顺序保留（App core:model 与 Web lib/source-options 各自实现同口径；真库词表实测 9 条 → 7 个平台词 site-a/site-b/forum-c/x/site-d/site-f/site-g）。消费点不变（App UploadViewModel/AssetEditViewModel、Web SourceSelectField）；词表管理卡仍显全量原始条目（组合原貌维护场景可见）；已选组合值回落「词表外已选」仍可见可移除。测试：App SourceWordsTest 重写 5 用例（含真库出厂词表全形态用例）、Web vitest 同步 5 用例；feature:upload 用例改断言拆词提取（只存在于组合的词必须出现）。
- **App 联想弹层改浮层下拉（②，用户拍板「搜索那种」）**：`QimengAuthorSuggestSection` 输入非空时的建议从内联 Card（`QimengAuthorSuggestionList`，已删）改为 **DropdownMenu 浮层**悬于输入框正下方——零布局位移治「敲字全表单重排」的卡顿，观感即搜索补全式弹层（行内容不变：displayName + 文件数；上限协议 10 条，菜单内自滚）；手动点外部关闭后继续输入自动重开（搜索惯例）。资产编辑页共用本组件同步受益；其空输入种子全显（inline 限高列表）保留不动。上笔的百度式空输入零建议行为不变。**同日装机补修**：DropdownMenu 锚定其直接父容器矩形——初版父容器是分节 Column，菜单翻转到了屏幕顶部（真机截图实证）；输入框与菜单同包一个 Box 令锚点=输入框自身，菜单贴输入框正下方展开。
- **测试与门禁**：web vitest 202 全绿（21 文件）、build 通过、lint 0 error；app `:core:model:test`（含新 SourceWordsTest）+ `:feature:upload:testDebugUnitTest` 全绿、`:core:ui`/`:feature:detail` 编译通过。
- **文档**：本笔。协议零改动。

## fix(app/web): 作者联想改百度式按需显示+来源建议只出单独词（2026-09-29 第三百九十八笔）

执行 AI：GLM-5.3-Flash（主代理直接实现：定位/双端修改/测试/文档收口，无子代理）

- **需求**（用户两项）：① 上传页作者联想「持续显示」改为百度搜索式——输入后才出建议列表；② 批次来源建议里不出现组合条目（如「site-a  site-b」），只保留单独词。Web 作者联想经核本就是百度式（`open` 门控空输入不弹），问题仅 App 端 seeds 常驻全显（第三百九十六笔「作者默认全显」的反转，用户当日复测后改主意）。
- **App 作者联想拆 seeds（①）**：上传页批次默认与逐项编辑的 `QimengAuthorSuggestSection` 不再传 seeds——空输入零建议、输入后走既有服务端联想（/authors/suggest 空 q 必返空，协议不动）；`UploadUiState.authorSeeds` 字段、`UploadViewModel.refreshAuthorSeeds()` 及 UI 三处传参链整体拆除（死代码零残留）；`toRegularAuthorSeeds` 保留（资产编辑页仍用全显，用户未要求改）。
- **来源建议只出单独词（②，双端同口径）**：新纯函数过滤——App `core:model` `individualSourceWords()`（含空白字符的条目视为组合形态过滤，空串/纯空白一并过滤，原序保留；SourceWordsTest 4 用例）+ Web `lib/source-options.ts`（vitest 4 用例）；消费点：App UploadViewModel `refreshSourceOptions`（批次+逐项共用）与 AssetEditViewModel `loadVocabulary`（编辑页同规则保持一致）、Web `SourceSelectField`（编辑页/上传工作台共用一处）；词表管理卡（SourceVocabularyCard）仍展示全量词表不过滤（维护场景要见组合原貌）；已选过的组合值回落「词表外已选」区仍可见可移除；自由输入不受限（组合可手输获得）。
- **测试与门禁**：web vitest 201 全绿（+4）、build 通过、lint 0 error（18 warning 均既有基线）；app `:feature:upload` 全部用例绿（seeds 测试改写为词表过滤测试，断言组合过滤+空输入无建议列表）、`:core:model:test` 全绿（含新 SourceWordsTest）、`:feature:detail` 编译通过。
- **文档**：本笔；HANDOVER §4 已在上一笔改写上传交互描述，本次为行为微调不再改现状段。协议零改动。

## feat(web/app/server): Web上传工作台对齐App+词表镜像迁移+App配置区固化+库根自动重挂（2026-09-28 第三百九十七笔）

执行 AI：GLM-5.3-Flash（主代理调度：现状核查/三执行子代理并行派发/对抗审查/文档收口；Web/Android/服务端三个执行子代理+对抗审查子代理，全部 GLM-5.3-Flash）

- **需求**（用户四项）：① 来源词表与作者镜像移入文件管理独立卡；② PC 上传对齐手机功能；③ 库根目录改名/移动自动跟随（「固化识别」，当日 3→2 cos图集 改名为实证场景）；④ 两端上传选项固化常显+排版操作逻辑优化。
- **Web 上传工作台（执行子代理）**：`UploadWorkbench` 替代退役的 `UploadCard`——批次目标库 pills+目录树+批次挂靠默认（作者联想/来源多选/应用到全部）+暂存区（点击/拖文件/拖目录）逐项编辑（作品名基名+扩展名锁定+序号联想 GET /assets/name-suggestions、作者、来源）+门禁上传递（超限/未选库前置拦截文案与 App 同口径）+常驻队列（聚合行+4xx message 原样透传更显眼）；201 后自动挂靠对齐 App（先 PUT /assets/{id}/authors 单项整体替换，后 PUT /authors/{id}/sources mode=append 并入，挂靠失败落「已入库·挂靠失败」专项态不重试）；新增 lib/upload-naming、lib/staged-upload 纯函数（vitest 7 用例锁定）。**词表/镜像迁移**：SourceVocabularyCard、AuthorMirrorCard 自设置页迁入 数据管理→上传文件，设置页留指引卡。三段式布局全部常驻显示、空态给引导。
- **App 配置区固化（执行子代理）**：UploadScreen 重排为常驻配置区（进入页面即见 选库→目标目录→批次默认作者/来源→添加文件→暂存列表→队列 全动线），删除「空态只显添加入口、配置区等暂存非空才出现」分支；空态给动线引导；「应用到全部」空列表不再渲染禁用态。纯交互层重排，VM/业务规则零改动（铁律 7）。
- **服务端库根自动重挂（执行子代理，ADR-0025）**：扫描器发现库根不存在时，取库内最小 5 条资产 (rel_path+size_bytes) 指纹，在旧父目录一层子目录中找**唯一**全命中候选——恰 1 个才改挂 root_path+显示名（跟随新目录 basename，用户拍板）并广播 library.changed（零计数载荷，SseBridge 零改动消费），0/≥2 候选保持原失败行为绝不猜测；匹配判定纯函数（relink.go，7 表驱动用例+6 端到端）；sqlc 新查询 ListLibraryRelinkSamples（v1.31.1 生成）；Scan/Watch 两调用点；协议/openapi/sdk.lock 零改动。**对抗审查【可合入】**（审查子代理独立重跑 build/vet/test 全绿、逐项核数据安全防线/并发/协议/测试真实性，2 条 P3 注释精度问题已顺手修正：Watch 启动期不持闸的并发表述、UpdateLibrary 目前唯一调用方是重挂本身；可选硬化「候选排除其他库 root_path」记档于 ADR 后果段）。
- **真库实证（主代理）**：重启后手动触发扫描——`库根自动重挂` 日志 oldRoot=…3  cos图集 → newRoot=…2  cos图集、displayName=2  cos图集、samples=5；5564 条旧资产零扰动找回（added/updated/moved/removed 路径零身份变更），另新收 4 张改名期间放入、扫描器此前进不去的新图（fileCount 5564→5568），缩略图/原图恢复 200。重挂前预演：5 条样本在新目录字节数全匹配、在 1  图集 零命中，唯一性成立。
- **当日装机实测数据核对（主代理，非代码变更）**：PC 上传 7 次=3×201（4.2MB IMG_20260927_234126.jpg 等成功件，其中 1 次为主代理白名单复现测试已入回收站即物理清除）+4×400（服务端四道校验拒绝，白名单 jpg/jpeg/png/gif/webp/avif/mp4/m4v/mkv/webm/mov/avi，具体被拒文件类型待用户补充）；手机端 16:55–16:59 正常浏览播放（守望先锋 5 部扫描新件已看、卡芙卡 12 播放进度 42.7s 存档），~200 条图片加载告警为 cos库根不可达期间的原图失败，重挂后应消失待复测。
- **测试与门禁**：web vitest 197 全绿+build 通过+lint 0 error（18 warning 均为既有基线）；app `:feature:upload` 58/58+`:core:model` 全绿（`:core:data` DataStoreStagingRepositoryTest 8 失败为既有 Windows DataStore 改名竞态，stash 基线比对证实与本批无关，已记 HANDOVER 已知问题）；server go build+go test ./... 全绿+gofmt 干净+golangci-lint 0 issues。
- **文档**：ADR-0025（库根自动重挂）+ INDEX 追加行、HANDOVER §4/§5 已知问题、本笔；GUIDE_API/DOMAIN_RULES 无涉（协议与领域口径零改动）。

## feat(app): 上传归档文件夹+浏览文件入口+作者默认全显+列表加载刷新修复（2026-09-28 第三百九十六笔）

执行 AI：GLM-5.3-Flash（主代理 GLM-5.3 调度：拆批派发/门禁/亲核审查结论/文档收口；四个执行子代理实现+接力子代理+对抗审查子代理+修复子代理+视觉验证子代理，全部 GLM-5.3-Flash）

- **需求**（用户七项）：① 上传配置选项默认全显（不要输入后才出）；② 下载收件箱改为「上传归档文件夹」——上传完的文件按 `<库名>/<文件名>` 存到指定文件夹供手动复制同步电脑；③ 上传选文件能看到 `.xxx` 隐藏文件夹（系统相册扫不到）；④ App 列表滑到底不再加载（回归）；⑤ 下拉刷新响应慢；⑥ 问 Web 端是否同步（结论：不需要，见尾）；⑦ 收件箱设置迁到数据管理页。**协议零改动**（全 App 客户端行为；作者全显复用既有 GET /authors 过滤 COS，不动 /authors/suggest 空q口径）。
- **上传归档文件夹（②⑦）**：DataStore 新键 `upload_archive_path`；`InboxFileStore.archiveToLibraryRoot`（库名按 Windows 保留字符集 sanitize——归档根会被复制到电脑取更严口径；同名异容加「 (N)」序号绝不覆盖、同名同内容删旧放新；renameTo 优先+copy 兜底且 copy 走 `.part` 临时名落地防半截残留）；库名经 `UploadRequestSpec.libraryName` 载荷传递（入队时 VM 从库列表解析，旧载荷缺键回退空串）；`UploadWorker.archiveNote` 三分派：设归档根且有库名→归档根；未设→**仅收件箱来源**（源父目录与收件箱目录 canonical 精确相等）维持 uploaded/ 旧口径，其它路径来源不动（审查 P1：原实现把 uploaded/ 搬移扩散到任意浏览目录，已收窄）；相册来源永不移动；归档切 `Dispatchers.IO`（审查 P2：GB 级文件比对拖住 Default 线程与完成通知）。设置页更名「上传收件箱与归档」（收件箱+归档双卡共用 core:ui `DirectoryBrowserCard`），入口从设置页迁数据管理页（三处引导文案同步改，审查 P1 文案死链）。
- **浏览文件入口（③）**：上传页第三入口「浏览文件」——目录/文件浏览器（`DirectoryBrowserCard` 从 InboxSettingsScreen 上提 core:ui 单源，含点前缀隐藏目录）+ 当前目录白名单文件多选（`InboxFileStore.listMediaFiles`）入暂存区（isPathSource 同收件箱口径）；MANAGE_EXTERNAL_STORAGE 闸门未授权弹引导跳系统设置；闸门判定收归 `InboxFileStore.hasAllFilesAccess` 单源（审查 P2：第三份同口径实现）。
- **作者默认全显（①）**：`toRegularAuthorSeeds` 纯函数（GET /authors 过滤 COS）单源供三消费方（批次默认/逐项编辑/资产编辑页）；`QimengAuthorSuggestSection` 新增 seeds 参数，空输入显示种子列表（限高 200dp 可滚）、输入后仍走服务端联想；拉取失败静默降级空列表。目标库 pills 与来源 chips 本就默认全显未动。
- **列表加载与刷新（④⑤）**：触底哨兵 `QimengMediaGrid` 加第三 key `reloadTick`（各 VM 加载成功落地 bump——失败不 bump，防与自动重试互喂请求风暴；钉底状态下加载结束即重评估，治「滑到底哑火」结构性缺陷）；推荐流 `appendNextSeedRound` fresh==0 换 seed 续拉上限 `MAX_EMPTY_SEED_ROUNDS=3` 后置 exhausted（上次第二百七十九笔只治 revealed 不推进，本次补齐去重空轮断路）；翻页失败 `APPEND_RETRY_DELAYS_MS=[1s,3s]` 退避自动重试（六 VM cursor 路径，重试间代际复检弃残局）；下拉刷新立即受理（isLoading 拦截收窄到翻页；推荐流新增 recommendGeneration 代际作废在途旧响应——既有筛选代际防乱序机制与 500ms tab 抑制窗原样保留）；数据落地即 `isRefreshing=false` 收圈（不等 facets）；`ThumbnailPrefetcher` 刷新窗口避让（`refreshPausedUntilMs` 15s 自愈时间窗，防漏恢复饿死预取）。
- **测试与门禁**：新增/改写约 20 条用例（InboxFileStoreTest 24 全含 copy 兜底无 .part 残留/冲突序号/sanitize/隐藏目录文件；UploadViewModelTest 53；HomeViewModelTest fresh==0 上限+刷新新代语义；AlbumViewModelTest 退避封顶；DataStore/WorkSpec/InboxSettings 同步）；`make app-build` + `make app-test` + `make app-lint` 全绿；对抗审查（独立重跑全部受影响模块测试）无 P0，2 P1+3 P2 数据安全项已返工收口。
- **Web 端（⑥）**：经核无同类问题——Web 列表是 IntersectionObserver+TanStack 哨兵无「key 不变永不触发」缺陷、无下拉刷新机制；上传页作者/来源控件已随 ADR-0024 退役无对应交互；收件箱/归档/浏览文件是 App 特有。不同步改。
- **记档（P2 不修）**：UploadScreen 685 行/UploadViewModel 646 行/HomeViewModel 799 行新破 600 软线（清偿需独立重构批）；Favorite/History/Search/AuthorCollection 四 VM 的重试+代际改造无本地行为测试（同范式靠 Album/Home 用例背书）；病态「空页+非空 cursor」服务端响应下 reloadTick 自喂无上限（Go 端尾页恒 null cursor，不构成现实路径）；收件箱设置页浏览器组件 selectedPath 恒 null，原「已选目录 ● 标记」由双当前值卡替代。
- **装机实测与收口（视觉验证子代理，qimeng_api35 + 隔离服务端 18499/临时数据目录，未触碰 8420 与 qimeng-data）**：入口迁移/设置页/作者空输入全显/暂存→上传→归档落盘（设备侧 `Download/测试归档库a/a1.png` 出现且源消失）/列表刷新回归 全部实测通过。实测发现两缺陷当批收口：① 浏览文件弹层目录行点击无响应（P0）——根因 `FileBrowserViewModel.refresh()` 漏回写 storageRoot，`enter()` 以空根触发 loadDirectory 空根保护静默 return（对照：文件行 toggle 不经该路径、设置页 VM 有回写，双证据定位）；补回写修复，装机复验下钻/返回正常，`goUp` 同链路一并恢复。② 「上传收件箱与归档」设置页选定后浏览器不收起（用户直报）——UiState 加 `browserVisible`（选定即收起），当前值卡加「重新选择」按钮原位再开（保留浏览位置），装机复验通过。记档：FileBrowserViewModel 无 JVM 单测（InboxFileStore 为 final 且直调 Environment，引 Robolectric 超本批边界），Bug A 回归以装机闭环覆盖。
- **文档**：ADR-0024 修订3（归档文件夹/浏览入口/作者全显/入口迁移）、HANDOVER §4、本笔；GUIDE_API/DOMAIN_RULES 无涉（协议与领域口径零改动）。

## feat(api/web/app/server): 持久暂存区+下载收件箱+作品名序号联想+通用词表出厂预填（2026-09-25 第三百九十五笔）

执行 AI：GLM-5.3-Flash（主代理：协议/文档/审查收口；服务端与 Android 由并行执行子代理实现，对抗审查子代理复核）

- **需求**（用户固化工作流）：① 下载文件在手机 `.xxx` 点前缀隐藏文件夹（相册扫不到）可能放数日——暂存区必须**持久化**（改到一半跨进程/跨天保留）；② 指定一个文件夹作**收件箱**，放进去的文件自动进暂存区；③ 暂存项**缩略图卡片、点开交互编辑**；④ 作品名编辑**序号联想**（输入「守望先锋dva」→ 库里有「守望先锋 DVA 12」→ 推荐「守望先锋 DVA 13」）+ **扩展名锁定**不可改；⑤ 出处推荐恢复「常见的那几个」——通用词表出厂**自动预填**通用平台名（单作者个人地址/链接形态排除）。
- **协议（65→66）**：新增 `GET /assets/name-suggestions?libraryId&q` → `{suggestions: []string}`（规范化前缀匹配+**少空格吸附**〔移除全部空白比对，命中「守望先锋 DVA 12」〕、族键=去尾部序号基名〔裸「名 12」/括号「名 (2)」两风格〕、建议=族内既有命名**原样风格**+最大序号+1〔用户脏输入自动吸附规范写法〕、无序号成员的族不产生建议、序号跨扩展名共用、每族一条 cap 3、前导零不保留〔审查记档口径，代码注释+文档双写〕；libraryId 空 400/库不存在 404/cos 库可用）；`make sdk` 三端重生成 + sdk.lock 同批更新。
- **服务端**：`filing/namesuggest.go` 纯函数 `SuggestSeriesNames`（22 子用例表驱动：风格提取/规范化/多族确定性）+ `httpapi/assets_namesuggest.go`（端到端含 cos 库）；`authorattach/vocabulary_prefill.go` 预填纯函数（不同作者数统计跨片段去重、≥2 保留、排除 http/www./含点号域名、authorCount 降序 name 升序 cap 20、有结果才写键）+ `EnsureSourceVocabulary`（键不存在才预填；PUT 恒写键故用户清空不复活；DSN `_txlock=immediate` 下 GET 触发写无并发插窗）+ httpapi 五态端到端。
- **Android**：暂存持久层 `StagingRepository/DataStoreStagingRepository`（client_prefs 三键：条目/批次配置/收件箱路径，moshi JSON 坏数据宁空不崩）+ **原子读改写**（`editItems/editBatchConfig` 收进单次 DataStore edit——审查定位的 first()+update 两步竞态返工修复，真 DataStore JVM 测试 6 例含并发叠加）；**收件箱** `InboxFileStore`（File API 扫描：白名单扩展名过滤〔与上传校验同源〕、排除 uploaded/、mtime 倒序、点前缀目录天然支持）+ 设置页「下载收件箱」App 内目录浏览器（InboxSettingsScreen，授权引导，无系统弹窗）；暂存 UI 缩略图卡片（Coil 直载 File/uri、视频角标、失效行「文件已不存在」可清除）+ 点开交互编辑（作品名联想回填+扩展名锁定拼接 `UploadNaming` 纯函数）；上传成功/挂靠失败后收件箱源文件移入 `uploaded/` 归档（renameTo 失败不阻断，同目录子目录无 EXDEV）。
- **对抗审查（Rv 复核）**：P0 sdk.lock 已重算同 commit；P1 文档本笔补齐；P2 暂存写竞态返工修复（原子变换下沉仓层）；P3 前导零口径注释+真 DataStore 往返测试（6 例）落地。审查确认：联想纯函数边界（无空格紧贴数字不算序号/全角不崩/溢出安全/多字节安全）、预填一次性语义、归档无 EXDEV 风险、门禁与超限保留语义。
- **测试**：server `go build/vet/gofmt` + `go test ./... -count=1` 15 包全绿；android 全仓单测 **929 例 0 失败** + assembleDebug 通过（新增 namesuggest 相关 + InboxFileStore 12/StagingJson 6/Models 12/InboxSettings 5/DataStore 往返 6/VM 50）；web tsc/oxlint 过（新端点类型已生成，Web 端本批不接线）。
- **文档**：ADR-0024 修订记录 2；DOMAIN_RULES §6 词表预填口径；GUIDE_API 66 路径+新端点条+词表预填；HANDOVER 同步。
- **记档**：收件箱只扫一级文件不递归（选存储根为收件箱亦有界）；库覆盖项入队落库根（dir=""，服务端 NormalizeRelPath 兜底）；Web 端收件箱/暂存/联想不接线（桌面无此工作流，编辑页已覆盖）。

## feat(app): 上传页流程重排——先选文件、暂存区配置目标库/作者/来源、门禁上传递（2026-09-25 第三百九十四笔）

执行 AI：GLM-5.3-Flash（主代理派执行子代理实现）

- **需求**（用户对上传页编排的修正）：流程改为「先选文件 → 待上传区出现 → 在暂存区配置目标库/作者/来源/作品名 → 上传」，目标库不再要求选文件前预设。
- **改动**（android/feature/upload 三文件）：库列表加载不自动选中；库选择控件（含目标目录）从页面顶部移入暂存区配置块首行「目标库（必选）」；`UploadUiState` 增 `canEnqueue`/`enqueueGateHint` 门禁派生（未选库「开始上传」禁用并提示，VM enqueue 兜底拦截文案同源）；空态（无待传项）只留「从相册选择」+ 引导文案，零配置项；MediaPicker/挂靠序列/ATTACH_FAILED 态零改动。
- **测试**：UploadViewModelTest 38 例全绿（默认选库用例改「不自动选库」、入队族补选库步骤、新增拦截/门禁派生/目录树后移 3 例）；`:app:assembleDebug` 通过。
- **文档**：HANDOVER §4 Android 能力行与资产编辑段同步上传流程新口径。

## feat(api/web/app/server): 上传流程自动挂靠——App 暂存列表逐项快捷编辑（作品名/作者/来源）+ 上传 201 后自动挂靠（来源 append 并入）| 文档: adr/0024 修订, DOMAIN_RULES §6, GUIDE_API, CHANGELOG.md（2026-09-25 第三百九十三笔）

执行 AI：GLM-5.3-Flash（主代理：协议/文档/终验；服务端与 Android 由并行执行子代理实现）

- **需求澄清（用户纠正第三百九十二笔的理解）**：真实工作流=下载文件先核对**作品名/作者/来源再上传**，App 内一步完成而非上传后另去编辑页。定稿形态：上传协议**保持纯上传**（ADR-0024 决策 1 不回收）；App 上传流程在单文件 201 后由客户端依次调用 `PUT /assets/{assetId}/authors`（authorIds 单项——新上传资产零关联，无覆盖风险）与 `PUT /authors/{authorId}/sources`（**mode=append**，不覆盖作者既有来源）；挂靠失败**不重试上传**（重试=文件重复入库），条目落「已入库待挂靠」态由资产编辑页补挂——编辑页兼任上传流程的失败恢复路径。上传前暂存列表逐项编辑**作品名**（=upload 既有 filename 参数，落库文件名即作者匹配与展示依据）与作者/来源，批次默认一键套用、逐项可覆盖、新进项继承批次默认。
- **协议（api/openapi.yaml，路径数不变 65）**：`PUT /authors/{authorId}/sources` 请求体 SourceVocabulary → **AuthorSourcesWriteRequest** `{sources, mode?=replace|append}`（缺省 replace=整体替换〔编辑页语义不变〕；append=并入去重、永不覆盖既有来源〔对齐 ADR-0023 原上传来源口径〕）；`make sdk` 三端重生成 + sdk.lock 同批更新。
- **服务端**：`authorattach/edit.go` `ReplaceAuthorSources` 增 mode（新增 `SourcesWriteMode` 常量与 `writeAuthorBlockSources` 分派助手：replace=`authoring.ReplaceSources`、append=`authoring.AppendSources` 幂等并入、回显=写入后来源区；无块新建路径两模式共用——displayNameAliases 修复原样复用）；`httpapi/author_edit.go` 适配新 gen 请求体 + `parseSourcesWriteMode`（nil/replace→replace、append→append、非法 400 INVALID_PARAM——decodeJSON 无枚举校验此处即唯一校验点）；kv 修剪两模式统一收口（append 后修剪恒 no-op，单一实现防口径漂移）。测试 +5（append 既有区保序去重/幂等零写入/无块新建回读不漂移/缺省 replace 回归/非法 mode 400 无副作用）。
- **Android**：`UploadItem` 增 `uploadFileName`（null 回退 displayName 的唯一回退口径 `effectiveUploadName`）/`attachAuthorId`/`attachSources`；`UploadStatus` 增 **ATTACH_FAILED** 专项态；暂存区批次默认控件（作者联想+来源多选+应用到全部）+ 逐项展开编辑（作品名/作者/来源，UploadPendingSection/UploadQueueSection 拆分守 600 行红线）；WorkManager 载荷增 `KEY_UPLOAD_FILE_NAME`/`KEY_ATTACH_AUTHOR_ID`/`KEY_ATTACH_SOURCES`（可空键不写，旧在途载荷反解兼容）；**`UploadAttacher`**（@Singleton 走生成 SDK：先 replaceAssetAuthors 单项、后 appendAuthorSources，任一失败收敛不外抛）+ Worker `resolveOutcome`（挂靠失败 → Result.success + ATTACH_FAILED 标志与「到 作品详情→作者→编辑 补挂」指引文案〔单一文案源〕，永不触发整 worker 重试）；`AssetUploader` 201 响应体解析 assetId（moshi，无新依赖）。
- **测试**：server `go build/vet/gofmt` + `go test ./... -count=1` 15 包全绿；android `testDebugUnitTest :core:model:test` 730 例全绿 + assembleDebug 通过（新增 UploadAttacherTest 8、WorkSpec 往返/兼容 +5、UploadViewModel 挂靠用例 +15）；web tsc/oxlint 过（生成类型 mode 可选，编辑页 hook 无需改动）。
- **文档**：ADR-0024 追加「修订记录（同日，上传流程自动挂靠）」；DOMAIN_RULES §6 sources 端点 mode 口径 + 新增「上传流程自动挂靠」条；GUIDE_API「作者」行与「关键机制」条同步。

## feat(api/web/app/server): 上传拆分 + 资产编辑页 + 通用来源词表 + Web 拖拽目录/Android 相册式选择器 | 文档: adr/0024, adr/0023 修订, adr/INDEX.md, DOMAIN_RULES.md, GUIDE_API.md, SECURITY.md, ARCHITECTURE.md, CAPABILITY_MAP.md, HANDOVER.md, CHANGELOG.md（2026-09-25 第三百九十二笔）

执行 AI：GLM-5.3-Flash（主代理：协议/编排/审查返工收口；协议前探索与三端实现/文档由并行执行子代理完成，对抗审查子代理复核）

- **需求与决策**（ADR-0024，用户拍板三选一）：① 上传回归纯上传——openapi 删 `POST /assets/upload` 的 authorId/authorName/source 三参数，Web/Android 上传卡撤作者/来源控件；挂靠改由**资产编辑页**承载（事后可纠错，上传是批量动作归属需看图确认）。② 来源语义修正：来源=「获取渠道/平台名」（如「forum-c」），**不是** §4 作品出处；建议词表弃用个人片段词表（作者 A 的个人链接对作者 B 无意义），改服务端手动维护的**通用来源词表**小清单。③ Android 上传选取弃系统 SAF 选择器（外部文件夹弹窗麻烦且不美观），改**内置相册式选择器**；Web 文件夹上传去 webkitdirectory 弹窗按钮、拖拽为主。
- **协议（api/openapi.yaml，路径 63→65）**：删三上传参数与 `GET /authors/sources`；新增 `GET/PUT /authors/source-vocabulary`（kv_settings `author_source_vocabulary`；GET 无记录=空数组；PUT 整体替换，trim+去重、单项 ValidSourceWord〔控制字符/超 500 rune 400〕、原始数组超 32 项 400）；新增 `GET/PUT /authors/{authorId}/sources`（常规作者片段来源区读写；GET 无块=空数组、不存在 404、COS 400；PUT 整体替换该作者块来源区，无块=最近导入片段新建块）；新增 `PUT /assets/{assetId}/authors`（body authorIds 全集整体替换；200 回 AssetDetail；仅 capabilities.authorAttach=true 库可用）。`make sdk` 三端重生成 + sdk.lock（223→225 entries）同批更新。
- **服务端**：`authoring/edit.go` 新纯函数 `RemoveWorks`（首遇块作品区删行：行文本精确匹配优先、trim+小写+去空格归一回退且**归一保留扩展名**——png/mp4 同名互不误伤）/`ReplaceSources`（来源区整体替换，空区标记行不残留、裸块补标记，写出可被 ParseAuthorBlocks 原样读回）/`PruneUploadEntries`；`authorattach/edit.go` 编辑编排 `ReplaceAssetAuthors`（新增=作品行写作者块〔落点规则与 Apply 共用 findAuthorBlock/mostRecentIndex〕、移除=遍历全部片段删行、同事务 swap asset_authors）与 `ReplaceAuthorSources`、`vocabulary.go` 词表 kv；`httpapi/author_edit.go` 5 个 handler（PUT 资产作者复用 GET 详情装配保证口径不漂移）。两处编辑落库后按 `MissingUploadEntries` 反向**修剪 kv `imported_txt_upload_entries`**（编辑是有意变更，防重导入 409 误报；编辑不新增登记条目，重导入保护此后只覆盖存量条目）。上传减法：`upload.go` 删 resolveUploadAttach 全分支（四道校验/落盘/响应零变化）。镜像刷新挂点=资产作者/来源编辑保存后。
- **对抗审查返工（Rv1 两项 P1 + 死代码清欠）**：① 无块新建路径把多别名 displayName（" / " 连接）整串当单别名写编号行 → 解析回读 GenerateAuthorID 漂移成幻影作者——`displayNameAliases` 拆分归一修复 edit.go 两处 + Apply AuthorID 路径（grep 新发现的第三处同型）共三点，新增多别名无块往返防回归测试 ×3（含变异验证：改回缺陷形态立即 FAIL）；② sdk.lock 未随协议更新 + android/sdk 陈旧生成物残留（已删 AuthorSourceStat/AuthorSourceVocabulary 四文件后重算）；③ 死代码：server `ExtractSourceVocabulary`/`SourceStat`、Android `AuthorSourceStat`/`LibraryChoice.authorAttach`/`acceptFolderTree`+FolderScanner 整族/showCreateNew 死分支。遗留记档：资产改名/移动不回写片段作品行（残留行被统一重建重关联，后续项）；同片段跨块同名作者 RemoveWorks 只清首遇块（解析器身份口径一致，重建兜底）；Android `UploadItem.relativeDir` 链路存活但恒空串（文件夹上传退役后的占位）。
- **Web**：上传减法（UploadCard 撤控件/`use-upload` 撤快照/`upload-params` 撤分支——buildUploadQuery 无挂靠输出与旧实现逐字节一致，测试锁定）；`lib/folder-upload.ts` 拖拽目录递归（webkitGetAsEntry、`..`/绝对路径/盘符/反斜杠路径闸门、readEntries 分批）+ enqueue 每条目 dir 覆盖（relativePath→库内子目录）；新建 `AssetEditPage`（详情页「编辑」进入：作者草稿增删 + 每作者来源区编辑，保存=PUT 全集再对脏作者逐个 PUT 来源区，失败留页可重试，错误一律 batchFailureReason）；设置页「通用来源词表」卡；`SourceSelectField` 数据源换新词表 hook；`AuthorSuggestField` 文案/KDoc 全面去上传语境（唯一消费方=编辑页）。
- **Android**：上传三参数全链拆除（UploadViewModel/SdkUploadRepository/UploadWorkSpec/UploadWorker/AssetUploader/UploadScreen；WorkManager 旧载荷兼容锁定——含挂靠键的旧 payload 反解为无挂靠正常上传）；SAF 选择器全链退役（含 FolderScanner 族），新增 `MediaPickerScreen/ViewModel`（MediaStore Images+Video 单查询、相册 bucket chips、3 列网格多选、Coil content:// 直载 + coil-video 视频首帧/时长角标、确认回传与旧管道共用超限拦截）；权限 READ_MEDIA_IMAGES/READ_MEDIA_VIDEO + READ_EXTERNAL_STORAGE≤32（MANAGE_EXTERNAL_STORAGE 保留——消费者是内嵌服务端读媒体库根，与选择器无关）；新增 `AssetEditRoutes/AssetEditScreen/AssetEditViewModel`（详情作者 Sheet「编辑」进入；AuthorSection/SourceSection 抽 core:ui 无状态组件复用）；`AuthorRepository` 增词表/来源区/资产作者四方法。
- **测试**：server `go build/vet/gofmt` + `go test ./... -count=1` 15 包全绿（新增 edit_test.go 表驱动、author_edit 端到端、别名往返 ×4）；web tsc/oxlint/vitest 190 通过 + build 通过；android testDebugUnitTest 845 例 + `:core:model:test` + assembleDebug 全绿（净删挂靠/文件夹用例 19、新增选择器 5 + 编辑页 10 + 兼容 2）。
- **文档**：新建 `docs/adr/0024-上传拆分资产编辑页与通用来源词表.md`（五段式）+ INDEX；ADR-0023 加修订记录（决策 3 上传入口退役，真相/同事务/重导入保护语义不变）；DOMAIN_RULES §6 小节改写「作者挂靠编辑与通用来源词表」；GUIDE_API 65 路径校准+上传/作者/关键机制条；SECURITY 上传节退役注记+镜像挂点更新；ARCHITECTURE §5 authorattach 行；CAPABILITY_MAP/HANDOVER 同步。

## feat(api/web/app/server): 上传挂靠作者与来源（TXT 真相原地更新 + 重导入保护 + 本地镜像 + 导出）| 文档: DOMAIN_RULES.md, SECURITY.md, GUIDE_API.md, adr/0023, adr/INDEX.md, adr/0012, ARCHITECTURE.md, CAPABILITY_MAP.md, HANDOVER.md, CHANGELOG.md（2026-09-25 第三百九十一笔）

执行 AI：GLM-5.3（执行子代理——代码与协议由前序会话实现并测试全绿，本笔为全库文档同步）

- **需求与语义**（`REQ-上传指定作者与来源.md`，仓库根；架构决策=ADR-0023）：上传媒体时可选择指定作者（单选、整批生效）与来源（多选）；挂靠与来源**直接写进已导入 TXT 清单片段本体**（唯一真相、原地变更，任何统一重建后不丢不重）；来源仅记录永不参与匹配；重导入同文件名清单不得静默丢弃上传条目（409 二选一）；本地 txt 自动镜像 + 片段导出下载。
- **协议（api/openapi.yaml，路径计数 59→63）**：① `POST /assets/upload` 增可选 query `authorId`（联想点选的既有作者 ID，404=不存在）/`authorName`（回车新建显示名，与 authorId 互斥 400）/`source`（数组同名重复传，须与作者参数同现否则 400，单项 ≤500 字符、≤32 项超限 400）——仅 `Library.capabilities.authorAttach=true` 的库接受挂靠参数（否则 400），不传新参数行为与旧协议逐字节一致；② 新增四路径：`GET /authors/suggest`（常规作者〔type=regular，含零关联〕displayName 子串匹配 ASCII 大小写不敏感——displayName 含 " / " 连接别名、别名片段命中同一人；q trim 空→空列表；limit 缺省 10、1..50 越界 400；displayName 升序）、`GET /authors/sources`（全部片段来源区去重词表 {name, authorCount}，authorCount 降序、name 升序=常用优先；与 §4 资产出处分区〔SourceMatcher/custom_sources〕互不相干禁止混用）、`GET /authors/import-txt/export?filename=`（片段原文 text/plain 下载，Content-Disposition attachment + filename*=UTF-8''转码；匿名片段 filename 空/缺省=最近一份、下载名「作者清单.txt」；404=不存在；导出内容往返可再导入不触发 409）、`GET/PUT /authors/mirror`（{path, fragmentFilename}；path 空=关闭〔默认〕、非空必须绝对路径否则 400；fragmentFilename 空=最近导入的片段；PUT 成功即尝试一次镜像刷新；持久化 kv_settings `author_mirror`）；③ `POST /authors/import-txt` 增可选 `conflictResolution=keep|remove`（其他值 400）——同文件名重导入且新内容缺少上传写入条目时缺省 **409 TxtImportConflict**{filename, authors[{authorId, displayName, works[], sources[]}]}（只列缺失项），keep=缺失条目自动并回新内容后替换落库（响应 `mergedUploadEntries`=并回行数）、remove=按明示移除（元数据一并清除）；格式 C 不触发（不存片段）；④ Library schema 增 `capabilities.authorAttach`（boolean；normal=true、cos=false；单一来源=server `scanner.SupportsAuthorAttach`，ADR-0012 接入清单新增第 6 项「能力声明」；POST /libraries 201 与 GET /libraries 都带）；`TxtImportResult` 增 `mergedUploadEntries`、`TxtImportedFile` 增 `importedAt`（RFC3339，旧数据零值=最旧）；三端 SDK 重生成。
- **服务端（server）**：`internal/authoring/attach.go` 纯函数挂靠引擎（AppendWorks/AppendSources/AppendAuthorBlock/ValidNewAuthorName/ExtractSourceVocabulary/MissingUploadEntries/MergeUploadEntries；写入内容与手工片段同构、能被 ParseAuthorBlocks 原样读回；幂等去重）。新编排包 `internal/authorattach`（**ADR-0019 首个落地新流程**）：片段存取单一来源 Source{filename, content, importedAt}（httpapi 旧 txtSource 已删除收敛）；Service.Apply 在调用方事务内完成挂靠（无任何片段时自动建「上传自动挂靠.txt」〔`authoring.AutoFragmentFilename`〕；既有作者并入其所在片段〔数组序第一个〕，新作者/不在片段的既有作者并入最近导入片段）→条目元数据→UpsertAuthor→AddAssetAuthor 立即可见（重放等价：作品行=最终落盘名被 MatchWorks 规则 1 精确命中）；上传条目元数据 kv `imported_txt_upload_entries`（仅重导入保护的比对依据，**不是第二真相**，REQ §4.2 允许的出处元数据）；MirrorWriter 原子写（临时文件+rename）尽力而为（失败 slog.Warn 绝不阻塞/回滚核心操作；未配置/无目标跳过；路径恢复后下一次变更自动补写）。`upload.go`：挂靠参数校验前置到收流之前（fail-fast）；UpsertAsset+挂靠同事务（REQ #6 失败安全——不产生「文件已入库但数据半更新」）；作品行记录**冲突重命名后的最终落盘文件名**（REQ #9）；响应 AssetDetail.Authors 带挂靠作者；挂靠成功后镜像刷新。镜像刷新挂点=上传挂靠后/TXT 导入落库后（含 keep/remove 结果）/片段删除后/镜像配置保存后；rebuild 不改片段不挂。上传既有四道校验与全部行为零变化。**对抗审查返工批（Rv1 两阻断项修复）**：source/authorName 含控制字符一律 400（内嵌换行可向 TXT 真相注入任意作者块/作品行，PoC 证实后封死）；新作者身份经 `CanonicalAuthorNames` 与解析器回读同构归一（连续空格/括号备注不再裂分身，`UploadEntry` 增 Names 字段做块重建往返锁定）；补注入拒绝/身份归并/事务回滚/格式 C 作者挂靠/镜像恢复补写共 12 个防回归测试。
- **Web**：上传卡新增作者联想字段（回车=与联想项大小写不敏感全等则选定该作者、否则新建——与 Android 一致）+ 来源多选字段（词表来自服务端，可自由输入新词；未选作者时禁用），仅 `capabilities.authorAttach===true` 渲染（不写死 kind）；TXT 导入卡增每片段「导出」按钮与 409 冲突三按钮面板（保留并导入/确认移除/取消）；设置页新增「作者总表镜像」卡；TopBar 的 useDebouncedValue 抽为共享 hook。
- **Android**：上传页新增同两输入段（Compose 全新写，ADR-0014）；LibraryChoice 增 authorAttach；联想/词表走 Repository→生成 SDK；WorkManager 载荷透传三参数。桌面壳零改动（ADR-0020 连接模式加载服务端 Web，自动覆盖）。
- **测试（REQ 验收 #1~#15 服务端面全覆盖）**：server `go test ./...` 全绿（新增 `authoring/attach_test.go`、`authorattach` 包测、httpapi `upload_attach_test.go` + `authors_attach_test.go`）；web vitest 177 通过（新增 upload-params 8 例）；android testDebugUnitTest 全绿（UploadViewModel +13 例、UploadWorkSpec +3 例）+ assembleDebug 通过。
- **文档（本笔全库同步）**：DOMAIN_RULES §6 新增「上传挂靠与作者来源」小节 + 文头记档；SECURITY「上传安全」补挂靠参数 fail-fast 一句 + 新增「作者总表镜像（唯一例外写点）」节 + 文头记档；GUIDE_API 文头记档、路径计数 59→63、「上传整理」/「作者」表行更新、关键机制新增「上传挂靠」条目；新建 `docs/adr/0023-上传挂靠作者来源与TXT真相原地更新.md` + INDEX 索引行；adr/0012 接入清单追加第 6 项「能力声明」（含首例执行说明）；ARCHITECTURE §5 模块边界表增 `internal/authorattach` 行 + 文头记档；CAPABILITY_MAP 作者体系/上传整理行现状更新 + 文头记档；HANDOVER §4 追加能力段 + 文头记档。
- **迁移**：零改动（无新表/新列——片段与条目元数据均存 kv_settings）。

## feat(app): 本机模式冻结自愈 + 内嵌服务端二进制重出——2026-09-25 真机「详情页加载失败」事故修复（2026-09-25 第三百九十笔）

执行 AI：GLM-5.3-Flash（主代理）

- **事故与根因**（真机某品牌真机 / Android 16 排查）：用户报「本机模式详情页加载失败」。经 adb forward 直打 API 复现，详情 JSON/标签/时间轴/签名缩略图/签名原图全链 200 毫秒级——接口与数据无恙；真机本机 curl `/healthz` 30s 无响应 + SIGQUIT goroutine dump 定位为**内嵌服务端子进程调度整体停摆**（accept 停在 netpoll、连接协程/scanner 定时器/warmup sleep 全部「计时器已到期、goroutine 已就绪、无线程调度」）。触发规律：两次冻结均在 App 退后台后、插电/回前台后解冻——Android 16 + OEM 省电对后台进程组的冻结/压制连坐裸子进程（与壳进程同 cgroup），FGS 管不到。诊断动作：对已冻死子进程 SIGQUIT 抓堆栈（服务本就无响应，非额外损害）。
- **冻结自愈**（`EmbeddedServerService`）：新增 `ACTION_HEALTH_CHECK`——探测 `/healthz`（回环、免鉴权、不碰数据库；3s 超时），两次探测（间隔 2s）均无响应即 `destroyForcibly`（冻结进程不执行 SIGTERM 处理器）并原位重拉，发「已自动恢复」一次性通知。触发链：`MainActivity.onStart` → `MainViewModel.onAppForeground()`（记录最近服务端地址，空串=未配置不触发）→ `EmbeddedServerController.ensureHealthyIfLocalMode`。刻意不在看护协程做后台探测：冻结时 Service 与子进程一起被冻，探测跑不动也救不了；前台回归时刻壳进程必已解冻、动作必可执行。配套：`startMutex`（启动序列异步化后防重复 START 并发拉起双子进程）、健康检查单飞闸（AtomicBoolean）、自愈前先摘看护（防 watchdog 把自愈误判为进程死亡而 stopSelf）、身份守卫对齐 `onServerDied`。
- **残留子进程回收（「后端占用」形态）**：Service 销毁重建后子进程句柄丢失，孤儿仍占 18430 → 新子进程 bind 失败秒退、反复「已退出」。新增 pid 落盘（`files/server/server.pid`，每次启动覆写）+ 启动前回收（`parseRecordedPid` 纯逻辑 + /proc cmdline 须仍含 `libqimeng.so` 防误杀 + SIGKILL 后轮询等端口释放）。子进程 pid 定位用扫 /proc（cmdline 匹配二进制 + stat 的 ppid==壳进程）：compileSdk 37 平台桩的 `java/lang/Process.class` 缺 `pid()`（API 24+ 官方方法编译期不可见，实测）。
- **内嵌服务端二进制重出**：jniLibs 里的 `libqimeng.so`（arm64 09-18 / x86_64 09-17 旧货）落后于 09-22 服务端批（FK 降级修复/Host 校验/回收站清扫等）——旧二进制下「浏览事件指向已删资产」走 ERROR+500 路径，客户端离线队列对孤儿事件反复重放（真机日志实测三条 FK ERROR）。本次双 ABI 重出随包，FK 场景回归 Warn+202 语义。
- **测试**：`EmbeddedServerConfigTest` 新增 pid 解析表驱动（合法/带换行/空白/脏数据/负数）；`MainViewModelTest` 新增前台自检透传与无地址不触发两用例 + Recording 桩；既有三处控制器桩（`NoopEmbeddedServerController`/`ServerSettingsViewModelTest`/`AuthRepositoryImplTest` Fake）补新接口方法。相关三模块 testDebugUnitTest 全绿。
- **文档**：HANDOVER §4 Android 行同步自愈能力记档。
- **协议/迁移**：零改动（无新端点、无 schema 变化；pid 文件为 App 私有排障产物）。

## feat(server): 磁盘生命周期与安全加固批——回收站到期清扫 + 缩略图删除联动 + Host 校验防 DNS rebinding（2026-09-22 第三百八十九笔）

执行 AI：GLM-5.3（主代理）

- **回收站到期自动物理清除**（清偿 DOMAIN_RULES §9 规划项与 REVIEW §2.2/Top5 #2，根因=`filing.TrashExpired` 判定函数自 M1 备好但零调用方）：新配置 `trash.retention_days`（默认 30，与 `filing.DefaultTrashRetentionDays` 同值互指）/`trash.sweep_interval`（默认 1h），yaml+env（`QIMENG_TRASH_RETENTION_DAYS`/`QIMENG_TRASH_SWEEP_INTERVAL`）双通道，零值 Load 兜底、非法值报错；后台清扫 `httpapi/trash_sweeper.go`（`backup.Manager.Start` 同款 ctx 生命周期，无 enabled 开关——到期清除是 §9 核心语义）；trash 列表 `ExpiresAt` 改与清扫判定同源取生效值（防配置覆盖后展示与实际清除漂移）；误配兜底沿用 `TrashExpired` 的 retention<1 永不判过期。
- **缩略图删除时机联动**（§11「孤儿由对账清理」的写侧半边，REVIEW §2.3/Top5 #2）：新增 `thumbnail.DeleteAssetThumbs`（全档位 256/512/1024+生效 md 档 × webp/jpg 双扩展名 × long_side 配置漂移键，幂等尽力而为）；四入口接线——回收站单条物理删除、清空回收站（先收集 meta 再 RemoveAll，meta 是真相源）、到期清扫、删除库（FK 级联前先取资产清单；缩略图=服务端自有缓存，不属「磁盘媒体文件不动」保护范围）。软删除→恢复刻意不清（asset_id 不变、同键缓存继续命中）。扫描器外部删除（deleteGone/removeIfPresent）孤儿仍待全量对账任务（§11 记档为规划项）。
- **Host 校验防 DNS rebinding**（复查新发现，安全子代理审查定位）：最外层中间件 `hostCheck`——默认白名单 = IP 直连（v4/v6 字面量）+ localhost，其余 Host 403；`trusted_hosts`/`QIMENG_TRUSTED_HOSTS`（`,`/`;` 分隔）放行域名（Tailscale MagicDNS 场景）。默认零配置零影响（既有访问形态全是 IP/本机名）；空 Host 放行（不构成 rebinding 向量，兼容古董客户端）。动机：无 CORS 输出挡不住 rebinding 伪同源，dev 免密形态下 dev-login 等于向攻击页面送 admin token。
- **安全响应头基线补 `X-Frame-Options: DENY`**（点击劫持防护；`<img>`/`<video>` 消费直链不受影响）。
- **正确性修复三件**（复查正确性子代理定位）：`listTrash` 遍历对并发 RemoveAll 的竞态容忍（回收站面板开着时另一端清空，Windows 目录枚举竞态偶发 500；容忍口径对齐 scanner.WalkDir）；缩略图 warmup 投递循环遇 `ErrPoolClosed` 中止整轮（原实现停机后 250ms 空转到进程退出）；auth setup 的 argon2 哈希移出写锁（双检锁：无锁快路径 409 保留——已初始化时不为注定失败的请求付 64MB 哈希成本）。附带 `config.applyEnv` 超函数警戒线拆分（节段函数，语义零变化）。
- **测试**：新增 10 用例——config 5（默认值/双通道覆盖/零值兜底/非法拒绝/Host 白名单拆分）、thumbnail 2（全档位清理+不误删他资产/漂移键覆盖）、httpapi 3 文件（到期清扫含未到期保留与缩略图联动、ExpiresAt 配置联动、物理删/清空联动、Host 判定表驱动+中间件端到端）。`go test ./...` 15 包全绿；golangci-lint 零告警。
- **文档**（行为先行同步）：DOMAIN_RULES §9/§11 口径改写+文头记档；SECURITY.md 回收站节改写、新增「Host 校验」节、安全头基线补 DENY；deploy/README 环境变量表补三键 + 遗留项 M5 收官改写；REVIEW-20260922 M5 表述更新+复核后记（Top5 #2/#4/#5 清偿记档）。
- **协议/迁移**：零改动（无新端点、无 schema 变化；`trusted_hosts`/`trash.*` 均为服务端私有配置）。

## docs: M5 收官回写 + 全库文档对齐——启动脚本收敛、根 compose 删除、死引用清偿（2026-09-22 第三百八十八笔）

执行 AI：GLM-5.3（主代理）

- **M5 收官**：用户确认真机 NAS 测试完成（2026-09-22）。PROJECT_PLAN M5 全勾 + 收官口径记档（arm64 移 M7+ 按需储备——实测部署形态 amd64；大库压测不设专项，真机真实库日常使用覆盖）；HANDOVER §3/§5、CAPABILITY_MAP 部署行、README/ARCHITECTURE 部署行、Makefile docker-build 注释、仓库外 00-总说明 同步。deploy/README 与 REVIEW-20260922 的收官改写随下一笔（server 批）提交。
- **上笔删除的提交收尾**：第三百八十七笔宣称的 8 个文件删除本次随笔入 git（前笔仅提交了 CHANGELOG 与活引用对齐，删除悬在工作区，HEAD 与 CHANGELOG 矛盾）。
- **过时句修正**：README「docker-build 尚未实现」、ARCHITECTURE「双架构镜像构建未落地/semver tag M5 落地」→ amd64 已交付+M5 收官现状；OBSERVABILITY 的 `deploy/grafana/` 死路径（目录不存在，改为随增强包交付）；llms.txt 部署导航改指 `deploy/`。
- **死引用清偿（注释级零行为改动）**：web 4 处（confirm-dialog/MediaCard/SearchPage/prototype.css 原指向已删 HANDOVER_UI）与 android stats 模块 4 处（原指向已删 REPLICATION_GAPS）改自包含表述或改指 DOMAIN_RULES §5；HANDOVER_APP 群保留（第三百八十七笔既有决定：出处级引用、约束自包含）。
- **工程卫生（REVIEW Top5 #5 清偿）**：两份 `启动服务端*.bat` 收敛为 `_server-common.cmd` 单一逻辑源（两 bat 改薄壳包装、行为不变，安全敏感的 `QIMENG_AUTH_DEV_MODE` 行从双写变单点）；根 `docker-compose.yml` 删除（内容失实——"CI 构建的双架构镜像"无此事实；生产样例唯一权威=`deploy/docker-compose.yml`，llms.txt 指针同步；非 root 加固项记入 deploy/README 遗留项）；`.gitignore` 增 `.zcode/`。

## docs: 文档严格清理与对齐——删过时交接/任务书，HANDOVER 重写为现状-only（2026-09-22 第三百八十七笔）

执行 AI：MiMo（主代理）

- **删除**（历史可溯 git）：`docs/任务书-审计清偿批-20260920.md`、`docs/.p2-progress-P2-1..6.md`、`docs/P2-修复执行报告.md`、`docs/REPLICATION_GAPS.md`、`docs/AUDIT-20260920.md`、`docs/HANDOVER_UI.md`、仓库外 `_archive-20260917/`。
- **重写**：`docs/HANDOVER.md`（现状-only）；`00-总说明.md` 同步。
- **活引用对齐**：`AGENTS.md`、`AI_README_FIRST.md`、`PROJECT_PLAN.md`、`CAPABILITY_MAP.md`、`adr/0014`。
- **原则**：CHANGELOG 历史笔不改写；现行文档只保留可验证事实。

## docs: 文档漂移修正——HANDOVER UI 待办与 HANDOVER_UI 发现项对齐已交付实现（2026-09-22 第三百八十六笔）

执行 AI：MiMo（主代理）

- **背景**：`docs/REVIEW-20260922.md` 审查误报「Web 上传入口/图片查看器/批次导航」为缺口；对照代码（`LibraryUploadPage`/`UploadCard`/`image-viewer.tsx`/`.asset-pager`）与 CHANGELOG（W-1 `6690b12`、E5 第一百一十八笔）确认均已交付。根因=`HANDOVER.md`「UI 路线现状」与 `HANDOVER_UI` 发现项/详情大改遗留句未跟上交付，形成文档漂移。
- **修正**：①`HANDOVER.md`「UI 路线现状」改写为已交付清单（W-1/W-2/W-3/E5/详情互动行），删除「剩余 UI 待办三项…上传 UI 入口」过时表述；②`HANDOVER_UI.md` §5.9 发现项与详情大改第 8 条遗留句标注 E5 清偿；③`REVIEW-20260922.md` 补勘误表。
- **同日追加勘误（用户批注）**：审查报告「回收站/备份设置 Web UI」表述不准——`TrashPage`/`BackupCard`/`DbBackupCard` 均已有；能力地图「备份设置 Web UI」实指**备份调度参数编辑**（启用/间隔/保留份数）仍 yaml/env+重启的窄缺口。报告表已改。
- **范围**：纯文档，零代码/零协议/零迁移。

## docs: M5 fnOS 虚拟机彩排收官+虚拟机删除——手机实测/断电恢复/回收站全过，SC 别名误命中记档不修（2026-09-22 第三百八十五笔）

执行 AI：GLM-5.3-Flash（主代理）

- **彩排收官**：用户实测手机真机（App 连 NAS 浏览 + 上传手机截图成功入库、缩略图自动生成）——M5 验收清单「手机真机访问」销项；AI 侧补测「断电重启数据完好」（VBoxManage 硬切电模拟断电 → 重启后 fnOS 自恢复、Docker 容器 restart=unless-stopped 自拉起 healthy、库数据 9 文件完好）与「回收站恢复」端到端（删除 200 → 回收站条目 → 恢复 200 → 文件实体回位，同场复验第三百八十四笔修复）。
- **已知问题记档（用户拍板不修）**：sourcematcher 内置表「星际争霸」条目含缩写别名 "SC"，§4 大小写不敏感前缀匹配使所有 `Screenshot_*.jpg` 误归出处「星际争霸」（实测：用户手机上传 `Screenshot_20260921_200712.jpg` 即命中）。§4 引擎口径与旧项目一致属数据侧别名过短；手机截图为最大受害场景，用户可用「自定义出处」手动改归类。HANDOVER 当前待办已记档，真机部署后若误伤明显再议（候选修复=从表数据移除该别名，需连动 matcher 测试与 DOMAIN_RULES §4 勘误）。
- **虚拟机删除**：`unregistervm --delete-all`（含快照与 vdi），C 盘回收约 18GB；重建完全可复现（装机→deploy/README.md 部署链路）。
- **文档**：PROJECT_PLAN M5 验收清单拆项勾销（手机/断电/回收站三项 ✅，大库压测留真机节点）；HANDOVER 当前进度+当前待办同步（日期 09-22、批D 在办收敛、已知问题记档）。

## fix(server): 回收站跨挂载点移动 EXDEV 失败——MoveFile 回落复制+删源（2026-09-22 第三百八十四笔）

执行 AI：GLM-5.3-Flash（主代理）

- **来源**：M5 彩排回收站链路实测（删除资产 → HTTP 500），容器日志 `移入回收站失败: rename /media/... /data/trash/...: invalid cross-device link`。Docker 部署里媒体根（/media）与数据目录（/data）是两个挂载卷，os.Rename 不能跨挂载点（哪怕底层同一块盘）——媒体库与应用数据分卷是 NAS 生产布局常态，此 bug 使回收站功能在所有分卷形态下不可用（铁律 4：删除=移入回收站）。同卷 systemd 形态不受影响，故此前未暴露。
- **修复**：filing 包新增 `MoveFile`（move.go，IO 例外 2，doc.go 同步声明）——rename 优先，EXDEV 回落「复制成功后删源」，中途失败源文件原状；EXDEV 判定用错误文本匹配（双平台 syscall 暴露不一致 + 项目字符串匹配惯例）。trash.go 三处换用：移入回收站、meta 写失败回滚、恢复出站。恢复（出站）与删除（进站）同修。filing.go 的库内移动与 upload.go 的落盘（临时文件在目标目录内，同挂载点）经排查无此风险，不改。
- **测试**：MoveFile rename 快路径 + copyFile 内容/权限位组件测试；EXDEV 分支单测环境无法复现（需真实双挂载点），由 fnOS 虚拟机端到端删除/恢复链路验证（随本笔执行）。`go test ./...` 15 包全绿。

## fix(server): 浏览事件引用已删资产时统计累加外键失败——孤儿事件按 ADR-0005 口径入库跳过统计（2026-09-22 第三百八十三笔）

执行 AI：GLM-5.3-Flash（主代理）

- **来源**：fnOS 虚拟机 Docker 彩排的日志监控抓到 `累加按天统计失败: FOREIGN KEY constraint failed (787)` 共 48 次——用户浏览器里仍开着的旧标签页持有上一代库的资产 ID，持续向全新库重放浏览事件：事件插入成功（view_events 无外键，ADR-0005），物化表 asset_daily_stats 累加外键失败 → 500 → 客户端按「2xx 才删本地暂存」约定无限重试。真机迁移场景（手机离线队列旧事件灌入新库）必然复现。
- **修复**（engagement.go）：UpsertAssetDailyStats 外键失败时按 ADR-0005 既有口径处理——孤儿事件照常入库（事件流是真相源）、统计累加跳过、原样 202 让离线队列收敛；与 RebuildAssetDailyStatsFromEvents 的 live 过滤（stats.go「已删资产：物化表不收，事件流保留」）同口径。错误判定沿用 libraries.go 的约束错误字符串匹配惯例（本机无 sqlc，未动生成物）。
- **测试**：新增 TestEngagementEventForDeletedAsset 锁定三类行为——孤儿 dwell/open 事件各保留 1 行、物化表无对应行、同会话二次 open 被去重收敛；`go test ./...` 15 包全绿。
- **部署**：随本修复重建 linux/amd64 二进制与 qimeng-media 镜像，fnOS 虚拟机 Docker 实例同步更新。

## feat(server): Docker 部署三件套交付 + fnOS 虚拟机部署实测——M5 批D 部分清偿（2026-09-22 第三百八十二笔）

执行 AI：GLM-5.3-Flash（主代理）

- **背景**：用户主动要求用 VirtualBox + fnOS 镜像做 NAS 实测彩排（2026-09-19 曾豁免虚拟机彩排，本笔按用户最新要求恢复执行并顺势清偿批D 开发侧产物）。路径绕开宿主机 Docker Desktop（仍未安装）：宿主机只交叉编译 + 构建 web 产物，镜像在 fnOS 虚拟机内构建。
- **deploy 三件套入库**：`deploy/Dockerfile`（debian-slim + ffmpeg/curl + 纯 Go 静态二进制 + SPA 产物，端口 8420 标注协议双同步责任）、`deploy/config-docker.yaml`（容器内兜底基线，auth_dev_mode 默认关闭，库白名单 /media）、`deploy/docker-compose.yml`（端口/卷挂载/healthcheck/restart=unless-stopped 样例）、`deploy/README.md`（构建→导入→部署全流程 + 国内镜像源备注 + 安全红线自查 + 实测记录）。
- **Makefile**：新增 `server-linux-amd64` 交叉编译目标；`docker-build` 占位 TODO 落地为 amd64 实构建（arm64 半边仍挂真机节点，输出诚实提示）。
- **fnOS 虚拟机实测**（VirtualBox 7.2.16 + fnOS 1.2.0401→在线升级 1.2.0604）：镜像 `qimeng-media:1.0`（590MB）构建成功、容器内 ffmpeg/ffprobe 自检通过；compose 部署后局域网 `GET /api/v1/healthz` 200（约 2ms）、Web 托管正常、容器 healthy；systemd 版与 Docker 版前后台切换验证端口与数据互斥；全链路彩排=建库（白名单校验）→扫描 8 个合成测试文件→缩略图/时长探测→局域网流式播放→上传 201 入库→重启自动拉起。测试媒体全部为 ffmpeg 合成图案，未触碰真实库数据。
- **环境备忘**：fnOS 自带 ffmpeg 8.1.1（mediasrv 版）与 Debian 源 ffmpeg 5.x 冲突，apt 安装报错后以 fnOS 自带版运行（dpkg 已修复）；Docker Hub 直连超时，镜像经 `docker.m.daocloud.io` 拉取（README 已记做法）；fnOS SSH 的 /home/admin 需 sudo 手工补建属其发行版惯例，公钥部署一次通过。
