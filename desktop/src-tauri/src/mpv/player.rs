//! mpv 播放会话：一线程三合一（Win32 消息泵 + mpv 事件循环 + 命令处理）。
//!
//! 线程纪律（MPV_MIGRATION 坑 4）：全部 mpv_* 与窗口调用收敛在本线程；
//! 跨线程只经 mpsc 命令与 Arc<Mutex> 状态缓存，绝不从外部直接调 mpv。

use std::ffi::{c_char, c_int, c_void, CStr, CString};
use std::sync::{mpsc, Arc, Mutex};
use std::time::Duration;

use serde::Serialize;

use crate::diag;
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
const PROP_VOLUME: &CStr = c"volume";
const PROP_MUTE: &CStr = c"mute";
/// mpv 音量属性上限（mpv max-volume 默认值，音量命令与钳制共用）
pub(crate) const MPV_VOLUME_MAX: f64 = 130.0;

/// 播放状态快照（web/控制层轮询消费；序列化字段名即 invoke 返回的 JSON 键）。
/// speed/volume/muted 为 C4.5 控制层新增观察项，web 侧 NativePollStatus 只声明
/// 既有四字段、多余字段忽略（结构化子集，向后兼容）。
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
    /// 倍速（mpv speed）
    pub speed: f64,
    /// 音量（mpv volume，0~130）
    pub volume: f64,
    /// 是否静音（mpv mute）
    pub muted: bool,
}

/// 会话内状态缓存：仅播放线程写（PROPERTY_CHANGE 驱动），命令线程读。
#[derive(Clone, Copy, Default)]
struct StatusInner {
    position: f64,
    duration: f64,
    paused: bool,
    eof: bool,
    speed: f64,
    volume: f64,
    muted: bool,
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
    /// 音量（0~MPV_VOLUME_MAX，外部超界值在线程侧钳制）
    Volume(f64),
    /// 静音开关
    Mute(bool),
    /// 播放窗全屏/还原（win32 toggle_fullscreen；mpv wid 模式下全屏由嵌入方负责）
    ToggleFullscreen,
    /// 内置播放窗摆位（物理像素，相对主窗客户区）+ 显隐
    StageRect { x: i32, y: i32, w: i32, h: i32, visible: bool },
    /// 关闭会话（窗口+mpv 一并清干净）
    Quit,
}

/// web/控制层轮询的控制动作字面量（与 video-player.tsx / player-controls.html
/// 侧调用对齐，双写须同步）。
pub(crate) fn parse_action(action: &str, value: f64) -> Option<Cmd> {
    match action {
        "pause" => Some(Cmd::Pause(true)),
        "resume" => Some(Cmd::Pause(false)),
        "toggle-pause" => Some(Cmd::TogglePause),
        "seek" => Some(Cmd::Seek(value)),
        "speed" => Some(Cmd::Speed(value)),
        "volume" => Some(Cmd::Volume(value)),
        "mute" => Some(Cmd::Mute(value != 0.0)),
        "toggle-fullscreen" => Some(Cmd::ToggleFullscreen),
        _ => None,
    }
}

/// 音量钳制：NaN 视为不可解释置 0；±∞/越界值收进 0..=MPV_VOLUME_MAX
/// （+∞=拉满比静音更符合操作直觉，clamp 对 ±∞ 语义天然正确）。
fn clamp_volume(v: f64) -> f64 {
    if v.is_nan() {
        return 0.0;
    }
    v.clamp(0.0, MPV_VOLUME_MAX)
}

/// 起点秒 → mpv start 选项串；非法值（≤0/NaN）返回 None=从头播。
fn format_start(start: f64) -> Option<String> {
    if start.is_finite() && start > 0.0 {
        Some(format!("{start}"))
    } else {
        None
    }
}

