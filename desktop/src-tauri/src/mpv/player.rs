//! mpv 播放会话：一线程三合一（Win32 消息泵 + mpv 事件循环 + 命令处理）。
//!
//! 线程纪律（MPV_MIGRATION 坑 4）：全部 mpv_* 与窗口调用收敛在本线程；
//! 跨线程只经 mpsc 命令与 Arc<Mutex> 状态缓存，绝不从外部直接调 mpv。

use std::ffi::{c_char, c_int, c_void, CStr, CString};
use std::sync::{mpsc, Arc, Mutex};
use std::time::Duration;

use serde::Serialize;

use super::ffi::{
    MpvEventProperty, MpvLib, MPV_ERROR_SUCCESS, MPV_EVENT_NONE, MPV_EVENT_PROPERTY_CHANGE,
    MPV_EVENT_SHUTDOWN, MPV_FORMAT_DOUBLE, MPV_FORMAT_FLAG, MPV_FORMAT_INT64,
};
use super::win32;

/// 播放窗初始尺寸（产品口径：1280×720，可缩放无下限）
const PLAYER_WINDOW_WIDTH: i32 = 1280;
const PLAYER_WINDOW_HEIGHT: i32 = 720;
/// 命令通道空转等待：兼作消息泵节拍（UI 响应 <15ms 足够，且避免满速空转烧 CPU）
const CMD_POLL: Duration = Duration::from_millis(15);
/// 退出时等待 SHUTDOWN 的事件超时（wait_event 阻塞参数，秒）
const SHUTDOWN_WAIT: f64 = 1.0;
/// quit 后 SHUTDOWN 至多等几拍（防 mpv 卡死拖住线程）
const SHUTDOWN_MAX_POLLS: u32 = 5;

// —— mpv 选项/属性名单一来源（observe 与 update_status 双处引用，防手抄漂移）——
const OPT_WID: &CStr = c"wid";
const OPT_IDLE: &CStr = c"idle";
const OPT_KEEP_OPEN: &CStr = c"keep-open";
const OPT_HWDEC: &CStr = c"hwdec";
const OPT_INPUT_BINDINGS: &CStr = c"input-default-bindings";
const OPT_OSD_PLAYING_MSG: &CStr = c"osd-playing-msg";
const PROP_TIME_POS: &CStr = c"time-pos";
const PROP_DURATION: &CStr = c"duration";
const PROP_PAUSE: &CStr = c"pause";
const PROP_EOF: &CStr = c"eof-reached";
const PROP_START: &CStr = c"start";
const PROP_SPEED: &CStr = c"speed";

/// 播放状态快照（web 轮询消费；序列化字段名即 invoke 返回的 JSON 键）。
#[derive(Clone, Debug, Serialize)]
pub struct MpvStatus {
    /// 当前播放位置（秒；mpv time-pos）
    pub position: f64,
    /// 总时长（秒；mpv duration，未知=0）
    pub duration: f64,
    /// 是否暂停（mpv pause）
    pub paused: bool,
    /// 是否播完（mpv eof-reached；keep-open 下停帧不清屏）
    pub eof: bool,
}

/// 会话内状态缓存：仅播放线程写（PROPERTY_CHANGE 驱动），命令线程读。
#[derive(Clone, Copy, Default)]
struct StatusInner {
    position: f64,
    duration: f64,
    paused: bool,
    eof: bool,
}

/// 播放命令（commands.rs → 线程）。
#[derive(Debug)]
pub(crate) enum Cmd {
    /// 加载/替换播放文件（标题同步改窗口标题；start=起点秒，≤0 视为从头）
    Load { url: String, title: String, start: f64 },
    /// 显式设置暂停态
    Pause(bool),
    /// 翻转暂停态
    TogglePause,
    /// 绝对定位（秒）
    Seek(f64),
    /// 倍速
    Speed(f64),
    /// 关闭会话（窗口+mpv 一并清干净）
    Quit,
}

/// web 轮询的控制动作字面量（与 video-player.tsx 侧调用对齐，双写须同步）。
pub(crate) fn parse_action(action: &str, value: f64) -> Option<Cmd> {
    match action {
        "pause" => Some(Cmd::Pause(true)),
        "resume" => Some(Cmd::Pause(false)),
        "toggle-pause" => Some(Cmd::TogglePause),
        "seek" => Some(Cmd::Seek(value)),
        "speed" => Some(Cmd::Speed(value)),
        _ => None,
    }
}

/// 起点秒 → mpv start 选项串；非法值（≤0/NaN）返回 None=从头播。
fn format_start(start: f64) -> Option<String> {
    if start.is_finite() && start > 0.0 {
        Some(format!("{start}"))
    } else {
        None
    }
}

