# 任务书：桌面播放内核 libmpv 迁移（长期任务 · 跨会话接手入口）

> **本文是本迁移唯一的任务书与接手文档**。决策依据见 `docs/adr/0036`，本文只记目标、进度、风险与交接。
> 接手 AI 规则：① 先通读本文；② 只做「当前批次」内的事；③ 完成任何条目**立刻更新本文 checkbox 与交接日志**再提交；
> ④ 任何时刻中断，下一位凭本文即可无损续作。
> 关联：`desktop/README.md`（用户视角）、`docs/CAPABILITY_MAP.md`「桌面原生播放内核」行、`docs/CHANGELOG.md`（每笔明细）。

## 一、目标与验收标准

**目标**：桌面壳引入 libmpv 作为原生播放内核（最终形态=桌面端唯一内核；ArtPlayer 留浏览器模式），为 VSR 主动控制 / RTX Video HDR / Anime4K / SVP 插帧 / 全格式 / 未来神经编码继承打地基。用户拍板（2026-10-05）：一次性到位，不积累未来债/技术债/维护债，工程量不设限。
**范围边界（2026-10-05 拍板）**：主体 = C1~C4（内核接入 + 验收走查）；C5+ 的画质增强预置/插件类后置按需再议。

**总验收（C4 出口条件，全部满足才算迁移完成）**：

1. 桌面壳内：详情页点「原生内核播放」→ 独立播放窗出画面，mpv OSC 可控（播放/暂停/进度/音量）；
2. 关播放窗 / 关 web 页 → 会话回收干净（无残留 mpv 线程/窗口），不崩壳；
3. web 进度上报链不断：原生播放期间 tick 照常、暂停 flush、离开补报——断点续播跨端一致；
4. 浏览器模式回归零影响：无原生入口、ArtPlayer 行为与迁移前逐项一致；
5. 降级路径：无 DLL 时 mpv_open 报错带指引，web 内核完全可用；
6. `cargo check/test` + `npm test` + `npm run build` 全绿；
7. **播放窗交互与现 web 播放器对齐（C4.5 出口）**：进度条点击/拖拽 seek、时间轴打点显示、倍速切换、播放/暂停/音量——以 video-player.tsx 现行为逐项对照；
8. ADR-0036/本文/CAPABILITY_MAP 状态行同步收口。

## 二、架构（目标态）

```
Web UI (AssetDetailPage → VideoPlayer)          仅浏览器模式直接用 ArtPlayer
   │  桌面壳内：『原生内核』按钮（'__TAURI__' in window 闸门）
   │  window.__TAURI__.core.invoke('mpv_open', {url, title, startSecs})
   ▼
Tauri 壳 (desktop/src-tauri)
   ├─ commands: mpv_open / mpv_status / mpv_control(pause|seek|speed) / mpv_close
   ├─ 播放窗：user32 FFI 自建（不经 Tauri 窗口系统/无 webview，见 win32.rs 头注）
   └─ mpv 会话线程（窗口线程=mpv 线程三合一）：LoadLibraryW("libmpv-2.dll") 运行时加载
        wid = 播放窗 HWND（initialize 前设置）→ mpv 自管渲染 + OSC
        状态缓存（Arc<Mutex>）← PROPERTY_CHANGE 事件循环
   ▲
   │  web 每 5s invoke('mpv_status') 轮询 → 喂 useProgress(tick/flush) + 起播上报
```

关键取舍：wid 嵌入（非 render API）；JS 轮询（非事件推送）；运行时加载（非构建期链接）；user32 自建窗（非 tauri unstable）。理由全在 ADR-0036。

## 三、批次路线图

### C1 决策与文档 ✅（f51da4e8）
- [x] ADR-0036 + INDEX 行 + CAPABILITY_MAP 行
- [x] 本文 + desktop/README 原生内核节
- [x] `setup-mpv.ps1`（用户自取 libmpv-2.dll，不入库）+ .gitignore

