# CHANGELOG 历史归档 · 2026-10-17

## fix(server): 后端安全与可靠性加固批次（2026-10-17 第五百三十六笔）

执行 AI：DeepSeek-Flash（主代理；4 路子审计代理并行产出 security/commits/quality/ops 报告，主代理逐条复核并修复）

- **来源**：2026-10-17 全仓审计（四路并行：后端安全 / 最近提交 / 整体质量性能 / 运维供应链，报告见 gitignored `build/audit/`）。本笔只落地**高价值、低风险、不改协议、不动用户既定免密用法**的项；P0/P1 无确认缺陷，不做全量重构。
- **安全修复（服务端）**：
  - **JSON Content-Type 强制**（`internal/httpapi/errors.go` `requireJSONContentType`）：全部 `decodeJSON` 端点拒绝非 `application/json` body。关闭"任意网页用跨站简单请求（no-cors+text/plain）向未初始化 setup 抢注密码 → 用户被 409 锁死"的伪造面，也堵住未鉴权方消耗 auth 限速配额的旁路。合法 SDK 本就声明 JSON，三端零适配。
  - **auth 限速计数顺序修正**（`authapi.go`）：limit 计数移到实际进入凭据校验之前；`/auth/dev-login` 在 dev 模式关闭时先 404（不消耗配额）、再限速、再共享密钥（顺序=404→限流→401，SECURITY 同步）。修复"未鉴权请求可把共享窗口打满、令管理员 setup/login 永久 429"的可用性 DoS。
  - **明文 token 响应 `Cache-Control: no-store`**（setup/login/dev-login 统一 `writeAuthToken` 单点）。
  - **ffmpeg/ffprobe 输入协议白名单**（`thumbnail/ffmpeg.go`、`blackframe.go` 全部 `-i` 调用点加 `-protocol_whitelist file`）：库内媒体是不可信输入，阻断 HLS/concat 等 demuxer 的外部 URL/本地文件引用面（SSRF、本地文件读取侧信道、解码器暴露面收窄）。新增 `protocol_test.go` 锁参数形态。
  - **local-sync trigger 409 脱敏**（`localsync.go`）：通道级 lastErr 可能含库根/数据目录绝对路径，改为固定文案 + 详情只进服务端日志（SECURITY 红线 7 例外收口）。
- **可靠性修复（服务端）**：
  - 新增 `internal/safego` 包：长驻后台 goroutine 统一 panic 兜底（扫描、缩略图预热、缩略图工作池/后台续生、回收站清扫、上传清扫、本机同步轮询、备份调度、推荐预热、库变更订阅、自定义出处重算、启动自愈），单个任务崩溃不再终止整个服务进程；工作池任务级 recover 后 worker 继续消费。
  - 优雅关闭：`srv.Shutdown` 超时不再 `os.Exit(1)` 跳过清理，改为 Warn + `srv.Close()` 断开剩余连接（SSE 长连接场景）后继续关闭工作池/上传会话/总线/数据库，退出码恢复 0。
  - HTTP Server 增 `IdleTimeout=120s` + `MaxHeaderBytes=64KB`（大文件直链不受影响；空闲连接与超长 header 的内存/fd 放大面收窄）。
  - SQLite 连接池显式上限（`MaxOpenConns=6`/`MaxIdleConns=2`；DSN `cache_size(-32000)` 是每连接 32MiB，先前不限连接数与手机单机形态内存冲突），facets 六路聚合改 `errgroup.WithContext` + `SetLimit(4)`（失败即取消其余）。
  - 缩略图预热单飞（`runWarmupOnce` + `warmupMu.TryLock`）：周期兜底与扫描后触发不再并发整库 `os.Stat`/重复 Submit。
  - 回收站恢复路径失败残留清理补 `ErrMoveDidNotCreateTarget` 双哨兵（ADR-0038 决策 2 三调用点一致；此前漏判，窄窗口下可能误删外部并发创建的完整目标文件）。
- **数据库（migration 0018，只加文件）**：`idx_asset_authors_author(asset_id→author 反查)`、`idx_asset_tags_tag(tag_id)`、`idx_likes_day(day, asset_id)`、`idx_assets_mtime(mtime DESC, asset_id DESC)`——补齐作者/标签列表与重建、周期榜单/推荐窗口聚合、默认浏览排序（2026-09-17 起=fileDate=mtime）的索引缺口；down 为对称 DROP INDEX。`store_test` 增加 0018 索引存在性用例与 down/up 链验证。
- **用户拍板保留项**：日常启动链 `_server-common.cmd` 的 `QIMENG_AUTH_DEV_MODE=1` 与 `:8420` 全接口监听**不改**（用户明确"免密是我自己再用"）；SECURITY 将其记为风险接受项并保留生产部署必须关闭 dev 模式的既有约束。
- **本次未做（审计报告已列，留待后续批次）**：httpapi 编排下沉（ADR-0019，L）、`GET /authors` view_count 预聚合、Web/Android 大文件拆分、Docker 非 root/digest 固定、CI Actions pin SHA + permissions、Dependabot/govulncheck 门禁、scanner 软链跳过、uploadsess 总量上限、DB 文件 0600、访问日志。均为 M/L 成本或需部署侧验证，避免本批动到用户日常形态。
- **自测**：`go build ./...`、`go vet ./...`、`gofmt -l .` 全清；`go test ./... -count=1` **20 包全绿**（新增 safego 包与 4 组加固回归测试：Content-Type 拒绝/放行、dev 404 不消耗限速、token no-store、回收站恢复 `ErrMoveDidNotCreateTarget` 清理跳过、0018 索引与 down/up 链）。
- **CI 回修（同日）**：首次推送后 server job 的 `go test ./... -race` 暴露两处数据竞争——① 备份调度接入 `safego` 时闭包延迟读取 `m.schedInterval`（锁内写 vs goroutine 读），改为锁内快照 `intervalSnapshot` 后传参；② 新增 `safego` 单测用共享 `bytes.Buffer` 写读，改为互斥 `syncBuffer`。本地全量测试通过，race 结论以 CI 复核为准。
- **文档**：`docs/SECURITY.md`（新增「2026-10-17 安全加固」节 + 红线 6/7、auth 限速、dev 校验顺序、上传 ffmpeg 白名单、作者镜像能力口径、镜像构建现状勘正）、`docs/CHANGELOG.md`（本笔）、`docs/HANDOVER.md`（服务端现状与遗留缺口）、`docs/GUIDE_API.md`（端点计数口径勘正 74→75/94→97）、`.github/workflows/build-apk.yml`（过时的 sdk.lock 红灯注释更新）。

