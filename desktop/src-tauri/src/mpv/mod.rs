//! mpv 原生播放内核（ADR-0036）。
//!
//! 模块边界：`ffi` 只做 DLL 加载与符号解析；`win32` 只做播放窗；`player` 只做
//! 会话线程（窗口+mpv 同线程）；`commands` 是 web invoke 的唯一门面。
//! 本模块不依赖 Tauri 窗口系统（播放窗为 user32 自建，见 win32.rs 头注）。

pub(crate) mod ffi;
pub(crate) mod player;
pub mod commands;
pub(crate) mod win32;
