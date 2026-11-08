# API：媒体研究流水线（provider 网关与 Lens sidecar）

> [API 目录与全局约定](../GUIDE_API.md)。字段/路径/schema 以 [OpenAPI](../../api/openapi.yaml) 为准；本页解释用法、限额与边界。决策见 [ADR-0053](../adr/0053-研究流水线API集成.md)。

研究流水线面向"作者/作品核验与反查"：服务端原生纯 HTTP provider 网关 + PC 本地 Lens sidecar 代理。**默认关闭**（`research.enabled=false`），嵌入式/Android 单机形态同样默认关闭且不新增任何监听面——三个端点都挂在既有服务端上。

## 能力边界（先看这里）

- **证据等级只看平台接口**：provider 返回的平台字段决定 confirmed/likely；`bing`/`wayback` 只表示"有线索"，不能单独支撑作者核验结论。
- **无硬依赖、可回退**：单个 provider 失败只让本次查询 `status=provider_unavailable`，不影响其他 provider；方法失败时调用方可改走其他 provider 或人工核对，服务端不串联、不自动重试。
- **Lens 是 best-effort**：sidecar 的视觉匹配卡片没有稳定外链，`links[]` 经常为空，**不得把 links 当作识别成功的证据**；`topTitles`/`topDomains`/`cites` 来自正文启发式，`status=ok` 也不等于认出了作者。
- **结果不落库**：研究结果只回响应，证据以 JSON 落盘到数据目录，剥会话参数、不存图片字节。

## 鉴权

三个端点全部走全局 Bearer（`Authorization: Bearer <token>`），无 `security: []` 豁免。

## GET /api/v1/research/context

返回 `ResearchContext`：

| 字段 | 含义 |
|---|---|
| `enabled` | 是否启用研究（`research.enabled`）；关闭时本端点仍 200，便于 UI 隐藏入口 |
| `sidecar.configured` | 是否配置了 loopback sidecar 端点 |
| `sidecar.available` | 最近一次 `GET /healthz` 探测结果（短缓存；未配置恒 false） |
| `sidecar.pending` | 当前在途 Lens 请求数（sidecar 单实例串行、1 并发） |
| `providers[]` | v1 固定矩阵：`name/operations/enabled/limits{perMinute,perDay}` |
| `limits` | Lens 预算 `lensPerHour/lensPerDay` |
| `fetchedAt` | 本响应生成时刻（RFC3339） |

## POST /api/v1/research/providers/{provider}/query

请求体 `ResearchProviderQuery`：

```json
{ "operation": "author", "target": "@somebody", "options": { "limit": 10 } }
```

- `target` 1–500 字符（按 Unicode 码点），空串/超长/含控制字符 400 `INVALID_PARAM`。
- `options.limit` 1–50（缺省 10）；无论取值多少，响应 `items` 最多 20 条。
- 矩阵外组合（provider 不支持该 operation）400 `INVALID_PARAM`；未知 provider 同样 400。

### v1 provider-operation 矩阵

| provider | operation | target 语义 | 上游 |
|---|---|---|---|
| `fxtwitter` | `author` | 账号（`@name`/`name`/账号主页链接） | `api.fxtwitter.com/{handle}` |
| `fxtwitter` | `post` | 帖子 ID 或含 `/status/{id}` 的链接 | `api.fxtwitter.com/status/{id}` |
| `pixiv` | `author` | 数字用户 ID | `www.pixiv.net/ajax/user/{id}?full=1`（需 Referer；`full=1` 不含作品列表） |
| `pixiv` | `work` | 数字作品 ID | `www.pixiv.net/ajax/illust/{id}` |
| `yandere` | `artist` | 画师名 | `yande.re/artist.json?name=` |
| `yandere` | `tags` | 空格分隔标签 | `yande.re/post.json?tags=` |
| `e621` | `post` | 数字帖子 ID | `e621.net/posts/{id}.json`（必须带自定义 UA） |
| `e621` | `tags` | 标签搜索表达式 | `e621.net/posts.json?tags=` |
| `wayback` | `url` | 待查存档的 http(s) URL | `web.archive.org/cdx/search/cdx`（`collapse=digest`） |
| `steam` | `workshop` | 数字创意工坊 ID | `api.steampowered.com` 的 `GetPublishedFileDetails`（POST 表单） |
| `bing` | `search` | 搜索词 | Bing HTML 结果页（解析 `b_algo`，最多与 limit 同量） |

