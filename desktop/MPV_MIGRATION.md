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
7. ADR-0036/本文/CAPABILITY_MAP 状态行同步收口。

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

### C3 Web 侧接入（进行中）
- [ ] `lib/engagement-reporting.ts` + 单测：`nativePollSignal`（轮询边沿→play/pause 信号纯函数，喂 stepPlayGate 同一口径 B 状态机）
- [ ] `video-player.tsx`：壳内（`'__TAURI__' in window`）渲染「原生内核」chip；invoke mpv_open（当前 web 进度优先、断点起点兜底）+ 暂停 web 播放器；5s 轮询 mpv_status 喂 onTimeUpdate/onPlay/onPause；状态 null → 复位；原生接手中用户点 web 播放 → 收回（mpv_close + 复位）
- [ ] `AssetDetailPage.tsx`：传 `nativeTitle={d.title ?? d.fileName}`
- [ ] `glass.css`：chip 样式（token 取色，禁硬编码色值）
- [ ] 自测：`npm test` + `npm run build` 全绿

### C4 复核与收尾
- [ ] `cargo build --offline --release` 出 release 产物（启动-桌面端.bat 的拉起目标；本次验收只覆盖 debug）
- [ ] 对抗复核：FFI 签名/枚举逐个对 client.h；线程边界；panic 面；铁律 7（UI 不碰业务）/14（无二进制入库）过一遍
- [ ] CHANGELOG 每笔同 commit；本文 checkbox 全勾；§七日志收口
- [ ] 运行验收（需真人）：按 §一验收清单 1~6 走查
- [ ] CAPABILITY_MAP 状态行改「已有」+ HANDOVER 收口

### C5+ 后续批次（**用户 2026-10-05 拍板：主体交付即告一段落，以下全部按需再议、不排期**——插件/画质预置类想到再弄）
- [ ] 播放窗交互对齐 web 控制条（倍速/打点联动/手势）
- [ ] `on_load` 钩子刷新签名直链（ADR-0027 窗口过期防御）
- [ ] mpv.conf 预置（VSR d3d11vpp / RTX Video HDR / Anime4K 挂载位）+ 设置页开关
- [ ] SVP 插帧指引（用户侧安装文档）
- [ ] render API 纹理合成评估（仅当需要视频合成进 web 界面时）
- [ ] Android 端不在本任务书范围（Media3 即终局内核，增强走 Media3 Effects 另立任务）

## 四、当前状态与交接日志（倒序追加）

- **2026-10-05 · GLM-5.3-Flash（主代理）会话 1（收口）**：C1 提交（f51da4e8）；C2 代码+验证完成（f1c56360 落码 → f6..fix 笔：2 编译错+5 警告修复，BUILD/TEST 全绿 22 单测，环境事故定性=被杀进程残留见 §六）；任务书 charter 化（8c110f76）。C3 web 侧未落码。**明日接手第一步（按序）**：
  1. `cd desktop/src-tauri && cargo build --offline --release`（启动-桌面端.bat 吃 release 产物，本次只验了 debug）；
  2. 跑 `desktop/src-tauri/setup-mpv.ps1` 取 libmpv-2.dll；
  3. 落 C3（本文件 C3 节 checklist，方案=定稿）；`npm test` + `npm run build`；
  4. C4 复核收口 + 运行走查（§一验收清单）。
  5. 网络可用时 `git push -u origin feat/desktop-libmpv-kernel`（分支目前只在本地）。
- （历史）2026-10-05 会话 1 中段：cargo check 曾被设备文件损坏阻塞——已定性为被杀进程残留物（§六），非代码问题，硬件未持续损坏（依赖树全量重编零损坏实证）。

## 五、坑与存疑（接手必读）

1. **FFI 枚举值与签名必须对官方 client.h 终验**（raw.githubusercontent.com/mpv-player/mpv/master/libmpv/client.h）——MPV_EVENT_* 数值、mpv_event 字段序（event_id/reply_userdata/data/error）。本会话调研子代理因 GitHub 被墙未返回，当前值=ABI 冻结口径（mpv 承诺永不重编号），风险低但终验不可省。
2. **wid 行为**：Windows 上 mpv 在给定 HWND 内自建子窗渲染；父窗 resize 是否自动跟随、OSC 是否可用，实测不符时兜底=监听尺寸变化手动同步（先实测再写）。
3. **远端页 invoke 自定义命令**：主窗是远端 URL，`window.__TAURI__.core.invoke` 依赖 withGlobalTauri + capability remote 上下文（titlebar.js 已验证窗口 API 可用）；自定义命令是否需显式 capability 条目**未实测**——报权限错就在 capabilities/default.json 补。
4. **单线程访问 mpv**：除文档明确线程安全者外，全部 mpv_* 调用收敛在播放线程；跨线程只走 mpsc + Mutex 缓存。
5. **签名直链 6h 窗口**（ADR-0027）：超长会话中途 403 → mpv 停止；已知限制，C5 on_load 刷新解。
6. **关主窗语义**：现「关主窗=退出应用」判定只看 main/setup 两 label，播放窗为 user32 自建、不经 Tauri 窗口系统，行为不受影响——勿"修复"。
7. **GPL 分发线**：DLL 不入库不进产物分发（gitignore + setup 脚本），见 ADR-0036 决策 6。

## 六、构建环境事故（2026-10-05 已定性：被杀进程的残留物，非硬件持续损坏）

**症状**：rustc 报 E0432（源文件"缺"实际存在的类型）/E0786（rmeta·rlib corrupt）/源文件全零（zmij lib.rs 落盘 66043+ NUL）。

**终局定性（T2 残留论成立）**：清空 `~/.cargo/registry/src/*` + `target/debug` 后**全量重编 100+ crate 一次通过、零损坏**（tokio/tao/tauri 全绿）。此前所有失败 = cargo 解包"先分配后写"语义下，进程被中途杀死（用户历史死机时正在构建 / 本会话被工具层掐断的 cargo）遗留的全零/半截文件；每次清一层、下一层浮出，形成"到处坏"假象。

**已实测排除**：.crate 缓存损坏（tar 抽取对照一致）、当前写路径损坏（同路径重解 3 次 + 3×256MB 写读校验全过）、沙箱（关沙箱同样现象）、OneDrive 接管 Desktop。

**未闭合一例**：windows_core rmeta"同轮写读即坏"一次（残留论无法直接解释，未复现）——保持观察，若未来复现按 T1（RAM/SSD）处置：mdsched → CrystalDiskInfo → chkdsk → 杀软排除项。

**给用户的独立事项**：构建高负载下死机仍复现过一次（用户实测确认），与本事故残留是两回事——死机原因排查（内存/供电/驱动）建议继续，不因本定性而搁置。

## 七、回滚

分支 `feat/desktop-libmpv-kernel` 独立演进；合入前发现不可挽回问题直接弃分支。合入后回滚 = revert 迁移 commit 序列（web 侧改动独立于桌面侧，可分笔回退）。
