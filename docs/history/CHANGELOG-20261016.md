# CHANGELOG 历史归档 · 2026-10-16

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