### C2 Rust 侧 mpv 子系统 ✅ 代码完成（f1c56360），编译验证被设备事故阻塞（见 §六）
- [x] `src/mpv/ffi.rs`：kernel32 extern（LoadLibraryW/GetProcAddress）+ 函数指针表 + MPV_FORMAT_*/MPV_EVENT_* 常量（ABI 冻结值；**接手者须对 client.h 逐值终验**，见 §五坑 1）
- [x] `src/mpv/player.rs`：单线程会话（命令 mpsc + PROPERTY_CHANGE 状态缓存 + 优雅 quit/SHUTDOWN 收尾）；mpv 选项/属性名常量单源；parse_action/format_start 纯函数带单测
- [x] `src/mpv/win32.rs`：user32/gdi32 FFI 自建播放窗（避开 tauri unstable）——窗口类 QimengMpvHost、1280×720 可缩放、消息泵、WM_CLOSE→WM_QUIT；类名编码单测
- [x] `src/mpv/commands.rs`：mpv_open/mpv_status/mpv_control/mpv_close 门面（会话槽 check-and-set 防并发双开；死会话自动重建；DLL 缺失错误带 setup 指引）
- [x] `src/mpv/mod.rs` + `main.rs` 挂载（mod/manage/generate_handler；on_window_event 无需感知播放窗）
- [x] **自测：`cargo check` / `cargo test` 全绿 ✅（2026-10-05 补验通过：BUILD_EXIT=0 / TEST_EXIT=0，22 单测全绿含 mpv 模块 5 个新单测；依赖树全量重编无一次损坏）**
- [x] 内核面完整性自查（防未来债）：loadfile/换片/起点/seek/倍速/暂停/状态缓存/标题/OSC/hwdec/keep-open/idle 全接；字幕·音轨切换=纯加命令项（无架构债）；VSR/HDR=mpv.conf 配置级（C5+）

### C3 Web 侧接入 ✅（2026-10-06 落码，自测全绿）
- [x] `lib/engagement-reporting.ts` + 单测：`nativePollSignal`（轮询边沿→play/pause 信号纯函数，喂 stepPlayGate 同一口径 B 状态机；首个快照开局即暂停/eof 不算起播、eof 边沿视同 pause、会话关闭不产出信号由接线层复位；10 个新单测）
- [x] `video-player.tsx`：壳内（`'__TAURI__' in window`，浏览器模式渲染零改动）渲染「原生内核」chip；invoke mpv_open（当前 web 进度优先、断点起点兜底；camelCase 参数 startSecs 对 Tauri v2 官方口径已查证）+ 暂停 web 播放器；每 PROGRESS_REPORT_INTERVAL_MS（5s，与 web 心跳同拍常量复用）轮询 mpv_status，边沿喂 onPlay/onPause（onPause 带轮询快照位置=flush）、播中 tick 喂 onTimeUpdate；状态 null→复位接管态；原生接手中用户点 web 播放→收回（mpv_close+闸门归零）；chip 再点=返回 web 内核；组件卸载 mpv_close 回收（验收 2「关 web 页」）；DLL 缺失错误串展示在 chip 旁（err token）
- [x] `AssetDetailPage.tsx`：传 `nativeTitle={d.cosWork ?? d.fileName ?? ''}`（任务书原文 `d.title`——AssetDetail 协议无 title 字段，按协议卡片标题口径 cosWork??fileName 修正）
- [x] `glass.css`：`.video-player-wrap`/`.mpv-chip`/`.mpv-chip-error`（token 取色禁硬编码；chip 悬浮播放器右上 watched-badge 对角位，z=--qm-z-fab，行容器 pointer-events:none 命中收窄到按钮）
- [x] 自测：`npm test` 272 全绿（含 10 新单测）+ `npm run build` 全绿 + `oxlint` 0 错误（20 警告均既有）✅

### C4 复核与收尾
- [x] `cargo build --offline --release` 出 release 产物（启动-桌面端.bat 的拉起目标）✅ 2026-10-06：47.6s BUILD_EXIT=0，qimeng-media-desktop.exe 11.9MB
- [x] 对抗复核 ✅（2026-10-06 复核子代理）：线程边界/锁序/FFI 面/panic 面/四关闭路径闭环/协议双写/铁律 7、14 全过；**抓出并已修 2 实锤**——① FULLSCREEN_RESTORE 进程级存根跨会话残留（新会话首次全屏错走还原分支）→ run 清理序 reset_fullscreen_restore()；② build_and_bind 无回滚 + 同 label 异步 close 先摘后建必撞车 → 改会话代数唯一 label（mpv-controls-N，capabilities glob 放行）+ 建后任一步失败即回收 + attach 代关上一代遗留窗 + detach 按 gen 精确回收（顺带闭环复核 3.3 detach 误伤隐患）；低危项（renderMarks 负时间过滤、控制页 130/5000ms 提常量）同修。**残余：mpv client.h 枚举终验（坑 1）未闭**——gh-proxy blob 页 JS 壳取不到内容、jsdelivr 404、web-reader 无配额，仍待网络可用或用户协助（ABI 冻结口径兜底，风险低）
- [ ] 对抗复核：FFI 签名/枚举逐个对 client.h；线程边界；panic 面；铁律 7（UI 不碰业务）/14（无二进制入库）过一遍
- [ ] CHANGELOG 每笔同 commit；本文 checkbox 全勾；§七日志收口
- [ ] 运行验收（需真人）：按 §一验收清单 1~6 走查
- [ ] CAPABILITY_MAP 状态行改「已有」+ HANDOVER 收口

