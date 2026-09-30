# SECURITY - 安全设计

> 威胁模型：纯内网单用户起步。防御目标 = 局域网内误访问与横向渗透（访客 WiFi、被入侵的智能设备）+ 未来远程访问的暴露面。不防御公网级 DDoS/爬虫。
> 每条规则都配有 API 集成测试用例（进 CI），**安全靠机制和门禁，不靠自觉**。
> 最后更新：2026-10-01（「上传安全」节补**分片续传通道四道校验分布**（ADR-0028：create 前置三道 + complete 终检，临时分片落 DataDir/uploads-tmp 不入库、autoAccept 同闸防旁路）。同日此前（「鉴权设计」签名直链条目补 exp 窗口对齐语义（ADR-0027），红线清单无涉不改）。此前 2026-09-25（上传安全节「挂靠参数 fail-fast」句改为退役注记——上传挂靠三参数随 ADR-0024 删除，上传回归四道校验纯路径；「作者总表镜像（唯一例外写点）」节无涉不改——来源词表是 kv 内容非文件写点）。同日此前（上传安全节补挂靠参数 fail-fast 校验一句；新增「作者总表镜像（唯一例外写点）」节）。2026-09-22（回收站到期自动清除落地（trash.retention_days/sweep_interval，原「尚未实现」表述废止）；新增「Host 校验（DNS rebinding 防御）」节与 trusted_hosts 配置；安全响应头基线补 `X-Frame-Options: DENY`）。2026-09-19（新增「备份快照」节）。2026-09-12（文档准确性清偿：四处「文档先行于实现」如实标注）

## 红线清单（AI 改代码时逐条自查）

| # | 红线 | 机制 |
|---|---|---|
| 1 | 路径穿越 | 一切来自请求的路径参数：`filepath.Clean` 规范化 → 前缀校验限制在 Library 根内 → 拒绝 `../` 逃逸。统一在接口层拦截，handler 禁止自行拼接路径 |
| 2 | SQL 注入 | sqlc 全参数化，禁止字符串拼接 SQL（lint 规则卡） |
| 3 | 媒体误删 | DELETE 一律进回收站；物理删除仅在回收站内显式操作；Docker 中媒体目录挂载可写但回收站兜底 |
| 4 | 上传滥用 | 四道校验：扩展名白名单 + MIME 嗅探（读文件头）+ 单文件大小上限 + 目标路径穿越检查。写入用临时文件 + 原子 rename |
| 5 | 无鉴权访问 | 除健康检查外全部 API 要求 Bearer token；媒体直链用短期 HMAC 签名 URL（默认 6h 有效），禁止裸直链 |
| 6 | 请求体滥用 | JSON body 上限 1MB（例外：旧版备份导入 64MB，errors.go decodeJSONWithLimit / legacyImportMaxBody，单文件全量备份随库规模增长）；分页 size 上限；SSE 连接数上限 |
| 7 | 信息泄露 | 错误响应不含内部路径/堆栈（已实现）；访问日志脱敏（token 只记前 4 位）为规划项——访问日志本身尚未实现，见 OBSERVABILITY；/metrics 随全局 Bearer 门禁 |
| 8 | 公网暴露 | 永不开公网端口；远程访问只走 Tailscale/WireGuard 隧道（服务端零改动） |

## 鉴权设计

