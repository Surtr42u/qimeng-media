# 绮梦影库 · 桌面壳（Tauri 2 连接模式）

B 站桌面客户端同款形态的薄壳：本体 UI 由 Go 服务端（默认 `:8420`）托管的完整 Web UI 提供，
桌面壳只负责「记住服务器地址 → 开窗加载 → 托盘 → 导航守卫」。

- 品牌名 `绮梦影库` 复用 `android/app/src/main/res/values/strings.xml` 的 `app_name`（单一来源）
- 应用标识 `media.qimeng.desktop`
- 图标 `app-icon.png` 由 Android 启动图标前景 + 品牌底色 `#DDBC98`（`values/colors.xml` 冻结资产）合成

## 首次使用

1. 启动应用。没有配置时自动弹出「连接服务器」设置窗。
2. 填服务器地址（与手机 App 登录页填同一个地址，如 `http://192.0.2.8:8420`）。
   可以省略 `http://`、省略结尾 `/`，会自动补全规范化。
3. 点「保存并连接」→ 配置写入系统标准配置目录 → 主窗口打开并加载该地址。

配置文件位置（Tauri 标准 app_config_dir，即 `config_dir/media.qimeng.desktop/server.json`）：

- Windows：`%APPDATA%\media.qimeng.desktop\server.json`
- macOS：`~/Library/Application Support/media.qimeng.desktop/server.json`
- Linux：`~/.config/media.qimeng.desktop/server.json`

## 改服务器地址

三种入口（任选）：

- 托盘右键 → 「切换服务器地址」
- 带参数启动：`绮梦影库 --setup`（兜底入口，直接进设置窗）
- 手工改上面那个 `server.json`（删掉它 = 回到首次启动流程）

## 开发

前置：Rust（含 Windows 的 MSVC 工具链 + WebView2）、Node.js。

```bash
cd desktop
npm install       # 装 @tauri-apps/cli
npm run icon      # 首次构建前必须跑一次：从 app-icon.png 生成 src-tauri/icons/ 全套
                  #（不跑的话 tauri::generate_context! 因缺图标编译失败）
npm run dev       # 开发调试
npm run build     # 出包（产物在 src-tauri/target/release/bundle/）
```

## 行为说明

- **导航守卫**：主窗口内只允许访问配置里那台主机（http/https 均放行），
  其它一律拦截 —— 防止服务端页面里的外链把壳带跑。
- **CSP（2026-09-20 审计 R14）**：`tauri.conf.json` 的 CSP 只注入本地页
  （`ui/setup.html` 设置窗）：脚本/样式放行内联（该页为静态内置表单）、
  IPC 走 Tauri 2 官方基线 `ipc: http://ipc.localhost`。主窗口加载的是
  服务端远端 URL，远端页响应头由服务端自主决定，本 CSP 不覆盖。
- **关主窗口 = 退出应用**（不做托盘常驻，批次 1 从简）。
- **托盘**：左键单击 = 打开/聚焦主窗口；右键菜单 = 打开主窗口 / 切换服务器地址 / 退出。

## 目录结构

```
desktop/
├── app-icon.png            # 1024×1024 图标源（`npm run icon` 的输入）
├── ui/
│   ├── setup.html          # 设置窗静态页（frontendDist，无前端构建步骤）
│   └── app-icon.png        # 设置窗引用的应用图标
└── src-tauri/
    ├── tauri.conf.json     # Tauri 2 配置（窗口在 Rust 侧按配置有无动态创建）
    ├── capabilities/       # ACL：core:default 最小权限
    ├── icons/              # `npm run icon` 生成，勿手工编辑
    └── src/
        ├── main.rs         # 窗口/托盘/命令编排 + 导航守卫（守卫纯函数带单测）
        ├── server_config.rs# 地址规范化纯函数 + 配置读写（规范化带单测）
        └── titlebar.js     # 无边框窗口的顶栏注入脚本（见下方跨仓契约）
```

## 跨仓契约：Web TopBar ↔ titlebar.js

