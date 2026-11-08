# 低 token 识图 / 作者核实工具包（research_kit）

本目录把本轮实战验证过的抓取能力整理成**低 token、可断点、可批处理**的独立脚本：Lens、Yandex、SauceNAO、FxTwitter、Kemono、ffprobe/frame、dHash、头像下载与编排。方法学与证据分级以[识图与作品归属核实](../IMAGE_RESEARCH.md)、[公开作者身份研究与证据留档](../AUTHOR_RESEARCH.md)、[媒体复核与双端整理](../MEDIA_RESEARCH_IMPORT.md)为准；本文件只写工具用法、契约与成本。

- 脚本不连接绮梦数据库、不写媒体库、不调用服务端、不启动 sidecar；不访问付费内容、不登录、不绕验证码。
- 真实作者名单、图片、结果、缓存与证据只放**被 Git 忽略**的本地目录（默认 `build/research-kit.local/`）。仓库内只保留通用代码、示例与契约。
- Node 24+ 标准库、Python 3.13 标准库，无 npm/pip 依赖；外部依赖只有 ffmpeg/ffprobe 与 Edge/Chrome（Lens 用）。

## 目录

```text
tools/research_kit/
  lens_batch.mjs        CDP/headless Edge 的 Google Lens 批处理（单飞、限速、断点）
  yandex_cbir.py        Yandex 反向图搜，输出精简候选
  saucenao_batch.py     SauceNAO 批量（4 次/30s、日上限、无 HTML）
  fxtwitter_probe.py    X 账号/帖子公开探针（昵称、数字 ID、简介、头像 URL、链接）
  kemono_probe.py       Kemono 公开 API（patreon/fanbox 创作者与帖子元数据）
  media_probe.py        ffprobe 元数据 + SHA-256 + 15/50/85% 帧抽取/裁剪
  dhash_compare.py      64-bit dHash 批量比对（汉明距离）
  avatar_fetch.py       头像 URL 规范化、下载、格式/像素校验、摘要、可选 JPEG
  run_pipeline.py       读 jobs、按阶梯调度、预算、失败单列、断点续跑
  examples/             jobs.example.jsonl、results.schema.json
  .gitignore            本目录缓存/证据兜底忽略
```

## 依赖与环境变量

| 依赖 | 要求 | 说明 |
|---|---|---|
| Node.js | 24+ | 仅 `lens_batch.mjs`（全局 `fetch`/`WebSocket`）；无 npm 包 |
| Python | 3.13 | 全部 `.py` 只用标准库（不用 requests/Pillow） |
| ffmpeg / ffprobe | 有可执行文件 | `media_probe`、`dhash_compare` 解码；`avatar_fetch --to-jpeg` 也用它 |
| Edge / Chrome | Lens 批处理 | 必须独占 profile；ARM NAS 常无可用浏览器 |

| 环境变量 | 默认 | 用途 |
|---|---|---|
| `QIMENG_RESEARCH_KIT_CACHE` | `build/research-kit.local/cache` | 所有缓存/中间帧/临时 jobs |
| `QIMENG_RESEARCH_KIT_EDGE` | 平台默认路径 | Lens 浏览器；回退 `QIMENG_RESEARCH_SIDECAR_EDGE` |
| `QIMENG_RESEARCH_KIT_NODE` | PATH 中的 `node` | `run_pipeline` 启动 Lens |
| `QIMENG_RESEARCH_KIT_FFMPEG` / `_FFPROBE` | PATH | 指定 ffmpeg/ffprobe |
| `QIMENG_RESEARCH_KIT_SAUCENAO_KEY` | 无 | SauceNAO API key，只从环境/被忽略的 key 文件读取 |
| `QIMENG_RESEARCH_KIT_KEMONO_BASE` | `https://kemono.su` | Kemono 镜像基址 |
| `HTTPS_PROXY` / `HTTP_PROXY` | 系统设置 | 默认沿用；`--no-proxy` 可关闭 |

单工具都支持 `--help`，并遵循统一退出码：**0 = 全部完成；1 = 部分失败/被限制/预算用尽；2 = 参数或环境错误**。

## 快速开始

下面的命令都在仓库根执行；占位路径按本机替换，真实路径与结果不要提交。

