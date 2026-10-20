# glib 安全补丁快照

Tauri 2 的 GTK3 链要求 glib 0.18，不能直接替换为 0.20。根据用户 2026-10-20 的修复要求，保留现有 ABI/API，回移植 [上游 #1343](https://github.com/gtk-rs/gtk-rs-core/pull/1343) 修复 GHSA-wrw7-89jp-8q8g / RUSTSEC-2024-0429；决策及退役条件见 [ADR-0047](../../docs/adr/0047-glib安全补丁回移植.md)。

- 来源：crates.io 官方 glib-0.18.5.crate，归档 SHA-256 为 `233daaf6e83ae6a12a52055f568f9d7cf4671dabb78ff9560ab6da230ce00ee5`，与原 Cargo.lock 一致。
- 原始归档的 121 个文件完整保留，包括 MIT LICENSE/COPYRIGHT；唯一源码变化为 `src/variant_iter.rs` 中 `let p` → `let mut p`、`&p` → `&mut p`，与上游合并提交 `05dff0ee696f9bcd8617cd48c4b812d046d440cb` 的修复相同。
- `glib-source.json` 记录来源及全部快照文件哈希。新增的 `glib/Cargo.lock` 仅锁定上游测试依赖，不属于原始归档；没有伪装成 glib 0.20 或改写版本。
- 桌面根 Cargo.toml 通过 `[patch.crates-io]` 使用该本地源码。运行 `python tools/check_glib_patch.py` 验证快照与实际依赖来源，防止锁文件回退到有缺陷的注册表包。
- Linux CI 先在隔离 checkout 中还原原始两行并确认 release 迭代器测试发生运行时失败，再恢复修复版运行相同测试；不把网络或编译失败当成漏洞复现。Windows 桌面门禁保持不变，两份锁文件均参与 Cargo 审计。

这是对现有依赖的最小上游补丁，不继续扩展该快照。上游提供兼容修复，或 Tauri 稳定版本能使用修复后的 GLib/GTK 链时，评估后删除整个快照、Cargo patch 和专属回归门禁。不能用忽略/关闭告警代替源码修复。
