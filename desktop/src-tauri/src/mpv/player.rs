//! mpv 播放会话：一线程三合一（Win32 消息泵 + mpv 事件循环 + 命令处理）。
//!
//! 线程纪律（MPV_MIGRATION 坑 4）：全部 mpv_* 与窗口调用收敛在本线程；
//! 跨线程只经 mpsc 命令与 Arc<Mutex> 状态缓存，绝不从外部直接调 mpv。

use std::ffi::{c_char, c_int, c_void, CStr, CString};
use std::sync::{mpsc, Arc, Mutex};
use std::time::{Duration, Instant};

use serde::Serialize;

use crate::diag;
use super::ffi::{
    MpvEventLogMessage, MpvEventProperty, MpvLib, MPV_ERROR_SUCCESS, MPV_EVENT_LOG_MESSAGE,
    MPV_EVENT_NONE, MPV_EVENT_PROPERTY_CHANGE, MPV_EVENT_SHUTDOWN, MPV_FORMAT_DOUBLE,
    MPV_FORMAT_FLAG, MPV_FORMAT_INT64,
};
use super::win32;

/// 播放窗初始尺寸（产品口径：1280×720，可缩放无下限）
const PLAYER_WINDOW_WIDTH: i32 = 1280;
const PLAYER_WINDOW_HEIGHT: i32 = 720;
/// 命令通道空转等待：兼作消息泵节拍（UI 响应 <15ms 足够，且避免满速空转烧 CPU）
const CMD_POLL: Duration = Duration::from_millis(15);
/// 媒体信息探测节拍（500ms）：控制条的清晰度标签/字幕菜单/设置面板都吃这份快照。
/// 走 1Hz 以内的轮询而不是属性观察——`track-list`/`video-params` 是 node 属性，
/// 观察要引 mpv_node FFI；读字符串档拿 JSON 零新增结构（见 ffi.rs 该字段注释）。
/// 探测只在状态缓存已存在时写入（不抢先创建条目：首个非空快照=起播边沿，
/// 提前创建会把起播上报提前一拍，口径 B 的闸门语义就会变）。
const PROBE_INTERVAL: Duration = Duration::from_millis(500);
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
// 注：`osd-playing-msg` 已不用——起播片名改由自绘控制条显示（用户实机反馈
// mpv 的 OSD 闪字"太丑"，配合 osd-level=0 整条关掉）。用到再回加常量。
const OPT_CHAPTERS_FILE: &CStr = c"chapters-file";
const OPT_OSC: &CStr = c"osc";
const OPT_INPUT_CURSOR: &CStr = c"input-cursor";
const OPT_WINDOW_DRAGGING: &CStr = c"window-dragging";
const OPT_MUTE: &CStr = c"mute";
const OPT_VOLUME: &CStr = c"volume";
const OPT_OSD_LEVEL: &CStr = c"osd-level";
const PROP_TIME_POS: &CStr = c"time-pos";
const PROP_DURATION: &CStr = c"duration";
const PROP_PAUSE: &CStr = c"pause";
const PROP_EOF: &CStr = c"eof-reached";
const PROP_START: &CStr = c"start";
const PROP_SPEED: &CStr = c"speed";
const PROP_VOLUME: &CStr = c"volume";
const PROP_MUTE: &CStr = c"mute";
// —— 控制条扩展面（2026-10-06 用户走查：清晰度/倍速/字幕/设置四入口）——
const PROP_SID: &CStr = c"sid"; // 当前字幕轨（0/无 = 关；关闭要写 "no"）
const PROP_ASPECT: &CStr = c"video-aspect-override"; // 画面比例（"no"=默认）
const PROP_LOOP_FILE: &CStr = c"loop-file"; // 单片循环（"no"/"inf"）
const PROP_TRACK_LIST: &CStr = c"track-list"; // 轨道表（按 STRING 读=JSON 文本）
const PROP_VIDEO_PARAMS: &CStr = c"video-params"; // 视频参数（JSON：w/h/pixelformat…）
const PROP_VIDEO_FORMAT: &CStr = c"video-format"; // 视频编码（h264/hevc…）
const PROP_FPS: &CStr = c"container-fps"; // 容器帧率
// 注：`fullscreen` 属性观察 + 宿主窗全屏已整体拆除（2026-10-06 用户走查拍板）——
// 旧版壳内用的是 **fullscreenWeb（应用窗口内铺满）**，系统全屏在壳内本来就是关的
// （video-player.tsx 旧配置 `fullscreen: !isTauriShell`）。Shell 侧全屏 = 网页
// CSS 铺满，mpv 播放窗只是跟着舞台矩形变大，不需要任何窗口脱离/置顶操作。
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
    /// 媒体信息（清晰度标签/字幕菜单/设置面板的数据源）
    pub media: MediaProbe,
}