响应 `ResearchProviderResult` 恒 200（除协议/鉴权/开关错误）：

| `status` | 含义 | `error.code` 示例 |
|---|---|---|
| `ok` | 平台返回了可用字段 | 无 |
| `not_found` | 平台明确不存在或空结果 | `NOT_FOUND` |
| `blocked` | 平台拒绝（401/403/451，或需要登录/验证码） | `BLOCKED` |
| `rate_limited` | 本地每分钟预算耗尽，或平台 429 | `RATE_LIMITED` |
| `provider_unavailable` | 上游超时/5xx/响应不可解析/网络失败 | `UPSTREAM_TIMEOUT`、`INVALID_RESPONSE`、`ADDRESS_BLOCKED` 等 |

`items[]` 字段 `title/url/domain/author/id/publishedAt/extra` 按 provider 能力尽力填充；`extra` 是字符串化附加字段（如 `followers`、`tags`、`score`、`statusCode`），只保留已核实字段。

`research.enabled=false` 时本端点 503 `RESEARCH_DISABLED`。

## POST /api/v1/research/lens

`multipart/form-data`，表单字段名 `file`，仅接受 JPEG/PNG/WebP（以魔数嗅探为准，不信任声明的 Content-Type），单文件 ≤20MiB。图片只转发给本机 sidecar，不落盘不入库。

响应 `ResearchLensResult`：

| 字段 | 含义 |
|---|---|
| `status` | `ok/blocked/timeout/upload_rejected/sidecar_unavailable`；sidecar 的 `blocked_bot`/`timeout`/`upload_rejected` 都以 **200** 返回并由本字段表达 |
| `frameSha256` | 入参图片字节的 SHA-256（小写十六进制，可复算去重） |
| `textSha256` | sidecar 提取正文的 SHA-256（无正文时省略） |
| `fetchedAt` / `elapsedMs` | sidecar 抓取时刻 / 单帧耗时（单帧实测 17–18s，25–40s 也正常） |
| `topTitles` / `topDomains` / `cites` | 正文启发式摘要（可为空，不保证是作品真名） |
| `links` | 结果卡片外链，**best-effort 且经常为空**；空数组不代表失败 |
| `resultUrl` | sidecar 结果页 URL（如有） |
| `error` | `{code,message}`（非 ok 状态时） |

错误映射：

| HTTP | code | 触发条件 |
|---|---|---|
| 400 | `INVALID_PARAM` | 非 multipart / 缺少 `file` 字段 / multipart 解析失败 / 类型不支持 / 图片为空 |
| 401 | `UNAUTHORIZED` | 缺 Bearer（全局鉴权） |
| 413 | `TOO_LARGE` | 图片超过 20MiB |
| 429 | `RATE_LIMITED` | Lens 每小时/每日预算耗尽，或 sidecar 返回 429 BUSY（单实例串行、已有请求在飞） |
| 502 | `RESEARCH_SIDECAR_ERROR` | sidecar 返回异常响应（401/403/502 NO_RESULT/5xx/结构不符） |
| 503 | `RESEARCH_SIDECAR_DISABLED` | 研究关闭或 sidecar 未配置 |
| 503 | `RESEARCH_SIDECAR_UNAVAILABLE` | sidecar 不可达 |
| 504 | `RESEARCH_SIDECAR_TIMEOUT` | 网关等待 sidecar 超过 120s（含进程内排队；sidecar 自报超时为 200 `status=timeout`） |

## 命令行工具

外部 AI 维护 CLI 已封装这三个端点（只读，无写入命令），无需再手写 HTTP；完整用法见 [工具 README](../../tools/ai_maintenance/README.md)。

```powershell
python -X utf8 tools/ai_maintenance/cli.py research context
python -X utf8 tools/ai_maintenance/cli.py research provider --provider fxtwitter --operation author --target "@synthetic" --limit 5
python -X utf8 tools/ai_maintenance/cli.py research lens --file "<本地图片>.png"
```