- **单用户起步**：首次启动 `POST /api/v1/auth/setup` 设置管理密码 → 服务端用 `crypto/rand` 生成 token 返回（只显示一次）；后续请求 `Authorization: Bearer <token>`。
- **多设备并发会话（ADR-0021，2026-09-17 起）**：有效 token 哈希存 `auth_sessions` 表（migration 0011）——每次登录/setup/dev-login 签发一条独立会话，多端并存互不挤兑（旧「单 token 签发即重铸」模型废弃，它是手机反复掉线的根因）；`POST /auth/logout` 按请求 token 吊销单条会话，其他设备不受影响；每用户会话上限 16，超限自动裁最旧。升级兼容：旧模型唯一哈希由迁移回填成一条 legacy 会话，已登录设备不掉线。`users.token_hash` 列弃用不删（NOT NULL 兼容占位）。
- 用户表结构第一天就按多用户设计（id/名称/密码哈希/角色/token），实现先只放一行。密码哈希用 argon2id（标准库外选型写 ADR）。
- **auth 端点限速（2026-09-17 起）**：setup/login/dev-login 共享进程内固定窗口限速（默认 10 次/分钟，超限 429 `RATE_LIMITED`）——缓冲暴力猜解与 argon2id（m=64MB/次）的内存 DoS；Bearer 业务端点与 /auth/verify 不受限速影响。
- **安全响应头基线（2026-09-17 起；2026-09-22 补 frame 头）**：全部响应带 `X-Content-Type-Options: nosniff`（MIME 嗅探防护）+ `X-Frame-Options: DENY`（点击劫持防护——SPA/验收页/管理页禁止被第三方 iframe 嵌套；`<img>`/`<video>` 标签消费媒体直链不受 framing 头影响）。
- **签名直链**：`/media/orig/...?exp=...&sig=HMAC(路径+过期时间, 服务端密钥)`——浏览器/播放器无需带 header 即可加载，但链接有时效。**exp 窗口对齐（ADR-0027，2026-10-01 起）**：exp = 下一窗口上界 + TTL（窗长=TTL=`token_ttl` 缺省 6h，对齐 Unix 纪元整点），同一窗口内所有响应签出**逐字节相同**的 URL（浏览器 HTTP 缓存/ETag 得以命中，URL 格式与参数未变、三端零适配）；有效期恒 ∈ (6h, 12h]，**吊销粒度=窗口级**——泄露直链最长存活约 2×TTL（粗于旧「请求级 6h」，风险接受换缓存命中），总闸不变 = 删除 media-secret 文件换密钥立即吊销全部存量直链。
- token 泄露应急（多会话模型口径）：受控设备直接 `POST /auth/logout` 吊销对应会话；**注意「自己再登录一次」不再吊销旧 token**（旧模型的挤兑行为已废除）。全部会话吊销 = 停服清空 `auth_sessions` 表（或删除数据目录重建）；直链吊销仍走删除 `media-secret` 文件。「管理端一键重置全部 token」为规划项。

## 开发模式（红线 5 的单点例外，仅限本机）

- **默认关闭**：`config.auth_dev_mode`（env `QIMENG_AUTH_DEV_MODE`）默认 false；关闭时 `POST /api/v1/auth/dev-login` 恒 404，所有 API 依旧要求 Bearer token——生产/默认部署零行为变化。
- **开启时语义**：`/auth/dev-login` 免密码直接签发 token（未初始化自动创建 admin 占位用户；多会话语义与 /auth/login 相同，ADR-0021——签发独立会话，既有会话不失效）。
- **App 端免密通道（2026-09-06 起）**：Android 客户端登录时**密码留空即走 dev-login**（`AuthRepositoryImpl` 空密码分支）；服务端未开启 dev 模式时 404 → App 提示「该服务器未开启免密模式，请输入密码登录」并要求密码，生产部署零影响。纯客户端行为，协议面无改动（dev-login 端点 2026-09-03 即有）。
- **内嵌形态共享密钥门禁（2026-09-30 批A）**：服务端配置 `auth_dev_shared_secret`（env `QIMENG_AUTH_DEV_SHARED_SECRET`）时，`/auth/dev-login` 须携带 `X-Qimeng-Dev-Secret` 请求头且匹配（`subtle.ConstantTimeCompare` 恒时比对）否则 401。校验顺序=限流 → dev 模式 404 → 密钥 401（404 语义优先，不泄露密钥配置状态；401 在自动建户之前，越权者触发不了占位用户创建）。**未配置密钥时零校验零影响**（Web 端 bat 免密/日常开发完全不变）。Android 内嵌形态由 `EmbeddedServerConfig.generateDevSharedSecret()` 每次拉起子进程随机生成（SecureRandom 32 字节 URL-safe，256bit 熵下限），经 ServerConfigDataSource **内存槽**（不落盘不进日志，生命周期=子进程）供 devLogin 同源带出。
- **边界**：仅限开发阶段本机调试（用户约定：项目未完成前免密码直奔 UI）；**禁止**与 `listen: ":0.0.0.0"`、公网、Tailscale 等任何远程访问组合使用——它等价于"无凭据登录通道"，必须保持在内网受信主机之内。服务端启动时对该组合打 Warn 日志（dev 模式开启且监听地址非回环，2026-09-07 起；只提醒不阻止，本机开发脚本的既定用法）。
- **本机开发脚本**：仓库根 `_server-common.cmd`（两份 `启动服务端*.bat` 共用的启动链单点）已默认带 `QIMENG_AUTH_DEV_MODE=1`（2026-09-03 起）——生产/远程部署**必须移除该行**（或改回 `0`）。
- 部署清单检查项：docker-compose / 生产 yaml **不得**出现 `auth_dev_mode: true`（CI 不做强制 gate，靠部署者自查 + 文档双签）。

