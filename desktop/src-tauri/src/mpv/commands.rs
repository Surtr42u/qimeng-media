//! web/控制层 → 原生内核的 IPC 门面（tauri #[tauri::command]）。
//!
//! 职责边界：参数校验 + 会话槽管理，不含任何播放逻辑（铁律 7 对偶面：壳不藏业务）。
//! 命令名与参数名是 web 侧（video-player.tsx）与控制层页（player-controls.html）
//! invoke 的协议（多处双写，改名须各端同步）。

use std::sync::Mutex;

use serde::{Deserialize, Serialize};
use tauri::{AppHandle, State};

use crate::diag;
use super::player::{parse_action, Cmd, MpvSession, MpvStatus};

/// 全局会话槽：Some=已有播放会话（复用窗口换片），None=需要新开会话。
/// overlay_data：控制层静态数据（时间轴打点，由 web 经 mpv_open 中转——控制层
/// 页零网络面，服务端数据一律走壳中转）。
#[derive(Default)]
pub struct MpvState {
    pub(crate) session: Mutex<Option<MpvSession>>,
    pub(crate) overlay_data: Mutex<Vec<Highlight>>,
}

/// 时间轴标签打点（web PlayerHighlight 同构：秒 + 悬停文本）。
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Highlight {
    pub time: f64,
    pub text: String,
}

/// 打开/换片。DLL 缺失时返回带 setup 指引的错误（web 据此降级提示）。
/// highlights：时间轴打点（web useTimelineTags 产物中转给控制层，可省略）。
/// AppHandle 参数由 Tauri 命令系统注入：新会话线程要经它在主线程建控制层窗。
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
    diag::trace(&format!(
        "mpv_open title_len={} url_len={} start={start_secs:.1} highlights={}",
        title.len(),
        url.len(),
        highlights.as_ref().map_or(0, Vec::len),
    ));
    let load = || Cmd::Load { url: url.clone(), title: title.clone(), start: start_secs };
    *state.overlay_data.lock().expect("overlay_data 锁中毒") = highlights.unwrap_or_default();
    let mut guard = state.session.lock().expect("MpvState 锁中毒");
    // 已有会话 → 原地换片；会话已死（用户关过窗）→ 重建
    if let Some(session) = guard.as_ref() {
        if session.send(load()).is_ok() {
            diag::trace("mpv_open reused_session");
            return Ok(());
        }
    }
    let spawned = MpvSession::spawn(app, load());
    diag::trace(&format!("mpv_open spawn={:?}", spawned.is_ok()));
    *guard = Some(spawned?);
    Ok(())
}

/// 轮询快照：None=无活动播放（窗口已关/会话已死/尚未起播），web 侧据此复位按钮态。
#[tauri::command]
pub fn mpv_status(state: State<'_, MpvState>) -> Option<MpvStatus> {
    let guard = state.session.lock().expect("MpvState 锁中毒");
    guard.as_ref().and_then(MpvSession::status)
}

/// 控制层打点数据（player-controls.html 启动与换片轮询消费）。
#[tauri::command]
pub fn mpv_overlay_info(state: State<'_, MpvState>) -> Vec<Highlight> {
    state.overlay_data.lock().expect("overlay_data 锁中毒").clone()
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

/// 关闭会话与播放窗（控制层窗销毁路径复用；幂等：没有会话视作已关闭）。
pub(crate) fn close_session(state: &MpvState) {
    diag::trace("close_session");
    let mut guard = state.session.lock().expect("MpvState 锁中毒");
    if let Some(session) = guard.take() {
        let _ = session.send(Cmd::Quit);
    }
}

/// 命令形态的关闭（web chip「返回 Web 内核」）。
#[tauri::command]
pub fn mpv_close(state: State<'_, MpvState>) -> Result<(), String> {
    close_session(&state);
    Ok(())
}
