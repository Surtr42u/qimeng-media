//! 原生播放窗：user32 FFI 自建 Win32 窗口（ADR-0036 决策 2 的实现选型）。
//!
//! 为什么不经 Tauri 窗口系统：tauri 2 的纯窗口 WindowBuilder 挂在 `unstable`
//! feature 后，且 hwnd() 取句柄再嵌 mpv 仍隔一层窗口管理；直接 Win32 自建让
//! 「窗口线程 = mpv 线程」三合一（消息泵 + 事件循环 + 命令处理），窗口生命周期
//! 与 mpv 生命周期天然同线程串行，规避跨线程窗口操作的全部时序问题。
//! Win32 ABI 二十余年冻结面，与本仓手写 mpv FFI 同一策略（零新增依赖）。

use std::ffi::{c_int, c_void};

pub(crate) type Hwnd = *mut c_void;
pub(crate) type Lresult = isize;
pub(crate) type Wparam = usize;
pub(crate) type Lparam = isize;

// Win32 消息（winuser.h，冻结值）
const WM_DESTROY: u32 = 0x0002;
const WM_CLOSE: u32 = 0x0010;
const WM_QUIT: u32 = 0x0012;
const PM_REMOVE: u32 = 1;
// WS_OVERLAPPEDWINDOW = 常规可缩放窗口（含标题栏/最大最小化/粗边框）
const WS_OVERLAPPEDWINDOW: u32 = 0x00CF_0000;
const WS_VISIBLE: u32 = 0x1000_0000;
const CW_USEDEFAULT: i32 = 0x8000_0000u32 as i32;
const SW_SHOW: i32 = 5;
const IDC_ARROW: *const c_void = 32512 as *const c_void; // MAKEINTRESOURCE 标准箭头光标
const BLACK_BRUSH_RGB: u32 = 0x0000_0000; // mpv 铺满前防花屏的黑底

/// 播放窗类名（注册一次）；UTF-16 字面量含 NUL，单测锁定。
pub(crate) const CLASS_NAME_WIDE: &[u16] = &[
    0x0051, 0x0069, 0x006D, 0x0065, 0x006E, 0x0067, 0x004D, 0x0070, 0x0076, 0x0048, 0x006F, 0x0073,
    0x0074, 0x0000,
];

#[repr(C)]
struct Point {
    x: i32,
    y: i32,
}

#[repr(C)]
struct Msg {
    hwnd: Hwnd,
    message: u32,
    wparam: Wparam,
    lparam: Lparam,
    time: u32,
    pt: Point,
}

#[repr(C)]
struct WndClassExW {
    cb_size: u32,
    style: u32,
    lpfn_wnd_proc: unsafe extern "system" fn(Hwnd, u32, Wparam, Lparam) -> Lresult,
    cb_cls_extra: i32,
    cb_wnd_extra: i32,
    h_instance: *mut c_void,
    h_icon: *mut c_void,
    h_cursor: *mut c_void,
    h_br_background: *mut c_void,
    lpsz_menu_name: *const u16,
    lpsz_class_name: *const u16,
    h_icon_sm: *mut c_void,
}

#[link(name = "kernel32")]
extern "system" {
    fn GetModuleHandleW(name: *const u16) -> *mut c_void;
}

#[link(name = "user32")]
extern "system" {
    fn RegisterClassExW(class: *const WndClassExW) -> u16;
    fn CreateWindowExW(
        ex_style: u32,
        class_name: *const u16,
        window_name: *const u16,
        style: u32,
        x: i32,
        y: i32,
        width: i32,
        height: i32,
        parent: Hwnd,
        menu: *mut c_void,
        instance: *mut c_void,
        param: *mut c_void,
    ) -> Hwnd;
    fn ShowWindow(hwnd: Hwnd, cmd: i32) -> i32;
    fn DestroyWindow(hwnd: Hwnd) -> i32;
    fn SetWindowTextW(hwnd: Hwnd, text: *const u16) -> i32;
    fn PeekMessageW(msg: *mut Msg, hwnd: Hwnd, min: u32, max: u32, remove: u32) -> i32;
    fn TranslateMessage(msg: *const Msg) -> i32;
    fn DispatchMessageW(msg: *const Msg) -> i32;
    fn PostQuitMessage(code: i32);
    fn DefWindowProcW(hwnd: Hwnd, msg: u32, wparam: Wparam, lparam: Lparam) -> Lresult;
    fn LoadCursorW(instance: *mut c_void, cursor: *const c_void) -> *mut c_void;
}

#[link(name = "gdi32")]
extern "system" {
    fn CreateSolidBrush(color: u32) -> *mut c_void;
}

/// 转 NUL 结尾 UTF-16（CreateWindowExW/SetWindowTextW 入参）。
pub(crate) fn to_wide_nul(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(std::iter::once(0)).collect()
}

