//! web → 原生内核的 IPC 门面（tauri #[tauri::command]）。
//!
//! 职责边界：参数校验 + 会话槽管理，不含任何播放逻辑（铁律 7 对偶面：壳不藏业务）。
//! 命令名与参数名是 web 侧 invoke 的协议（调用方接线属插件计划范围，
//! 见 desktop/PLUGIN_PLAN.md；改名须两端同步）。

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;

use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Manager, State};

use super::player::{parse_action, scale_len, scale_stage_rect, Cmd, MpvSession, MpvStatus};
use super::win32;
use crate::diag;

/// 主窗口 label（与 main.rs MAIN_WINDOW_LABEL 双写，改一处须同步另一处）
const MAIN_WINDOW_LABEL: &str = "main";

/// 摆位原始参数只落盘一次（每进程）：滚动会高频上报，逐次记会刷爆日志。
static STAGE_RAW_LOGGED: AtomicBool = AtomicBool::new(false);

/// 全局会话槽 + 主窗句柄缓存：内置形态下子窗坐标换算要读主窗客户区
/// （main_hwnd 由 mpv_open 捕获，主线程取句柄安全，跨线程只读值）。
#[derive(Default)]
pub struct MpvState {
    pub(crate) session: Mutex<Option<MpvSession>>,
    pub(crate) main_hwnd: Mutex<Option<isize>>,
}

/// 时间轴标签打点（web PlayerHighlight 同构：秒 + 悬停文本；控制层接线时消费）。
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Highlight {
    pub time: f64,
    pub text: String,
}

/// 打开/换片。DLL 缺失时返回带 setup 指引的错误（web 据此降级到 ArtPlayer）。
/// highlights：时间轴打点（秒 + 标签名）→ 播放线程落成 mpv chapter 列表，
/// 在 mpv OSC 进度条上出标记（对齐旧内核 ArtPlayer highlight 的可视效果）。
#[tauri::command]
pub fn mpv_open(
    app: AppHandle,
    state: State<'_, MpvState>,
    url: String,
    title: String,
    start_secs: f64,
    highlights: Option<Vec<Highlight>>,
) -> Result<(), String> {
    if url.is_empty() {
        return Err("播放地址为空".into());
    }
    // mpv 不解析相对 URL：`/media/orig/…` 会被当成 Windows 本地路径去找
    // （2026-10-06 实机实测 `Cannot open file '\media\orig\…' Invalid argument`）。
    // mpv 侧只报"打不开"，从现象极难反推，故在入口拦下并说清。
    if !url.contains("://") {
        return Err("播放地址必须是绝对 URL（收到相对路径，mpv 无法解析）".into());
    }
    let marks = sanitize_marks(highlights.unwrap_or_default());
    // 内置形态：捕获主窗 HWND（子窗父体）。主窗不存在（理论不可达——能点开
    // 视频必在主窗里）则 None → 会话退回独立窗口形态兜底。
    let main_hwnd = app.get_webview_window(MAIN_WINDOW_LABEL).and_then(|w| w.hwnd().ok());
    *state.main_hwnd.lock().expect("main_hwnd 锁中毒") = main_hwnd.map(|h| h.0 as isize);
    let load = || Cmd::Load {
        url: url.clone(),
        title: title.clone(),
        start: start_secs,
        highlights: marks.clone(),
    };
    let mut guard = state.session.lock().expect("MpvState 锁中毒");
    // 已有会话 → 原地换片；会话已死（用户关过窗）→ 重建
    if let Some(session) = guard.as_ref() {
        if session.send(load()).is_ok() {
            return Ok(());
        }
    }
    *guard = Some(MpvSession::spawn(load(), main_hwnd.map(|h| h.0 as isize))?);
    Ok(())
}

