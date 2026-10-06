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
- **现状（2026-10-06）**：mpv 内核已并入 master（`src-tauri/src/mpv/`：运行时加载 FFI + user32 播放窗 + 会话线程 + IPC 四命令），**暂无触发入口**——透明控制层方案因运行闪退撤回、可见胶囊入口已删除（壳内暂走 web 内核）；触发方式拍板与接线步骤见 PLUGIN_PLAN §三。
- **首次使用（内核接线后需要）**：`powershell -ExecutionPolicy Bypass -File src-tauri\setup-mpv.ps1`（GitHub 双源+镜像自取 mpv-dev 包；DLL 不入库）。
- **浏览器模式零影响**：浏览器环境无壳 IPC，ArtPlayer 行为不变。

## 已知限制（记档，暂不处理）

- 标题栏双击最大化的判定用 `mousedown` 的 `e.detail === 2`——Windows 下
  第一次 mousedown 已进入系统拖动模态循环，第二次物理点击能否送达页面未
  实测验证；若真机出现双击偶发失灵，改 click 计时判定（勿用
  `data-tauri-drag-region`，那需要改 web 端，违反「web/ 零改动」约束）。
- `capabilities/default.json` 的 remote 授权是 `http(s)://*` 通配——服务器
  地址是用户运行时配置，静态 capability 无法收窄到具体主机（文件 description
  注明的是「远程源须显式声明才生效」，通配的理由即本条）。威胁模型是用户
  自己的 NAS，接受；若将来远程隧道暴露常态化，再评估收紧。
- 无自动更新器（单用户自用，接受；HANDOVER 记档级同条）。
- 原生内核接线前：壳内播放走 web 内核；接线后播放窗交互以 mpv 内建 OSC 为基线，缺口对齐清单见 `PLUGIN_PLAN.md` §三-2。
