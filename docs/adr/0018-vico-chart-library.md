# ADR-0018：Android 图表库选型 Vico（compose-m3）

- 状态：已接受（2026-09-09）
- 决策人：用户（2026-09-08 原话「不要自绘」「统计页折线图换 Vico」；该拍板优先于同页 C3 批次
  「Compose Canvas 自绘」的既往拍板，C3 相关注释已同步改写，不留死引用）
- 执行：GLM-5.3-Flash（执行子代理，任务H H2 批）

## 背景

统计页趋势卡此前是 Compose Canvas 手绘折线/面积图（C3 批次拍板），违反用户「不要自绘」的
总体方向；且任务I I3 统计详情页需要多系列趋势图 + 「点击数据点高亮 + 数值气泡」交互，
Canvas 自绘意味着每张图都要重新手写布局/命中测试/标签抽稀——自绘路线不可持续。
Web 端同类问题已由 ADR-0016（recharts）解决，Android 端需要对等的图表库决策。

## 决策

引入 **Vico 2.5.1**（`com.patrykandpatrick.vico:compose-m3:2.5.1`，传递依赖 compose + core）。
版本锁定依据（2026-09-09 官方源当场实测，非训练记忆）：

- 官方仓库与 Maven group：https://github.com/patrykandpatrick/vico +
  https://repo1.maven.org/maven2/com/patrykandpatrick/vico/compose-m3/ ；
- 当前全局最新稳定线是 3.x（3.3.1），2.x 末位是 2.5.2——但两者的构件 .module 均声明
  kotlin-stdlib 2.4.0，其 @Metadata 2.4.0 超出工程 Hilt 2.58 的 kotlin-metadata-jvm 上限
  2.3.0（同 ADR-0014 批次否决 Coil 3.5.0 的既证死因）；
- 2.5.1 = 2.x 线在工程冻结工具链（AGP 8.13.2 / compileSdk 36 / Kotlin 2.3.21）上的末位可落
  稳定版：stdlib 2.3.21（metadata 2.3.0 合规）、compose/core/compose-m3 三构件 AAR
  aar-metadata 实测 minCompileSdk=36、compose-bom requires 2026.05.00（工程 BOM 2026.06.01
  更新，覆盖解析）。Kotlin/AGP/compileSdk 均不动（升级需整体走 AGP 9 + 37 路线，另行立项）。

统计页趋势卡渲染层换 QimengTrendLineChart 封装（feature/stats 内），旧 Canvas 实现删除。
API 形态为 2.x 稳定版 Cartesian 命名：CartesianChartHost + rememberCartesianChart +
rememberLineCartesianLayer（LineFill.single 折线 / AreaFill.single 渐变面积 / PointProvider
数据点），X 轴标签防重叠抽稀由 Vico ItemPlacer 内置。

## 落选方案

- **维持 Canvas 自绘**：被用户 2026-09-08 原话直接否决；I3 多系列 + 点击交互的手绘成本高。
- **Vico 3.3.1（最新稳定线）**：kotlin-stdlib 2.4.0 与 Hilt 2.58 冲突（见上）；待工程整体
  升级 AGP 9 + Kotlin ≥2.4 工具链后可再评估升级。
- **MPAndroidChart 等 View 体系图表库**：非 Compose 原生，须 AndroidView 桥接，声明式状态
  管理割裂，且社区维护放缓。

## 影响

- ADR-0014 依赖白名单扩一项：Vico（白名单外新依赖，用户 2026-09-08 原话拍板；此后 Android
  端图表统一走 Vico，不再自绘图表）。
- I3 复用约定：QimengTrendLineChart 封装系列数/颜色可配（多系列天然支持），marker /
  persistentMarkers 参数位已预留给「点击数据点高亮 + 数值气泡」；本批零交互。
- 受影响文件：`android/gradle/libs.versions.toml`、`android/feature/stats/build.gradle.kts`、
  `android/feature/stats/.../QimengTrendLineChart.kt`（新增）、
  `android/feature/stats/.../StatsScreen.kt`（Canvas 实现删除）、`docs/adr/INDEX.md`。
- C3 拍板推翻记档：StatsScreen.kt 文件头注释已改写为引 2026-09-08 用户拍板。
