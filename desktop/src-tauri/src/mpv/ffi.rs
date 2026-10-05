//! libmpv 运行时加载 FFI（ADR-0036 决策 3）。
//!
//! 为什么自写而不是 libmpv-rs/libloading：见 ADR-0036 决策 3 自研四问。
//! 加载失败不缓存——用户跑完 setup-mpv.ps1 后无需重启即可生效。
//!
//! ABI 依据：libmpv/client.h（mpv 承诺 libmpv-2 ABI 冻结、枚举值永不重编号）。
//! 值来源为本会话官方 manual/头文件核对；`MPV_MIGRATION.md` 坑 1 是最终逐值
//! 核对责任项——发现不符只改本文件常量，不动调用面。

use std::ffi::{c_char, c_int, c_void};

/// libmpv-2.dll 的 UTF-16 字面量（LoadLibraryW 入参，含结尾 NUL）。
/// 手写数组避免运行时转换；单测锁定编码。
pub(crate) const DLL_NAME_WIDE: &[u16] = &[
    0x006C, 0x0069, 0x0062, 0x006D, 0x0070, 0x0076, 0x002D, 0x0032, 0x002E, 0x0064, 0x006C, 0x006C,
    0x0000,
];

// —— mpv_format（client.h：值即 ABI，永不重编号）——
pub(crate) const MPV_FORMAT_FLAG: c_int = 3;
pub(crate) const MPV_FORMAT_INT64: c_int = 4;
pub(crate) const MPV_FORMAT_DOUBLE: c_int = 5;

// —— mpv_event_id（同上）——
pub(crate) const MPV_EVENT_NONE: c_int = 0;
pub(crate) const MPV_EVENT_SHUTDOWN: c_int = 2;
pub(crate) const MPV_EVENT_PROPERTY_CHANGE: c_int = 22;
// 未知事件一律忽略（防御：将来 ABI 追加新事件不致误读）。

pub(crate) const MPV_ERROR_SUCCESS: c_int = 0;

#[link(name = "kernel32")]
extern "system" {
    fn LoadLibraryW(name: *const u16) -> *mut c_void;
    fn GetProcAddress(module: *mut c_void, name: *const c_char) -> *mut c_void;
}

/// client.h 的 mpv_handle（opaque，按指针搬运）。
pub(crate) type MpvHandle = *mut c_void;

/// 本项目用到的 client API 子集（≤15 函数，见 ADR-0036 决策 3②）。
/// 调用约定：client.h 的 MPV_EXPORT 在 Windows 上为 cdecl（x64 只有一种约定）。
pub(crate) struct MpvLib {
    pub create: unsafe extern "C" fn() -> MpvHandle,
    pub initialize: unsafe extern "C" fn(MpvHandle) -> c_int,
    pub terminate_destroy: unsafe extern "C" fn(MpvHandle),
    pub set_option: unsafe extern "C" fn(MpvHandle, *const c_char, c_int, *mut c_void) -> c_int,
    pub set_option_string:
        unsafe extern "C" fn(MpvHandle, *const c_char, *const c_char) -> c_int,
    pub set_property: unsafe extern "C" fn(MpvHandle, *const c_char, c_int, *mut c_void) -> c_int,
    pub set_property_string:
        unsafe extern "C" fn(MpvHandle, *const c_char, *const c_char) -> c_int,
    pub command: unsafe extern "C" fn(MpvHandle, *mut *const c_char) -> c_int,
    pub observe_property:
        unsafe extern "C" fn(MpvHandle, u64, *const c_char, c_int) -> c_int,
    pub wait_event: unsafe extern "C" fn(MpvHandle, f64) -> *mut MpvEvent,
    pub error_string: unsafe extern "C" fn(c_int) -> *const c_char,
}

/// client.h 的 mpv_event（x64 布局：enum/u32/指针/int，repr(C) 对齐）。
#[repr(C)]
pub(crate) struct MpvEvent {
    pub event_id: c_int,
    pub reply_userdata: u32,
    pub data: *mut c_void,
    pub error: c_int,
}

