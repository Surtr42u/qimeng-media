# 插件计划：桌面原生播放内核接线 + 播放增强（下一位 AI 的执行入口）

> **本文是 mpv 内核后续工作的唯一执行文档**（2026-10-06 起；原 `MPV_MIGRATION.md` 已退役删除）。
> 决策依据见 `docs/adr/0036`（含 2026-10-06 实施修订）。接手规则：① 先通读本文与 ADR-0036；
> ② 只做当前批次；③ 完成条目立刻更新本文 checkbox 再提交；④ 中断时下一位凭本文续作。

## 一、目标态（用户 2026-10-06 拍板口径）

桌面壳内 **mpv 为唯一播放内核**——**视频长在页面舞台位**（"内置播放"，不是独立弹窗）；
**无任何可见切换入口**（壳内点视频=直接原生播放）；**不做双内核并存/功能开关保留计划**；
ArtPlayer 留给浏览器模式（浏览器无法嵌原生内核，见 ADR-0036）。**旧内核的交互与播放体验**
（进度/断点/上报/播控）是需求的一部分，不是可选项。

## 二、现状（2026-10-06 晚，P0 修复批）

### 已落地

- **P0 修复（本批，最重要）**：`mpv_stage_rect` **漏注册进 `generate_handler!`**，
  而它是子窗**唯一的摆位/显示入口**（子窗创建不带 `WS_VISIBLE`）→ 表现为
  **"有声音、没画面、没控件"**。修前编译器有 4 条 dead_code 警告（`mpv_stage_rect` /
  `Cmd::StageRect` / `client_size` / `GetClientRect` 全部不可达），修后归零——
  这是判定"接线是否真的接上"的**编译级哨兵**，回归时先看这个。
- **libmpv 默认值矫正**（本批实测发现，见 §四坑 10）：`osc` / `input-cursor` /
  `window-dragging` / `mute` / `volume` 五个选项在 libmpv 口径下与普通 mpv 不同。
- **子窗 z-order**：摆位改用 `HWND_TOP`（原 `SWP_NOZORDER` 不保证压在 WebView2 之上）。
- **全屏语义修复**：原 `toggle_fullscreen` 只换 `WS_POPUP` 不清 `WS_CHILD`，
  子窗态下被父窗裁剪=全屏无效；现走 `SetParent(NULL)` 脱离 / `SetParent(原父)` 接回，
  并拦截 mpv 的 `fullscreen` 属性边沿（OSC 全屏按钮与 `f` 键因此可用）。
- **时间轴打点 = mpv 章节**：web 打点经 `mpv_open` 中转 → 播放线程写 ffmetadata 章节文件
  → OSC 进度条上出标记。web 侧原先**根本没传** highlights（Rust 侧还 `let _ = highlights`），
  本批接通。
- **web 侧**：采样节拍 5s→**1s**（与 5s 上报节拍解耦，暂停 flush 最多迟 1s）；DLL 缺失等
  `mpv_open` 失败 → **壳内回退 ArtPlayer**（原实现只显示一行错误串=壳里彻底不能播）；
  「重开=最后已知位置」修好（原读的是被清空的 ref，恒退化成断点起点）；
  `mpv_stage_rect` 失败不再静默吞（首报一次 console）。
- **退出回收会话**：`RunEvent::ExitRequested` 由"只写日志"改为投 `Cmd::Quit`。

### 待用户走查确认（AI 无法自测）

- mpv 在 `wid` 子窗里的**实际渲染表现**、OSC 外观与可点性、滚动跟手度；
- 全屏（`f` 键 / OSC 按钮）展开与还原；
- 打点在 OSC 进度条上的显示；
- 退出/关页面后无残留窗口。

### 明确未做

- **旧内核那套控制条的像素级复刻**（见 §四坑 11：透明控制层"判死"从未做过对照实验）。
- mpv.conf 画质基线、RTX Video HDR 配套（§三-3 两项已立项，本批未动）。

## 三、批次路线图

### 1. 内核接线批（✅ 2026-10-06 内置形态落码 + P0/默认值/全屏/打点修复）
- [x] web 侧接线：挂载即 `mpv_open`（断点起点直入）+ 1s 轮询 `mpv_status`（喂
      `useProgress` 口径 B 闸门）+ `mpv_stage_rect` 舞台矩形上报 + 播放窗关闭后舞台
      点击重开（最后已知位置优先）+ 卸载 `mpv_close`；壳内不挂 ArtPlayer。
- [x] `client.h` 终验（2026-10-06 两 ABI bug 已修：`MPV_EVENT_SHUTDOWN=1`、
      `mpv_event` 字段序 + `data` offset 布局锁）。
- [x] 内置形态：mpv 播放窗 = 主窗 WS_CHILD 子窗铺在舞台矩形上；滚出视口 `SW_HIDE`。
- [x] 降级路径：DLL 缺失 → 错误串 + **壳内回退 ArtPlayer**。
- [x] 时间轴打点接线（章节文件）；退出回收会话。
- [ ] **运行走查（§五清单，需用户）**。
- [ ] `cargo build --release` + 三端工具链全绿 + 文档收口（本批已跑，见下）。

