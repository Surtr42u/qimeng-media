# ADR-0014：M4 Android 走 Compose 重建（先进优先），取代 ADR-0013 照搬路线

- 状态：已接受（2026-09-04，用户二次拍板）

## 决策

M4 Android 客户端采用 **Kotlin + Jetpack Compose 全新实现**，交互规格照搬旧项目（GUIDE_UI.md），实现代码全部新写。用户在同日先后两次拍板间选择了后者：先进方案优先（额度不设限），接受复刻走样风险换取长期技术形态。

- **技术栈（冻结）**：Kotlin + Compose（Material 3 + Compose BOM）+ Hilt + Coroutine/Flow + Navigation Compose + Coil 3 + Media3/ExoPlayer + Room（缓存/事件队列）+ DataStore（配置）+ WorkManager（队列）+ `make sdk` 生成的 Kotlin 客户端（`media.qimeng.sdk`）。
- **架构标准（冻结，对齐 Google 官方 Now in Android 范式）**：Gradle 多模块——`:app`（壳/导航/装配）+ `:core:model`（纯数据）+ `:core:network`（生成 SDK 封装）+ `:core:data`（Repository 层）+ `:core:ui`（共享组件/主题）+ `:feature:*`（按页面族拆分）。依赖方向单向：feature → core，禁止反向。UI（Compose）→ ViewModel（StateFlow）→ Repository 接口 → SDK；UI 禁直调网络、禁内嵌业务规则（ADR-0008）。
- **包名**：`media.qimeng.app`（与 SDK 域对齐）；minSdk 26。
- **旧代码关系（取代 ADR-0013 的照搬口径）**：交互语义以旧项目 `docs/GUIDE_UI.md` 为规格书 + ADR-0013 调研的泄漏点/消费点清单作参考；**实现代码全部 Compose 新写，禁搬旧 Kotlin**。例外白名单：复杂自绘控件（BiliPlayerView 手势播放器、ZoomImageView，及同类复杂自绘件如 LineChartView，同规则）允许经 **AndroidView 互操作**桥接旧 View 复用（Compose 官方互操作机制，不算违背先进性）；桥接与否由执行时按复刻成本定，桥接清单必须写入交付报告。
- **单机形态预留（联动 ADR-0015）**：App 的「服务器地址」配置天然支持指向本机内嵌服务端（localhost），UI 层零改动即可切换双形态——解耦红利的直接兑现。

## 理由

- 用户明确「先进方案为主、不考虑后果、额度不设限」：技术形态优先于复刻保真度。
- Compose 是 Android 官方主推方向，AI 语料密度高且持续增长；本项目 100% AI 生成，长期看新范式对 AI 产出质量更有利。
- 高度模块化（多模块+接口驱动）与项目原旨（UI 最便宜可换、逻辑共享）一致，且为 ADR-0015 单机形态提供干净的分层基础。

## 后果

- ADR-0013 废弃（其旧项目架构调研结论仍有效，交接文档继续引用）。
- 复刻走样风险由用户自担（已知情）；对齐类问题按 Web 端 HANDOVER_UI §4.5 的教训模式管理（先测后交）。
- 工作量大于照搬路线（15 页交互全复刻），批次任务书见 HANDOVER_APP.md（已按本 ADR 重写）。
