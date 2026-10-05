# MPV_MIGRATION - 桌面播放内核 libmpv 迁移（任务文档 / 接手入口）

> **本文是本次迁移的唯一进度与接手文档**（决策依据见 `docs/adr/0036`，本文只记事实与进度）。
> 接手 AI：先读完本文再动手；完成任何条目后**立刻更新本文对应 checkbox**再提交，保证任意时刻中断都可持续。

## 一、目标与拍板

- 桌面壳引入 **libmpv** 作为原生播放内核，最终形态 = 桌面端唯一内核（ArtPlayer 保留给浏览器模式）。
- 用户拍板（2026-10-05）：一次性到位、不积累未来债/技术债/维护债；迁移工程量不设限。
- 能力动机：VSR 主动控制 / RTX Video HDR / Anime4K / SVP 插帧 / 全格式 / 未来神经编码——全部依赖「自持内核」这一个前提。

## 二、架构（目标态）

```
Web UI (AssetDetailPage → VideoPlayer)          仅浏览器模式直接用 ArtPlayer
   │  桌面壳内：『原生内核』按钮
   │  window.__TAURI__.core.invoke('mpv_open', {url, title, startSecs})
   ▼
Tauri 壳 (desktop/src-tauri)
   ├─ commands: mpv_open / mpv_status / mpv_control(pause|seek|speed) / mpv_close
   ├─ 播放窗（纯窗口，无 webview）→ hwnd()
   └─ mpv 子线程：LoadLibraryW("libmpv-2.dll") 运行时加载
        wid = 播放窗 HWND（initialize 前设置）→ mpv 自管渲染 + OSC
        状态缓存（Mutex）← PROPERTY_CHANGE 事件循环
   ▲
   │  web 每 5s invoke('mpv_status') 轮询 → 喂 useProgress(tick/flush) + 起播上报
```

关键取舍：wid 嵌入（非 render API）；JS 轮询（非事件推送）；运行时加载（非构建期链接）。理由全在 ADR-0036。

## 三、里程碑与进度

### C1 决策与文档（本次已提交）
- [x] ADR-0036 + INDEX 行 + CAPABILITY_MAP 行
- [x] 本文（任务文档）+ desktop/README 原生内核节
- [x] `setup-mpv.ps1`（用户自取 libmpv-2.dll，不入库）+ .gitignore

### C2 Rust 侧 mpv 子系统
- [ ] `src/mpv/ffi.rs`：kernel32 extern（LoadLibraryW/GetProcAddress/FreeLibrary）+ 函数指针表 + MPV_FORMAT_*/MPV_EVENT_* 常量（**以官方 client.h 核对值为准**，勿凭记忆写）
- [ ] `src/mpv/player.rs`：独立线程持有 mpv 句柄（单线程访问原则）；命令经 mpsc（loadfile/pause/seek/speed/quit）；事件循环 wait_event → 更新状态缓存；SHUTDOWN → terminate_destroy
- [ ] `src/mpv/commands.rs`：四个 #[tauri::command]；播放窗创建（label `mpv-player`，取 hwnd 传 wid）；窗口 Destroyed → 发 quit
- [ ] `main.rs`：mod 挂载 + invoke_handler 注册 + 状态管理
- [ ] 自测：`cargo check` + `cargo test`（desktop/src-tauri 下）全绿

### C3 Web 侧接入
- [ ] `video-player.tsx`：壳内（`'__TAURI__' in window`）渲染「原生内核」按钮；invoke mpv_open（带当前进度起点）+ 暂停 web 播放器；5s 轮询 mpv_status 喂 onTimeUpdate/onPlay/onPause（复用 stepPlayGate 口径）；状态为 null（窗口已关）→ 停轮询复位
- [ ] `AssetDetailPage.tsx`：传 `nativeTitle={d.title ?? d.fileName}`（仅新增一个 prop）
- [ ] 降级：mpv_open 报错 → 按钮提示（含 setup-mpv 指引），web 内核不受影响
- [ ] 自测：`npm run build`（tsc -b）+ `npm test` 全绿

### C4 复核与收尾
- [ ] 对抗复核：FFI 签名/枚举逐个对 client.h；线程边界；panic 面；铁律 7（UI 不碰业务）/14（无二进制入库）过一遍
- [ ] CHANGELOG 每笔同 commit；本文 checkbox 全勾
- [ ] 运行验收（需真人）：桌面壳起 → 详情页点原生内核 → 播放窗出画面/OSC 可控 → 关窗回收 → web 进度续上

### 后续批次（不在本次范围）
- [ ] 播放窗交互对齐 web 控制条（倍速/打点/手势）
- [ ] `on_load` 钩子刷新签名直链（ADR-0027 窗口过期防御）
- [ ] mpv.conf 预置（VSR d3d11vpp / RTX HDR / Anime4K 挂载位）与设置页开关
- [ ] SVP 插帧指引（用户侧安装文档）
- [ ] render API 纹理合成（仅当需要把视频合成进 web 界面时再立项）

## 四、环境准备（运行前提）

```powershell
# 一次性：拉取 libmpv-2.dll（GitHub shinchiro/zhongfly 构建源，bsdtar 解 7z）
powershell -ExecutionPolicy Bypass -File desktop\src-tauri\setup-mpv.ps1
# DLL 落位 desktop/src-tauri/mpv/lib/（gitignored），并尽力复制到 target/{debug,release}/
```

构建：`cd desktop/src-tauri && cargo build`（无需 mpv-dev 头文件，编译零依赖）。

## 五、坑与存疑（接手必读）

1. **FFI 枚举值与签名必须对官方 client.h**（raw.githubusercontent.com/mpv-player/mpv/master/libmpv/client.h）——尤其 MPV_EVENT_* 数值与 mpv_event 字段序；调研代理产出在本会话记录，若缺失重新核对。
2. **wid 行为**：wid 在 Windows 上由 mpv 创建子窗口渲染；父窗 resize 是否自动跟随、OSC 是否在 wid 模式可用，以官方 manual 为准；实测不符时兜底方案=监听窗口缩放手动 `set_property("current-window-size")`（先实测再写）。
3. **远端页 invoke 自定义命令**：主窗口是远端 URL，`window.__TAURI__.core.invoke` 依赖 withGlobalTauri + capability remote 上下文（titlebar.js 用 window API 已验证可用）；自定义命令是否需要显式 capability 条目**未实测**——若 invoke 报权限错，在 capabilities/default.json 补条目或改走 events。
4. **单线程访问 mpv**：除文档明确线程安全的函数外，全部 mpv_* 调用收敛在播放线程；跨线程只传 mpsc 命令与 Mutex 状态缓存，不做跨线程直接调用。
5. **签名直链 6h 窗口**（ADR-0027）：超长播放会话中途 403 → mpv 停止；已知限制，`on_load` 刷新方案在后绕批次。
6. **主窗关闭语义**：现「关主窗=退出应用」判定只看 main/setup 两 label，播放窗存在不阻止退出——行为正确，勿"修复"。
7. **GPL 分发线**：DLL 不入库不进产物分发（gitignore + setup 脚本），见 ADR-0036 决策 6。

## 六、回滚

分支 `feat/desktop-libmpv-kernel` 独立演进；合入前发现不可挽回问题直接弃分支。合入后回滚 = revert 迁移 commit 序列（web 侧改动独立于桌面侧，可分笔回退）。