## 媒体库注册（allowed_library_roots）

- **配置键**：`allowed_library_roots`（yaml）/ `QIMENG_ALLOWED_LIBRARY_ROOTS`（env，路径列表，`;` 或本平台 `os.PathListSeparator` 分隔）。
- **空 = 关闭**：默认空列表，注册行为与本开关引入前一致——任意服务端可访问的绝对目录均可注册（本地零配置开发不受影响）。
- **非空 = 强制**：`POST /api/v1/libraries` 的 `rootPath` 必须位于任一允许前缀之下（含前缀本身，路径 Clean + 大小写不敏感），否则 400 `INVALID_PARAM`「库路径不在允许的根目录白名单内」。白名单外路径不探测文件系统，避免存在性侧信道。
- **生产建议**：NAS/Docker 部署务必配置，把注册面收敛到已挂载的媒体卷（如 `/media`、`/mnt/photos`），防止误把 `/`、系统目录或无关共享挂进扫描/直链管线。该白名单只约束**新注册**；已入库的库不受影响（改配置不会追溯删除既有库）。

## 上传安全（对应需求：手机直传 NAS）

- 白名单：图片 jpg/jpeg/png/gif/webp/avif；视频 mp4/mkv/webm/mov/m4v/avi。
- MIME 嗅探按文件头魔数判断（不信 Content-Type 声明）。
- 文件名清洗：去路径分隔符、控制字符、Windows 非法字符 `:*?"<>|`；冲突自动追加序号。
- 流式落盘（禁止整个读进内存），临时目录写入完成后原子 rename 到目标。
- 挂靠参数退役注记（2026-09-25，ADR-0024）：上传曾有的可选作者/来源 query（`authorId`/`authorName`/`source`，ADR-0023 曾设收流前 fail-fast 校验：互斥/依附/上限/能力限定/控制字符拒绝）已**整体删除**——上传回归纯四道校验路径，参数面仅 libraryId/dir/filename，原「控制字符可向 TXT 片段注入任意行」的注入面随参数一并消失。程序化写 TXT 的新入口是作者编辑端点（PUT /assets/{id}/authors、PUT /authors/{id}/sources、PUT /authors/source-vocabulary，ADR-0024）：资产作者关联只接受既有作者 ID（不接受自由文本新建），来源词仍逐项拒绝控制字符（单项超 500 rune/原始数组超 32 项 400）——行文本一律由服务端构造，不存在用户自由文本拼行面；既有四道校验与上传行为不变。
- 上传速率/并发上限为规划项（尚未实现）：当前防线 = 单文件大小上限（配置 + 设置页 kv 双层取小）；速率/并发档位待 NAS 实机压测后立项。
- **分片续传通道的四道校验分布（ADR-0028，2026-10-01）**：`POST /uploads` 创建会话时做**可前置的三道**——文件名清洗（第④道规则，禁止路径穿越）、扩展名白名单（第①道）、size ≤ 上限（第③道），另与直传同闸 `upload.autoAccept`（false 时两通道一起 403，防分片通道成为直传被关后的旁路）并要求库存在且启用；`POST /uploads/{id}/complete` 对拼装完成的整文件做**终检**——魔数嗅探（第②道，读拼装文件头）+ 四道复跑（`filing.ValidateUpload` 同一函数）+ 目标路径穿越检查，终检失败会话保留可重试。分片字节落 `DataDir/uploads-tmp/`（服务端私有数据根，**不入库目录**——未过四道校验的半成品绝不进媒体面；.part 后缀在媒体白名单外，目录误配进库也不会被扫描器收）；入库走与直传完全相同的管线（临时文件+原子 rename、单写事务）。会话/分片生命周期：无活动 24h 过期清扫连临时文件回收、关停尽力清理；全部操作 Bearer 鉴权（无匿名豁免）。同名冲突语义与直传一致：自动重命名，永不 409。

## 回收站（可写目录的安全补偿）

- 位置：数据目录内（`/data/trash/`），与媒体库隔离。
- 结构：保留原相对路径结构 + 元数据 JSON（原路径/删除时间/asset_id）。
- 恢复：原路径被占用时冲突重命名。
- **到期自动物理清除（2026-09-22 实现原规划项）**：后台清扫按 `trash.sweep_interval`（默认 1h，env `QIMENG_TRASH_SWEEP_INTERVAL`）巡检，超过 `trash.retention_days`（默认 30 天，env `QIMENG_TRASH_RETENTION_DAYS`）的条目物理清除并联动清理缩略图缓存；trash 列表 ExpiresAt 按生效保留天数返回（DOMAIN_RULES §9）。误配兜底：retention <1 永不判过期（`filing.TrashExpired`）。
- 清空回收站 = 二次确认的显式管理操作。