## chore(desktop+web): 清理桌面端 mpv 内核死代码与脚手架残留——全端统一收敛 ArtPlayer 播控（2026-10-17 第五百三十七笔）

执行 AI：Gemini-3.8-Flash（主代理）

- **背景与根因剖析**：
  - 用户询问 Web 与 App 画质增强及桌面端媒体内核现状，指出桌面壳早已在第四百九十五笔（`ee3d86d3`）用户拍板回归与 Web 端完全一致的标准播控，为何代码库中仍残留 mpv 遗留痕迹。
  - 实查确认：
    1. 前端在 495 笔全面遵循 ArtPlayer 官方规范重构后，不论是纯浏览器/PWA 还是 Tauri 桌面壳，实际渲染与交互已 100% 运行标准 HTML5 `<video>`（ArtPlayer）；
    2. 但桌面壳 Rust 后端 `desktop/src-tauri` 仍保留 `mod mpv;`、`src/mpv/` 模块、`permissions/mpv-commands.toml` 及 `setup-mpv.ps1`；前端 `video-player.tsx` 中保留防守性 `closeOrphanMpvInShell()`，文档 ADR-0036 与 `PLUGIN_PLAN.md` 处于未归档/滞后状态，给后续协作造成认知混淆与死代码包袱；
  - 用户明确指令：“清理掉吧”。
- **改动内容**：
  1. **桌面壳 Rust 后端彻底清理**：
     - 彻底删除 `desktop/src-tauri/src/mpv/` 目录（`player.rs`, `commands.rs`, `ffi.rs`, `win32.rs`, `mod.rs`）；
     - `desktop/src-tauri/src/main.rs` 移除 `mod mpv;`、`tauri::generate_handler!` 中的 5 条 mpv 命令、`.manage(mpv::commands::MpvState::default())` 状态挂载与退出时的 `close_session` 回收调用；
     - 彻底删除 `desktop/src-tauri/permissions/mpv-commands.toml`、`desktop/src-tauri/setup-mpv.ps1` 与 `desktop/PLUGIN_PLAN.md`；
     - `desktop/src-tauri/capabilities/default.json` 移除 5 项 `allow-mpv-*` 权限声明；
     - `desktop/.gitignore` 移除 `src-tauri/mpv/` 路径；
     - `desktop/README.md` 移除 `原生播放内核（libmpv）` 章节，更新为全端统一使用标准 ArtPlayer 的说明。
  2. **Web 前端防御性死代码与未引用模型清理**：
     - `web/src/components/media/video-player.tsx`：移除 `closeOrphanMpvInShell()` 定义与 `useEffect` 中的调用，移除遗留未引用的 `nativeTitle?: string` 属性；
     - `web/src/pages/AssetDetailPage.tsx`：移除向 `VideoPlayer` 传递的 `nativeTitle` 属性；
     - `web/src/lib/engagement-reporting.ts` 与 `engagement-reporting.test.ts`：移除仅由早期 mpv 轮询使用的未引用类型定义（`NativeTrackInfo`、`NativeMediaProbe`、`NativePollStatus`、`NativePollSignal`）与 `nativePollSignal` 纯函数及相关测试用例；
     - `web/src/lib/player-labels.ts`：更新注释描述，彻底去除历史 mpv 术语。
  3. **文档与架构决策同步**：
     - `docs/adr/0036-desktop-playback-kernel-libmpv.md` 状态正式标记为**已废弃（Superseded / Retired）**，详细记档回归 ArtPlayer 的决策历程与本笔清理范围；
     - `docs/adr/INDEX.md` 同步更新 ADR-0036 状态为已废弃；
     - `docs/CAPABILITY_MAP.md` 桌面播放内核行同步更新为“全端统一收敛标准 ArtPlayer（已有）”。
- **自测验证**：
  - Web 端：`npm test` 29 个测试套件 311 项单元测试全绿通过；`npm run build` 打包成功（零类型错误、零警告）；
  - 桌面壳：`cargo test` 17 项单元测试全绿通过（零错误、零 dead_code 警告）。
- **文档**：`docs/adr/0036-desktop-playback-kernel-libmpv.md`、`docs/adr/INDEX.md`、`docs/CAPABILITY_MAP.md`、`desktop/README.md`、`docs/CHANGELOG.md`（本笔）。
