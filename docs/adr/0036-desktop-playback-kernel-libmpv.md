# ADR-0036：桌面播放内核引入 libmpv

## 背景（Context）

桌面壳（ADR-0020）是连接模式薄壳，视频播放走 WebView2 里的 HTML5 `<video>`（ArtPlayer 只是控制条皮肤，真正的媒体内核 = Chromium 内建栈）。这条路线的三块硬顶：

1. **格式兼容由 Chromium 决定**：配合 ADR-0002「查看永远发原件」，浏览器解不动的封装/编码没有任何兜底；
2. **逐帧处理不可注入**：VSR 只能蹭驱动对 Chromium 呈现路径的自动增强，SVP 插帧、Anime4K、主动 VSR/RTX Video HDR 全部没有挂载点；
3. **升级节奏跟随 WebView2 运行时**，扩展面为零。

用户需求链（2026-10-05 对话拍板）：把「插帧 / 超分 / HDR 直通 / 全格式」作为面向未来的产品能力，选型标准为「持续维护 + 接口丰富 + 扩展活跃 + 社区积极 + 未来功能好接入」，迁移工程量不设限，要求一次性到位、不积累未来债/技术债/维护债。

候选对比（桌面可嵌入、持续维护的主流内核全集）：

| 候选 | 维护 | 接口/扩展 | 判定 |
|---|---|---|---|
| **libmpv**（mpv 引擎） | 20+ 年血统，社区极活跃，客户端 API 稳定（libmpv-2 ABI） | 五层扩展面：配置/Lua·JS 脚本+JSON IPC/滤镜链/钩子/shader hook；画质生态事实标准（SVP、Anime4K、IINA 等全部在此） | ✅ 唯一全条件满足 |
| libVLC | 极活跃 | API 面宽但画质向扩展面缺失（shader hook、外挂插帧均无） | ❌ 缺口正是需求方向 |
| GStreamer | 活跃 | 插件管线强 | ⚠️ 多媒体框架生态强、视频画质生态薄，复杂度换不来目标回报 |
| FFmpeg 裸用自建 | — | 无现成接口 | ❌ 自养技术债 |

未来编码演进维度（神经压缩等）：mpv 解码层 = FFmpeg 继承制（VVC/H.266 已随 FFmpeg 7.x 可播；首个商用纯神经编解码器 Deep Render 已集成进 FFmpeg/VLC，mpv 同源继承；AV2 在 FFmpeg 社区射程内），渲染层 = libplacebo（0.41 起 gpu-next 为默认渲染器，Vulkan Video 硬解优先）——新编码落地时本项目的动作仍是「跟版换 DLL」，无新增架构债。

## 决策（Decision）

1. **桌面壳引入 libmpv 作为原生播放内核**（用户 2026-10-05 拍板）。最终形态：桌面壳内 libmpv 为播放内核，ArtPlayer 路径保留给浏览器模式（浏览器环境无法嵌入原生内核，非双内核债）。**不做永久双内核并存**——两套内核状态永远对齐才是真正的维护债；迁移期过渡见 `desktop/MPV_MIGRATION.md`。
2. **嵌入形态 = wid HWND 嵌入**：专用原生播放窗（无 webview 的纯窗口），取其 HWND 交 mpv（`wid` 选项）由 mpv 自管渲染与 OSC 控制条。刻意不走 render API 纹理合成（复杂度高一个量级，里程碑收益不匹配）；何时升级 render API 见决策 3 复查条件。
3. **FFI = 自写运行时加载**（`LoadLibraryW`/`GetProcAddress`，kernel32 extern 内联声明，**零新增运行时依赖**）。自研四问记档：
   - ① 业界通行方案：`libmpv-rs`（构建期链接 libmpv）、`libloading`（运行时加载封装）。
   - ② 不用的可证伪理由：`libmpv-rs` 要求构建期拿到 mpv 头文件与导入库（pkg-config/MSVC 路径脆，破坏「无 mpv-dev 也能编译」的可移植性）；`libloading` 引 windows-sys 传递依赖，违反轻量通道「零传递依赖」口径；本项目 client API 使用面 ≤15 函数，手写运行时加载面可控且 DLL 缺失可优雅降级（错误信息带指引）。
   - ③ 复查条件：所需 client API 面 >30 函数、或引入 render API 纹理合成需求、或 `libmpv-rs` 支持免构建期链接的运行时加载。
   - ④ 退役触发：满足③任一条件时，FFI 层整体替换为成熟绑定，调用面（player/commands 两文件）不动。
