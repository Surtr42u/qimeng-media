//! libmpv 运行时加载 FFI（ADR-0036 决策 3）。
//!
//! 为什么自写而不是 libmpv-rs/libloading：见 ADR-0036 决策 3 自研四问。
//! 加载失败不缓存——用户跑完 setup-mpv.ps1 后无需重启即可生效。
//!
//! ABI 依据：libmpv/client.h（mpv 承诺 libmpv-2 ABI 冻结、枚举值永不重编号）。
//! ✅ 已终验（2026-10-06，mpv v0.38.0 client.h 逐值核对；libmpv-2 起冻结不变，
//! 终验值由单测锁定）：MPV_FORMAT_*=NONE0/STRING1/OSD2/FLAG3/INT64 4/DOUBLE5；
//! MPV_EVENT_*=NONE0/**SHUTDOWN1**/LOG_MESSAGE2/…/PLAYBACK_RESTART21/
//! PROPERTY_CHANGE22/QUEUE_OVERFLOW24/HOOK25；`struct mpv_event` 字段序=
//! **event_id(int)/error(int)/reply_userdata(uint64)/data(void\*)**（曾错写为
//! reply_userdata 前置且 u32——data 指针错位读成 userdata=0，状态链全瞎，
//! 终验修正）；`mpv_event_property`={name,format,data} ✓。

use std::ffi::{c_char, c_int, c_void};

/// libmpv-2.dll 的 UTF-16 字面量（LoadLibraryW 入参，含结尾 NUL）。
/// 手写数组避免运行时转换；单测锁定编码。
pub(crate) const DLL_NAME_WIDE: &[u16] = &[
    0x006C, 0x0069, 0x0062, 0x006D, 0x0070, 0x0076, 0x002D, 0x0032, 0x002E, 0x0064, 0x006C, 0x006C,
    0x0000,
];

// —— mpv_format（client.h：值即 ABI，永不重编号；2026-10-06 v0.38.0 终验）——
pub(crate) const MPV_FORMAT_FLAG: c_int = 3;
pub(crate) const MPV_FORMAT_INT64: c_int = 4;
pub(crate) const MPV_FORMAT_DOUBLE: c_int = 5;

// —— mpv_event_id（同上）——
pub(crate) const MPV_EVENT_NONE: c_int = 0;
pub(crate) const MPV_EVENT_SHUTDOWN: c_int = 1;
pub(crate) const MPV_EVENT_LOG_MESSAGE: c_int = 2;
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
    /// 请求日志消息（LOG_MESSAGE 事件）。诊断用：mpv 的加载失败/解码失败/窗口创建
    /// 失败全部只从这里出来——不订阅它，故障就是完全静默的（2026-10-06 走查实况）。
    pub request_log_messages: unsafe extern "C" fn(MpvHandle, *const c_char) -> c_int,
    /// 读属性为字符串（client.h：返回值须 mpv_free 释放，失败返回 NULL）。
    ///
    /// 为什么用字符串档而不是 observe + mpv_node：控制条要的 `track-list` /
    /// `video-params` 都是 node 属性，按 STRING 读会拿到 **JSON 文本**（client.h
    /// 明文 "If the format is MPV_FORMAT_STRING, ... NODE is converted to JSON"），
    /// 于是 FFI 面零新增结构（mpv_node/mpv_node_list 一个都不用碰），
    /// 与 ADR-0036 决策 3「client API 面 ≤15」的自研四问口径一致。
    pub get_property_string: unsafe extern "C" fn(MpvHandle, *const c_char) -> *mut c_char,
    /// 释放 mpv 返回的堆串（client.h：所有 mpv_* 返回的 char* 都归它）。
    pub free: unsafe extern "C" fn(*mut c_void),
}

/// client.h 的 mpv_event（2026-10-06 终验字段序：event_id/error/reply_userdata/
/// data；曾错写为 reply_userdata 前置且 u32——data 错位致状态链全瞎，见头注）。
#[repr(C)]
pub(crate) struct MpvEvent {
    pub event_id: c_int,
    pub error: c_int,
    pub reply_userdata: u64,
    pub data: *mut c_void,
}

/// client.h 的 mpv_event_property（PROPERTY_CHANGE 事件的 data 指向它）。
#[repr(C)]
pub(crate) struct MpvEventProperty {
    pub name: *const c_char,
    pub format: c_int,
    pub data: *mut c_void,
}

/// client.h 的 mpv_event_log_message（LOG_MESSAGE 事件的 data 指向它）：
/// `{const char *prefix; const char *level; const char *text; mpv_log_level log_level;}`
/// 三个串的生命周期只到下一次 mpv_wait_event —— 必须在事件处理内取用，不得留存。
#[repr(C)]
pub(crate) struct MpvEventLogMessage {
    pub prefix: *const c_char,
    pub level: *const c_char,
    pub text: *const c_char,
    pub log_level: c_int,
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
                request_log_messages: Self::sym(module, b"mpv_request_log_messages\0")?,
                get_property_string: Self::sym(module, b"mpv_get_property_string\0")?,
                free: Self::sym(module, b"mpv_free\0")?,
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

    /// ABI 冻结值锁定（2026-10-06 对 mpv v0.38.0 client.h 逐值终验；libmpv-2 起
    /// 永不重编号——mpv 手改这些值前本测试必须先改，防的是本地手抄漂移）。
    #[test]
    fn abi_frozen_values_match_client_h() {
        assert_eq!(MPV_FORMAT_FLAG, 3);
        assert_eq!(MPV_FORMAT_INT64, 4);
        assert_eq!(MPV_FORMAT_DOUBLE, 5);
        assert_eq!(MPV_EVENT_NONE, 0);
        assert_eq!(MPV_EVENT_SHUTDOWN, 1);
        assert_eq!(MPV_EVENT_LOG_MESSAGE, 2);
        assert_eq!(MPV_EVENT_PROPERTY_CHANGE, 22);
        assert_eq!(MPV_ERROR_SUCCESS, 0);
        // mpv_event 字段序（client.h：event_id/error/reply_userdata(u64)/data）：
        // x64 布局 data 必须落在偏移 16（错位即状态链全瞎，见头注）
        assert_eq!(
            std::mem::offset_of!(MpvEvent, data),
            16,
            "mpv_event.data 偏移漂移——对 client.h 重新终验",
        );
        assert_eq!(std::mem::size_of::<MpvEvent>(), 24);
        // mpv_event_log_message：三个指针 + enum(int)；x64 下 text 落在偏移 16
        assert_eq!(std::mem::offset_of!(MpvEventLogMessage, text), 16);
        assert_eq!(std::mem::offset_of!(MpvEventLogMessage, log_level), 24);
        assert_eq!(std::mem::size_of::<MpvEventLogMessage>(), 32);
    }
}