/// client.h 的 mpv_event_property（PROPERTY_CHANGE 事件的 data 指向它）。
#[repr(C)]
pub(crate) struct MpvEventProperty {
    pub name: *const c_char,
    pub format: c_int,
    pub data: *mut c_void,
}

impl MpvLib {
    /// 按需加载 libmpv-2.dll 并解析全部符号；任一缺失即整体失败（版本不齐宁可报错）。
    /// 成功结果进程级缓存；失败不缓存（装完 DLL 立即可用）。
    pub(crate) fn load() -> Result<&'static MpvLib, String> {
        static LOADED: std::sync::OnceLock<Result<MpvLib, ()>> = std::sync::OnceLock::new();
        // OnceLock.get() 本就返回 'static 引用（静态项），无需任何指针搬运
        if let Some(Ok(lib)) = LOADED.get() {
            return Ok(lib);
        }
        match Self::load_uncached() {
            Ok(lib) => {
                let _ = LOADED.set(Ok(lib));
                Ok(LOADED.get().unwrap().as_ref().unwrap())
            }
            Err(msg) => Err(msg),
        }
    }

    fn load_uncached() -> Result<MpvLib, String> {
        // SAFETY: 传入 NUL 结尾的宽字面量；kernel32 标准调用
        let module = unsafe { LoadLibraryW(DLL_NAME_WIDE.as_ptr()) };
        if module.is_null() {
            return Err(
                "未找到 libmpv-2.dll。请在 desktop/src-tauri 下运行：powershell -ExecutionPolicy Bypass -File setup-mpv.ps1"
                    .to_string(),
            );
        }
        // SAFETY: module 来自成功的 LoadLibraryW；符号名均为 client.h 导出名
        unsafe {
            Ok(MpvLib {
                create: Self::sym(module, b"mpv_create\0")?,
                initialize: Self::sym(module, b"mpv_initialize\0")?,
                terminate_destroy: Self::sym(module, b"mpv_terminate_destroy\0")?,
                set_option: Self::sym(module, b"mpv_set_option\0")?,
                set_option_string: Self::sym(module, b"mpv_set_option_string\0")?,
                set_property: Self::sym(module, b"mpv_set_property\0")?,
                set_property_string: Self::sym(module, b"mpv_set_property_string\0")?,
                command: Self::sym(module, b"mpv_command\0")?,
                observe_property: Self::sym(module, b"mpv_observe_property\0")?,
                wait_event: Self::sym(module, b"mpv_wait_event\0")?,
                error_string: Self::sym(module, b"mpv_error_string\0")?,
            })
        }
    }

    /// GetProcAddress 转函数指针；类型由调用点字段声明约束（transmute 只做指针→同签名函数）。
    /// SAFETY（调用方）：module 有效且 name 为 NUL 结尾符号名。
    unsafe fn sym<T>(module: *mut c_void, name: &[u8]) -> Result<T, String> {
        let ptr = unsafe { GetProcAddress(module, name.as_ptr() as *const c_char) };
        if ptr.is_null() {
            return Err(format!(
                "libmpv-2.dll 缺少导出符号 {}（DLL 版本过旧？）",
                String::from_utf8_lossy(&name[..name.len() - 1])
            ));
        }
        Ok(unsafe { std::mem::transmute_copy(&ptr) })
    }

    /// 错误码 → 文本（mpv_error_string 返回静态串，无需 free）。
    pub(crate) fn err_text(&self, code: c_int) -> String {
        if code == MPV_ERROR_SUCCESS {
            return "成功".into();
        }
        // SAFETY: error_string 返回静态 NUL 结尾串
        unsafe {
            let p = (self.error_string)(code);
            if p.is_null() {
                return format!("mpv 错误码 {code}");
            }
            std::ffi::CStr::from_ptr(p).to_string_lossy().into_owned()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dll_name_wide_is_libmpv2_dll_with_nul() {
        let s: String = DLL_NAME_WIDE[..DLL_NAME_WIDE.len() - 1]
            .iter()
            .map(|&u| char::from_u32(u as u32).unwrap())
            .collect();
        assert_eq!(s, "libmpv-2.dll");
        assert_eq!(*DLL_NAME_WIDE.last().unwrap(), 0);
    }
}