## Host 校验（DNS rebinding 防御，2026-09-22 起）

- **威胁形态**：恶意页面把自有域名解析到内网 IP，其页面对本服务的请求在浏览器同源判定下"看起来同源"——本服务无 CORS 输出、Bearer 走 Authorization 头，正常跨域读取默认被拒，但 rebinding 伪同源绕过这层；dev 免密形态下 `dev-login` 等于凭空送出 admin token，未初始化形态下 `setup` 可被抢跑。
- **机制**：最外层中间件校验 Host 头——默认白名单 = IP 直连（v4/v6 字面量）+ `localhost`，其余一律 403 `INVALID_PARAM`。局域网/隧道的既定访问形态全是 IP 或本机名，**默认零配置零影响**；空 Host（HTTP/1.0 古董客户端）放行（rebinding 必须携带攻击者域名，空 Host 不构成该向量）。
- **域名访问**：需要域名（如 Tailscale MagicDNS 主机名）访问的部署，把主机名加入 `trusted_hosts`（yaml）/ `QIMENG_TRUSTED_HOSTS`（env，`,` 或 `;` 分隔）；匹配大小写不敏感。
- 测试锁定：`httpapi/host_check_test.go`（判定表驱动 + 中间件端到端，含伪 IP 域名 `192.168.1.5.evil.com` 拒绝用例）。

## 备份快照（库文件热备，2026-09-19 任务Q 批B 起）

- **快照是敏感数据**：快照 = 完整 SQLite 库文件（VACUUM INTO 产出），内含 argon2id 口令哈希与全部行为/库登记数据——拿到快照 ≈ 拿到整库（离线爆破口令哈希面）。备份目录位于 `DataDir/backups/`，不出服务端私有数据根；运维做异地保管/上传网盘时自行承担同等密级责任，建议加密后传输。
- **端点面三道防线**（POST/GET /backups、GET /backups/{name}/file、DELETE /backups/{name}）：
  1. **鉴权**：全部走既有 Bearer 中间件（无 security: [] 豁免；无 token 401，进 CI 安全测试清单）；
  2. **文件名白名单**：{name} 严格匹配 `^qimeng-\d{8}-\d{6}\.db$`，任何带路径分隔符/扩展名变体/目录段的输入一律 400 INVALID_PARAM（快照名完全由服务端生成，白名单外的都当穿越探测处理）；
  3. **os.Root 目录锚定**：下载/删除的文件访问经 `os.Root` 打开并锚定在备份目录内（Go 1.24+，原生拒绝绝对路径与 `..` 段），白名单误放行时的第二道闸（与 spa.go/trash.go 同一标准库底座）。
- **删除语义**：DELETE /backups/{name} 是物理删除（快照可由 VACUUM INTO 随时再生，不走回收站——铁律 4 的「媒体文件回收站」语义不覆盖服务端自产的库文件副本）。
- **扫描自噬**：备份目录在 DataDir 内，若 DataDir 被误注册为媒体库，既有扫描自噬两道防线已将其排除（与缩略图/回收站同待遇），快照文件不会被扫进媒体面。
- **恢复流程（文档化手动操作）**：停服 → 用快照替换数据目录中的库文件（`qimeng.db`，注意 WAL/SHM 伴生文件一并清理）→ 起服。v1 无在线恢复端点（防误操作把「恢复」变成「覆盖现库」的单按钮事故）。

## 作者总表镜像（唯一例外写点，2026-09-25 起，ADR-0023）

- **路径由用户显式配置**：`GET/PUT /api/v1/authors/mirror` 配置镜像目标（持久化 kv_settings `author_mirror`）；path 空=关闭（默认），非空必须是**绝对路径**（相对路径 400 INVALID_PARAM），PUT 走既有 Bearer 门禁——服务端不因此获得写任意路径的能力（无配置即无写点，配置动作本身有鉴权；这是本项目服务端写「数据目录/媒体库之外」路径的唯一例外）。
- **写法与故障语义**：镜像单向（服务端→本地），本地 txt 手改会被下一次服务端变更覆盖；写入=临时文件+rename 原子落盘，**尽力而为**——失败仅 slog Warn，绝不阻塞/回滚资产作者/来源编辑、TXT 导入等核心操作；未配置或目标片段不存在时跳过，路径恢复后下一次变更自动补写。刷新挂点=资产作者/来源编辑保存后（ADR-0024，上传挂靠入口已退役）/TXT 导入落库后（含 keep/remove 结果）/片段删除后/镜像配置保存后（rebuild 不改片段不挂）。

