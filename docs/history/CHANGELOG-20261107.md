# CHANGELOG 历史归档：2026-11-07

> 第六百九十四～六百九十五笔，完整原文迁入；现行状态见 HANDOVER。

## feat(tools): 研究核验 CLI 命令（context/provider/lens）（2026-11-07 第六百九十四笔）

执行 AI：DeepSeek V4.1 Flash（主代理复核；执行子代理）

- `tools/ai_maintenance/cli.py` 新增只读 `research` 命令组：`research context`（GET /api/v1/research/context，打印 enabled、sidecar configured/available/pending、provider 矩阵与限额）、`research provider --provider <name> --operation <author|post|work|artist|tags|url|workshop|search> --target <值> [--limit N]`（POST /api/v1/research/providers/{provider}/query；CLI 不硬编码矩阵，矩阵外/非法参数由服务端 400 透传）、`research lens --file <本地图片>`（POST /api/v1/research/lens 的 multipart 字段 file；扩展名与 20MiB 本地预检、150 秒超时、摘要只打印 status/frameSha256/topTitles/links 数量，不打印图片内容）。所有命令支持全局 `--json`；缺配置退出码 2 且不回显 token，图片字节不进入任何输出。
- 规则包与文档同步：`rules.json` 三条 research capabilities 的 `cli` 指向实际命令、研究指南补 CLI 用法并删除「本切片未提供研究 CLI」，规则版本 `2026-11-06.3`；`rulepack.py` 去掉与指南重复的研究节并把 research 命令加入只读命令清单；`tools/ai_maintenance/README.md` 增研究示例与 `RESEARCH_DISABLED`/`RESEARCH_SIDECAR_*`/`RATE_LIMITED`/`INVALID_PARAM` 语义；`docs/api/research.md` 增「命令行工具」小节，注明研究默认关闭、sidecar 需另行部署。
- 验证边界：`tools/test_ai_maintenance.py` 由 23 项增至 31 项并全部通过，仅用临时目录与 `127.0.0.1` 随机端口 stub（研究成功摘要与 --json、POST 路径/Bearer/JSON 体、multipart 字段与文件名、服务端 400/503 透传、缺配置、token 脱敏、本地文件预检）；未访问真实平台、未安装 sidecar、未触碰 8420 真库，也未改 OpenAPI/Go。`python -X utf8 tools/check_docs.py` 0 错误、`python -X utf8 tools/check_privacy.py --mode worktree` passed。第六百六十四笔完整原样移入 docs/history/CHANGELOG-20261028.md，近期日志保持 30 笔。

## feat(app): 安装 796d813f 双端并完成研究流水线真实实例试验（2026-11-07 第六百九十五笔）

执行 AI：DeepSeek V4.1 Flash（主代理复核；执行子代理）

- 云端同源门禁：ci/安全/文档与 PC/APK 出包均在 796d813f 通过（CI 首轮 Windows 文件操作作业因环境清理失败，同作业重跑通过）。PC 安装 119 项清单本地 SHA-256 核对通过，version JSON revision=796d813f、schema22、health/ready 200；手机同签名保留数据覆盖安装，版本 0.2.1/code35（lastUpdateTime 2026-11-07 01:59）。
- 研究流水线真实实例试验：临时开启 `research.enabled` 并运行本机 sidecar，`/research/context` 返回 enabled=true、sidecar available、7 provider；`/research/lens` 真实返回 200/约 13s（frame SHA 与真实库图片一致、links 空、top 域噪声，符合 best-effort 预期）。`/providers/fxtwitter/query` 因本机 DNS fake-IP（198.18.1.x 保留段）被 SSRF 防护拦截返回 ADDRESS_BLOCKED；未放宽安全策略，需本机切真实 DNS 或另立显式逃生配置后再验。
- 顺带按用户 2026-11-07 授权补挂作者：POST /assets/{assetId}/authors 将「守望先锋 天使 18.jpg」关联到 vgerotica 并回读一致；写前已做服务端热备。
- 边界：未做手机 v2 同步；研究默认关闭、日常启动不带试验环境；真实 429/503/504 未触发；所有证据与安装副本只留忽略目录，未写测试脏数据。
