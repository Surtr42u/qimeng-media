//! 播放窗透明控制层（C4.5 双层播放窗：底层 mpv wid 画面，顶层 WebView2 控制层）。
//!
//! 架构（MPV_MIGRATION §C4.5 定稿，刻意不用 render API 纹理合成）：控制层是
//! Tauri 管理的透明无边框窗，加载壳内静态页 player-controls.html；创建后经
//! GWL_HWNDPARENT 归属播放窗（Win32 owned 语义 = 永在播放窗之上、随播放窗
//! 最小化/销毁，z-order 无需自行管理）。输入面：控制层覆盖播放窗客户区，
//! 鼠标/键盘归控制层（页内经 mpv_control IPC 驱动 mpv），播放窗标题栏仍归
//! 系统（拖动/缩放/关闭）。
//! 几何同步走事件驱动：播放窗 WM_WINDOWPOSCHANGED（win32::wnd_proc）触发
//! sync_geometry，经 run_on_main_thread 以物理像素 set_position/set_size——
//! 跨线程窗口操作只用 Tauri 官方通道，杜绝 Win32 跨线程 SetWindowPos 的
//! 输入队列附着死锁；wm 消息路径在拖动模态循环内也照常派发，拖动跟手。
//! 生命周期（对抗复核 2026-10-06 定案）：每个会话一个唯一 label（代数后缀），
//! 从根上消灭「同 label 先关后建的异步竞态」；attach 先关槽内上一代遗留窗
//! （旧会话 detach 晚到时按 gen 失配跳过，其窗已由新 attach 代关），detach
//! 只回收本代；所有回收路径经 run_on_main_thread 排队，与 attach 天然保序。
//! 已知实测风险（任务书 §C4.5）：WebView2 透明背景/叠放 z-order/输入穿透——
//! 若实测不可行，fallback = mpv input.conf/Lua 手势，本模块整体退役。

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;

use tauri::{AppHandle, PhysicalPosition, PhysicalSize, Position, Size, WebviewUrl, WebviewWindow, WebviewWindowBuilder, Wry};

use super::win32::{self, Hwnd};

/// 控制层窗 label 前缀（capabilities/default.json 以 `mpv-controls-*` glob 放行；
/// main.rs 据此前缀在窗口销毁时回收会话——双层同生命周期）
pub(crate) const CONTROLS_WINDOW_PREFIX: &str = "mpv-controls";
/// 控制层静态页（相对 frontendDist = desktop/ui/，与 setup.html 同机制）
const CONTROL_PAGE: &str = "player-controls.html";

/// 会话代数：attach 时分配、detach 时携带，会话严格串行（单会话槽）下
/// 保证「谁建谁关、旧不误伤新」（player.rs run 的 attach/detach 调用序保底）
static GENERATION: AtomicU64 = AtomicU64::new(0);

/// 几何同步钩子：仅会话生命周期内存在（attach 挂、detach 摘）。
/// player 以 isize 存 HWND 值——裸指针非 Send，static 容器只收值类型；
/// 用时转回 Hwnd，Win32 只读几何（GetClientRect/ClientToScreen 线程安全）。
struct Hook {
    gen: u64,
    app: AppHandle<Wry>,
    overlay: WebviewWindow<Wry>,
    player: isize,
    /// 上次同步的几何（屏幕 x/y/宽/高，物理像素），去重防抖拖动期的消息洪泛
    last: (i32, i32, i32, i32),
}

static HOOK: Mutex<Option<Hook>> = Mutex::new(None);

/// 会话线程建好播放窗后调用（player.rs run）：分配本会话代数并排程主线程
/// 建控制层窗。返回代数供 run 在清理序传给 detach。失败只告警降级（无控制层
/// = mpv 键盘/OSC 仍可用，不阻断播放）。
pub(crate) fn attach(app: &AppHandle<Wry>, player: Hwnd) -> u64 {
    let gen = GENERATION.fetch_add(1, Ordering::Relaxed) + 1;
    let player_isize = player as isize;
    // 会话线程先读初始几何（此刻播放窗已在本线程建成，读取最可靠）
    let rect = win32::client_screen_rect(player);
    let app_main = app.clone();
    let scheduled = app.run_on_main_thread(move || {
        if let Err(e) = build_and_bind(&app_main, player_isize, rect, gen) {
            eprintln!("[qimeng-mpv] 控制层创建失败（降级为无控制层）：{e}");
        }
    });
    if let Err(e) = scheduled {
        eprintln!("[qimeng-mpv] 控制层排程失败（降级为无控制层）：{e}");
    }
    gen
}