- 全部使用 `QIMENG_API_BASE`/`QIMENG_API_TOKEN`；缺配置退出码 2，不回显 token。默认输出人读摘要，全局 `--json` 输出与本节端点一致的原始 JSON。
- `research provider` 不校验 provider 矩阵：矩阵外或非法参数按服务端 400 `INVALID_PARAM` 报错，不自动换 provider。
- `research lens` 本地只按扩展名（`.jpg/.jpeg/.png/.webp`）与 20MiB 预检，真实类型仍由服务端按魔数嗅探；摘要只打印 `status`/`frameSha256`/`topTitles`/`links` 数量，不打印图片内容。
- 研究**默认关闭**：需服务端 `research.enabled=true`；Lens 还需**另行部署**本机 loopback sidecar（服务端不启动、不托管）。未启用时 provider 查询返回 503 `RESEARCH_DISABLED`，Lens 返回 503 `RESEARCH_SIDECAR_DISABLED`。

## sidecar 契约（本机进程）

sidecar 实现与部署细节见 [tools/research_sidecar/README.md](../../tools/research_sidecar/README.md)（PC/NAS 专用，Node + headless Edge，单实例串行）。

- 监听 loopback（`127.0.0.1` 或 `::1`），HTTP + Bearer token；服务端只接受 `http://127.0.0.1:<port>` 或 `http://[::1]:<port>`，启动期校验。
- `GET /healthz`：无需鉴权，2xx 且 `ready!=false` 视为可用（服务端仍会带 Bearer，实现可忽略）。
- `POST /lens`：`multipart/form-data` 字段 `file`（≤20MiB，JPEG/PNG/WebP）；响应为 JSON，字段用 snake_case（`frame_sha256`、`text_sha256`、`elapsed_ms`、`fetched_at`、`result_url`、`topTitles`、`topDomains`、`cites`、`links`），服务端同时兼容 camelCase。
- `ok`/`status`：成功 `{"ok":true,"status":"ok"}`；业务失败 `{"ok":false,"status":"blocked_bot|timeout|error","error":{"code":...}}`，错误码 `INPUT_INVALID/UPLOAD_REJECTED/BUSY/BLOCKED_BOT/TIMEOUT/NO_RESULT`。
- 映射：400/422 → 200 `upload_rejected`；503 BLOCKED_BOT → 200 `blocked`；504 TIMEOUT → 200 `timeout`；429 BUSY → API 429 `RATE_LIMITED`；401/502/5xx/结构不符 → 502 `RESEARCH_SIDECAR_ERROR`；不可达 → 503 `RESEARCH_SIDECAR_UNAVAILABLE`；网关自身等待超过 120s → 504 `RESEARCH_SIDECAR_TIMEOUT`。
- 服务端在进程内把 Lens 请求串行排队（sidecar 只有 1 并发），并在排队/请求期间计入 `sidecar.pending`。
- sidecar 未运行时研究仍可用：provider 查询不受影响，Lens 返回 503。

## 配置

```yaml
research:
  enabled: false            # 默认关闭；嵌入式/Android 形态保持关闭
  sidecar_endpoint: ""      # 仅 http://127.0.0.1:<port> 或 http://[::1]:<port>
  sidecar_token: ""         # 只从 env 或被忽略的 *.local 配置读取，禁止入库
  evidence_dir: ""          # 空 = DataDir/research-evidence
  lens_per_hour: 20
  lens_per_day: 100
  provider_per_minute: 20
```

环境变量（`QIMENG_RESEARCH_` 前缀）：`ENABLED`、`SIDECAR_ENDPOINT`、`SIDECAR_TOKEN`、`EVIDENCE_DIR`、`LENS_PER_HOUR`、`LENS_PER_DAY`、`PROVIDER_PER_MINUTE`。

启动期校验（失败即拒绝启动）：sidecar 端点必须回环 http 字面量且带显式端口；配置端点时必须提供 token；预算必须为正整数（yaml 显式 0 回落默认值，env 0 报错）。

## 限额

- 每个 provider **每分钟** `provider_per_minute` 次（默认 20），按 provider 独立计数；Lens **每小时** `lens_per_hour`（默认 20）与**每日** `lens_per_day`（默认 100）。
- 计数在进程内存，窗口按 UTC 对齐（小时=整点、日=UTC 零点），**重启即清零**。
- 超限时 provider 查询返回 `status=rate_limited`（仍 200），Lens 返回 429 `RATE_LIMITED`。