/// 舞台矩形上报（内置形态）：web 传 CSS px + 页面视口宽，Rust 按主窗客户区
/// 物理宽折算（zoom/DPI 折进比例，scale_stage_rect 纯函数带单测）。无会话时
/// 静默忽略（组件挂载先于 mpv_open 完成的竞态无害——open 后首拍会再上报）。
#[tauri::command]
pub fn mpv_stage_rect(
    state: State<'_, MpvState>,
    x: f64,
    y: f64,
    w: f64,
    h: f64,
    doc_w: f64,
    visible: bool,
    radius: Option<f64>,
) -> Result<(), String> {
    let main = *state.main_hwnd.lock().expect("main_hwnd 锁中毒");
    let Some(main) = main else { return Ok(()) };
    // SAFETY: main 为 mpv_open 时捕获的主窗 HWND 值；GetClientRect 只读
    let Some((phys_w, _)) = win32::client_size(main as *mut std::ffi::c_void) else {
        return Ok(());
    };
    let Some((sx, sy, sw, sh)) = scale_stage_rect(x, y, w, h, doc_w, phys_w) else {
        return Ok(());
    };
    // 圆角半径由 web 传**计算后的 CSS 值**（读 .asset-stage 的 border-radius）：
    // 与矩形同一折算口径，故 zoom/DPI 自动跟随，Rust 侧不硬编码色值/尺寸。
    let r = scale_len(radius.unwrap_or(0.0), doc_w, phys_w);
    // 首报落一次原始参数（CSS px + 视口宽 + 主窗客户区物理宽）与折算结果：
    // 换算链出问题时，这几个数是唯一能定位"比例错在哪一段"的证据（截图看不出来）。
    if !STAGE_RAW_LOGGED.swap(true, Ordering::Relaxed) {
        diag::trace(&format!(
            "stage_rect_raw css=({x:.1},{y:.1},{w:.1},{h:.1}) doc_w={doc_w:.1} \
             phys_w={phys_w} radius_css={:.1} -> phys=({sx},{sy},{sw},{sh}) r={r} visible={visible}",
            radius.unwrap_or(0.0)
        ));
    }
    let guard = state.session.lock().expect("MpvState 锁中毒");
    if let Some(session) = guard.as_ref() {
        let _ = session.send(Cmd::StageRect { x: sx, y: sy, w: sw, h: sh, radius: r, visible });
    }
    Ok(())
}

/// 轮询快照：None=无活动播放（窗口已关/会话已死/尚未起播），web 侧据此复位按钮态。
#[tauri::command]
pub fn mpv_status(state: State<'_, MpvState>) -> Option<MpvStatus> {
    let guard = state.session.lock().expect("MpvState 锁中毒");
    guard.as_ref().and_then(MpvSession::status)
}

/// 播放控制（action 字面量与 player.rs parse_action 双写：pause/resume/
/// toggle-pause/seek/speed/volume/mute/subtitle/aspect/loop）。
///
/// `text` 只给字面量型动作（画面比例）：数值通道是 f64，而
/// `video-aspect-override` 取值是串。白名单校验在 player.rs normalize_aspect。
#[tauri::command]
pub fn mpv_control(
    state: State<'_, MpvState>,
    action: String,
    value: f64,
    text: Option<String>,
) -> Result<(), String> {
    let cmd = parse_action(&action, value, text.as_deref())
        .ok_or_else(|| format!("不支持的控制动作或参数：{action}"))?;
    let guard = state.session.lock().expect("MpvState 锁中毒");
    match guard.as_ref() {
        Some(session) => session.send(cmd),
        None => Err("没有正在进行的原生播放".into()),
    }
}

/// 关闭会话与播放窗（幂等：没有会话视作已关闭）。
pub(crate) fn close_session(state: &MpvState) {
    let mut guard = state.session.lock().expect("MpvState 锁中毒");
    if let Some(session) = guard.take() {
        let _ = session.send(Cmd::Quit);
    }
}

/// 命令形态的关闭。
#[tauri::command]
pub fn mpv_close(state: State<'_, MpvState>) -> Result<(), String> {
    close_session(&state);
    Ok(())
}

/// 打点清洗（纯函数，单测锁定）：非有限值（NaN/±∞）与非正值丢弃——章节时间戳
/// 不接受负值；随后按时间升序——章节序号按此序生成且 mpv 不重排，顺序必须在
/// 入口定死（web 侧页面虽已按 timeMillis 排序，协议不保证，边界不靠上游）。
fn sanitize_marks(highlights: Vec<Highlight>) -> Vec<(f64, String)> {
    let mut marks: Vec<(f64, String)> = highlights
        .into_iter()
        .filter(|h| h.time.is_finite() && h.time > 0.0)
        .map(|h| (h.time, h.text))
        .collect();
    marks.sort_by(|a, b| a.0.total_cmp(&b.0));
    marks
}

#[cfg(test)]
mod tests {
    use super::*;

    fn mark(time: f64, text: &str) -> Highlight {
        Highlight { time, text: text.to_string() }
    }

    #[test]
    fn sanitize_marks_drops_invalid_and_sorts() {
        let out = sanitize_marks(vec![
            mark(30.0, "B"),
            mark(f64::NAN, "nan"),
            mark(0.0, "zero"),
            mark(-5.0, "neg"),
            mark(f64::INFINITY, "inf"),
            mark(10.0, "A"),
        ]);
        assert_eq!(out, vec![(10.0, "A".to_string()), (30.0, "B".to_string())]);
    }

    #[test]
    fn sanitize_marks_empty_is_empty() {
        assert!(sanitize_marks(Vec::new()).is_empty());
    }

    #[test]
    fn sanitize_marks_keeps_equal_times() {
        let out = sanitize_marks(vec![mark(5.0, "一"), mark(5.0, "二")]);
        assert_eq!(out.len(), 2);
        assert_eq!(out[0].0, 5.0);
        assert_eq!(out[1].0, 5.0);
    }
}