/// 主线程侧：建窗 → 物理像素摆位 → owned 归属播放窗 → 显示 → 挂同步钩子。
/// 任何一步失败都回收已建窗再返回（防隐形孤儿窗永久占坑——对抗复核 2.2②）。
fn build_and_bind(app: &AppHandle<Wry>, player: isize, rect: Option<(i32, i32, i32, i32)>, gen: u64) -> Result<(), String> {
    // 槽内上一代遗留窗（其会话 detach 尚未执行/晚到）：本代代关（对抗复核 3.3）
    if let Some(mut guard) = HOOK.lock().ok() {
        if let Some(old) = guard.take() {
            let _ = old.overlay.close();
        }
    }
    let (x, y, w, h) = rect.ok_or_else(|| "读取播放窗客户区失败".to_string())?;
    let overlay = WebviewWindowBuilder::new(app, format!("{CONTROLS_WINDOW_PREFIX}-{gen}"), WebviewUrl::App(CONTROL_PAGE.into()))
        .title("播放控制")
        .decorations(false)
        .transparent(true) // 双层的关键：WebView2 透明背景露出 mpv 画面（wry 置 DefaultBackgroundColor alpha=0）
        .shadow(false) // 透明层禁 DWM 阴影：窗口矩形铺满客户区，阴影会在视频四周画出灰晕框
        .skip_taskbar(true)
        .resizable(false)
        .focused(false) // 不抢播放窗焦点：mpv 键盘快捷键（空格/方向键）保留
        .visible(false) // 先隐形完成摆位/归属再显示，防白窗闪现
        .inner_size(1.0, 1.0) // 占位值，下面按物理像素精确 set
        .build()
        .map_err(|e| format!("控制层 WebviewWindow 创建失败：{e}"))?;
    let bind = || -> Result<(), String> {
        overlay
            .set_position(Position::Physical(PhysicalPosition::new(x, y)))
            .map_err(|e| format!("控制层摆位失败：{e}"))?;
        overlay
            .set_size(Size::Physical(PhysicalSize::new(w.max(1) as u32, h.max(1) as u32)))
            .map_err(|e| format!("控制层设尺寸失败：{e}"))?;
        // SAFETY: hwnd 来自刚 build 的有效窗口；player 为本会话播放窗的有效 HWND 值
        let hwnd = overlay.hwnd().map_err(|e| format!("读取控制层 HWND 失败：{e}"))?;
        win32::set_window_owner(hwnd.0, player as *mut std::ffi::c_void);
        overlay.show().map_err(|e| format!("控制层显示失败：{e}"))?;
        Ok(())
    };
    if let Err(e) = bind() {
        let _ = overlay.close();
        return Err(e);
    }

    let mut guard = HOOK.lock().map_err(|_| "overlay HOOK 锁中毒".to_string())?;
    *guard = Some(Hook { gen, app: app.clone(), overlay, player, last: (x, y, w, h) });
    Ok(())
}

/// 播放窗几何变化（win32::wnd_proc WM_WINDOWPOSCHANGED，会话线程）→ 主线程摆控制层。
/// 事件在拖动/缩放期间高频到达：last 去重后每次变化一次主线程往返。
/// FFI 边界禁 unwind：锁中毒/读取失败一律静默跳过，绝不 panic。
pub(crate) fn sync_geometry() {
    let mut guard = match HOOK.lock() {
        Ok(g) => g,
        Err(_) => return,
    };
    let Some(hook) = guard.as_mut() else { return };
    // SAFETY: player 为 attach 时记录的播放窗 HWND 值，读取几何线程安全
    let player = hook.player as *mut std::ffi::c_void;
    let Some(rect) = win32::client_screen_rect(player) else { return };
    if rect == hook.last {
        return;
    }
    hook.last = rect;
    let app = hook.app.clone();
    let overlay = hook.overlay.clone();
    drop(guard);
    let (x, y, w, h) = rect;
    let scheduled = app.run_on_main_thread(move || {
        let _ = overlay.set_position(Position::Physical(PhysicalPosition::new(x, y)));
        let _ = overlay.set_size(Size::Physical(PhysicalSize::new(w.max(1) as u32, h.max(1) as u32)));
    });
    if let Err(e) = scheduled {
        eprintln!("[qimeng-mpv] 控制层几何同步排程失败：{e}");
    }
}

/// 会话收尾调用（player.rs run 清理序）：按代数摘钩子并关控制层窗。
/// 排队执行保证晚于同会话 attach；gen 失配（本代窗已被新会话 attach 代关）
/// 直接跳过，幂等。
pub(crate) fn detach(gen: u64) {
    let app = match HOOK.lock() {
        Ok(guard) => match guard.as_ref() {
            Some(hook) if hook.gen == gen => hook.app.clone(),
            _ => return, // 无钩子或已是新一代的钩子（其 attach 负责关旧窗）
        },
        Err(_) => return,
    };
    let scheduled = app.run_on_main_thread(move || {
        let Ok(mut guard) = HOOK.lock() else { return };
        if guard.as_ref().map(|h| h.gen) == Some(gen) {
            if let Some(hook) = guard.take() {
                let _ = hook.overlay.close();
            }
        }
    });
    if let Err(e) = scheduled {
        eprintln!("[qimeng-mpv] 控制层关闭排程失败：{e}");
    }
}
