# Google Lens / Google 文本搜索 sidecar

默认关闭、仅 PC/NAS 可用的本机外部能力：服务端通过 loopback HTTP 调用本 sidecar，
由 sidecar 复用一个 headless Edge/Chromium 完成 Google Lens 反查图片和 Google 文本搜索。
sidecar 不访问绮梦数据库、不写媒体库、不对外监听，也不用于嵌入式/移动端形态。

本目录只包含 Node 标准库实现，无 npm 依赖。协议以本文件为准；服务端调用约定见下文「服务端接入示例」。

## 1. 形态与定位

- 一个 sidecar 进程 = 一个 headless 浏览器进程；请求之间复用该浏览器，每个请求使用新的 page target。
- HTTP 只绑定 127.0.0.1，默认端口 18421；浏览器 CDP 端口在 loopback 上随机选择（或显式指定），不对外暴露。
- 进程内单飞：同一时刻只允许一个 /lens 或 /search 在飞；第二个请求返回 429 BUSY 和 Retry-After: 2，无队列。
- 单个请求总预算默认 90 秒；Lens 上传页的 input[name=encoded_image] 每 1 秒轮询，最多等待 20 秒，
  用于覆盖冷启动慢页（旧原型在 10 秒时误报 422 的回归）。
- /search 使用同一浏览器的另一个 page，不共享 Lens 页面状态；文本搜索不会绕过验证码或登录墙。
- 收到 SIGINT/SIGTERM 或未捕获异常时关闭浏览器进程树；Windows 使用 taskkill /T /F 清理子进程。
- 仅作为 PC/NAS 上的可选 sidecar；Android/嵌入式/Tauri 离线形态必须禁用，不得把 18421 暴露到公网或反向代理。

## 2. 运行要求

- Node.js 24 或更高版本（需要全局 fetch、WebSocket、AbortSignal.timeout）。
- Windows 默认使用 Edge：
  C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe 或
  C:\Program Files\Microsoft\Edge\Application\msedge.exe。
- Linux 需自行提供 Chrome/Chromium（QIMENG_RESEARCH_SIDECAR_EDGE）；ARM NAS 常无可用浏览器，不能假定支持。
- 浏览器需要可写的独占 profile 目录；不要把同一个 SESSION_DIR 同时给两个 sidecar 进程。
- 无 npm 依赖，不需要 npm install。

## 3. 启动

### Windows PowerShell

不要把 token 写进命令历史、脚本或仓库。推荐先把 token 放进被 Git 忽略的本地文件，再注入进程环境：

~~~
$env:QIMENG_RESEARCH_SIDECAR_TOKEN = (Get-Content -Raw .\sidecar.token).Trim()
$env:QIMENG_RESEARCH_SIDECAR_ALLOWED_ROOT = 'D:\frames-readonly'
node .\tools\research_sidecar\sidecar.mjs
~~~

也可以使用 start.cmd；它只检查 token 是否已在环境中，不读取、不打印、不写 token：

~~~
$env:QIMENG_RESEARCH_SIDECAR_TOKEN = (Get-Content -Raw .\sidecar.token).Trim()
.\tools\research_sidecar\start.cmd
~~~

### Linux

~~~
set -a
. /etc/qimeng-research/sidecar.env
set +a
node tools/research_sidecar/sidecar.mjs
~~~

start.sh 同样只检查环境变量。环境文件权限必须限制为服务账号可读（如 0600），且不得提交。

### 环境变量

| 变量 | 必填 | 默认 | 说明 |
|---|---|---|---|
| QIMENG_RESEARCH_SIDECAR_TOKEN | 是 | 无 | 长随机串；缺失时进程拒绝启动并提示，不打印 token。建议至少 32 字符。 |
| QIMENG_RESEARCH_SIDECAR_ALLOWED_ROOT | 否 | 未设置 | JSON 路径模式允许的根目录；必须是已存在目录。未设置时只接受 multipart，JSON 路径模式返回 400。启动时 realpath 校验。 |
| QIMENG_RESEARCH_SIDECAR_PORT | 否 | 18421 | HTTP 监听端口；仅 127.0.0.1。 |
| QIMENG_RESEARCH_SIDECAR_CDP_PORT | 否 | 0 | 0 表示随机 loopback 端口；也可固定为 18422 等空闲端口。 |
| QIMENG_RESEARCH_SIDECAR_EDGE | 否 | 平台默认 | Edge/Chrome/Chromium 可执行文件路径。 |
| QIMENG_RESEARCH_SIDECAR_TIMEOUT_MS | 否 | 90000 | 单请求总预算，1000–300000。生产保持 90000。 |
| QIMENG_RESEARCH_SIDECAR_ATTACH_TIMEOUT_MS | 否 | 20000 | Lens 上传控件轮询上限，1000–60000。调小会让慢页更容易 422，仅用于排障/验证。 |
| QIMENG_RESEARCH_SIDECAR_DATA_DIR | 否 | OS 临时目录下 qimeng-research-sidecar | 数据目录；只用于默认 session 目录。 |
| QIMENG_RESEARCH_SIDECAR_SESSION_DIR | 否 | DATA_DIR/session | 浏览器 profile（含 Google cookie）。需独占、可写、不要提交。 |
| QIMENG_RESEARCH_SIDECAR_LOG | 否 | 仅 stdout | 可选 JSONL 日志文件。日志不包含 token、Cookie、完整本地路径。 |

