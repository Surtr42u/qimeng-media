# 绮梦影库 · 桌面壳（Tauri 2 连接模式）

B 站桌面客户端同款形态的薄壳：本体 UI 由 Go 服务端（默认 `:8420`）托管的完整 Web UI 提供，
桌面壳只负责「记住服务器地址 → 开窗加载 → 托盘 → 导航守卫」。

- 品牌名 `绮梦影库` 复用 `android/app/src/main/res/values/strings.xml` 的 `app_name`（单一来源）
- 应用标识 `media.qimeng.desktop`
- 图标 `app-icon.png` 由 Android 启动图标前景 + 品牌底色 `#DDBC98`（`values/colors.xml` 冻结资产）合成

## 首次使用

1. 启动应用。没有配置时自动弹出「连接服务器」设置窗。
2. 填服务器地址（与手机 App 登录页填同一个地址，如 `http://192.168.1.8:8420`）。
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
- **关主窗口 = 退出应用**（不做托盘常驻，批次 1 从简）。
- **托盘**：左键单击 = 打开/聚焦主窗口；右键菜单 = 打开主窗口 / 切换服务器地址 / 退出。

## 目录结构

```
desktop/
├── app-icon.png            # 1024×1024 图标源（`npm run icon` 的输入）
├── ui/setup.html           # 设置窗静态页（frontendDist，无前端构建步骤）
└── src-tauri/
    ├── tauri.conf.json     # Tauri 2 配置（窗口在 Rust 侧按配置有无动态创建）
    ├── capabilities/       # ACL：core:default 最小权限
    ├── icons/              # `npm run icon` 生成，勿手工编辑
    └── src/
        ├── main.rs         # 窗口/托盘/命令编排 + 导航守卫（守卫纯函数带单测）
        └── server_config.rs# 地址规范化纯函数 + 配置读写（规范化带单测）
```
