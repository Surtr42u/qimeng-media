# 插件计划：桌面原生播放内核接线 + 播放增强（下一位 AI 的执行入口）

> **本文是 mpv 内核后续工作的唯一执行文档**（2026-10-06 起；原 `MPV_MIGRATION.md` 已退役删除）。
> 决策依据见 `docs/adr/0036`（含 2026-10-06 实施修订）。接手规则：① 先通读本文与 ADR-0036；
> ② 只做当前批次；③ 完成条目立刻更新本文 checkbox 再提交；④ 中断时下一位凭本文续作。

## 一、目标态（用户 2026-10-06 拍板口径）

桌面壳内 **mpv 为唯一播放内核，旧 web 内核（ArtPlayer）在壳内不再保留**（浏览器模式照旧用
ArtPlayer——浏览器无法嵌原生内核，见 ADR-0036）；**无任何可见切换入口**（右上角胶囊已删除，
不要复活）；**不做双内核并存/功能开关保留计划**。触发方式**未拍板**（候选见 §三-0）。

## 二、现状（2026-10-06 合并主分支时点）

- **已并入 master**：mpv 子系统本体（`desktop/src-tauri/src/mpv/` 四文件：ffi 运行时加载 /
  win32 播放窗+全屏 / player 会话线程 / commands 四命令 IPC 面）——编译、23 单测全绿，
  **未经运行验证**（见 §四闪退史）；诊断设施（`src/diag.rs` 跟踪日志、main.rs panic 落盘
  qimeng-panic.log、RunEvent::ExitRequested 落盘、WER LocalDumps 注册表 HKCU 配置）；
  `setup-mpv.ps1`（已修 PS5.1 BOM + gh-proxy.com 镜像回退）；web 侧 IPC 白名单
  （permissions/mpv-commands.toml + capabilities 四命令）。
- **已撤回**（代码在分支 `feat/desktop-libmpv-kernel` 历史里，可摘樱桃，勿重写）：
  - C3 web 接入（chip/5s 轮询/收回/卸载回收）：commit `f553f07c`
  - C4.5 透明控制层（overlay.rs + player-controls.html + 代数化生命周期）：commit `a847aafd`
  - 恢复 web 侧文件可直接 `git show f553f07c -- web/src/...` 对照。
- **闪退史（撤回原因，详见该分支 57623ca1 笔 CHANGELOG）**：点 chip → 整壳"干净消失"，
  100% 复现 3 次；diag 跟踪钉死死亡点=主线程 `WebviewWindowBuilder::build()` 内部
  （mpv 侧已健康跑到 mpv_initialized）；无 panic/无事件 1000/无 WER/dump（ExitProcess 级）；
  头号嫌疑=tao 透明窗 legacy DWM blur-behind 路径 + WebView2 二次建环境叠加。

## 三、批次路线图

### 0. 触发方式拍板（动手前先问用户，候选）
- **A 点播即原生**：详情页视频舞台点击 → 打开 mpv 播放窗（推荐：行为自然、无可见组件）；
- **B 设置页开关**：设置页加「桌面端用原生内核播放」开关；
- **C 自动播放**：进详情页自动开窗（用户已明确说"没必要"，默认排除）。

### 1. 内核接线批（先让内核真正跑起来）
- [ ] **client.h 终验（最高优先，两个已知疑点）**：从 `setup-mpv.ps1` 下载的 mpv-dev 7z 内
  `include/mpv/client.h` 核对（GitHub 被墙，走 gh-proxy.com 前缀；blob 页是 JS 壳取不到内容，
  **必须解 7z**）：
  - `MPV_EVENT_SHUTDOWN`：当前 ffi.rs 写 **2**，疑应为 **1**（LOG_MESSAGE=2）；
  - `struct mpv_event` 字段序：当前 ffi.rs 写 `event_id/reply_userdata(u32)/data/error`，
    疑应为 `event_id/error(i32)/reply_userdata(u64)/data`——**若后者为真，mpv_status 的
    状态缓存永远读不到数据（data 指针错位读成 reply_userdata=0），进度上报链全瞎**，
    自动/轮询类方案在终验前都是空中楼阁；
  - 顺带全量核对 MPV_FORMAT_*/MPV_EVENT_* 与 mpv_event_property 字段序（name/format/data）。
- [ ] 触发方式落地（按 §三-0 拍板结果），壳内触发 → `mpv_open(url, title, startSecs)`
  （web 进度优先、断点起点兜底的口径沿旧 C3）；web 轮询 `mpv_status` 5s 喂
  `useProgress`（tick/flush）+ 起播上报（口径 B 纯函数 `nativePollSignal` 可从
  `f553f07c` 的 `web/src/lib/engagement-reporting.ts` 摘回）。
- [ ] 降级路径：DLL 缺失 → `mpv_open` 错误串（自带 setup 指引）→ 壳内提示并回退 web 内核播放
  （此为降级 UX，不是双内核并存——正常态无任何可见切换件）。
- [ ] 壳内 ArtPlayer 退役：触发方案验证通过后，壳内视频舞台不再挂 ArtPlayer（浏览器模式不动）。
- [ ] 运行走查（§五清单 1~6）+ `cargo build --release` + 三端工具链全绿 + 文档收口
  （CAPABILITY_MAP 行、HANDOVER、本 checkbox）。