/// CString 构造（URL/标题/数字不含 NUL；防御性替换保证不 panic）。
fn cstring(s: &str) -> CString {
    CString::new(s.replace('\0', "\u{FFFD}")).expect("NUL 已剔除")
}

/// 会话句柄（MpvState 持有；Option 表示"有无播放会话"）。
pub(crate) struct MpvSession {
    tx: mpsc::Sender<Cmd>,
    status: Arc<Mutex<Option<StatusInner>>>,
}

impl MpvSession {
    pub(crate) fn spawn(initial: Cmd) -> Result<MpvSession, String> {
        let lib = MpvLib::load()?;
        let (tx, rx) = mpsc::channel::<Cmd>();
        let status = Arc::new(Mutex::new(None::<StatusInner>));
        let status_for_thread = Arc::clone(&status);
        // 窗口/窗口类/消息泵都在本线程——窗与 mpv 同生命周期（见 win32.rs 头注）
        std::thread::Builder::new()
            .name("qimeng-mpv".into())
            .spawn(move || run(lib, rx, status_for_thread, initial))
            .map_err(|e| format!("播放线程启动失败：{e}"))?;
        Ok(MpvSession { tx, status })
    }

    pub(crate) fn send(&self, cmd: Cmd) -> Result<(), String> {
        self.tx.send(cmd).map_err(|_| "播放会话已退出".to_string())
    }

    /// 轮询快照：None=无活动播放（窗口已关/尚未起播/会话已死），web 侧据此复位。
    pub(crate) fn status(&self) -> Option<MpvStatus> {
        let inner = self.status.lock().ok()?;
        inner.map(|s| MpvStatus {
            position: s.position,
            duration: s.duration,
            paused: s.paused,
            eof: s.eof,
        })
    }
}

/// 播放线程主体：建窗 → 初始化 mpv（wid 嵌入）→ 循环（命令/消息/事件）→ 统一清理。
fn run(lib: &'static MpvLib, rx: mpsc::Receiver<Cmd>, status: Arc<Mutex<Option<StatusInner>>>, initial: Cmd) {
    // 首命令必须是 Load（mpv_open 保证）；其余命令在窗口就绪前被线程排队消费
    let first_load = match initial {
        Cmd::Load { url, title, start } => Some((url, title, start)),
        Cmd::Quit => None,
        other => {
            eprintln!("[qimeng-mpv] 首命令非 Load/Quit，忽略：{other:?}");
            None
        }
    };
    let (url, title, start) = match first_load {
        Some(v) => v,
        None => return, // 纯 Quit 开场：无窗口无句柄，直接结束
    };

    let hwnd = match win32::create_player_window(&title, PLAYER_WINDOW_WIDTH, PLAYER_WINDOW_HEIGHT) {
        Ok(h) => h,
        Err(e) => {
            eprintln!("[qimeng-mpv] {e}");
            return;
        }
    };

    // SAFETY: create 返回新句柄；play_loop 与本函数全程持有，尾处 terminate_destroy
    let handle = unsafe { (lib.create)() };
    if handle.is_null() {
        eprintln!("[qimeng-mpv] mpv_create 失败");
        win32::destroy_window(hwnd);
        return;
    }

    if let Err(e) = play_loop(lib, handle, hwnd, &rx, &status, (url, title, start)) {
        eprintln!("[qimeng-mpv] 播放循环异常退出：{e}");
    }
    // —— 清理顺序：先关窗（mpv 的 wid 子窗随父销毁，画面即刻消失），再释放 mpv ——
    win32::destroy_window(hwnd);
    // SAFETY: 本线程是句柄创建者；此刻播放循环已退出，无并发 mpv 调用
    unsafe { (lib.terminate_destroy)(handle) };
    *status.lock().expect("status 锁中毒") = None;
}