QIMENG_RESEARCH_SIDECAR_TOKEN 只通过进程环境注入。启动脚本和 README 都不包含真实 token。
传给浏览器的子进程环境会删除该 token（浏览器不需要它）。

## 4. API 契约

### 鉴权

除 GET /healthz 外，所有 POST 都必须带以下任一头部：

~~~
Authorization: Bearer <token>
~~~

或：

~~~
x-lens-token: <token>
~~~

缺失或错误返回 401 UNAUTHORIZED。token 只从环境读取，不写入日志、不回显、不返回长度或哈希。
/healthz 不含鉴权，以便同一台机器上的守护进程探测；不要把它暴露到非 loopback。

### GET /healthz

无鉴权，HTTP 200。ready 表示浏览器进程和 CDP 均就绪；ok 只表示 HTTP 服务在运行。

~~~
{
  "ok": true,
  "ready": true,
  "edgePid": 12345,
  "inflight": 0,
  "counters": {
    "requests_total": 8,
    "ok_total": 3,
    "error_total": 5,
    "busy_total": 1,
    "error_counts": { "UNAUTHORIZED": 2, "INPUT_INVALID": 2, "BUSY": 1 }
  },
  "version": "1.0.0",
  "startedAt": "2026-11-06T15:56:05.582Z",
  "error": null,
  "browserState": "ready",
  "cdpPort": 3206,
  "timeoutMs": 90000,
  "attachTimeoutMs": 20000,
  "allowedRootConfigured": true
}
~~~

- 浏览器启动中/启动失败/退出后：ready:false，error 给出简短原因（不含绝对路径/token），browserState 为
  starting、failed 或 stopped。进程会继续提供 healthz，便于守护进程诊断。
- edgePid 在浏览器退出后为 null。
- counters 中的错误码计数用于运维；inflight 为 0/1。

### POST /lens

两种输入，二选一。

A. multipart/form-data（服务端推荐）

字段名固定为 file，只能有一个；文件 ≤ 20 MiB，内容必须是 JPEG/PNG/WebP（按魔数校验，
扩展名存在时必须与内容一致）。sidecar 把字节写入临时文件（权限 0600）后上传，请求结束后删除，
不保留原始帧。

~~~
POST /lens
Authorization: Bearer <token>
Content-Type: multipart/form-data; boundary=...
~~~

B. application/json

~~~
{ "file": "D:\\frames-readonly\\01-upload.webp" }
~~~

- file 必须是绝对路径；sidecar 对路径做 realpath，要求仍在 QIMENG_RESEARCH_SIDECAR_ALLOWED_ROOT
  内（符号链接解析后仍须在根内），且是普通文件、扩展名和魔数均为允许的图片。
- 未配置 allowed root 时该模式返回 400 INPUT_INVALID，只能使用 multipart。
- JSON body 上限 64 KiB；multipart body 上限约 21 MiB（20 MiB 文件 + 头部余量）。

成功 HTTP 200：

~~~
{
  "ok": true,
  "status": "ok",
  "frame_sha256": "ca8fa38c...",
  "elapsed_ms": 25513,
  "text_sha256": "afa3beef...",
  "text_len": 3353,
  "topTitles": [
    { "title": "页面标题（页面正文启发式）", "domain": "example.com" }
  ],
  "topDomains": ["example.com", "example.org"],
  "cites": [
    { "title": "引用文字（可选）", "url": "https://example.com/page", "domain": "example.com" }
  ],
  "links": [
    { "url": "https://example.com/page", "domain": "example.com", "title": "锚文本" }
  ],
  "result_url": "https://www.google.com/search?udm=26&lns_mode=un&lns_surface=26",
  "fetched_at": "2026-11-07T00:12:00.000Z",
  "request_id": "uuid"
}
~~~