```powershell
# 1) 视频抽帧（可选裁四角/面部 ROI），得到 ≤1600px WebP
python -X utf8 tools/research_kit/media_probe.py "<本地视频>" --frames 15,50,85 --crops full,corner:br

# 2) Lens 批处理：jobs JSONL {id,file,rid?}，结果追加式 JSONL
node tools/research_kit/lens_batch.mjs --jobs tools/research_kit/examples/jobs.example.jsonl

# 3) Yandex / SauceNAO
python -X utf8 tools/research_kit/yandex_cbir.py "<本地图片>" --summary
python -X utf8 tools/research_kit/saucenao_batch.py --jobs "<jobs.jsonl>"

# 4) 作者探针
python -X utf8 tools/research_kit/fxtwitter_probe.py "@example_account" --summary
python -X utf8 tools/research_kit/kemono_probe.py --service patreon --creator "<creator-id>" --posts 10

# 5) dHash 比对（同帧/同作品筛查）
python -X utf8 tools/research_kit/dhash_compare.py --ref "<帧>" --candidate "<候选图或目录>"

# 6) 头像
python -X utf8 tools/research_kit/avatar_fetch.py --jobs "<avatars.jsonl>" --to-jpeg

# 7) 编排（默认自动选择依赖可用的阶段）
python -X utf8 tools/research_kit/run_pipeline.py --jobs "<jobs.jsonl>" --dry-run
python -X utf8 tools/research_kit/run_pipeline.py --jobs "<jobs.jsonl>"
```

### 输入与输出

- jobs JSONL（Lens/流水线）：每行 `{"id":"job-001","file":"<本地路径>","rid":"可选"}`；`id` 必须唯一，相对路径按**当前目录**解析。
- `rid` 只用于作者阶段：`@handle`、`x:handle`、`post:<id>`、`kemono:<service>:<creator>[:postId]`。
- 批量结果 JSONL 追加写、逐行落盘（media_probe 的 `--out` 每次重写）；字段契约见 [examples/results.schema.json](examples/results.schema.json)（**文档契约**，脚本运行时不校验，避免引入第三方 schema 库）。
- 批量工具默认跳过已记录 id；`--retry-errors`（Lens/SauceNAO/头像）重试非成功项。流水线默认跳过 `ok/skipped`，自动重试 `partial/failed`。

| 工具 | 单文件输出 | 关键字段 |
|---|---|---|
| lens_batch | 1 行 JSONL | `status/topTitles[]/topDomains[]/citesCount/resultUrl/ts`，可选 `frameSha256/attempts` |
| yandex_cbir | 1 个 JSON | `status/sites[]/similar[]/tags[]/cbirId` |
| saucenao_batch | 1 行 JSONL | `status/similarity/creator/source/title/index` |
| fxtwitter_probe | 1 个 JSON | 作者：`handle/name/id/bio/avatarUrl/links`；帖子：`id/text/authorId` |
| kemono_probe | 1 个 JSON | `creator{id,name,postCount}/posts[]{id,title,published,attachments}` |
| media_probe | 1 个 JSON 或 JSONL | `sha256/durationSec/width/height/fps/tags/frames[]` |
| dhash_compare | 1 个 JSON | `refs[]/matches[]{distance,hit,verdict}/stats` |
| avatar_fetch | manifest JSONL + 摘要 | `sourceUrl/format/width/height/sha256/savedAs` |
| run_pipeline | results JSONL + 失败 JSONL + ≤N 行摘要 | `lens/yandex/saucenao/author` 压缩摘要 |

## token 预算（每工具、每文件）

按“常见英文标题 + 域名 + URL”估算：约 3.5 字符/token；中文按 1.5 字符/token。下表取保守值，**单文件**输出通常不超过表中“典型”区间；上限由脚本自身裁剪。

| 工具 | 典型字符 | 典型 token | 硬性裁剪 |
|---|---|---|---|
| lens_batch | 350–700 | 100–200 | `topTitles` ≤8、`topDomains` ≤8、标题 ≤160 字符 |
| yandex_cbir | 900–1,800 | 260–520 | 站点 ≤12、相似图 ≤8、标签 ≤10、URL ≤500 |
| saucenao_batch | 160–260 | 50–80 | 仅首条 best、creator ≤120、source ≤300 |
| fxtwitter_probe | 600–1,000 | 180–300 | 简介 ≤500、链接 ≤3、帖子正文 ≤600 |
| kemono_probe | 1,000–1,600 | 300–480 | 帖子 ≤50、标题 ≤200、不含附件路径 |
| media_probe | 500–900 | 150–270 | 标签 ≤17 个/300 字符、帧 ≤12、可 `--no-frames` |
| dhash_compare | 500–1,200 | 150–360 | 默认 top 20 对；`--top 0` 才全量 |
| avatar_fetch | manifest 每行 ≈250 / 摘要 ≈250 | 70–80 / 80 | 不打印图片内容 |
| run_pipeline | 每 job 250–450；摘要 600–900 | 80–140 / 200–300 | 每阶段 ≤5 域名；摘要 ≤`--summary-lines`（默认 12）行 |