### 2. 播放窗交互对齐（原 C4.5，重设计）
- [x] **控制条收敛 Phase 1（第四百八十九笔）**：网页端与桌面壳收敛为同一个 React 组件 `PlayerControls`（`player-controls.tsx`）+ 同一套 `glass.css` 规则，两端右起四入口（清晰度/倍速/字幕/设置）完全对齐，改一处两端同变；网页端彻底拔除 3 处历史缩放与倍速 hack 补丁。
- [ ] **实测 mpv OSC 在 wid 子窗下的表现**（按钮/进度条/音量/菜单；这是零开发成本的基线）。
- [ ] 缺口对齐（对照 video-player.tsx 基线行为）：倍速菜单（mpv OSC 的左侧"菜单"按钮 →
      `select` 脚本内有 0.25~8 档，实测二进制确认存在）、时间轴打点、键盘映射。
- [ ] **未做的关键实验（坑 11）**：从 `a847aafd` 取回透明控制层，**只去掉
      `.transparent(true)` 一个变量**再点一次——不闪退则"透明=元凶"坐实、旧控制条
      可像素级复活；仍闪退则改走 Lua 自绘控制条（进程内、零第二窗口）。

### 3. 画质/插件批（用户 2026-10-06 已立项两项）
- [ ] **mpv.conf 画质基线**：gpu-next + 高质量缩放 + 抖动位深，代码内 `set_option_string`
      预置（**软失败**：`soft_option` 已就位，未知选项告警不阻断）。
- [ ] **RTX Video HDR 配套**（HDR 屏已确认；库内 0 HDR 片源）+ `desktop/README.md` 指南。
- [ ] on_load 刷新签名直链（ADR-0027 6h 窗口防御）。
- [ ] 3D/VR 播放辅助（**未立项**）。
- [ ] **明确不做**（数据依据）：Anime4K、SVP 插帧、字幕/音轨切换 UI、render API 纹理合成。

## 四、已知坑（接手必读）

1. **`generate_handler!` 注册**（新增 `mpv_*` 命令须同步**三处**：handler / `permissions/*.toml`
   清单 / `capabilities/default.json` 引用）。漏注册的表现是**静默失效**：web 侧 `.catch`
   吞掉错误，界面显示"正在播放中"但什么都没有。**回归哨兵：`cargo test` 出现
   `never used` 死代码警告即漏注册。**
2. **wid 行为（源码级确认，2026-10-06）**：mpv 官方手册明文——win32 下 **mpv 自建窗口并把 wid 窗设为父窗**
   （`w32_common.c`：`CreateWindowExW(WS_EX_NOPARENTNOTIFY, …, WS_CHILD|WS_VISIBLE, …, parent = 你的 HWND)`）。
   推论（都影响本仓代码，改前必读）：
   - **鼠标归 mpv**：子窗直接收 `WM_MOUSEMOVE/WM_LBUTTONDOWN` 并无条件喂 input → **OSC 可点，无需焦点**；
   - **子窗尺寸由 mpv 自己维护**：`install_parent_hook()` 捕宿主 `WM_WINDOWPOSCHANGED` → 子窗自动铺满宿主客户区。
     故本仓只需摆宿主窗（`set_stage_rect`），**不要手工去动 mpv 的子窗**；
   - **宿主必须先可见**：子窗是 `WS_CHILD`，祖先链不可见就什么都不显示（本仓已 `SW_SHOWNA`）；
   - **ID 必须按 `uint32_t` 传，mpv 不接受负值**（代码里已掩 32 位）；
   - **键盘**：mpv 全程不调 `SetFocus`（鼠标只 `SetCapture`），而 Windows 只把按键投给有焦点的窗口
     → **首次交互前先点一下画面**键盘才生效（与旧 ArtPlayer「点画面后热键可用」同构）。
     若用户反馈"空格没用"，解法=给 mpv 子窗 `SetFocus`（`EnumChildWindows` 找它）或宿主自行转发 `keypress`。
   - **全屏：嵌入时 mpv 直接忽略**——`update_fullscreen_state()` 首行 `if (w32->parent) return;`。
     即 OSC 全屏按钮 / `f` 键**只会翻 `fullscreen` 属性，窗口纹丝不动**。本仓的对策是观察该属性边沿、
     转成**宿主窗全屏**再把属性复位（`player.rs::is_fullscreen_edge`），这是嵌入形态下唯一的全屏通路。
3. **远端页 invoke 自定义命令**：需应用级权限 `permissions/mpv-commands.toml`（缺了报
   "Command mpv_open not allowed by ACL"）。