字段说明：

- frame_sha256：实际上传字节的 SHA-256；应与调用方文件 SHA-256 一致。
- text_sha256/text_len：Google 结果页 document.body.innerText 的 UTF-8 SHA-256 和字符数。
- topTitles/topDomains：从正文逐行识别的域名启发式结果，不是平台官方结构化数据；topTitles 最多 20 条，
  topDomains 最多 30 个，顺序为页面出现顺序。
- links：页面中真实存在的外部 http(s) 锚点，最多 50 条；Lens 视觉匹配卡片通常没有外部 href，
  因此该数组实测常为 []，sidecar 不会编造链接。
- cites：带真实外部锚点的 cite 元素，最多 80 条；没有 href 的 cite 不返回。也可能为空。
- result_url：最终 Google 结果页 URL，已按「URL 清理」规则去会话参数。
- status:"ok" 只表示查询和解析完成，不表示图片识别正确；结果质量须由调用方复核。

### POST /search

~~~
{ "query": "site:x.com \"Aznyan07\"", "waitMs": 5000 }
~~~

- query：非空字符串，≤ 500 字符。
- waitMs：可选，0–30000，默认 5000；表示导航后至少等待的毫秒数，之后还会做短稳定等待。
- 使用同一浏览器另开 page，访问 Google 文本搜索；不绕过验证码/登录。Google consent 页会尝试一次
  正常「接受全部」点击；验证码、登录墙、/sorry/、HTTP 429 一律返回 503，不继续。

成功 HTTP 200：

~~~
{
  "ok": true,
  "status": "ok",
  "final_url": "https://www.google.com/search?q=site%3Ax.com+%22Aznyan07%22&num=20",
  "title": "site:x.com \"Aznyan07\" - Google Search",
  "text_len": 2458,
  "items": [
    {
      "title": "Aznyan07 \"Wholesome\"",
      "url": "https://x.com/aznyan07",
      "domain": "x.com"
    }
  ],
  "fetched_at": "2026-11-07T00:12:00.000Z",
  "request_id": "uuid",
  "elapsed_ms": 14048
}
~~~

- items 最多 20 条，按页面顺序去重；只包含外部 http(s) 结果。
- Google 当前搜索结果标题链接多为 google.com/goto?url=<opaque>。sidecar 使用当前浏览器内存中的 Google cookie
  对这一跳发起 GET，只读取 302 的 Location，并立即取消响应体，不下载目标站点正文；不把 cookie 写日志。
  若某条跳转返回中间页、超时或非 302，则该条被省略，不编造 URL。
- 有效结果页可以没有任何可解析 item（例如 Google 判定无匹配）；此时返回 200 和 items: []，不是 503。
  只有页面本身不可用/不是搜索结果页时才返回 502。
- /search 不需要 file，因此不会返回 422；422 只属于 /lens 的上传控件失败。

### URL 清理

所有响应中的 URL 都会清理：

- Google 域只保留白名单参数 udm、lns_mode、lns_surface、q、tbm、start、num；
  hl、gsessionid、lsessionid、vsrid、authuser、qsubts、biw、bih、sxsrf、ved、
  usg、sa、source 等会话/追踪参数不会返回。
- 外部 URL 去除上述会话参数和 #:~:text= 文本片段；不删除业务查询参数（如 utm_* 之外的正常参数）。
- 含用户名/密码的 URL 会清空凭据；非 http(s) URL 不返回。
- 返回的 domain 为小写、去前导 www.、去尾点的主机名。

### 错误响应

HTTP 状态 + error.code 固定如下：

| HTTP | code | 触发 | 额外 |
|---|---|---|---|
| 400 | INPUT_INVALID | JSON 坏、缺 file、相对路径、不存在、根目录外、非法/不匹配图片、超 20 MiB、multipart 缺 file、body 超限 | 不返回绝对路径 |
| 401 | UNAUTHORIZED | token 缺失/错误 | 无 |
| 404 | NOT_FOUND | 未知路由 | 无 |
| 422 | UPLOAD_REJECTED | Lens 上传页 20 秒内找不到 input[name=encoded_image]（未上传） | inputs 仅字段名/accept |
| 429 | BUSY | 已有请求在飞；limit=1 | Retry-After: 2 |
| 502 | NO_RESULT | 未到结果页、结果正文过短、浏览器未就绪/页面任务失败 | 可能带 result_url |
| 503 | BLOCKED_BOT | captcha、unusual traffic、/sorry/、HTTP 429、consent/login 墙 | signals；Retry-After: 600 |
| 504 | TIMEOUT | 单请求 90 秒预算耗尽 | 无 |