主窗口无边框（decorations=false），顶栏三枚窗口控件（最小化/最大化/关闭）由
Web UI 的 `web/src/components/shell/TopBar.tsx` 渲染，桌面壳经 `titlebar.js`
注入后按 **`button.win-btn` 类名 + 中文 `title` 属性值**（最小化/最大化/关闭）
分发窗口操作，拖动判定依赖 TopBar 的 `<header>` 元素。改动 TopBar 时**必须**
保持这三个按钮的类名与 title 文案、以及 `<header>` 元素，否则桌面壳的窗口
操作与拖动会静默失效（无边框窗口无系统边框兜底，只能靠托盘退出）。
TopBar.tsx 内已有单向注释提示；本节为 desktop 侧的逆向记档。

## 原生播放内核（libmpv）

- **决策与进度**：选型决策见 `docs/adr/0036`（含 2026-10-06 实施修订）；**后续接线与插件计划见 `PLUGIN_PLAN.md`（唯一执行文档）**。
- **现状（2026-10-06 已接线并修复 P0）**：壳内点视频 = mpv 播放窗**内置在页面舞台位**（主窗 WS_CHILD 子窗，随滚动/缩放跟随，滚出视口自动隐藏）；mpv 为壳内唯一内核，无可见切换组件；控制条已与网页端收敛为同一份自绘组件 `PlayerControls`（`web/src/components/media/player-controls.tsx`，含清晰度/倍速/字幕/设置四入口），桌面端贴底排布、网页端绝对定位浮在画面底部，改一处两端同变。mpv 内建默认键位（**先点一下画面**再按：空格暂停、←→ ±5s、↑↓ ±60s、`[`/`]` 倍速、`m` 静音、`f` 全屏）。
- **播放口径对齐旧内核**：默认静音 + 音量 0.7（2026-09-17 用户拍板口径）；时间轴标签以 mpv 章节形式画在 OSC 进度条上；进度采样 1s（上报仍 5s 协议节拍），暂停即时 flush。
- **首次使用（内核接线后需要）**：`powershell -ExecutionPolicy Bypass -File src-tauri\setup-mpv.ps1`（GitHub 双源+镜像自取 mpv-dev 包；DLL 不入库）。**DLL 缺失不再是不可播**：`mpv_open` 失败时壳内自动回退 ArtPlayer。
- **浏览器模式零影响**：浏览器环境共用同一份控制条，补齐右侧四入口，拔除历史坐标与文案 hack 补丁。
- **排障入口**：`src-tauri/target/release/qimeng-shell.log`（生命周期跟踪）/ `qimeng-panic.log`（panic 落盘）/ `%LOCALAPPDATA%\CrashDumps`（WER 转储）。排查"有声音没画面"先看 `cargo test` 是否有 `never used` 死代码警告（=命令漏注册，见 PLUGIN_PLAN 坑 1）。

## 已知限制（记档，暂不处理）

- 标题栏双击最大化机制（2026-10-06 修复）：废除脆弱的浏览器全局 `e.detail === 2`，
  改为严格记录合法 header 空白拖拽区的 mousedown 时间戳与坐标（350ms 内且位移 ≤ 5px 判定为双击），
  并对三枚窗口控件以及全屏浮层（如图片查看器关闭）卸载增加 400ms 冷却拦截，
  彻底解决双击最小化或关闭图片查看器时的误触发最大化；窗口控件与浮层显式声明 `-webkit-app-region: no-drag`
  并贴顶贴边扩大热区，满足菲茨定律并提供瞬时 `:active` 触感反馈。
- `capabilities/default.json` 的 remote 授权是 `http(s)://*` 通配——服务器
  地址是用户运行时配置，静态 capability 无法收窄到具体主机（文件 description
  注明的是「远程源须显式声明才生效」，通配的理由即本条）。威胁模型是用户
  自己的 NAS，接受；若将来远程隧道暴露常态化，再评估收紧。
- 无自动更新器（单用户自用，接受；HANDOVER 记档级同条）。
- 原生内核的播放窗交互以 mpv 内建 OSC + 默认键位为基线（**wid 嵌入下 OSC 的实测表现属用户走查项**）；缺口对齐清单见 `PLUGIN_PLAN.md` §三-2。旧内核那套控制条的像素级复刻路线（透明控制层）**尚未定案**——"闪退判死刑"是未经对照实验的推测，见 PLUGIN_PLAN 坑 11。
- **libmpv 与普通 mpv 的默认值不同**（`osc` 默认关、`input-cursor`/`window-dragging`/`mute`/`volume` 各异），改播放窗行为前先读 PLUGIN_PLAN 坑 10 的实测表，勿按普通 mpv 手册的默认值推断。