省 token 的做法：Lens 只回标题/域名/引用计数（不回正文）；Yandex/SauceNAO 只回候选摘要；`--summary` 进一步压缩；同一 `frameSha256` 直接命中缓存；批处理只把摘要喂给下一轮，不把整页 HTML/正文塞进提示词。

## 限速与失败语义

### 状态词

`ok`、`not_found`、`blocked`、`rate_limited`、`timeout`、`error`、`skipped`。SauceNAO 另有 `low_confidence`、`no_match`；Lens 另有 `upload_rejected` 与汇总态 `blocked_cooldown`；流水线 job 状态为 `ok/partial/failed/skipped`。`blocked` 只表示“本次被平台拒绝”，**不能据此判定账号/作品不存在**。

### 限速

| 服务 | 本地约束 | 失败处理 |
|---|---|---|
| Google Lens | 单飞，`--min-interval-ms 8000`；每 job 最多 2 次，blocked/timeout 不重试 | 风险信号立即停，写 60 分钟冷却标记；`--ignore-block-cooldown` 不建议用 |
| Yandex | 文件锁 1 次/4s；结果缓存 168h | 403/429 → 停该阶段，不循环重试 |
| SauceNAO | 4 次/30s（跨进程文件限速）+ 日上限 90 | 401/403/429 → 立即停；日额度用尽保留未处理项 |
| FxTwitter | 无强制限速；缓存 24h；超时 20s | 403/429 记录 blocked/rate_limited，不重试 |
| Kemono | 1 次/1.5s；缓存 6h | 不重试；CDN TLS 失败记 `blocked/tls_failed` |
| 本地 ffmpeg/dHash | 无网络 | 缺工具 → 退出码 2；单文件失败 → 1 |
| 流水线 | 默认预算 Lens 15、Yandex 10、SauceNAO 20、作者 20 | 预算用尽写 `*:budget` 备注，下次重试 partial |

### 重试原则

只对“可恢复的偶发错误”做有界重试：Lens 422/解析类错误最多 1 次；503/429/验证码/登录墙一律停止。SauceNAO、Kemono、Yandex 不做自动重试；TLS 证书失败不重试、不换 UA、不绕过。

## 优先级阶梯