响应体统一为：

~~~
{
  "ok": false,
  "status": "error",
  "request_id": "uuid",
  "elapsed_ms": 12,
  "error": {
    "code": "INPUT_INVALID",
    "message": "file is outside the configured allowed root"
  }
}
~~~

status 为 blocked_bot（503）或 timeout（504）时与错误码对应；其余为 error。
error.message 已做 token/本地路径脱敏，调用方只应按 code 分支，不应依赖 message 文本。

## 5. 服务端接入示例

服务端（Go）侧建议默认关闭、异步任务化，并只通过 loopback 调用：

~~~
QIMENG_RESEARCH_ENABLED=false
QIMENG_RESEARCH_SIDECAR_ENDPOINT=http://127.0.0.1:18421
QIMENG_RESEARCH_SIDECAR_TOKEN=<与 sidecar 相同的 secret>
~~~

接入要求：

- 只在服务端进程内使用 endpoint/token，不下发给 Web/Android/桌面客户端；不把 18421 暴露到局域网或公网。
- 用任务队列串行化调用，sidecar 单并发；收到 429 按 Retry-After 退避，不并发重试。
- 调用超时应大于 90 秒（如 100–120 秒）；请求处理本身不要同步阻塞在 sidecar 上。
- 优先使用 multipart file 字段，避免把本机绝对路径暴露给服务端或配置 allowed root。
- 按 frame_sha256 缓存 Lens 结果，避免重复上传同一帧；status=ok 不等于识别正确，仍需 AI/人工复核。
- 502/503/504 应降级到缓存/其他 provider/人工，不能形成重试风暴。503 至少停止 30–60 分钟；429 至少停止 10 分钟。
- 422 只对 Lens；冷启动偶发时最多重试一次，且必须先确认 sidecar 仍 ready。

## 6. 安全边界

- 仅 loopback：HTTP 监听地址写死 127.0.0.1，不支持配置外部网卡；不要用端口转发/反向代理暴露。
- 必须 token：QIMENG_RESEARCH_SIDECAR_TOKEN 缺失时拒绝启动；token 不落日志、不落响应、不回显；
  Edge 子进程环境会删除该 token。
- 路径边界：JSON 路径模式 realpath 后必须位于 allowed root；Windows 比较大小写不敏感；拒绝非普通文件、
  非法扩展名/魔数、空文件和超过 20 MiB 的文件。multipart 不依赖路径，但同样做魔数和大小校验。
- 临时文件：multipart 落盘到系统临时目录，权限 0600，请求结束（含失败/超时）删除；日志不记录临时路径。
- 浏览器 profile：SESSION_DIR 含 Google cookie，属于敏感本地状态；权限由操作系统账户控制，不提交、
  不跨用户共享。需要重置会话时停止进程后删除该目录。
- 浏览器以 --no-sandbox 无头模式运行以兼容自动化环境；必须使用专用低权限账户和独占 profile，不能与
  不可信页面、其他服务或交互式浏览器共享。
- 不绕过风控：不自动解决验证码、不提交登录、不切换 UA/代理规避；检测到风险信号立即 503。
  仅对 Google consent 页做一次普通「接受全部」点击。
- 无 CORS：不返回 Access-Control-Allow-Origin；浏览器页面不能跨源读取响应。POST 仍需 token。
- 嵌入式/移动端禁用：Edge/CDP 方案只适用于 PC/NAS；Android 内嵌、离线单机和 Tauri 壳不得启动 sidecar，
  也不得假定有可用浏览器。
- 日志：结构化日志只包含事件、request_id、耗时、SHA、字节数、域名、错误码/信号；不记录 token、Cookie、
  Authorization、完整搜索 query（只记录长度）或完整本地路径。

## 7. 限制

- 速度：实测 Lens 单帧约 13–34 秒（含冷启动；常见 15–25 秒），不是交互级接口。/search 约 8–20 秒。
- 并发：单进程 limit=1，无队列；并发第二请求直接 429。需要吞吐时由服务端排队，不能靠多开端口共享 profile。
- links 常为空：Lens 视觉匹配卡片通常无外部 href；links/cites 实测可为空，调用方不能把空数组当“无结果”。
  topTitles/topDomains 是正文启发式解析，精度有限，需复核。