## 证据落盘

默认 `<dataDir>/research-evidence/<YYYY-MM-DD>/<provider|lens>-<HHMMSS>-<rand>.json`，权限 0600、目录 0700：

- provider 证据记录 provider/operation/target/status/耗时/items（URL 已剥 `token/sig/exp/session` 等会话参数）。
- Lens 证据只记 `frameSha256/textSha256/耗时/启发式摘要`，不含图片字节。
- 证据不入库、不参与业务查询；保留期由部署方自行清理（v1 无自动轮转）。

## 客户端研究流水线（方法学）

本节只约束"客户端如何用这些端点组合出可复核结论"，不新增协议字段。完整识图方法见[识图与作品归属核实](../../tools/IMAGE_RESEARCH.md)，身份判定与档案写入见[公开作者身份研究与证据留档](../../tools/AUTHOR_RESEARCH.md)。

### 研究顺序

| 步 | 动作 | 依据 |
|---|---|---|
| 1 | 读 `GET /research/context`，确认 `enabled`、provider 矩阵与剩余预算 | 关闭时不要发查询/Lens |
| 2 | 用 Lens 定作品归属（原帖、作品 ID、作者线索） | `POST /research/lens`；单实例串行，按 `frameSha256` 去重 |
| 3 | 用 provider 取平台字段核身份（X 优先，其次官方平台） | `POST /research/providers/{provider}/query` |
| 4 | 交叉核对稳定数字 ID、互链与时间，记录排除项 | 平台字段决定证据等级，Lens 只给线索 |
| 5 | 结论以 candidate 写入档案，主代理复核后才 verified | 研究结果不落库；写库走作者档案 API |

