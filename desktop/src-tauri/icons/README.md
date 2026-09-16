# 此目录下的图标由 `npm run icon`（tauri icon app-icon.png）生成：

- 32x32.png / 128x128.png / 128x128@2x.png
- icon.icns（macOS）/ icon.ico（Windows）
- 以及 Square*.png 等平台全套

**不要手工编辑本目录**；改图标请改 `desktop/app-icon.png` 后重新执行 `npm run icon`。
构建前若本目录为空，`cargo build` 会在 `tauri::generate_context!` 处因缺图标编译失败。