/// WndProc：只认 WM_CLOSE（默认行为=DestroyWindow→WM_DESTROY→WM_QUIT），
/// 其余全交 DefWindowProc。窗口不承载任何业务状态（mpv 状态在线程栈上）。
unsafe extern "system" fn wnd_proc(hwnd: Hwnd, msg: u32, wparam: Wparam, lparam: Lparam) -> Lresult {
    match msg {
        WM_DESTROY => {
            unsafe { PostQuitMessage(0) };
            0
        }
        _ => unsafe { DefWindowProcW(hwnd, msg, wparam, lparam) },
    }
}

/// 注册窗口类（幂等：RegisterClassExW 对同名类返回 0 但 GetLastError=ERROR_CLASS_ALREADY_EXISTS，
/// 本进程只在一个线程建一次窗，不特判——注册失败直接报错暴露问题）。
pub(crate) fn register_class() -> Result<(), String> {
    // SAFETY: 结构体字段按 winuser.h 布局填满；类名/光标/画刷均为合法常量或句柄
    unsafe {
        let class = WndClassExW {
            cb_size: std::mem::size_of::<WndClassExW>() as u32,
            style: 0,
            lpfn_wnd_proc: wnd_proc,
            cb_cls_extra: 0,
            cb_wnd_extra: 0,
            h_instance: GetModuleHandleW(std::ptr::null()),
            h_icon: std::ptr::null_mut(),
            h_cursor: LoadCursorW(std::ptr::null_mut(), IDC_ARROW),
            h_br_background: CreateSolidBrush(BLACK_BRUSH_RGB),
            lpsz_menu_name: std::ptr::null(),
            lpsz_class_name: CLASS_NAME_WIDE.as_ptr(),
            h_icon_sm: std::ptr::null_mut(),
        };
        if RegisterClassExW(&class) == 0 {
            // 已注册（重开播放窗场景）不算失败；真失败会在 CreateWindowExW 暴露
            return Ok(());
        }
    }
    Ok(())
}

/// 创建播放窗（居中 1280×720，常规可缩放边框），返回 HWND 供 wid 嵌入。
pub(crate) fn create_player_window(title: &str, width: i32, height: i32) -> Result<Hwnd, String> {
    register_class()?;
    let title_w = to_wide_nul(title);
    // SAFETY: 类已注册（register_class），标题为 NUL 结尾 UTF-16；参数按 winuser.h 语义
    let hwnd = unsafe {
        CreateWindowExW(
            0,
            CLASS_NAME_WIDE.as_ptr(),
            title_w.as_ptr(),
            WS_OVERLAPPEDWINDOW | WS_VISIBLE,
            CW_USEDEFAULT,
            CW_USEDEFAULT,
            width,
            height,
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            GetModuleHandleW(std::ptr::null()),
            std::ptr::null_mut(),
        )
    };
    if hwnd.is_null() {
        return Err("创建播放窗口失败（CreateWindowExW）".into());
    }
    // SAFETY: hwnd 来自成功的 CreateWindowExW
    unsafe { ShowWindow(hwnd, SW_SHOW) };
    Ok(hwnd)
}

pub(crate) fn set_window_title(hwnd: Hwnd, title: &str) {
    let wide = to_wide_nul(title);
    // SAFETY: hwnd 为本线程创建的窗口；wide 含 NUL
    unsafe { SetWindowTextW(hwnd, wide.as_ptr()) };
}

pub(crate) fn destroy_window(hwnd: Hwnd) {
    // SAFETY: 仅窗口所属线程调用（player.rs 线程纪律）；已销毁时返回 0 忽略
    unsafe { DestroyWindow(hwnd) };
}

/// 消息泵抽干一次队列；返回 true 表示收到 WM_QUIT（窗口已关，应退出播放循环）。
pub(crate) fn pump_messages() -> bool {
    let mut msg = Msg {
        hwnd: std::ptr::null_mut(),
        message: 0,
        wparam: 0,
        lparam: 0,
        time: 0,
        pt: Point { x: 0, y: 0 },
    };
    // SAFETY: msg 为合法缓冲；hwnd=null 取本线程全部窗口消息；PM_REMOVE 取出即删
    unsafe {
        while PeekMessageW(&mut msg, std::ptr::null_mut(), 0, 0, PM_REMOVE) != 0 {
            if msg.message == WM_QUIT {
                return true;
            }
            TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
    }
    false
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn class_name_wide_is_qimengmpvhost_with_nul() {
        let s: String = CLASS_NAME_WIDE[..CLASS_NAME_WIDE.len() - 1]
            .iter()
            .map(|&u| char::from_u32(u as u32).unwrap())
            .collect();
        assert_eq!(s, "QimengMpvHost");
        assert_eq!(*CLASS_NAME_WIDE.last().unwrap(), 0);
    }

    #[test]
    fn to_wide_nul_appends_terminator() {
        assert_eq!(to_wide_nul("a"), vec![0x0061, 0]);
    }
}