4. **进度上报 = JS 轮询**：web 每 5s `invoke('mpv_status')` 读状态缓存，喂进既有 `useProgress`（tick/flush）与起播上报链。轮询与既有 5s 心跳节流同拍，且规避「远端页订阅 Tauri 事件」的额外权限与 API 面。
5. **降级策略**：`libmpv-2.dll` 缺失时 `mpv_open` 返回带 setup 指引的错误，web 端按钮提示、web 内核照常可用。运行时加载 + 缺失降级 = 编译期零依赖，任何环境可构建。
6. **许可线**：mpv 为 GPLv2+（官方 Windows 构建含 GPL 组件）。本项目 MIT——**DLL 绝不入库**（gitignore），由用户经 `setup-mpv.ps1` 自取；仓库分发面保持 MIT 干净。若未来发行含 libmpv 的二进制包，须重新评估分发义务（届时可选「产物不含 DLL、用户脚本自取」保持现状）。
7. **迁移任务文档**：`desktop/MPV_MIGRATION.md` 为唯一进度/接手入口（里程碑、自测清单、坑与存疑、回滚），完成后其「遗留与后续」并入 HANDOVER。

### 刻意不做（防过度工程）

- render API 纹理合成（决策 2）；字幕轨/音轨选择 UI（后续按需）；签名直链临期自动刷新（ADR-0027 窗口 6h，超长播放会话遇 403 重开即可，`on_load` 钩子方案留后续）；插件系统暴露（脚本/IPC 能力已够）。

## 状态（Status）

Accepted（2026-10-05，用户拍板）；**实施修订（2026-10-06，用户拍板）**：内核随
`feat/desktop-libmpv-kernel` 并入 master；「迁移期可见切换入口（chip）」取消——壳内最终
直接以 mpv 为唯一内核（ArtPlayer 留浏览器模式不变）；C4.5 透明 WebView2 控制层撤回
（运行闪退未解，判死该路径，见 `desktop/PLUGIN_PLAN.md` §四-8）；嵌入形态修订=**主窗口子窗**
（WS_CHILD 铺在 web 舞台矩形，「内置播放」，仍 wid/零 render API，见 PLUGIN_PLAN）；触发方式定稿=壳内点视频即原生播放（无可见组件，非"自动播放"特性）；后续插件
计划统一收口到 `desktop/PLUGIN_PLAN.md`（本 ADR 只记决策不记进度）。

## 后果（Consequences）

- **积极**：桌面端获得内核拥有权——VSR 主动控制、RTX Video HDR、Anime4K、SVP 插帧、mkv 等全格式、未来神经编码全部变为配置级接入；客户端 GPU 承担增强算力，NAS 后端零感知（拉原件直链，与现路径一致）；FFI 零依赖保证壳的可构建性不与 mpv 运行库耦合。
- **代价**：跟版动作由本项目承担（换 DLL + 回归）；`wid` 嵌入的窗口尺寸联动与 OSC 表现依赖 mpv 自身行为（存疑点见 MPV_MIGRATION）；播放窗交互与 web 控制条存在能力差（里程碑内 mpv OSC 为准，逐批对齐）。
- **实施入口**：`desktop/MPV_MIGRATION.md`（里程碑/自测/接手指南）；本 ADR 只记决策不记进度。
