# 绮梦影库 · 桌面壳（Tauri 2 连接模式）

> 本端入口；全局规则按 [AGENTS](../AGENTS.md) 读取。只改共享 Web UI 时先读 web/README；触及标题栏、设置、托盘、导航/播放才补读本文对应节。

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

Linux GTK 链的 glib 0.18 使用经官方归档哈希核验的上游安全补丁，Cargo 自动从 `vendor/glib` 解析；不要删除 path patch 或恢复注册表来源。来源、许可、两行修复及退役条件见 [补丁说明](vendor/README.md)，验证用 `python tools/check_glib_patch.py`；Linux release 迭代器回归由安全 CI 执行，Windows 桌面门禁继续执行。该 Linux job 验证既有 GTK 依赖漏洞修复，不代表新增 Linux 桌面适配、安装包或完成其设备验收。

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

主窗口无边框（decorations=false），窗口按钮由 `web/src/components/shell/TopBar.tsx` 渲染，`titlebar.js` 以 `data-qimeng-window-action` 的 minimize/maximize/close 值分派动作，拖动区标记 `data-qimeng-titlebar`。未知新动作不回退文案，避免误触发。样式类和中文 title 不再是新壳的主分派协议。

兼容期：新壳继续识别旧 Web 的 `button.win-btn` + 中文 title + header；新 Web 保留这些旧属性，兼容未更新的旧壳。两端尚未全部部署前仍不得修改旧 title/header。双向注释互指；`web/tests/desktop-titlebar.test.ts` 执行实际注入脚本，验证文案独立、新壳读旧 Web、未知动作拒绝和旧壳所需属性保留。自动化不替代原生窗口验收。

## 媒体播放

桌面壳（Tauri 2 连接模式）通过 WebView2 呈现服务端 Web UI，媒体播放全端统一使用标准 **ArtPlayer（HTML5 `<video>`）**：
- 与浏览器模式/PWA 端共享完全一致的播控逻辑、交互体验与设计语言；
- 进度、断点续播、倍速、时间轴打点、全屏展示均由 Web 前端同一套组件驱动，无额外的原生内核桥接与多头维护负担。

当前未接入视频超分、HDR 增强或插帧 SDK；WebView2 的实际增强支持须单独验证，不能以 Edge 浏览器支持推定本壳支持。视频增强属于解码/帧/GPU 管线适配；Go 可调度外部处理，不提供直接加载 GPU 增强算法的通用能力。本轮仅评估，未更换播放内核。

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