/// 一条字幕轨（`track-list` 里 type=sub 的条目；控制条字幕菜单一行一条）。
#[derive(Clone, Debug, Default, Serialize)]
pub struct TrackInfo {
    /// mpv 轨道 id（写 `sid` 用它；菜单里的选择值）
    pub id: i64,
    /// 轨道标题（mkv 的 title；缺省空串）
    pub title: String,
    /// 语言码（chi/eng…；缺省空串）
    pub lang: String,
    /// 是否外挂（mpv 的 external 标志）
    pub external: bool,
}

/// 媒体信息探测结果（PROBE_INTERVAL 一拍一次，写入状态缓存）。
///
/// 清晰度口径：**库内直通原件**（ADR-0002 + 用户拍板「查看永远发原件」，本项目
/// 明确不做实时转码），所以这里没有"档位"可切——控制条显示的是**原件真实分辨率**，
/// 菜单里只有一项「原画」，「1080P 高清」这类文案由真实高度推导（web 侧纯函数）。
#[derive(Clone, Debug, Default, Serialize)]
pub struct MediaProbe {
    /// 视频宽（video-params/w；未知=0）
    pub video_width: i64,
    /// 视频高（video-params/h；未知=0）
    pub video_height: i64,
    /// 视频编码（video-format；未知=空串）
    pub video_codec: String,
    /// 容器帧率（container-fps；未知=0）
    pub fps: f64,
    /// 画面比例覆盖值（video-aspect-override；"no"=默认）
    pub aspect: String,
    /// 单片循环（loop-file=inf）
    pub loop_file: bool,
    /// 当前字幕轨 id（0=关闭）
    pub sid: i64,
    /// 字幕轨清单（无字幕=空数组）
    pub tracks: Vec<TrackInfo>,
}

/// 会话内状态缓存：仅播放线程写（PROPERTY_CHANGE 驱动 + 探测拍），命令线程读。
#[derive(Clone, Default)]
struct StatusInner {
    position: f64,
    duration: f64,
    paused: bool,
    eof: bool,
    speed: f64,
    volume: f64,
    muted: bool,
    media: MediaProbe,
}

/// 播放命令（commands.rs → 线程）。
#[derive(Debug)]
pub(crate) enum Cmd {
    /// 加载/替换播放文件（标题同步改窗口标题；start=起点秒，≤0 视为从头）
    Load { url: String, title: String, start: f64, highlights: Vec<(f64, String)> },
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
    /// 字幕轨选择（None=关闭；Some(id)=mpv `sid`）
    Subtitle(Option<i64>),
    /// 画面比例（已白名单归一：no / 16:9 / 4:3 / -1）
    Aspect(String),
    /// 单片循环开关（mpv `loop-file` = no / inf）
    LoopFile(bool),
    /// 内置播放窗摆位（物理像素，相对主窗客户区）+ 显隐 + 圆角半径（物理像素，0=直角）
    StageRect { x: i32, y: i32, w: i32, h: i32, radius: i32, visible: bool },
    /// 关闭会话（窗口+mpv 一并清干净）
    Quit,
}

/// web/控制层轮询的控制动作字面量（与 video-player.tsx / player-controls.html
/// 侧调用对齐，双写须同步）。
///
/// `text` 只给字面量型动作（当前=画面比例）用：mpv_control 的数值通道是 f64，
/// 而 `video-aspect-override` 取值是串（"no"/"16:9"/"4:3"/"-1"）。
/// 非法值一律 None（调用方回“不支持的控制动作或参数”），白名单在 normalize_aspect。
pub(crate) fn parse_action(action: &str, value: f64, text: Option<&str>) -> Option<Cmd> {
    match action {
        "pause" => Some(Cmd::Pause(true)),
        "resume" => Some(Cmd::Pause(false)),
        "toggle-pause" => Some(Cmd::TogglePause),
        "seek" => Some(Cmd::Seek(value)),
        "speed" => Some(Cmd::Speed(value)),
        "volume" => Some(Cmd::Volume(value)),
        "mute" => Some(Cmd::Mute(value != 0.0)),
        // 字幕轨：value=轨道 id，≤0/非有限=关闭。mpv 的 `sid` 关闭语义是串 "no"
        // （写 0 不是"关闭"），所以 0 必须翻译成 None 而不是 Some(0)。
        "subtitle" => Some(Cmd::Subtitle(
            if value.is_finite() && value >= 1.0 { Some(value as i64) } else { None },
        )),
        "aspect" => normalize_aspect(text).map(Cmd::Aspect),
        // 循环：value 非 0 即开
        "loop" => Some(Cmd::LoopFile(value.is_finite() && value != 0.0)),
        _ => None,
    }
}