## 依赖与供应链

- Dependabot 尚未配置（计划中，与 ARCHITECTURE §10 口径一致）；当前依赖更新靠 AI 会话按「新技术先读官方文档」纪律人工核对。
- 镜像构建（官方基础镜像 + 固定 digest；CI 构建的镜像才允许部署）：`make docker-build` **amd64 已实构建**（2026-09-22 fnOS 彩排过）；**arm64 半边挂真机 NAS**。
- 引入任何新依赖必须：官方文档确认维护状态 + 无已知 CVE + 写 ADR。

## 仓库卫生（密钥与隐私不入库，2026-09-30 开源前审计建立）

- **红线**：明文口令/令牌/密钥/共享密钥、设备序列号、内网拓扑细节、含用户名的本机绝对路径，一律不得进入任何被跟踪文件——代码、配置、测试、文档（CHANGELOG/HANDOVER 等记录性文档一视同仁）。
- **正确姿势**：密钥只经 `QIMENG_*` 环境变量注入或运行时生成并持久化到数据目录（如 media-secret、`QIMENG_AUTH_DEV_SHARED_SECRET`）；部署差异走 gitignore 的 `*.local`；测试口令必须是明显合成值（如 `test-password-001`）。
- **提交前自查**：`git grep` 扫敏感模式（`password|secret|token` 的字面量赋值、`C:\Users\<user>`、私网 IP 与真机序列号、`eyJ` 开头长串）；发现自己或 AI 写入立即脱敏，**已提交/已推送的必须 `git filter-repo --replace-text` 全历史重写后强制推送**，再考虑公开。
- 2026-09-30 开源前审计实录：CHANGELOG 曾明文记录真实管理口令与真机序列号（连同全部历史版本），已 filter-repo 全历史重写清除；测试口令换合成值。

## 安全测试（进 CI，全绿才许合并）

1. 无 token 访问任意 API → 401
2. 过期/伪造签名的直链 → 403
3. `../`、绝对路径、URL 编码穿越变体 → 400 且无文件读写发生
4. 超大 body、超限分页 → 413/400
5. 白名单外扩展名、伪造扩展名（.jpg 实为 .exe）→ 400
6. 删除资产 → 文件出现在回收站而非消失

## 已知安全边界（2026-09-17 全检审计记档，均为风险接受而非遗漏）

- **App 全局明文 HTTP**（`usesCleartextTraffic`，无 network_security_config）：局域网 http 是既定部署形态（本机 18430 与 LAN 8420 都是 http），远程访问走隧道兜底（红线 8）。network_security_config 只能按域名放行明文、无法表达「任意私网 IP 放行、其余拒绝」，收窄会直接断掉核心场景，故维持现状；陌生 WiFi 下连局域网地址时 token/媒体明文过空口属用户责任边界。
- **Android 内嵌形态 dev-login 跑在设备共享回环**（127.0.0.1:18430 + AUTH_DEV_MODE=1）：Android loopback 全设备共享，同机恶意 App 理论上可免密登录读库（App 持 MANAGE_EXTERNAL_STORAGE 放大后果）。~~缓解规划 = App 拉起内嵌进程时注入共享密钥、dev-login 校验该密钥，待单机形态真机验收（ADR-0015 T7）后立项~~ —— **已实现（2026-09-30 批A）**：App 拉起子进程随机生成 256bit 密钥注入 `QIMENG_AUTH_DEV_SHARED_SECRET`，dev-login 携带 `X-Qimeng-Dev-Secret` 头校验，不匹配 401（机制详见「开发模式」节）。
- **App token 明文 DataStore**：2026-09-06 风险接受决策（代码注释记档）。2026-09-17 补备份排除规则（`dataExtractionRules`/`fullBackupContent` 排除 `server_config.preferences_pb`）——明文本机落盘在接受范围，随系统/云备份外带不在，已关闭。
- **web token 存 localStorage**：Bearer-SPA 常见取舍（XSS 可读面）；多会话模型下泄露后果收敛为单会话（可 logout 吊销），维持现状，有实测需求再动协议（cookie/刷新机制）。

## 远程访问姿势（将来启用）

NAS 与手机均装 Tailscale（免费档够用），登录同一账号组成虚拟内网。服务端 HTTP 不变，加密由隧道保证。**禁止**自行在路由器做端口映射 + 反代裸露服务。