/// 舞台矩形 CSS px → 父窗客户区物理 px：缩放比 = 主窗客户区物理宽 / 页面视口
/// CSS 宽——html zoom 1.1 与 DPI 全部折进同一比例，两端无需各自感知换算口径。
/// 返回 (x, y, w, h)；doc_w/phys_w 非法（≤0）返回 None（本轮不上报）。
pub(crate) fn scale_stage_rect(
    x: f64,
    y: f64,
    w: f64,
    h: f64,
    doc_w: f64,
    phys_w: i32,
) -> Option<(i32, i32, i32, i32)> {
    if !(doc_w.is_finite() && doc_w > 0.0) || phys_w <= 0 {
        return None;
    }
    let k = phys_w as f64 / doc_w;
    if !k.is_finite() || k <= 0.0 {
        return None;
    }
    let r = |v: f64| -> i32 { if v.is_finite() { v.round() as i32 } else { 0 } };
    Some((r(x * k), r(y * k), r(w * k), r(h * k)))
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
    /// main_hwnd 以 isize 传值（裸指针非 Send；HWND 是进程级句柄值，跨线程
    /// 传值安全，Win32 调用全在目标线程展开）。
    pub(crate) fn spawn(initial: Cmd, main_hwnd: Option<isize>) -> Result<MpvSession, String> {
        let lib = MpvLib::load()?;
        let (tx, rx) = mpsc::channel::<Cmd>();
        let status = Arc::new(Mutex::new(None::<StatusInner>));
        let status_for_thread = Arc::clone(&status);
        // 窗口/窗口类/消息泵都在本线程——窗与 mpv 同生命周期（见 win32.rs 头注）
        std::thread::Builder::new()
            .name("qimeng-mpv".into())
            .spawn(move || run(lib, rx, status_for_thread, initial, main_hwnd))
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
            speed: s.speed,
            volume: s.volume,
            muted: s.muted,
        })
    }
}

