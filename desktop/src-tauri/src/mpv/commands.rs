//! web → 原生内核的 IPC 门面（tauri #[tauri::command]）。
//!
//! 职责边界：参数校验 + 会话槽管理，不含任何播放逻辑（铁律 7 对偶面：壳不藏业务）。
//! 命令名与参数名是 web 侧 invoke 的协议（调用方接线属插件计划范围，
//! 见 desktop/PLUGIN_PLAN.md；改名须两端同步）。

use std::sync::Mutex;

use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Manager, State};

use super::player::{parse_action, scale_stage_rect, Cmd, MpvSession, MpvStatus};
use super::win32;

/// 主窗口 label（与 main.rs MAIN_WINDOW_LABEL 双写，改一处须同步另一处）
const MAIN_WINDOW_LABEL: &str = "main";

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

/// 打开/换片。DLL 缺失时返回带 setup 指引的错误（web 据此降级提示）。
/// highlights：时间轴打点（控制层接线后由 web 中转，可省略）。
#[tauri::command]
pub fn mpv_open(
    app: AppHandle,
    state: State<'_, MpvState>,
    url: String,
    title: String,
    start_secs: f64,
    highlights: Option<Vec<Highlight>>,
) -> Result<(), String> {
    let _ = highlights; // 控制层撤回（闪退排查，见 PLUGIN_PLAN），参数面保留免协议破环
    if url.is_empty() {
        return Err("播放地址为空".into());
    }
    // 内置形态：捕获主窗 HWND（子窗父体）。主窗不存在（理论不可达——能点开
    // 视频必在主窗里）则 None → 会话退回独立窗口形态兜底。
    let main_hwnd = app.get_webview_window(MAIN_WINDOW_LABEL).and_then(|w| w.hwnd().ok());
    *state.main_hwnd.lock().expect("main_hwnd 锁中毒") = main_hwnd.map(|h| h.0 as isize);
    let load = || Cmd::Load { url: url.clone(), title: title.clone(), start: start_secs };
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
) -> Result<(), String> {
    let main = *state.main_hwnd.lock().expect("main_hwnd 锁中毒");
    let Some(main) = main else { return Ok(()) };
    // SAFETY: main 为 mpv_open 时捕获的主窗 HWND 值；GetClientRect 只读
    let Some((phys_w, _)) = win32::client_size(main as *mut std::ffi::c_void) else {
        return Ok(());
    };
    let Some((x, y, w, h)) = scale_stage_rect(x, y, w, h, doc_w, phys_w) else {
        return Ok(());
    };
    let guard = state.session.lock().expect("MpvState 锁中毒");
    if let Some(session) = guard.as_ref() {
        let _ = session.send(Cmd::StageRect { x, y, w, h, visible });
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
/// toggle-pause/seek/speed/volume/mute/toggle-fullscreen）。
#[tauri::command]
pub fn mpv_control(state: State<'_, MpvState>, action: String, value: f64) -> Result<(), String> {
    let cmd = parse_action(&action, value).ok_or_else(|| format!("未知控制动作：{action}"))?;
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