### 2. 播放窗交互对齐（原 C4.5，重设计）
- [ ] mpv OSC 实测先行：wid 模式下 mpv 内建 OSC/快捷键表现（空格/方向键/进度条）——
  **零开发成本的基线，先实测再决定做多少**（坑：wid 下 OSC 可用性未实测）。
- [ ] 缺口对齐（对照 video-player.tsx 现行为）：进度打点显示、倍速菜单 0.5~3x（1x 文案
  「正常」）、键盘映射。**透明 WebView2 控制层方案已判死刑**（闪退史）；若 OSC+快捷键
  不够用，候选=mpv Lua 脚本自绘控制条（`SCRIPT` 目录，进程内无第二窗口，天然避开闪退面）
  或 mpv.conf 精调 + 操作习惯让步（过用户拍板）。
- [ ] IPC 控制面已就绪：`mpv_control` 动作 pause/resume/toggle-pause/seek/speed/volume/
  mute/toggle-fullscreen（parse_action 单测锁定；web 侧双层窗时代的调用代码可从
  `a847aafd` 的 player-controls.html 摘取参考）。

### 3. 画质/插件批（用户 2026-10-06 已立项两项，实测依据见下）
- [ ] **mpv.conf 画质基线**（用户拍板）：gpu-next 渲染器 + 高质量上/下缩放（ewa_lanczossharp/
  mitchell）+ 抖动位深。落法=代码内 `set_option_string` 预置（player.rs OPT_* 单源模式，
  **软失败**：未知选项告警不阻断——旧 DLL 兼容）；库画像=4K 39%/60fps 为主，性能优先于画质档。
- [ ] **RTX Video HDR 配套**（用户拍板，HDR 屏已确认；库内 **0 个 HDR 片源**全 bt709 SDR——
  本项=驱动侧 SDR→HDR 全库转换的呈现路径适配 + `desktop/README.md` 使用指南：
  NVIDIA App 开 RTX HDR、与 Windows Auto HDR 二选一、验证方法）。
- [ ] on_load 刷新签名直链（ADR-0027 6h 窗口防御；库内超长片仅 5 个，低价值低成本）。
- [ ] 3D/VR 播放辅助（**未立项**）：库里 8 个异形分辨率文件（3840×3840 鱼眼 1 个、
  5120×2160 等），用户暂未确认有桌面观看需求，先不做。
- [ ] **明确不做**（数据依据，勿再提案）：Anime4K（库以 3D 渲染/MMD 为主非 2D 线稿 +
  39% 已 4K 原生）、SVP 插帧（60fps 占大头）、字幕/音轨切换 UI（抽样 0 字幕流 + 全立体声）、
  render API 纹理合成（无场景）。

## 四、已知坑（接手必读）
1. **ffi.rs 两疑点（§三-1，终验前勿信状态链）**。
2. **wid 行为**：mpv 在给定 HWND 自建子窗；父窗 resize/OSC 可用性实测未做。
3. **远端页 invoke 自定义命令**：已解决——应用级权限 `permissions/mpv-commands.toml`
   （缺了报 "Command mpv_open not allowed by ACL"）；新增命令须两处同步。
4. **单线程访问 mpv**：全部 mpv_* 收敛播放线程；跨线程只走 mpsc + Mutex 缓存。
5. **签名直链 6h 窗口**（ADR-0027）：超长会话 403 → mpv 停止；§三-3 on_load 解。
6. **关主窗语义**：「关主窗=退出」只看 main/setup 两 label——勿"修复"。
7. **GPL 分发线**：DLL 不入库不进产物分发（gitignore + setup 脚本）。
8. **透明 WebView2 叠加窗 = 判过死刑的路径**：不要再尝试（闪退史见 §二）；
   控制条需求走 OSC/Lua 进程内方案。
9. **诊断设施用法**：`target/release/qimeng-shell.log`（生命周期跟踪）/
  `qimeng-panic.log`（panic 落盘）/ `%LOCALAPPDATA%\CrashDumps`（WER 全量转储，
  注册表 HKCU…LocalDumps\qimeng-media-desktop.exe 已配）——复现问题先读这三处。

## 五、运行走查清单（需用户配合，AI 不得自行拉前台）
1. 详情页触发原生播放 → mpv 窗出画面，OSC 可控（播放/暂停/进度/音量）；
2. 关播放窗/关 web 页 → 会话回收干净（无残留 mpv 线程/窗口），不崩壳；
3. 进度上报链不断：tick 照常、暂停 flush、离开补报——断点续播跨端一致；
4. 浏览器模式回归零影响（无壳时 ArtPlayer 行为与迁移前一致）；
5. 降级：无 DLL 时错误带指引，web 内核可用；
6. `cargo check/test` + `npm test` + `npm run build` 全绿。

## 六、回滚
master 上内核相关 commit 序列可独立 revert（web 侧改动与桌面侧分笔提交，可分笔回退）；
分支 `feat/desktop-libmpv-kernel` 保留完整迁移史（f51da4e8..57623ca1）供摘樱桃/考古。

> 最后更新：2026-10-06（创建，GLM-5.3-Flash；取代已删除的 MPV_MIGRATION.md）