/// 播放线程主体：建窗 → 初始化 mpv（wid 嵌入）→ 循环 → 统一清理。
fn run(
    lib: &'static MpvLib,
    rx: mpsc::Receiver<Cmd>,
    status: Arc<Mutex<Option<StatusInner>>>,
    initial: Cmd,
    main_hwnd: Option<isize>,
) {
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
    diag::trace("session_thread: player_window_creating");

    // 内置形态：有主窗句柄 → 主窗子窗（铺在舞台矩形，随 mpv_stage_rect 摆位显隐）；
    // 无句柄（异常路径兜底）→ 退回独立窗口（C2 形态，可关可拖）
    let hwnd = match main_hwnd.map(|v| v as *mut c_void) {
        Some(parent) => {
            match win32::create_player_window_child(parent, PLAYER_WINDOW_WIDTH, PLAYER_WINDOW_HEIGHT) {
                Ok(h) => h,
                Err(e) => {
                    diag::trace(&format!("session_thread: create_player_window_child FAILED {e}"));
                    eprintln!("[qimeng-mpv] {e}");
                    return;
                }
            }
        }
        None => {
            match win32::create_player_window(&title, PLAYER_WINDOW_WIDTH, PLAYER_WINDOW_HEIGHT) {
                Ok(h) => h,
                Err(e) => {
                    diag::trace(&format!("session_thread: create_player_window FAILED {e}"));
                    eprintln!("[qimeng-mpv] {e}");
                    return;
                }
            }
        }
    };

    // SAFETY: create 返回新句柄；play_loop 与本函数全程持有，尾处 terminate_destroy
    let handle = unsafe { (lib.create)() };
    if handle.is_null() {
        diag::trace("session_thread: mpv_create FAILED");
        eprintln!("[qimeng-mpv] mpv_create 失败");
        win32::reset_fullscreen_restore();
        win32::destroy_window(hwnd);
        return;
    }
    diag::trace("session_thread: mpv_created_entering_play_loop");

    if let Err(e) = play_loop(lib, handle, hwnd, &rx, &status, (url, title, start)) {
        diag::trace(&format!("session_thread: play_loop_err {e}"));
        eprintln!("[qimeng-mpv] 播放循环异常退出：{e}");
    }
    diag::trace("session_thread: cleanup_begin");
    // —— 清理顺序：先关窗（mpv 的 wid 子窗随父销毁，画面即刻消失），再释放 mpv ——
    // 全屏还原存根随会话清零，防跨会话残留把新会话首次全屏切换错走还原分支
    win32::destroy_window(hwnd);
    win32::reset_fullscreen_restore();
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
            (4u64, PROP_SPEED, MPV_FORMAT_DOUBLE),
            (5u64, PROP_VOLUME, MPV_FORMAT_DOUBLE),
            (6u64, PROP_MUTE, MPV_FORMAT_FLAG),
        ] {
            check(lib, (lib.observe_property)(handle, idx, name.as_ptr(), fmt))?;
        }

        check(lib, (lib.initialize)(handle))?;
    }
    diag::trace("session_thread: mpv_initialized");

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
            Ok(Cmd::Volume(v)) => {
                let v = clamp_volume(v);
                // SAFETY: DOUBLE 格式数据是 f64
                unsafe {
                    check(lib, (lib.set_property)(handle, PROP_VOLUME.as_ptr(), MPV_FORMAT_DOUBLE, &v as *const f64 as *mut c_void))?;
                }
            }
            Ok(Cmd::Mute(m)) => set_flag(lib, handle, PROP_MUTE, m)?,
            Ok(Cmd::ToggleFullscreen) => win32::toggle_fullscreen(hwnd),
            Ok(Cmd::StageRect { x, y, w, h, visible }) => win32::set_stage_rect(hwnd, x, y, w, h, visible),
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
        ("speed", MPV_FORMAT_DOUBLE) => unsafe {
            if let Some(v) = (prop.data as *const f64).as_ref() {
                slot.speed = *v;
            }
        },
        ("volume", MPV_FORMAT_DOUBLE) => unsafe {
            if let Some(v) = (prop.data as *const f64).as_ref() {
                slot.volume = *v;
            }
        },
        ("mute", MPV_FORMAT_FLAG) => unsafe {
            if let Some(v) = (prop.data as *const i32).as_ref() {
                slot.muted = *v != 0;
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
        assert!(matches!(parse_action("volume", 80.0), Some(Cmd::Volume(v)) if v == 80.0));
        assert!(matches!(parse_action("mute", 1.0), Some(Cmd::Mute(true))));
        assert!(matches!(parse_action("mute", 0.0), Some(Cmd::Mute(false))));
        assert!(matches!(parse_action("toggle-fullscreen", 0.0), Some(Cmd::ToggleFullscreen)));
        assert!(parse_action("unknown", 0.0).is_none());
    }

    #[test]
    fn format_start_rejects_non_positive_and_nan() {
        assert_eq!(format_start(0.0), None);
        assert_eq!(format_start(-3.0), None);
        assert_eq!(format_start(f64::NAN), None);
        assert_eq!(format_start(12.5), Some("12.5".to_string()));
    }

    #[test]
    fn scale_stage_rect_folds_zoom_and_dpi() {
        // 视口 CSS 宽 1000 → 客户区物理 1100（zoom 1.1 × DPI 1.0 的合成比）：
        // 任意矩形按同一比例折算，两端无需各自感知 zoom/DPI
        assert_eq!(scale_stage_rect(10.0, 20.0, 800.0, 450.0, 1000.0, 1100), Some((11, 22, 880, 495)));
        assert_eq!(scale_stage_rect(0.0, 0.0, 1000.0, 500.0, 1000.0, 2000), Some((0, 0, 2000, 1000)));
        assert_eq!(scale_stage_rect(0.0, 0.0, 100.0, 50.0, 0.0, 1000), None);
        assert_eq!(scale_stage_rect(0.0, 0.0, 100.0, 50.0, f64::NAN, 1000), None);
        assert_eq!(scale_stage_rect(0.0, 0.0, 100.0, 50.0, 1000.0, 0), None);
        assert_eq!(scale_stage_rect(f64::NAN, 0.0, 100.0, 50.0, 1000.0, 1000), Some((0, 0, 100, 50)));
    }

    #[test]
    fn clamp_volume_bounds_and_defense() {
        assert_eq!(clamp_volume(80.0), 80.0);
        assert_eq!(clamp_volume(0.0), 0.0);
        assert_eq!(clamp_volume(-1.0), 0.0);
        assert_eq!(clamp_volume(f64::NAN), 0.0);
        assert_eq!(clamp_volume(f64::INFINITY), MPV_VOLUME_MAX);
        assert_eq!(clamp_volume(999.0), MPV_VOLUME_MAX);
    }
}