Lens 仅在 `sidecar.available=true` 时使用，否则直接走 provider 或人工。单文件 Lens 预算 1–3 次、失败最多重试 1 次、批量 JSONL 与断点续跑见[识图与作品归属核实](../../tools/IMAGE_RESEARCH.md#token-降本)。顺序上的硬约束是"先作品、后身份"：不能拿 Lens 页面出现的同名账号直接当作者。

### 证据落盘与 status 语义

| 观察 | 可支持的结论 |
|---|---|
| provider `status=ok`，平台字段含稳定账号/作品 ID 与互链 | 可到 likely；人工确认后才 confirmed |
| provider `status=ok`，但只有同名或简介相似 | candidate |
| provider `not_found` | 只证明该平台该次查询无对象，不等于账号不存在 |
| provider `blocked`/`rate_limited`/`provider_unavailable` | 本次失败；换 provider 或人工，不据此排除账号 |
| Lens `status=ok`（含空 `links`） | 最多 candidate；`topTitles`/`topDomains`/`cites` 是正文启发式 |
| Lens `blocked`/`timeout`/`upload_rejected`/`sidecar_unavailable` | 无结论；429/503 按退避，不形成重试风暴 |

服务端证据文件（`<dataDir>/research-evidence/...`）只含公开字段与哈希；客户端自己的批次记录写本地 JSONL，不把原文 HTML 或图片字节入库、入仓。

### provider 与 Lens 配合

| 已有线索 | 先查 | 再查 |
|---|---|---|
| 只有图片/视频帧 | Lens 整图，必要时四角水印或面部裁剪 | fxtwitter(author) 或 pixiv(author/work) 核对账号/作品 |
| 作品数字 ID | pixiv(work) 或 e621(post) | 作者主页与互链 |
| 账号或 handle | fxtwitter(author) | Pixiv、Iwara 等官方页 |
| 标签/角色 | yandere(tags)、e621(tags) | 作品页 → 作者 |
| 旧链接或失效页 | wayback(url) | 现平台页 |
| 创意工坊物品 | steam(workshop) | 作者主页 |

Lens 与 provider 互不替代：Lens 失败不影响 provider，provider 失败也不影响已拿到的作品线索。

### 外部 AI 的 JSON 契约建议

给外部 AI 的研究交接建议（本地文件，不是 API schema；字段与 `CreatorProfileWrite`、`ResearchProviderResult`、`ResearchLensResult` 对齐）：

```json
{
  "jobId": "local-20261108-01",
  "createdAt": "2026-11-08T00:00:00Z",
  "target": { "authorId": "内部 authorId", "originalName": "内部原名", "imageSha256": "64 位小写十六进制" },
  "candidates": [
    {
      "source": "lens|fxtwitter|pixiv|iwara|patreon",
      "workUrl": "https://example.com/work",
      "platformId": "平台数字 ID",
      "authorHandle": "@example",
      "confidence": "confirmed|likely|candidate|unresolved",
      "rejectedReason": "仅被排除的候选填写"
    }
  ],
  "evidence": [
    {
      "kind": "provider|lens|metadata|hash",
      "ref": "provider+operation+target 或 frame",
      "status": "ok|not_found|blocked|rate_limited|provider_unavailable|timeout|upload_rejected|sidecar_unavailable",
      "frameSha256": "可选",
      "fetchedAt": "RFC3339",
      "summary": "候选标题/域名/ID，不含原文 HTML"
    }
  ],
  "authorProfile": {
    "nickname": "公开昵称",
    "homepageUrl": "https://example.com",
    "avatarUrl": "https://example.com/avatar.png",
    "evidenceUrls": ["https://example.com/about"],
    "status": "candidate",
    "xProfile": { "url": "https://x.com/example", "biography": "原文", "biographyZh": "译文" }
  }
}
```

- `authorProfile` 是 `CreatorProfileWrite` 的摘要，实际 PUT 仍需 `revision`、`autoRefresh`、`feedUrl`、`trackUpdates` 等必填字段（以 OpenAPI 为准）；长度上限：nickname ≤200，biography/avatarUrl/homepageUrl/evidenceUrls 单项 ≤2000，evidenceUrls ≤8，xProfile.biography ≤1000，biographyZh ≤2000。
- `status` 只取 candidate/verified。研究阶段写 candidate；verified 需 HTTPS 主页与非空有效证据，且经主代理复核。
- 同一 `authorId` 的 job 可续跑；`candidates[].confidence` 按[识图文档的证据分级](../../tools/IMAGE_RESEARCH.md#证据分级)填写，不把 Lens 命中当 confirmed。
- 契约只描述交接内容，不新增端点或字段；实际调用以 [OpenAPI](../../api/openapi.yaml) 为准。

## 安全与部署条件

- **出口**：只走内建安全 HTTP 客户端——仅 https、provider 域名白名单、禁重定向与环境代理、DNS 解析后校验公网地址、超时 20s、响应上限 2MiB。不提供任意 URL 抓取面。
- **sidecar 凭据**：token 只经环境变量或被忽略的 `*.local` 配置注入；错误响应不回显 token、sidecar 响应正文不转发。
- **部署方式**：PC 上另行运行 Lens sidecar（headless 浏览器 + 单实例串行），配置 `sidecar_endpoint` 与 `QIMENG_RESEARCH_SIDECAR_TOKEN`，再设 `research.enabled=true`；服务端不启动/托管 sidecar。
- **嵌入式/Android**：默认关闭；打开只会带来出网与 CPU 开销，不新增监听面，但移动网络下的平台访问与 sidecar 依赖不保证。

## 已知限制（实际验收状态）

- 796d813f 已安装 PC/手机；2026-11-07 真实实例试验跑通 `context` 与 `/lens`（13s、frame SHA 一致、links 空）。provider 真实查询未通过：本机 DNS fake-IP（198.18.1.x）触发 SSRF 防护 ADDRESS_BLOCKED，需切真实 DNS/代理模式或另立显式逃生配置后再验收；真实 429/503/504 未触发。默认关闭，日常启动不自动启用研究。
- Bing 依赖 HTML 结构（`b_algo`），平台改版会导致 `provider_unavailable`；yande.re/e621/wayback 同为第三方公开接口，可用性不受本项目控制。
- Lens 单实例串行、单帧耗时 17–18s（实测 25–40s/帧），批量反查需按预算排队；`links` 为空是已确认缺陷，不能靠重试修复。
- 研究证据落盘目录随 `dataDir`（Docker 部署注意卷内空间与备份策略）。