- 结果质量：Lens 的网页兜底结果可能完全不相关；status=ok 只表示查询执行完成。不要直接据此入库。
- 冷启动：首次请求可能在上传页等待较久；sidecar 已把 attach 轮询放宽到 20 秒。仍可能因网络/consent 返回 422/503。
- 搜索链接解析：Google 结果标题使用 /goto?url=<opaque>；sidecar 依赖当前浏览器 cookie 做一次 302 解析。
  若 Google 返回中间页、超时或拒绝，该条 item 会被省略，因此 items 数量可能少于页面可见结果；不会编造 URL。
- 风控即停：本 sidecar 不做自动重试/换 IP/绕过。503 后由调用方停止并人工处理；连续失败时不要重启风暴。
- 单浏览器/单 IP：一个进程只对应一个浏览器和出口 IP；没有吞吐、长稳、配额或多 IP 结论。
- 20 MiB / 20 秒 attach / 90 秒总预算是硬边界；更大的帧或更慢的网络需要调用方先压缩/重试策略。
- 无持久化调用审计：不保存请求/响应正文；需要证据时由调用方自己记录（不要把 token/Cookie 写进去）。

## 8. 部署

### Windows PC

1. 安装 Edge，确认默认路径存在；如不在默认路径，设置 QIMENG_RESEARCH_SIDECAR_EDGE。
2. 用专用低权限账户运行 sidecar；token 放在只有该账户可读的本地文件/服务环境变量中，不放到命令行或仓库。
3. 用 start.cmd 或任务计划/服务包装器启动；保证 SESSION_DIR 在本地磁盘且该账户可写。
4. 防火墙不需要放行 18421（仅 loopback）；不要做 netsh 端口转发。
5. Edge 自动更新可能改变页面结构；升级后先跑一次真实帧验证，再恢复调用。

### Linux / NAS

- 仅建议 x86_64 且能运行 Chrome/Chromium headless 的 NAS/PC。多数 ARM NAS 没有可用浏览器，明确不支持。
- 设置 QIMENG_RESEARCH_SIDECAR_EDGE 指向实际可执行文件；先用 --version 和一次真实请求验证。
- systemd 示例（文件名/路径/用户按现场替换；token 放在 EnvironmentFile，权限 0600，拒绝提交）：

~~~
[Unit]
Description=Qimeng research sidecar
After=network-online.target

[Service]
Type=simple
User=qimeng-research
EnvironmentFile=/etc/qimeng-research/sidecar.env
ExecStart=/usr/bin/node /opt/qimeng/tools/research_sidecar/sidecar.mjs
Restart=on-failure
RestartSec=5
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
~~~

- PrivateTmp=true 时临时上传目录在服务私有 tmp；如果启用 systemd 文件系统沙箱，还要给 SESSION_DIR 和
  QIMENG_RESEARCH_SIDECAR_LOG 所在目录写权限。
- 容器部署：sidecar 和 Go 服务必须在同一个 network namespace，或 sidecar 跑在宿主机且 Go 通过 host loopback
  访问；不要 -p 18421:18421 发布端口。不要挂载 Docker socket。
- 会话目录放在持久卷可减少冷启动/consent 波动；如果放在 tmpfs，重启后 cookie 丢失，验证会更慢。

## 9. 故障排查

| 现象 | 处理 |
|---|---|
| 启动即退出，提示 QIMENG_RESEARCH_SIDECAR_TOKEN is required | 在服务环境设置 token；不要通过命令行参数或仓库文件传递。 |
| 401 UNAUTHORIZED | 检查调用方与 sidecar 是否同一个 token；Bearer 格式为 Authorization: Bearer <token>，或 x-lens-token。 |
| 400 INPUT_INVALID（路径） | 检查绝对路径、文件存在、realpath 是否在 allowed root、扩展名/魔数、大小 ≤20 MiB；未配置根目录时改用 multipart。 |
| 422 UPLOAD_REJECTED | 上传页未在 20 秒内出现 encoded_image。常见于冷启动/网络慢/consent；重试一次，仍失败则检查出口网络和页面是否被拦截，不要循环。 |
| 429 BUSY | 已有请求在飞；服务端队列表串行化，按 Retry-After 等待；不要并发重试。 |
| 502 NO_RESULT | 先看 /healthz 的 ready/error/browserState；浏览器退出或页面任务失败时重启 sidecar。也可能是结果页未稳定/正文过短。 |
| 503 BLOCKED_BOT | captcha/unusual traffic/sorry/consent/login；立即停止该出口的调用 30–60 分钟，人工处理；不换 UA/代理绕过。 |
| 504 TIMEOUT | 网络太慢或总预算不足；检查 90 秒预算、Google 可达性和服务端超时；不要用更短超时反复重试。 |
| /search 200 但 items 为空 | 查询本身无结果，或 Google /goto 跳转被中间页/超时拦截；这不是 503。放宽查询或改用 Lens，稍后重试。 |
| 端口占用/启动失败 | 改 QIMENG_RESEARCH_SIDECAR_PORT；确认上一进程已退出；不要复用同一 SESSION_DIR 启动第二个实例。 |
| 浏览器 profile 锁/Edge 启动失败 | 停止旧进程，检查 SESSION_DIR 权限和残留 Edge 进程；必要时更换独立 session 目录。 |
| Lens 结果与旧结果不一致 | Lens 排名/网页兜底会漂移；用 frame_sha256 去重并以 AI/人工复核，不把 status=ok 当识别正确。 |

