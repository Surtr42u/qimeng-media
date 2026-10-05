//! web → 原生内核的 IPC 门面（tauri #[tauri::command]）。
//!
//! 职责边界：参数校验 + 会话槽管理，不含任何播放逻辑（铁律 7 对偶面：壳不藏业务）。
//! 命令名与参数名是 web 侧 invoke 的协议（video-player.tsx 双写，改名须两端同步）。

use std::sync::Mutex;

use tauri::State;

use super::player::{parse_action, Cmd, MpvSession, MpvStatus};

/// 全局会话槽：Some=已有播放会话（复用窗口换片），None=需要新开会话。
#[derive(Default)]
pub struct MpvState(pub(crate) Mutex<Option<MpvSession>>);

/// 打开/换片。DLL 缺失时返回带 setup 指引的错误（web 据此降级提示）。
#[tauri::command]
pub fn mpv_open(state: State<'_, MpvState>, url: String, title: String, start_secs: f64) -> Result<(), String> {
    if url.is_empty() {
        return Err("播放地址为空".into());
    }
    let load = || Cmd::Load { url: url.clone(), title: title.clone(), start: start_secs };
    let mut guard = state.0.lock().expect("MpvState 锁中毒");
    // 已有会话 → 原地换片；会话已死（用户关过窗）→ 重建
    if let Some(session) = guard.as_ref() {
        if session.send(load()).is_ok() {
            return Ok(());
        }
    }
    *guard = Some(MpvSession::spawn(load())?);
    Ok(())
}

/// 轮询快照：None=无活动播放（窗口已关/会话已死/尚未起播），web 侧据此复位按钮态。
#[tauri::command]
pub fn mpv_status(state: State<'_, MpvState>) -> Option<MpvStatus> {
    let guard = state.0.lock().expect("MpvState 锁中毒");
    guard.as_ref().and_then(MpvSession::status)
}

/// 播放控制（action 字面量与 video-player.tsx 双写：pause/resume/toggle-pause/seek/speed）。
#[tauri::command]
pub fn mpv_control(state: State<'_, MpvState>, action: String, value: f64) -> Result<(), String> {
    let cmd = parse_action(&action, value).ok_or_else(|| format!("未知控制动作：{action}"))?;
    let guard = state.0.lock().expect("MpvState 锁中毒");
    match guard.as_ref() {
        Some(session) => session.send(cmd),
        None => Err("没有正在进行的原生播放".into()),
    }
}

/// 关闭会话与播放窗（用户点 X 关窗走 Win32 路径，不经此命令）。
#[tauri::command]
pub fn mpv_close(state: State<'_, MpvState>) -> Result<(), String> {
    let mut guard = state.0.lock().expect("MpvState 锁中毒");
    match guard.take() {
        Some(session) => session.send(Cmd::Quit),
        None => Ok(()), // 幂等：没有会话视作已关闭
    }
}
