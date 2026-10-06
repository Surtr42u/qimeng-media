//! mpv 原生播放内核（ADR-0036）。
//!
//! 模块边界：`ffi` 只做 DLL 加载与符号解析；`win32` 只做播放窗与全屏/几何
//! Win32 面；`player` 只做会话线程（窗口+mpv 同线程）；`overlay` 只做透明
//! 控制层窗（C4.5 双层播放窗，Tauri 窗在主线程、几何经 WM_WINDOWPOSCHANGED
//! 事件同步）；`commands` 是 web/控制层 invoke 的唯一门面。

pub(crate) mod ffi;
pub(crate) mod overlay;
pub(crate) mod player;
pub mod commands;
pub(crate) mod win32;