### C4.5 播放窗交互对齐（2026-10-05 二次拍板：从 C5+ 移回主体——"手势交互这种"属于主体）

**架构（定稿）**：专用播放窗双层结构——底层 mpv `wid` 出画面（C2 已建），顶层**透明 WebView2 控制层**（壳内新页 `desktop/ui/player-controls.html`，复用 `--qm-*` token 与 glass 材质），鼠标事件归控制层、画面归 mpv。**刻意不用 render API**（纹理合成是量级最重且最脆的路径，双层透明叠加即可达成"手势浮在 mpv 画面上"），交互规格照搬现 ArtPlayer 实际行为（进度打点/倍速菜单/手势映射以 video-player.tsx 现行为为对照源）。

- [x] 控制层页骨架 ✅（2026-10-06 落码）：`mpv/overlay.rs`——控制层=Tauri 透明无边框窗（transparent+shadow(false)+skip_taskbar+不抢焦点），`GWL_HWNDPARENT` 归属播放窗（owned 语义：永在播放窗之上/随最小化/随销毁，z-order 免管理）；几何同步=播放窗 `WM_WINDOWPOSCHANGED`（win32::wnd_proc）→ `run_on_main_thread` 物理像素 set_position/set_size（跨线程只用 Tauri 官方通道，拖动模态循环内照常跟手）；页面 `desktop/ui/player-controls.html`（透明背景+玻璃控制条，token 子集复制自 tokens.css 暗色段）
- [x] 进度条 + 时间轴打点渲染 ✅：数据源=**经壳中转**（web mpv_open 携 highlights → MpvState.overlay_data → 控制层 mpv_overlay_info 5s 轮询捕捉换片；控制层零网络面，CSP 无需扩面）；打点点击回看+悬停 title 提示；进度条悬停时间预览
- [x] 手势层 ✅：进度条点击/拖拽 seek（纯视觉坐标，本页无全局 zoom、对照源的 zoom 1.1 修正在此按构造成立）；点画面切暂停（220ms 延时区分双击）/双击全屏（mpv wid 模式全屏归嵌入方——win32 toggle_fullscreen：WS_POPUP 落显示器整屏，还原存根 static 暂存）；键盘 空格/←→(±5s)/↑↓(音量±5)/M 静音/F 全屏（对照 ArtPlayer 默认热键）
- [x] 倍速菜单 + 播放/暂停/音量控件 ✅：倍速 0.5~3x（1x 文案「正常」=ArtPlayer zh-cn i18n 同款）经 mpv_control('speed')；play/pause/mute/音量滑条（mpv volume 0~130=max-volume 默认）——mpv_control 新增 volume/mute/toggle-fullscreen 动作，mpv_status 新增 speed/volume/muted 观察项（web NativePollStatus 结构化子集向后兼容）；音量 NaN→0/±∞ 钳界有单测
- [ ] 已知实测风险：WebView2 透明背景 + 叠放 z-order + 输入穿透——**留用户走查首验**（透明叠加不可行则 fallback = mpv `input.conf`/Lua 手势，记档后切，overlay.rs 整体退役）

### C5+ 后续批次（**用户 2026-10-05 拍板：插件/画质预置类按需再议、不排期**；其中"交互对齐"已于同日二次拍板移回主体=C4.5）
- [ ] 播放窗交互对齐 web 控制条（倍速/打点联动/手势）
- [ ] `on_load` 钩子刷新签名直链（ADR-0027 窗口过期防御）
- [ ] mpv.conf 预置（VSR d3d11vpp / RTX Video HDR / Anime4K 挂载位）+ 设置页开关
- [ ] SVP 插帧指引（用户侧安装文档）
- [ ] render API 纹理合成评估（仅当需要视频合成进 web 界面时）
- [ ] Android 端不在本任务书范围（Media3 即终局内核，增强走 Media3 Effects 另立任务）