/// 画面比例白名单 → `video-aspect-override` 取值。
///
/// 为什么白名单而不是原样透传：该属性接受任意 `宽:高` 串与特殊负数，原样透传等于
/// 把 mpv 的属性语法暴露给页面（可写坏解码链路的显示几何）；控制条只用这四档。
/// `fill` 映射到 mpv 的 `-1`（拉伸铺满，忽略原始比例）。
fn normalize_aspect(v: Option<&str>) -> Option<String> {
    match v? {
        "no" => Some("no".to_string()),
        "16:9" => Some("16:9".to_string()),
        "4:3" => Some("4:3".to_string()),
        "fill" => Some("-1".to_string()),
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

/// 时间轴打点 → mpv 章节文件路径（每会话固定一份）。
///
/// 走文件而非 mpv_node API：client API 面刻意压在 ≤15 个函数（ADR-0036 决策 3），
/// 而 `chapter-list` 是 node 数组、需要额外引入 mpv_node/mpv_node_list 结构体面；
/// `chapters-file` 是 mpv 官方支持的章节输入格式，每次 loadfile 重新读取，
/// 换片前重写文件即可，零新增 FFI 结构。
/// 路径取系统临时目录（含用户名，属运行时计算值，不入任何被跟踪文件——铁律 14）。
fn chapters_path() -> std::path::PathBuf {
    std::env::temp_dir().join("qimeng-mpv-chapters.txt")
}

/// 写章节文件。格式 = **ffmetadata**（`;FFMETADATA1` 头 + `[CHAPTER]` 块）。
///
/// 为什么不是 OGM/simple（`CHAPTER01=00:00:05.000`）：mpv 官方手册
/// `--chapters-file` 明文「This doesn't work with OGM or XML chapters directly」，
/// 本机用真 DLL 实测 OGM 格式得到 **0 章节**，ffmetadata 得到 2 章节（2026-10-06）。
/// `END` **必须给**：实测缺 END 时章节能解析但 `title` 全为空串。
/// 末章 END 取 START+1s：END 仅用于章节区间，打点位置与章节导航都只看 START，
/// 而此刻尚不知片长（duration 要 loadfile 后才有），取定值比猜片长安全。
/// **无打点也必须写**（只写头行）：否则 mpv 会读到上一部片子的残留章节。
fn write_chapters_file(path: &std::path::Path, marks: &[(f64, String)]) -> Result<(), String> {
    let mut body = String::from(";FFMETADATA1\n");
    for (i, (secs, text)) in marks.iter().enumerate() {
        let start_ms = chapter_ms(*secs);
        let end_ms = match marks.get(i + 1) {
            Some((next, _)) => chapter_ms(*next).max(start_ms + 1),
            None => start_ms + 1000,
        };
        // 章节名里的换行破坏 key=value 行结构，替换为空格
        let name = text.replace(['\r', '\n'], " ");
        body.push_str("[CHAPTER]\n");
        body.push_str("TIMEBASE=1/1000\n");
        body.push_str(&format!("START={start_ms}\n"));
        body.push_str(&format!("END={end_ms}\n"));
        body.push_str(&format!("title={name}\n"));
    }
    std::fs::write(path, body).map_err(|e| format!("写章节文件失败：{e}"))
}

/// 秒 → 章节毫秒（ffmetadata TIMEBASE=1/1000 口径）；非法值归 0。
fn chapter_ms(secs: f64) -> i64 {
    if secs.is_finite() && secs > 0.0 {
        (secs * 1000.0).round() as i64
    } else {
        0
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

/// CSS px → 物理 px（与 `scale_stage_rect` 同一比例口径，用于圆角半径这类长度）。
/// doc_w/phys_w 非法或 css_px 非法（≤0/NaN/∞）返回 0（=不裁圆角）。
pub(crate) fn scale_len(css_px: f64, doc_w: f64, phys_w: i32) -> i32 {
    if !(doc_w.is_finite() && doc_w > 0.0) || phys_w <= 0 {
        return 0;
    }
    if !(css_px.is_finite() && css_px > 0.0) {
        return 0;
    }
    let v = css_px * phys_w as f64 / doc_w;
    if v.is_finite() && v > 0.0 {
        v.round() as i32
    } else {
        0
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
        let guard = self.status.lock().ok()?;
        let s = guard.as_ref()?;
        Some(MpvStatus {
            position: s.position,
            duration: s.duration,
            paused: s.paused,
            eof: s.eof,
            speed: s.speed,
            volume: s.volume,
            muted: s.muted,
            media: s.media.clone(),
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
        Cmd::Load { url, title, start, highlights } => Some((url, title, start, highlights)),
        Cmd::Quit => None,
        other => {
            eprintln!("[qimeng-mpv] 首命令非 Load/Quit，忽略：{other:?}");
            None
        }
    };
    let (url, title, start, highlights) = match first_load {
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
        win32::destroy_window(hwnd);
        return;
    }
    diag::trace("session_thread: mpv_created_entering_play_loop");

    if let Err(e) = play_loop(lib, handle, hwnd, &rx, &status, (url, title, start, highlights)) {
        diag::trace(&format!("session_thread: play_loop_err {e}"));
        eprintln!("[qimeng-mpv] 播放循环异常退出：{e}");
    }
    diag::trace("session_thread: cleanup_begin");
    // —— 清理顺序：先关窗（mpv 的 wid 子窗随父销毁，画面即刻消失），再释放 mpv ——
    // 全屏还原存根随会话清零，防跨会话残留把新会话首次全屏切换错走还原分支
    win32::destroy_window(hwnd);
    // 章节临时文件随会话清掉（下次会话会按新片重写；残留文件本身无敏感内容）
    let _ = std::fs::remove_file(chapters_path());
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
    first: (String, String, f64, Vec<(f64, String)>),
) -> Result<(), String> {
    unsafe {
        // wid 必须在 initialize 前设置（client.h：嵌入窗在核心初始化时接管）
        // 手册原文：win32 下 ID 按 uint32_t 传（"all Windows handles are 32-bit ...
        // mpv will not accept negative values"）。故显式掩到 32 位——直接 i64 传
        // 符号扩展后的句柄值在 bit31 置位时会被 mpv 判为非法而丢弃 wid，
        // 退回 mpv 自建顶层窗（表现=视频跑到独立窗口里）。
        let wid: i64 = (hwnd as usize & 0xFFFF_FFFF) as i64;
        check(lib, (lib.set_option)(handle, OPT_WID.as_ptr(), MPV_FORMAT_INT64, &wid as *const i64 as *mut c_void))?;
        // idle=yes：无文件时核心不退出（会话可连续换片）；keep-open=yes：播完停帧不清屏
        check(lib, (lib.set_option_string)(handle, OPT_IDLE.as_ptr(), c"yes".as_ptr()))?;
        check(lib, (lib.set_option_string)(handle, OPT_KEEP_OPEN.as_ptr(), c"yes".as_ptr()))?;
        check(lib, (lib.set_option_string)(handle, OPT_HWDEC.as_ptr(), c"auto".as_ptr()))?;
        // 播放窗聚焦时保留基础键位（空格/方向键），OSC 管鼠标交互
        check(lib, (lib.set_option_string)(handle, OPT_INPUT_BINDINGS.as_ptr(), c"yes".as_ptr()))?;
        // —— 控制条口径（2026-10-06 用户拍板：自带 OSC 太丑，改用壳内自绘控制条）——
        // osc=no：不加载 mpv 内建控制条（我们自绘，位置/外观/倍速菜单全部一致）；
        // osd-level=0：**关掉全部 OSD**——此前 `osd-playing-msg` 会在每次起播时
        //   在画面上闪一串片名（用户实机反馈"太丑"），seek/音量提示也一并交给自绘条；
        // 键位仍开（空格/←→/↑↓/f/m 在播放窗获焦时可用）。
        soft_option(lib, handle, OPT_OSC, c"no", "osc");
        soft_option(lib, handle, OPT_OSD_LEVEL, c"0", "osd-level");
        soft_option(lib, handle, OPT_INPUT_CURSOR, c"yes", "input-cursor");
        soft_option(lib, handle, OPT_WINDOW_DRAGGING, c"no", "window-dragging");
        // 旧内核的默认口径：ArtPlayer option `muted: true`（2026-09-17 用户拍板）+
        // 内置音量 0.7。mpv 默认不静音、音量 100——不矫正就是"打开即满音量出声"。
        // ⚠ 若用户嫌"默认没声音"，把 mute 那行的 yes 改 no 即可。
        soft_option(lib, handle, OPT_MUTE, c"yes", "mute");
        soft_option(lib, handle, OPT_VOLUME, c"70", "volume");
        // （起播片名走自绘控制条，不再用 mpv 的 OSD——见上面 osd-level=0）
        // 时间轴打点 = mpv 章节文件（「菜单」与自绘进度条的标记都以它为源）
        let chapters = cstring(&chapters_path().to_string_lossy());
        soft_option(lib, handle, OPT_CHAPTERS_FILE, &chapters, "chapters-file");

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

        // 订阅 mpv 日志（warn 级：含 fatal/error/warn）。**这是整条播放链唯一的
        // 错误出口**——加载失败（签名过期 403/网络/格式不支持）、VO 建窗失败、
        // 解码失败，全都不产生我们已订阅的 PROPERTY_CHANGE，不订阅日志就是
        // "点开没画面、界面还显示正在播放中、日志一片安静"（2026-10-06 实况）。
        let _ = (lib.request_log_messages)(handle, c"warn".as_ptr());
        // 点画面暂停（旧 ArtPlayer 的单击手势）：mpv 默认 MBTN_LEFT=ignore，
        // 用运行时 keybind 补上。
        let _ = command(
            lib,
            handle,
            &[c"keybind".as_ptr(), c"MBTN_LEFT".as_ptr(), c"cycle".as_ptr(), c"pause".as_ptr()],
        );
        // f 解绑：mpv 默认 f=cycle fullscreen，而嵌入形态下 mpv 管不了窗口几何
        // （源码 update_fullscreen_state 首行 `if (w32->parent) return;`），留着只会在
        // 画面上留一个"按了没反应"的死键。壳内全屏走网页侧（控制条按钮 / Esc），
        // 与旧版一致（旧配置 fullscreen: !isTauriShell，壳内本就没有系统全屏）。
        let _ = command(lib, handle, &[c"keybind".as_ptr(), c"f".as_ptr(), c"ignore".as_ptr()]);
    }
    diag::trace("session_thread: mpv_initialized");

    let (url, title, start, highlights) = first;
    apply(lib, handle, hwnd, &url, &title, start, &highlights)?;

    // 摆位诊断态：首帧 + 显隐翻转 + 尺寸变化落盘（滚动时的逐帧位移不记，防刷爆日志）。
    // 这是 P0 的可观测面——`mpv_stage_rect` 漏注册时这条日志**一行都不会有**，
    // 而它是播放窗唯一的显示入口；排查"有声音没画面"先看这里有没有 stage_rect。
    let mut stage_seen = false;
    let mut stage_prev_visible = false;
    let mut stage_prev_w = 0i32;
    let mut stage_prev_h = 0i32;
    // 媒体信息探测时钟（到点即探一次；见 PROBE_INTERVAL）
    let mut next_probe = Instant::now();

    loop {
        // ① 命令（15ms 节拍）
        match rx.recv_timeout(CMD_POLL) {
            Ok(Cmd::Quit) => break,
            Ok(Cmd::Load { url, title, start, highlights }) => {
                // 换片：旧片的分辨率/轨道必须立刻失效（探测要等一拍，否则菜单里
                // 会短暂留着上一部片子的字幕轨）
                clear_media(status);
                next_probe = Instant::now();
                apply(lib, handle, hwnd, &url, &title, start, &highlights)?
            }
            Ok(Cmd::Pause(p)) => set_flag(lib, handle, PROP_PAUSE, p)?,
            Ok(Cmd::TogglePause) => {
                let paused = status
                    .lock()
                    .map(|s| s.as_ref().map(|v| v.paused).unwrap_or(false))
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
            // 字幕轨：Some=切到该轨，None=关（mpv `sid` 的关闭值是串 "no"）
            Ok(Cmd::Subtitle(id)) => match id {
                Some(tid) => {
                    // SAFETY: INT64 格式数据是 i64
                    unsafe {
                        check(lib, (lib.set_property)(handle, PROP_SID.as_ptr(), MPV_FORMAT_INT64, &tid as *const i64 as *mut c_void))?;
                    }
                }
                None => {
                    // SAFETY: STRING 值经 set_property_string 直传
                    unsafe {
                        check(lib, (lib.set_property_string)(handle, PROP_SID.as_ptr(), c"no".as_ptr()))?;
                    }
                }
            },
            Ok(Cmd::Aspect(v)) => {
                let cv = cstring(&v);
                // SAFETY: 同上；v 已过 normalize_aspect 白名单
                unsafe {
                    check(lib, (lib.set_property_string)(handle, PROP_ASPECT.as_ptr(), cv.as_ptr()))?;
                }
            }
            Ok(Cmd::LoopFile(on)) => {
                // SAFETY: 同上；loop-file 取值 no/inf
                unsafe {
                    let val = if on { c"inf".as_ptr() } else { c"no".as_ptr() };
                    check(lib, (lib.set_property_string)(handle, PROP_LOOP_FILE.as_ptr(), val))?;
                }
            }
            Ok(Cmd::StageRect { x, y, w, h, radius, visible }) => {
                let resized = (w - stage_prev_w).abs() > 2 || (h - stage_prev_h).abs() > 2;
                if !stage_seen || visible != stage_prev_visible || resized {
                    diag::trace(&format!(
                        "stage_rect x={x} y={y} w={w} h={h} r={radius} visible={visible}"
                    ));
                    stage_seen = true;
                    stage_prev_visible = visible;
                    stage_prev_w = w;
                    stage_prev_h = h;
                }
                win32::set_stage_rect(hwnd, x, y, w, h, radius, visible)
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
                MPV_EVENT_LOG_MESSAGE => {
                    // SAFETY: LOG_MESSAGE 的 data 是 mpv_event_log_message，串在
                    // 下一次 wait_event 前有效（本作用域内即用完，不留存）
                    if let Some(m) = unsafe { (event.data as *const MpvEventLogMessage).as_ref() } {
                        let cstr = |p: *const c_char| -> String {
                            if p.is_null() {
                                String::new()
                            } else {
                                // SAFETY: p 为 mpv 提供的 NUL 结尾串
                                unsafe { CStr::from_ptr(p) }.to_string_lossy().into_owned()
                            }
                        };
                        let line = format!(
                            "mpv_log [{}] {}: {}",
                            cstr(m.level),
                            cstr(m.prefix),
                            redact_query(&cstr(m.text).trim_end())
                        );
                        diag::trace(&line);
                    }
                }
                MPV_EVENT_SHUTDOWN => return Ok(()),
                _ => {} // 未知/未消费事件按 ABI 演进防御性忽略
            }
        }

        // ④ 媒体信息探测（控制条的清晰度/字幕/设置面板数据源；半秒一拍）
        if Instant::now() >= next_probe {
            next_probe = Instant::now() + PROBE_INTERVAL;
            let media = probe(lib, handle);
            if let Ok(mut guard) = status.lock() {
                // 只在状态缓存已存在时写入：探测不得抢先创建条目（首个非空快照
                // = 起播边沿，提前创建会把口径 B 的起播上报提前一拍）
                if let Some(slot) = guard.as_mut() {
                    slot.media = media;
                }
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
/// 打点先落章节文件（mpv 在 loadfile 时读），换片即换标记。
fn apply(
    lib: &'static MpvLib,
    handle: *mut c_void,
    hwnd: win32::Hwnd,
    url: &str,
    title: &str,
    start: f64,
    highlights: &[(f64, String)],
) -> Result<(), String> {
    win32::set_window_title(hwnd, title);
    // 章节文件写失败不阻断播放（打点是增强项，缺标记仍能正常看片）
    if let Err(e) = write_chapters_file(&chapters_path(), highlights) {
        diag::trace(&format!("session_thread: chapters_write_err {e}"));
    }
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

/// 软失败选项设置：未知/不支持的选项只落诊断日志，**不中断播放**。
///
/// 为什么必须软：换 DLL 跟版时某个选项被改名/移除是常态（ADR-0036 的跟版动作只有
/// "换 DLL + 回归"），硬失败会让整场播放起不来——代价远超少一个体验开关。
/// 结构性选项（wid/idle/keep-open/hwdec）仍走硬失败，见 play_loop。
fn soft_option(lib: &'static MpvLib, handle: *mut c_void, name: &CStr, value: &CStr, tag: &str) {
    // SAFETY: handle 由本线程创建；name/value 为 NUL 结尾静态串
    let code = unsafe { (lib.set_option_string)(handle, name.as_ptr(), value.as_ptr()) };
    if code != MPV_ERROR_SUCCESS {
        diag::trace(&format!("session_thread: option_rejected {tag}={} ({})",
            value.to_string_lossy(), lib.err_text(code)));
    }
}

/// 诊断日志脱敏：查询串（签名直链的签名/过期参数）不得落盘——铁律 14 的精神，
/// qimeng-shell.log 虽在 gitignore 内，也不留可用凭据。
/// 把每个 `?` 起到下一个空白（或行尾）之间替换为 `?<redacted>`；无 `?` 原样返回。
fn redact_query(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut rest = s;
    while let Some(i) = rest.find('?') {
        out.push_str(&rest[..i]);
        out.push_str("?<redacted>");
        let after = &rest[i + 1..];
        match after.find(char::is_whitespace) {
            Some(j) => rest = &after[j..],
            None => return out,
        }
    }
    out.push_str(rest);
    out
}

/// 读一个属性为字符串（STRING 档：node 属性会拿到 JSON 文本）。
///
/// 返回的堆串归 mpv 所有，必须 `mpv_free` 释放（client.h 明文）；属性不存在/
/// 尚未就绪时 mpv 返回 NULL → None。**失败静默**：探测是增强项，坏一拍不影响播放。
fn get_prop_string(lib: &'static MpvLib, handle: *mut c_void, name: &CStr) -> Option<String> {
    // SAFETY: handle 由本线程创建并持有；name 为 NUL 结尾静态串；返回串按
    // client.h 归 mpv 所有，取出后立即 mpv_free（不留存指针）
    unsafe {
        let p = (lib.get_property_string)(handle, name.as_ptr());
        if p.is_null() {
            return None;
        }
        let s = CStr::from_ptr(p).to_string_lossy().into_owned();
        (lib.free)(p as *mut c_void);
        Some(s)
    }
}

/// JSON 文本 → serde_json::Value（解析失败=None，调用方按缺省值兜底）。
fn parse_json(v: Option<String>) -> Option<serde_json::Value> {
    serde_json::from_str::<serde_json::Value>(v.as_deref()?).ok()
}

/// 媒体信息探测拍：分辨率/编码/帧率/画面比例/循环/字幕轨。
///
/// 口径注释：`track-list` 只取 `type=="sub"`（字幕菜单），音频/视频轨控制条不用；
/// `sid` 关闭时按 STRING 读回来是 "no"（解析失败即 0=关），选中轨是轨道 id 的十进制。
fn probe(lib: &'static MpvLib, handle: *mut c_void) -> MediaProbe {
    let mut out = MediaProbe::default();
    if let Some(params) = parse_json(get_prop_string(lib, handle, PROP_VIDEO_PARAMS)) {
        out.video_width = params.get("w").and_then(|v| v.as_i64()).unwrap_or(0);
        out.video_height = params.get("h").and_then(|v| v.as_i64()).unwrap_or(0);
    }
    out.video_codec = get_prop_string(lib, handle, PROP_VIDEO_FORMAT).unwrap_or_default();
    out.fps = get_prop_string(lib, handle, PROP_FPS)
        .and_then(|s| s.trim().parse::<f64>().ok())
        .filter(|v| v.is_finite() && *v > 0.0)
        .unwrap_or(0.0);
    out.aspect = get_prop_string(lib, handle, PROP_ASPECT)
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "no".to_string());
    out.loop_file = matches!(
        get_prop_string(lib, handle, PROP_LOOP_FILE).as_deref().map(str::trim),
        Some("inf") | Some("yes")
    );
    out.sid = get_prop_string(lib, handle, PROP_SID)
        .and_then(|s| s.trim().parse::<i64>().ok())
        .unwrap_or(0);
    if let Some(list) = parse_json(get_prop_string(lib, handle, PROP_TRACK_LIST))
        .as_ref()
        .and_then(|v| v.as_array())
    {
        for t in list {
            if t.get("type").and_then(|v| v.as_str()) != Some("sub") {
                continue;
            }
            let id = t.get("id").and_then(|v| v.as_i64()).unwrap_or(0);
            if id <= 0 {
                continue;
            }
            let str_of = |key: &str| -> String {
                t.get(key).and_then(|v| v.as_str()).unwrap_or_default().to_string()
            };
            out.tracks.push(TrackInfo {
                id,
                title: str_of("title"),
                lang: str_of("lang"),
                external: t.get("external").and_then(|v| v.as_bool()).unwrap_or(false),
            });
        }
    }
    out
}

/// 换片：清掉上一部片子的媒体信息（其余播放字段由 PROPERTY_CHANGE 自己刷新）。
fn clear_media(status: &Mutex<Option<StatusInner>>) {
    if let Ok(mut guard) = status.lock() {
        if let Some(slot) = guard.as_mut() {
            slot.media = MediaProbe::default();
        }
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
        assert!(matches!(parse_action("pause", 0.0, None), Some(Cmd::Pause(true))));
        assert!(matches!(parse_action("resume", 0.0, None), Some(Cmd::Pause(false))));
        assert!(matches!(parse_action("toggle-pause", 0.0, None), Some(Cmd::TogglePause)));
        assert!(matches!(parse_action("seek", 12.5, None), Some(Cmd::Seek(v)) if v == 12.5));
        assert!(matches!(parse_action("speed", 2.0, None), Some(Cmd::Speed(v)) if v == 2.0));
        assert!(matches!(parse_action("volume", 80.0, None), Some(Cmd::Volume(v)) if v == 80.0));
        assert!(matches!(parse_action("mute", 1.0, None), Some(Cmd::Mute(true))));
        assert!(matches!(parse_action("mute", 0.0, None), Some(Cmd::Mute(false))));
        assert!(parse_action("unknown", 0.0, None).is_none());
    }

    /// 字幕轨选择：id≥1 才当"选中某轨"，0/负数/NaN 一律翻译成"关闭"——
    /// mpv 的 sid 关闭语义是串 "no"，写 0 会被当成"轨道 0"（不存在）而行为不定。
    #[test]
    fn parse_action_subtitle_maps_zero_to_off() {
        assert!(matches!(parse_action("subtitle", 2.0, None), Some(Cmd::Subtitle(Some(2)))));
        assert!(matches!(parse_action("subtitle", 0.0, None), Some(Cmd::Subtitle(None))));
        assert!(matches!(parse_action("subtitle", -1.0, None), Some(Cmd::Subtitle(None))));
        assert!(matches!(parse_action("subtitle", f64::NAN, None), Some(Cmd::Subtitle(None))));
    }

    /// 画面比例白名单：只认四档，其余（含空/未传/任意比例串）一律拒绝，
    /// 防止页面把 mpv 属性语法原样透传进来。
    #[test]
    fn parse_action_aspect_whitelist() {
        assert!(matches!(parse_action("aspect", 0.0, Some("no")), Some(Cmd::Aspect(v)) if v == "no"));
        assert!(matches!(parse_action("aspect", 0.0, Some("16:9")), Some(Cmd::Aspect(v)) if v == "16:9"));
        assert!(matches!(parse_action("aspect", 0.0, Some("4:3")), Some(Cmd::Aspect(v)) if v == "4:3"));
        // fill = mpv 的 -1（拉伸铺满）
        assert!(matches!(parse_action("aspect", 0.0, Some("fill")), Some(Cmd::Aspect(v)) if v == "-1"));
        assert!(parse_action("aspect", 0.0, Some("2.35:1")).is_none());
        assert!(parse_action("aspect", 0.0, Some("")).is_none());
        assert!(parse_action("aspect", 0.0, None).is_none());
    }

    /// 循环开关：非 0 即开；NaN 视为关（不误开）。
    #[test]
    fn parse_action_loop_flag() {
        assert!(matches!(parse_action("loop", 1.0, None), Some(Cmd::LoopFile(true))));
        assert!(matches!(parse_action("loop", 0.0, None), Some(Cmd::LoopFile(false))));
        assert!(matches!(parse_action("loop", f64::NAN, None), Some(Cmd::LoopFile(false))));
    }

    /// 探测用的 JSON 容错：`track-list`/`video-params` 读回来可能是 null/空串/
    /// 半截 JSON（换片瞬间属性会闪空），解析失败必须退化成 None 而不是 panic。
    #[test]
    fn parse_json_tolerates_garbage() {
        assert!(parse_json(None).is_none());
        assert!(parse_json(Some(String::new())).is_none());
        assert!(parse_json(Some("null".into())).is_some()); // "null" 是合法 JSON
        assert!(parse_json(Some("{".into())).is_none());
        let v = parse_json(Some(r#"[{"type":"sub","id":2}]"#.into())).expect("JSON 数组");
        assert_eq!(v.as_array().map(|a| a.len()), Some(1));
        assert_eq!(v[0].get("id").and_then(|x| x.as_i64()), Some(2));
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

    #[test]
    fn chapter_ms_rejects_invalid() {
        assert_eq!(chapter_ms(5.25), 5250);
        assert_eq!(chapter_ms(0.0), 0);
        assert_eq!(chapter_ms(f64::NAN), 0);
        assert_eq!(chapter_ms(-3.0), 0);
    }

    #[test]
    fn write_chapters_file_emits_ffmetadata_and_clears() {
        let p = std::env::temp_dir().join("qimeng-test-chapters.txt");
        let marks = vec![(5.0, "开场".to_string()), (15.5, "a\nb".to_string())];
        write_chapters_file(&p, &marks).expect("写章节文件");
        assert_eq!(
            std::fs::read_to_string(&p).expect("读回章节文件"),
            ";FFMETADATA1\n\
             [CHAPTER]\nTIMEBASE=1/1000\nSTART=5000\nEND=15500\ntitle=开场\n\
             [CHAPTER]\nTIMEBASE=1/1000\nSTART=15500\nEND=16500\ntitle=a b\n"
        );
        // 空打点必须只写头行：否则 mpv 会读到上一部片子的残留章节
        write_chapters_file(&p, &[]).expect("写空章节文件");
        assert_eq!(std::fs::read_to_string(&p).expect("读回空章节文件"), ";FFMETADATA1\n");
        let _ = std::fs::remove_file(&p);
    }

    #[test]
    fn scale_len_matches_rect_ratio_and_rejects_invalid() {
        // 与 scale_stage_rect 同口径：1100/1000 = 1.1
        assert_eq!(scale_len(18.0, 1000.0, 1100), 20);
        assert_eq!(scale_len(18.0, 1200.0, 2100), 32);
        assert_eq!(scale_len(0.0, 1000.0, 1000), 0);
        assert_eq!(scale_len(-5.0, 1000.0, 1000), 0);
        assert_eq!(scale_len(f64::NAN, 1000.0, 1000), 0);
        assert_eq!(scale_len(18.0, 0.0, 1000), 0);
        assert_eq!(scale_len(18.0, 1000.0, 0), 0);
    }

    #[test]
    fn redact_query_strips_signed_params() {
        assert_eq!(
            redact_query("Failed to open http://nas:8420/media/a.mp4?token=x&expires=1."),
            "Failed to open http://nas:8420/media/a.mp4?<redacted>"
        );
        // 行内多个 URL、无查询串、纯文本三种情况
        assert_eq!(
            redact_query("a?x=1 and b?y=2 tail"),
            "a?<redacted> and b?<redacted> tail"
        );
        assert_eq!(redact_query("no query here"), "no query here");
    }

    #[test]
    fn write_chapters_file_keeps_end_after_start() {
        // 同点/倒序打点：END 必须 > START（实测 END 缺失或非正区间会让 title 读空）
        let p = std::env::temp_dir().join("qimeng-test-chapters2.txt");
        write_chapters_file(&p, &[(10.0, "x".to_string()), (10.0, "y".to_string())]).expect("写");
        let body = std::fs::read_to_string(&p).expect("读");
        let starts: Vec<i64> = body
            .lines()
            .filter_map(|l| l.strip_prefix("START="))
            .map(|v| v.parse().unwrap())
            .collect();
        let ends: Vec<i64> = body
            .lines()
            .filter_map(|l| l.strip_prefix("END="))
            .map(|v| v.parse().unwrap())
            .collect();
        assert_eq!(starts, vec![10000, 10000]);
        assert!(ends.iter().zip(&starts).all(|(e, s)| *e > *s), "END 必须大于 START：{ends:?}");
        let _ = std::fs::remove_file(&p);
    }
}