fn play_loop(
    lib: &'static MpvLib,
    handle: *mut c_void,
    hwnd: win32::Hwnd,
    rx: &mpsc::Receiver<Cmd>,
    status: &Arc<Mutex<Option<StatusInner>>>,
    first: (String, String, f64),
) -> Result<(), String> {
    unsafe {
        // wid 必须在 initialize 前设置（client.h：嵌入窗在核心初始化时接管）
        let wid: i64 = hwnd as isize as i64;
        check(lib, (lib.set_option)(handle, OPT_WID.as_ptr(), MPV_FORMAT_INT64, &wid as *const i64 as *mut c_void))?;
        // idle=yes：无文件时核心不退出（会话可连续换片）；keep-open=yes：播完停帧不清屏
        check(lib, (lib.set_option_string)(handle, OPT_IDLE.as_ptr(), c"yes".as_ptr()))?;
        check(lib, (lib.set_option_string)(handle, OPT_KEEP_OPEN.as_ptr(), c"yes".as_ptr()))?;
        check(lib, (lib.set_option_string)(handle, OPT_HWDEC.as_ptr(), c"auto".as_ptr()))?;
        // 播放窗聚焦时保留基础键位（空格/方向键），OSC 管鼠标交互
        check(lib, (lib.set_option_string)(handle, OPT_INPUT_BINDINGS.as_ptr(), c"yes".as_ptr()))?;
        // 起播 OSD 显示片名（${media-title} 由 mpv 展开）
        check(lib, (lib.set_option_string)(handle, OPT_OSD_PLAYING_MSG.as_ptr(), c"${media-title}".as_ptr()))?;

        for (idx, name, fmt) in [
            (0u64, PROP_TIME_POS, MPV_FORMAT_DOUBLE),
            (1u64, PROP_DURATION, MPV_FORMAT_DOUBLE),
            (2u64, PROP_PAUSE, MPV_FORMAT_FLAG),
            (3u64, PROP_EOF, MPV_FORMAT_FLAG),
        ] {
            check(lib, (lib.observe_property)(handle, idx, name.as_ptr(), fmt))?;
        }

        check(lib, (lib.initialize)(handle))?;
    }

    let (url, title, start) = first;
    apply(lib, handle, hwnd, &url, &title, start)?;

    loop {
        // ① 命令（15ms 节拍）
        match rx.recv_timeout(CMD_POLL) {
            Ok(Cmd::Quit) => break,
            Ok(Cmd::Load { url, title, start }) => apply(lib, handle, hwnd, &url, &title, start)?,
            Ok(Cmd::Pause(p)) => set_flag(lib, handle, PROP_PAUSE, p)?,
            Ok(Cmd::TogglePause) => {
                let paused = status
                    .lock()
                    .map(|s| s.map(|v| v.paused).unwrap_or(false))
                    .unwrap_or(false);
                set_flag(lib, handle, PROP_PAUSE, !paused)?;
            }
            Ok(Cmd::Seek(t)) => {
                let target = if t.is_finite() && t >= 0.0 { t } else { 0.0 };
                let target_c = cstring(&format!("{target}"));
                command(lib, handle, &[c"seek".as_ptr(), target_c.as_ptr(), c"absolute".as_ptr(), c"exact".as_ptr()])?;
            }
            Ok(Cmd::Speed(v)) => {
                let v = if v.is_finite() && v > 0.0 { v } else { 1.0 };
                // SAFETY: DOUBLE 格式数据是 f64
                unsafe {
                    check(lib, (lib.set_property)(handle, PROP_SPEED.as_ptr(), MPV_FORMAT_DOUBLE, &v as *const f64 as *mut c_void))?;
                }
            }
            Err(mpsc::RecvTimeoutError::Timeout) => {}
            Err(mpsc::RecvTimeoutError::Disconnected) => break, // 会话句柄被丢弃=退出
        }

        // ② 窗口消息（WM_QUIT=用户点关闭）
        if win32::pump_messages() {
            break;
        }

        // ③ mpv 事件抽干（wait_event 单线程纪律：只在本线程）
        loop {
            // SAFETY: 本线程是 wait_event 的唯一消费线程（client.h 线程模型）
            let ev = unsafe { (lib.wait_event)(handle, 0.0) };
            if ev.is_null() {
                break;
            }
            // SAFETY: wait_event 返回指向内部事件的有效指针（下次调用前有效）
            let event = unsafe { &*ev };
            if event.event_id == MPV_EVENT_NONE {
                break;
            }
            match event.event_id {
                MPV_EVENT_PROPERTY_CHANGE => {
                    // SAFETY: PROPERTY_CHANGE 的 data 是 mpv_event_property
                    if let Some(prop) = unsafe { (event.data as *const MpvEventProperty).as_ref() } {
                        update_status(status, prop);
                    }
                }
                MPV_EVENT_SHUTDOWN => return Ok(()),
                _ => {} // 未知/未消费事件按 ABI 演进防御性忽略
            }
        }
    }

    // 优雅收尾：命令 quit → 等 SHUTDOWN（窗口可能已被用户销毁，命令失败不致命）
    if command(lib, handle, &[c"quit".as_ptr()]).is_ok() {
        // SAFETY: 同事件循环，单线程消费
        unsafe {
            for _ in 0..SHUTDOWN_MAX_POLLS {
                let ev = (lib.wait_event)(handle, SHUTDOWN_WAIT);
                if let Some(e) = ev.as_ref() {
                    if e.event_id == MPV_EVENT_SHUTDOWN {
                        break;
                    }
                }
            }
        }
    }
    Ok(())
}