## 四、当前状态与交接日志（倒序追加）

- **2026-10-06 · GLM-5.3-Flash（主代理）会话 2（本日三段）**：①②③④ 见历史与各节；走查三连修（坑 3 ACL 实测修复 304fdf5d / 图表焦点框 6d8c4ebf / C5 立项调研）；**C4.5 走查撞上透明控制层闪退（100% 复现）→ 取证定位 build() 内干净退场（§八）→ 用户拍板删除胶囊入口保稳定**（web NATIVE_KERNEL_ENABLED=false，代码全保留；恢复形态=壳内自动原生内核、无可见入口）；C5 立项两项（mpv.conf 基线 + RTX HDR 配套）随入口暂闭冻结。**下一步（待用户定时机）**：按 §八恢复路径修透明窗 → 无 chip 形态接回 → C4 走查收口 → C5 落码。
- （历史）**2026-10-05 · GLM-5.3-Flash（主代理）会话 1（收口）**：C1 提交（f51da4e8）；C2 代码+验证完成（f1c56360 落码 → f6..fix 笔：2 编译错+5 警告修复，BUILD/TEST 全绿 22 单测，环境事故定性=被杀进程残留见 §六）；任务书 charter 化（8c110f76）。C3 web 侧未落码。
- （历史）2026-10-05 会话 1 中段：cargo check 曾被设备文件损坏阻塞——已定性为被杀进程残留物（§六），非代码问题，硬件未持续损坏（依赖树全量重编零损坏实证）。

## 五、坑与存疑（接手必读）

1. **FFI 枚举值与签名必须对官方 client.h 终验**（raw.githubusercontent.com/mpv-player/mpv/master/libmpv/client.h）——MPV_EVENT_* 数值、mpv_event 字段序（event_id/reply_userdata/data/error）。本会话调研子代理因 GitHub 被墙未返回，当前值=ABI 冻结口径（mpv 承诺永不重编号），风险低但终验不可省。**（2026-10-06 闪退排查中静态复读发现两处与记忆口径不符的高危点待终验：MPV_EVENT_SHUTDOWN 疑应为 1 非 2；mpv_event 字段序疑应为 event_id/error/reply_userdata(u64)/data。client.h 经 gh-proxy blob 页/jsdelivr/fastly/web-reader 多路取回均失败，mpv-dev 7z 内含头文件可作终极来源。）**
2. **wid 行为**：Windows 上 mpv 在给定 HWND 内自建子窗渲染；父窗 resize 是否自动跟随、OSC 是否可用，实测不符时兜底=监听尺寸变化手动同步（先实测再写）。
3. **远端页 invoke 自定义命令**：✅ 已实测并修复（2026-10-06 用户走查命中）——主窗是远端 URL，自定义命令须**应用级 ACL 显式放行**（报错 "Command mpv_open not allowed by ACL"；本地窗不受限，故 setup.html 一直正常）。修法=src-tauri/permissions/mpv-commands.toml（五个 allow-mpv-* 内联权限，官方 v2.tauri.app/security/permissions/ 口径）+ capabilities/default.json 裸引用；窗口 API 的 remote 上下文放行此前已就位（titlebar.js 先例）。
4. **单线程访问 mpv**：除文档明确线程安全者外，全部 mpv_* 调用收敛在播放线程；跨线程只走 mpsc + Mutex 缓存。
5. **签名直链 6h 窗口**（ADR-0027）：超长会话中途 403 → mpv 停止；已知限制，C5 on_load 刷新解。
6. **关主窗语义**：现「关主窗=退出应用」判定只看 main/setup 两 label，播放窗为 user32 自建、不经 Tauri 窗口系统，行为不受影响——勿"修复"。
7. **GPL 分发线**：DLL 不入库不进产物分发（gitignore + setup 脚本），见 ADR-0036 决策 6。
8. **透明控制层闪退（未解，2026-10-06 用户拍板暂闭入口）**：见 §八。

## 八、闪退排查（2026-10-06，未闭合——原生内核入口暂删）

**现象**：壳内点视频右上角「原生内核播放」chip → 整壳闪退，复现率 100%（3 次复现）。