## 10. 实测记录

本次验证使用只读批次帧，未写入 8420/真实库，未提交 Git。证据保存在被忽略的本地目录
audit/research-sidecar-test.local/（不在仓库内提交）：

- 帧：audit/new-folder2-review.local/images/01-upload.webp，文件 SHA-256
  ca8fa38c299990eb2fc9497c8eb5cf694b6da703f0c0183800b2fb3219fa8dcf。
- POST /lens（JSON 路径模式）：HTTP 200，status:"ok"，frame_sha256 与文件 SHA 一致；
  最终运行 29.5 秒，text_len 2911，topTitles 9 条，links: []、cites: []。多次运行耗时约 25–34 秒。
- POST /lens（multipart file）：HTTP 200，同一 frame_sha256；最终运行 13.2 秒，text_len 2913。
- POST /search：site:x.com "Aznyan07"，HTTP 200；最终运行 15.9 秒，返回 5 条 items（上限 20），
  final_url 已去 hl/会话参数。不同运行因 Google /goto 解析差异返回 2–9 条，均只保留可解析的外部 URL。
- GET /healthz：ready:true、edgePid 在请求前后一致；计数器显示 3 次成功、2 次 401、2 次 400、1 次 429。
- 并发第二请求：HTTP 429，BUSY，Retry-After: 2。
- 坏路径/根目录外：HTTP 400，INPUT_INVALID；未知路由：404；缺失/错误 token：401。
- 缺失 token 启动：退出码 1，提示设置 QIMENG_RESEARCH_SIDECAR_TOKEN，不打印 token。
- 422 边界：另起隔离实例，将 attach 轮询临时调为 1000ms，Lens 请求返回 422 UPLOAD_REJECTED
  （input[name=encoded_image] not found，未上传）；生产默认仍为 20 秒。
- 504 边界：另起隔离实例，将总预算临时调为 3000ms，/search 返回 504 TIMEOUT（elapsed 3003ms）。
- 浏览器退出：kill 掉隔离实例的 Edge 后，/healthz 变为 ready:false、edgePid:null、
  error:"browser exited unexpectedly (code=1)"、browserState:"failed"；随后 /search 返回 502 NO_RESULT。

未能在本次不制造风险的前提下真实触发 503（验证码/429/风控）；它由代码路径和信号检测实现，本次未观察到。
未做 20–30 帧/1 小时 soak、吞吐/配额、代理/多 IP、真实 NAS 硬件验收；这些不能由本次单机小样本推断。

## 11. 未做/未验证

- 真实风控 503 未触发；未做验证码/登录墙人工流程。
- 未做 20–30 帧长时间 soak、并发队列和吞吐测试。
- 未在真实 NAS/ARM 设备验证；Linux 只保证「有可用 Chromium/Chrome」的 x86_64 环境。
- Lens links 仍可能为空；未实现视觉匹配卡片专用 URL 提取。
- 结果标题/域名是正文启发式，未做与平台官方 API 的逐条对账。
- 没有内置持久化调用审计、指标端点、token 轮换或多进程管理；由服务端/操作系统负责。
- SIGINT/SIGTERM 优雅退出路径已注册但未单独做信号级验证；本次只验证了任务被强制结束后没有残留测试 Edge 进程。

## 12. 相关工具

- 服务端调用契约以本文件为准；上层服务端代理实现不在本目录。
- 维护与验证工具总入口：[tools/README.md](../README.md)。
