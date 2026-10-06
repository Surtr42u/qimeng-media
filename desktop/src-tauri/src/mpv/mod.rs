//! mpv 原生播放内核（ADR-0036）。
//!
//! 模块边界：`ffi` 只做 DLL 加载与符号解析；`win32` 只做播放窗与全屏/几何
//! Win32 面；`player` 只做会话线程（窗口+mpv 同线程）；`commands` 是 web
//! invoke 的唯一门面。
//! 状态（2026-10-06）：内核已并入主分支、暂无触发入口（C4.5 透明控制层随
//! 闪退排查撤回，胶囊入口删除）；触发方式与后续插件计划见 `desktop/PLUGIN_PLAN.md`。

pub(crate) mod ffi;
pub(crate) mod player;
pub mod commands;
pub(crate) mod win32;