**取证链（diag.rs 跟踪日志 + panic 落盘 + WER LocalDumps 三网齐下）**：
- mpv 侧健康：DLL 加载→mpv_create→**mpv_initialized** 全部到达（FFI 加载/创建/初始化面无罪）。
- 死亡点钉死：主线程 `WebviewWindowBuilder::build()` **内部**（"overlay: building gen=1" 之后零输出——无 attached/无 FAILED/无 window_destroyed/无 exit_requested/无 event_loop_exit）。
- 死亡方式=**进程干净消失**：无 Rust panic（panic 钩子零输出，debug 版 stderr 空白）、无事件 1000/1001、无 WER 报告、无 dump——非崩溃，是 ExitProcess/TerminateProcess 级消失。**tauri 的 RunEvent::ExitRequested 都没触发**，排除壳自身退出逻辑。
- 头号嫌疑：tao 透明窗实现=legacy DWM blur-behind 空区域技巧（window.rs:1284，Win7 时代 API），与 WebView2 二次建环境在同一 build() 内叠加；WebviewWindowBuilder 无 no_redirection_bitmap 方法（E0599 实证）无法换现代路径。次嫌：shadow(false)/skip_taskbar 与 DWM 的组合。
- 已排除：杀软（Defender 日志无动作）、Nahimic 音频服务崩溃风暴（自身 32 位服务反复崩，与壳时间线不锁定）。

**用户拍板（2026-10-06）**：删除视频右上角胶囊入口保稳定（web 侧 `NATIVE_KERNEL_ENABLED=false` 总开关，代码全保留）；不再保留可见入口。**恢复时的形态约束：壳内自动走原生内核、无任何可见 chip**（迁移期脚手架不复活）。

**恢复路径（按序）**：① 置 `web/src/components/media/video-player.tsx` NATIVE_KERNEL_ENABLED=true；② 修透明窗——候选序：wry/tauri 升级（新版可能换掉 legacy DWM 路径）→ 拆 WindowBuilder+WebViewBuilder 用 no_redirection_bitmap → Windows Auto HDR/驱动维度复测 → fallback=mpv input.conf/Lua 手势（overlay.rs 整体退役）；③ 修 mpv_event 结构序/SHUTDOWN 值（坑 1 终验时一并）。诊断设施（diag.rs 跟踪、panic 落盘、WER LocalDumps 注册表 HKCU 配置）保留为常驻。

**连带状态**：C5 已立项两项（mpv.conf 画质基线 + RTX Video HDR 配套，用户 2026-10-06 拍板、HDR 屏已确认）随内核入口暂闭一并冻结，恢复入口后再落码。

## 六、构建环境事故（2026-10-05 已定性：被杀进程的残留物，非硬件持续损坏）

**症状**：rustc 报 E0432（源文件"缺"实际存在的类型）/E0786（rmeta·rlib corrupt）/源文件全零（zmij lib.rs 落盘 66043+ NUL）。

**终局定性（T2 残留论成立）**：清空 `~/.cargo/registry/src/*` + `target/debug` 后**全量重编 100+ crate 一次通过、零损坏**（tokio/tao/tauri 全绿）。此前所有失败 = cargo 解包"先分配后写"语义下，进程被中途杀死（用户历史死机时正在构建 / 本会话被工具层掐断的 cargo）遗留的全零/半截文件；每次清一层、下一层浮出，形成"到处坏"假象。

**已实测排除**：.crate 缓存损坏（tar 抽取对照一致）、当前写路径损坏（同路径重解 3 次 + 3×256MB 写读校验全过）、沙箱（关沙箱同样现象）、OneDrive 接管 Desktop。

**未闭合一例**：windows_core rmeta"同轮写读即坏"一次（残留论无法直接解释，未复现）——保持观察，若未来复现按 T1（RAM/SSD）处置：mdsched → CrystalDiskInfo → chkdsk → 杀软排除项。

**给用户的独立事项**：构建高负载下死机仍复现过一次（用户实测确认），与本事故残留是两回事——死机原因排查（内存/供电/驱动）建议继续，不因本定性而搁置。

## 七、回滚

分支 `feat/desktop-libmpv-kernel` 独立演进；合入前发现不可挽回问题直接弃分支。合入后回滚 = revert 迁移 commit 序列（web 侧改动独立于桌面侧，可分笔回退）。
