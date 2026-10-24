# CHANGELOG 历史归档：2026-10-21

> 第五百六十三笔，完整原文迁入；现行状态见 HANDOVER。

## chore(server+app+web+desktop+docs): AI 维护第一批——发布身份、架构门禁、窗口契约及升级恢复工具（2026-10-21 第五百六十三笔）

执行 AI：未知（会话未提供可核实的完整模型名称与变体；主代理）

- 用户授权分批实施架构评估建议、由 AI 执行维护并记录断点；NAS 测试暂不执行。新增 AI_MAINTENANCE_PLAN 任务清单，区别实现/验证/部署，未完成编排和设备事项保留。
- 发布身份：Go/Android 共用 0.2.1，Android 安装序号 2；服务端 -version 在配置/DB/端口前输出 VCS 身份，系统状态/SSE 复用既有 version 字符串，无协议/schema/SDK 锁变化。未更新日常包。
- 门禁：Go AST 检查四个算法包直接依赖白名单；Web TypeScript AST 检查 UI 直接请求/运行时 SDK，允许类型引用/组件懒加载；migration 对 PR base/push before 锁历史 SQL，工具自测仅临时合成仓库。
- 窗口：新壳按动作属性分派并兼容旧 Web，新 Web 保留旧壳属性；实际注入脚本回归通过，无视觉参数变化，不据此声称原生窗口验收。
- 工具：发布清单拒绝旧提交/dirty 正式产物；停写目录副本/核对/恢复工具保留原件/密钥/mtime/哈希，拒绝覆盖既有目标与损坏清单。工具不自动停服、不证明同一事务快照/介质独立/掉电安全，未操作真实数据。
- 文档影响：更新协作规范、架构、各端入口、安全、恢复、系统 API、能力地图和工具说明；HANDOVER 阶段证据归档保留、当前入口缩短；GUIDE_UI 去除视觉参数重复，修正架构绝对承诺。最旧完整 CHANGELOG 条目按 30 笔上限归档。
- 验证：Go 全量测试/vet、golangci-lint 0 issues；Web npm ci/audit=0 后 317 单测、类型/生产构建、knip 及 lint 通过（既有 Fast Refresh 警告）；工具 15 项测试、桌面 cargo test --locked 的 17 项测试；Android processDebugMainManifest 与 0.2.1/2 属性核对；开发服务端 -version 与开发交付清单实跑通过。文档/迁移检查通过。远端新 head CI、原生窗口、手机/NAS/真实介质与正式包部署未执行；本机 CGO=0，本轮未执行 race，8420 无测试写入。