/// 加载/换片：start 为 file-local 选项（对下一次 loadfile 生效），随后 replace 换源。
fn apply(lib: &'static MpvLib, handle: *mut c_void, hwnd: win32::Hwnd, url: &str, title: &str, start: f64) -> Result<(), String> {
    win32::set_window_title(hwnd, title);
    match format_start(start) {
        Some(s) => {
            let cs = cstring(&s);
            // SAFETY: STRING 值经 set_property_string 直传（无需 char** 包一层）
            unsafe {
                check(lib, (lib.set_property_string)(handle, PROP_START.as_ptr(), cs.as_ptr()))?;
            }
        }
        None => {
            // start 是 file-local：本片从头播必须显式清零，防上次换片的起点残留
            unsafe {
                check(lib, (lib.set_property_string)(handle, PROP_START.as_ptr(), c"0".as_ptr()))?;
            }
        }
    }
    set_flag(lib, handle, PROP_PAUSE, false)?;
    let url_c = cstring(url);
    // flags=replace：换片不断会话（窗口/句柄复用）
    command(lib, handle, &[c"loadfile".as_ptr(), url_c.as_ptr(), c"replace".as_ptr(), std::ptr::null()])
}

fn set_flag(lib: &'static MpvLib, handle: *mut c_void, name: &CStr, v: bool) -> Result<(), String> {
    let raw: i32 = i32::from(v);
    // SAFETY: FLAG 格式数据是 int
    unsafe { check(lib, (lib.set_property)(handle, name.as_ptr(), MPV_FORMAT_FLAG, &raw as *const i32 as *mut c_void)) }
}

/// 字符串数组命令（指针数组以 NUL 指针结尾；CStr 生命周期覆盖调用点作用域）。
fn command(lib: &'static MpvLib, handle: *mut c_void, args: &[*const c_char]) -> Result<(), String> {
    let mut ptrs = args.to_vec();
    ptrs.push(std::ptr::null());
    // SAFETY: ptrs 以 NUL 指针结尾，mpv_command 只读
    unsafe { check(lib, (lib.command)(handle, ptrs.as_mut_ptr())) }
}

fn check(lib: &'static MpvLib, code: c_int) -> Result<(), String> {
    if code == MPV_ERROR_SUCCESS {
        Ok(())
    } else {
        Err(lib.err_text(code))
    }
}

/// PROPERTY_CHANGE → 状态缓存（仅播放线程写；锁中毒 panic 即暴露竞态）。
fn update_status(status: &Mutex<Option<StatusInner>>, prop: &MpvEventProperty) {
    // SAFETY: name 来自 mpv 内部静态/事件存储串，事件处理期内有效
    let name = unsafe { CStr::from_ptr(prop.name).to_string_lossy() };
    let mut guard = status.lock().expect("status 锁中毒");
    let slot = guard.get_or_insert_with(StatusInner::default);
    match (name.as_ref(), prop.format) {
        ("time-pos", MPV_FORMAT_DOUBLE) => unsafe {
            if let Some(v) = (prop.data as *const f64).as_ref() {
                slot.position = *v;
            }
        },
        ("duration", MPV_FORMAT_DOUBLE) => unsafe {
            if let Some(v) = (prop.data as *const f64).as_ref() {
                slot.duration = *v;
            }
        },
        ("pause", MPV_FORMAT_FLAG) => unsafe {
            if let Some(v) = (prop.data as *const i32).as_ref() {
                slot.paused = *v != 0;
            }
        },
        ("eof-reached", MPV_FORMAT_FLAG) => unsafe {
            if let Some(v) = (prop.data as *const i32).as_ref() {
                slot.eof = *v != 0;
            }
        },
        _ => {}
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_action_maps_known_actions() {
        assert!(matches!(parse_action("pause", 0.0), Some(Cmd::Pause(true))));
        assert!(matches!(parse_action("resume", 0.0), Some(Cmd::Pause(false))));
        assert!(matches!(parse_action("toggle-pause", 0.0), Some(Cmd::TogglePause)));
        assert!(matches!(parse_action("seek", 12.5), Some(Cmd::Seek(v)) if v == 12.5));
        assert!(matches!(parse_action("speed", 2.0), Some(Cmd::Speed(v)) if v == 2.0));
        assert!(parse_action("unknown", 0.0).is_none());
    }

    #[test]
    fn format_start_rejects_non_positive_and_nan() {
        assert_eq!(format_start(0.0), None);
        assert_eq!(format_start(-3.0), None);
        assert_eq!(format_start(f64::NAN), None);
        assert_eq!(format_start(12.5), Some("12.5".to_string()));
    }
}