与[识图文档的优先序阶梯](../IMAGE_RESEARCH.md#优先序阶梯)一致；阶梯用于“定作品”，拿到可核验原帖/稳定作品 ID 即停，再转入身份核验。**本工具包自动化视觉三入口 + 作者探针；第 8/9 级另有 media/dHash/头像工具**：

| 顺序 | 入口 | 本工具包 | 说明 |
|---|---|---|---|
| 1 | Google Lens | `lens_batch.mjs`（或服务端 `/research/lens`） | 视觉反查；`links` 常为空，`status=ok` ≠ 认出作者 |
| 2 | Yandex CBIR | `yandex_cbir.py` | 补 Lens 未覆盖站点；结果页结构可能变化 |
| 3 | SauceNAO | `saucenao_batch.py` | 二次元/绘画来源；相似度不是身份证据 |
| 4 | trace.moe | 外部/人工 | 动画帧定位，本包未封装 |
| 5 | 站内标签库/作品索引 | 外部/人工 | Danbooru/AIBooru/Iwara/Kemono 等，标签可能错标 |
| 6 | X（fxtwitter） | `fxtwitter_probe.py`（或 API provider） | 数字 ID + 查询日期一起留档 |
| 7 | 官方页 | 外部/人工 | Patreon/FANBOX/Pixiv 公开页；付费内容不碰 |
| 8 | 容器元数据 | `media_probe.py` | ARTIST/comment/PURL 只作线索，可被转载工具改写 |
| 9 | 水印/帧比对 | `media_probe --crops` + `dhash_compare.py` | dHash ≤2 同帧、3–15 人工、≥16 不同；仍需字节/时长核对 |

`run_pipeline` 的调度顺序固定为 media → Lens → Yandex → SauceNAO → 作者探针；命中指已知平台域名（X/Pixiv/Patreon 等）出现在 `topDomains` 或 Yandex 站点域名中，调用次数受 `--max-*` 限制；默认首个命中即停，`--deep` 才继续下一视觉入口。`cites` 只作人工复核线索，不自动算命中。作者探针与图片命中相互独立，避免把“同名账号”当作者。

## 与 /research API、research_sidecar 的边界

**能走 API 就走 API。** 研究默认关闭（`research.enabled=false`）；启用后优先用正式端点，协议与状态语义见[研究 API](../../docs/api/research.md)，决策见[ADR-0053](../../docs/adr/0053-研究流水线API集成.md)：

| 需求 | 优先入口 | 本工具包何时使用 |
|---|---|---|
| Lens 单图/少量 | `POST /api/v1/research/lens`（需本机 sidecar） | 批量、API 关闭、sidecar 预算/队列受限、需要本地 JSONL 断点 |
| X 账号/帖子 | `provider fxtwitter` | API 关闭或需要批量缓存/摘要 |
| Pixiv/yande.re/e621/Wayback/Steam/Bing | 对应 API provider | **不重复实现**；本包没有这些 provider |
| Yandex/SauceNAO/Kemono | 无 API | `yandex_cbir.py`/`saucenao_batch.py`/`kemono_probe.py` |
| 容器元数据/帧/dHash/头像 | 无 API | `media_probe.py`/`dhash_compare.py`/`avatar_fetch.py` |

- `lens_batch.mjs` 与 [research_sidecar](../research_sidecar/README.md) 是**两条独立路径**：sidecar 是仅供服务端 loopback 调用的常驻进程（Bearer token、单实例、`/lens`）；`lens_batch` 是研究用一次性 CLI，自带浏览器与 profile、无 token、无监听端口。两者不要共用同一个 `SESSION_DIR`/profile，也不要同时跑同一 profile。
- 本工具包不写数据库、不建媒体库、不调用受控填充；作者档案写入仍走正式 API 与复核流程（见[受控填充](../README.md#作者公开档案受控填充)）。
- 服务端证据目录（`<dataDir>/research-evidence`）与本包的本地 JSONL 是两套留档：前者由 API 生成，后者由本包生成；不要把真实作者/媒体路径提交进仓库。

## 缓存、证据与隐私

- 默认缓存：`build/research-kit.local/cache/<tool>/`（根 `.gitignore` 已忽略 `build/`）。本目录 `.gitignore` 另兜底忽略 `cache/`、`frames/`、`avatars/`、`evidence/`、`out/`、`__pycache__/`。
- 帧、头像、Yandex HTML（`--keep-html`）都可能含真实素材：只在本地保留，交接时只给摘要/哈希，不入库、不粘贴正文。
- API key 只从环境变量或 `--api-key-file`（被忽略文件）读取；脚本不打印、不落缓存。
- 头像下载只取显式给出的公开 URL（不跟随主页/付费页），只允许 https、拒绝字面回环/私网主机、限制 5MiB 与 4096px、按魔数+像素校验后写 0600 文件；SVG 等非位图拒收。
- 面部裁剪是**固定上部中央 ROI**，不是人脸检测；作为证据时必须写明“启发式裁剪”。四角裁剪用于水印/签名的可读性。
- Lens 浏览器以 `--no-sandbox` 无头方式运行（与 sidecar 相同的自动化兼容选项）：使用专用低权限账户与独占 profile，不与交互式浏览器、其他服务或不可信页面共享账户。
- 不绕验证码/登录墙，不换 UA/代理规避，不做重试风暴；403/429/503 停止并留状态。

## 自检

不访问外部网络的检查：

```powershell
node --check tools/research_kit/lens_batch.mjs
python -m py_compile tools/research_kit/yandex_cbir.py tools/research_kit/saucenao_batch.py tools/research_kit/fxtwitter_probe.py tools/research_kit/kemono_probe.py tools/research_kit/media_probe.py tools/research_kit/dhash_compare.py tools/research_kit/avatar_fetch.py tools/research_kit/run_pipeline.py
python -X utf8 tools/research_kit/run_pipeline.py --jobs tools/research_kit/examples/jobs.example.jsonl --dry-run
python -X utf8 tools/check_docs.py
```

`--help` 与 `--dry-run` 均不发起网络请求。真实批次建议先用合成样例跑通 `--dry-run` 与单文件探针，再放开预算。

## 已知限制

- Lens 结果来自正文启发式解析：`topTitles/topDomains/cites` 精度有限，`links` 常为空；`status=ok` 只证明查询执行完成。
- Yandex 页面结构随版本变化，解析可能为空（会记 `sites_parse_empty`）；Kemono/SauceNAO 是第三方公开接口，可用性不受本项目控制。
- SauceNAO 免费额度有限；批处理会等待限速窗口，单批可能很久。
- dHash 对裁剪/叠加/字幕敏感，只能筛同帧与近似，不能证明作者；同文件判定需再加 SHA-256 与时长。
- `media_probe` 的面部裁剪不是检测；`fps` 取容器平均帧率，VFR 素材可能不精确。
- 流水线不封装 trace.moe、Danbooru 等站内标签库与官方页抓取；这些步骤按[识图文档](../IMAGE_RESEARCH.md)人工执行。
- 未做 NAS/ARM 浏览器验证；Linux 需自备 Chrome/Chromium。