4. **单线程访问 mpv**：全部 `mpv_*` 收敛播放线程；跨线程只走 mpsc + Mutex 缓存。
5. **签名直链 6h 窗口**（ADR-0027）：超长会话 403 → mpv 停止；§三-3 on_load 解。
6. **关主窗语义**：「关主窗=退出」只看 main/setup 两 label——勿"修复"。
7. **GPL 分发线**：DLL 不入库不进产物分发（gitignore + setup 脚本）。
8. **透明 WebView2 叠加窗**：见坑 11——**结论未定案，不要当既定事实引用**。
9. **诊断设施用法**：`target/release/qimeng-shell.log`（生命周期跟踪）/
   `qimeng-panic.log`（panic 落盘）/ `%LOCALAPPDATA%\CrashDumps`（WER 全量转储）。
10. **libmpv 默认值与普通 mpv 不同（2026-10-06 本机加载 libmpv-2.dll 实测，非猜测）**：
    **本机 DLL = mpv v0.41.0-1102-g6c092d978（0.42-dev，`-Dlua=enabled`，故 osc.lua/select.lua 都在）**。
    | 选项 | libmpv 默认 | 后果 |
    |---|---|---|
    | `osc` | **no** | 不显式开 `osc=yes` = 零控制条（"播控=内建 OSC"的前提就是错的） |
    | `input-cursor` | 手册："Necessary to use the OSC" | 不开 OSC 点不动 |
    | `window-dragging` | yes | 按住画面拖=拖动窗口，嵌入子窗下必须关 |
    | `mute` / `volume` | no / 100 | 旧内核口径是 `muted:true` + 0.7 |
    | `input-default-bindings` | no | 已显式开 yes（否则空格/方向键全哑） |
    | `input-vo-keyboard` | no | **语义是"禁用"，别照抄命令行的 yes**——`no`=不禁用才对；win32 上该开关是否生效未证实 |
    改选项一律走 `soft_option`（未知选项只告警不中断），**结构性选项**（wid/idle/
    keep-open/hwdec）才硬失败。**另：`--osc-visibility` 不是 mpv 选项**（是 script-opt /
    `script-message osc-visibility`），`mpv_set_option_string` 设它会返回错码；本批未用它。
    **倍速入口**：0.40+ 的 OSC 有左侧 `menu` 按钮 → `select` 菜单内含 `set speed 0.25/0.5/0.75/1/1.25/1.5/1.75/2/4/8`
    （二进制串实测）；默认 OSC **没有**独立的 speed 按钮，要独立按钮得用 0.40+ 的
    `osc-custom_button_N_*` script-opts。
11. **"透明控制层闪退判死刑"是未验证结论**：当时的对照实验（去掉 `transparent(true)`）
    在执行前被一条针对"删胶囊"的指令打断，从此写进文档当成事实。取证只证明
    "进程死在 `WebviewWindowBuilder::build()` 内、ExitProcess 级、无 panic/WER/dump"，
    **没有证明凶手是透明**。要拿回旧控制条，这是唯一的路，且一次点击就能定案。
12. **章节文件格式**：`--chapters-file` 只吃 **ffmetadata**（`;FFMETADATA1` + `[CHAPTER]`
    + `TIMEBASE/START/END/title`）；**OGM 格式实测 0 章节**，**缺 END 时标题读成空串**。
    末章 END 取 START+1s（此刻不知片长）。

## 五、运行走查清单（需用户配合，AI 不得自行拉前台）

1. 详情页打开视频 → **画面出现在舞台位**（P0 判据），有声音；
2. OSC 控制条出现且可点：播放/暂停、进度条拖动、音量、左侧"菜单"按钮 → 倍速档位；
3. 键盘：**先点一下画面**再按（mpv 不 `SetFocus`，首交互前焦点在网页里）——空格暂停、
   ←/→ 5s、↑/↓ 60s、`f` 全屏、`m` 静音、`[`/`]` 倍速；
4. 时间轴打点在 OSC 进度条上可见（有标签的资产）；
5. 全屏（`f` 或 OSC 按钮）能进能出，画面跟随；
6. 滚动页面 → 播放窗跟随/滚出视口自动隐藏，不遮页面；
7. 关播放窗/关页面 → 会话回收干净（无残留窗口/线程），不崩壳；
8. 进度上报链不断：tick 照常、暂停 flush、离开补报、断点续播跨端一致；
9. 浏览器模式回归零影响（无壳时 ArtPlayer 行为与迁移前一致）；
10. 降级：临时移走 `libmpv-2.dll` → 壳内应回退 ArtPlayer 正常播放。

## 六、回滚

mpv 内核相关 commit 可按笔独立 revert（web 侧与桌面侧分笔）；分支
`feat/desktop-libmpv-kernel` 保留完整迁移史（f51da4e8..57623ca1）**供摘樱桃/考古——
注意它的 tip 就是撤回提交 fb0cc504 本身，checkout 它拿不到被删的控制层**，
控制层要从 `a847aafd` 取（`git show a847aafd:desktop/ui/player-controls.html` 等）。

> 最后更新：2026-10-06（P0 修复批，DeepSeek-Flash；前版由 GLM-5.3-Flash 创建）
