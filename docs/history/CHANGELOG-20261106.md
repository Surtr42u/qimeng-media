# CHANGELOG 历史归档：2026-11-06

> 第六百九十三笔，完整原文迁入；现行状态见 HANDOVER。

## feat(research): 媒体研究流水线 API 集成（provider 网关 + Lens sidecar）（2026-11-06 第六百九十三笔）

执行 AI：DeepSeek V4.1 Flash（主代理复核；执行子代理）

- 新增 `GET /api/v1/research/context`、`POST /api/v1/research/providers/{provider}/query`（fxtwitter/pixiv/yandere/e621/wayback/steam/bing 固定矩阵，矩阵外 400 INVALID_PARAM）、`POST /api/v1/research/lens`（multipart file ≤20MiB，loopback sidecar）；错误映射含 400/401/413/429/502/503/504 与 `RESEARCH_DISABLED`、`RESEARCH_SIDECAR_*` 码，provider 上游失败以 200+status 表达。协议先改 OpenAPI，再 `make sdk` 生成三端并同批重算 `api/sdk.lock`；Go 枚举用 `x-enum-varnames` 规避 oapi-codegen v2.8.0 的常量去重冲突（否则既有 `Ok` 常量被改名、编译失败）。
- 新包 `server/internal/research`：provider 接口与注册表、安全 HTTP 出口（仅 https、域名白名单、禁重定向与环境代理、DNS 解析后校验公网地址、20s 超时/2MiB 响应上限）、进程内预算（provider 每分钟、Lens 每小时/每日，时钟可注入、UTC 对齐）、sidecar 客户端（loopback 校验、Bearer、/healthz、/lens multipart、超时映射）与证据落盘（剥会话参数、只记哈希/公开字段、不入库）。配置新增 `research` 段与 `QIMENG_RESEARCH_*`，默认关闭，启动期校验端点/凭据/预算；嵌入式/Android 默认关闭且不新增监听面。
- 验证边界：research 与 httpapi 测试全部使用 httptest 假上游、假 sidecar 与合成数据，不访问真实平台、不触碰 8420 真库；**未安装 Lens sidecar、未在真实实例验收真实平台查询**，现装 PC/手机仍为 264b9d83。文档同批：`docs/api/research.md`、ADR-0053 与索引、能力地图、HANDOVER 研究交接、llms 导航，规则包增研究能力/工作流/手册节。第六百六十三笔完整原样移入 docs/history/CHANGELOG-20261028.md，近期日志保持 30 笔。
