//! 绮梦影库桌面壳（Tauri 2 连接模式）。
//!
//! 职责边界：壳只做「记住服务器地址 → 开窗加载 → 托盘 → 导航守卫」，
//! 全部业务 UI 由 Go 服务端（默认 :8420）托管的 Web UI 提供 —— B 站桌面客户端同款形态。
//!
//! 窗口不在 tauri.conf.json 里静态声明，而是启动时按「有无配置 / --setup 参数」动态创建：
//! 首次启动（无配置）→ 设置窗；有配置 → 主窗口。切换地址通过受管状态 + `navigate`
//! 原地换页（守卫实时读状态，无需销毁重建窗口，避开窗口 label 释放时序问题）。

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")] // release 版不弹控制台

mod mpv;
mod server_config;

use server_config::{load_config, navigation_allowed, normalize_server_url, save_config, ServerConfig};
use std::sync::Mutex;
use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Manager, State, WebviewUrl, WebviewWindowBuilder, WindowEvent};

/// 品牌名：复用 android/app/src/main/res/values/strings.xml 的 app_name（单一来源原则）
const APP_TITLE: &str = "绮梦影库";
/// 设置窗静态页（相对 frontendDist = desktop/ui/）
const SETUP_PAGE: &str = "setup.html";
const SETUP_WINDOW_LABEL: &str = "setup";
const MAIN_WINDOW_LABEL: &str = "main";
/// 设置窗尺寸（产品需求 420×340）
const SETUP_WINDOW_WIDTH: f64 = 420.0;
const SETUP_WINDOW_HEIGHT: f64 = 340.0;
/// 主窗口尺寸（产品需求 1200×800，最小 900×600）
const MAIN_WINDOW_WIDTH: f64 = 1200.0;
const MAIN_WINDOW_HEIGHT: f64 = 800.0;
const MAIN_WINDOW_MIN_WIDTH: f64 = 900.0;
const MAIN_WINDOW_MIN_HEIGHT: f64 = 600.0;
/// 改地址的兜底入口：`绮梦影库 --setup` 直接进设置窗
const SETUP_FLAG: &str = "--setup";
/// 主窗口无边框注入脚本（B 站同款：去系统边框，Web UI 顶栏即标题栏，见 titlebar.js 头注）
const TITLEBAR_INJECT: &str = include_str!("titlebar.js");
const TRAY_ID_OPEN_MAIN: &str = "tray-open-main";
const TRAY_ID_CHANGE_SERVER: &str = "tray-change-server";
const TRAY_ID_QUIT: &str = "tray-quit";

/// 当前生效的服务器地址。None = 尚未配置。
/// 导航守卫每次导航实时读它（而非建窗时烤死），这样"切换服务器地址"只需 navigate 现有窗口。
struct ServerState(Mutex<Option<tauri::Url>>);

/// 守卫判定（读受管状态）：无配置时只放行 about:blank（此时根本不该开主窗口，防御性兜底）
fn navigation_allowed_by_state(app: &AppHandle, target: &tauri::Url) -> bool {
    let state = app.state::<ServerState>();
    let current = state.0.lock().expect("ServerState 锁中毒");
    match current.as_ref() {
        Some(server) => navigation_allowed(server.host_str().unwrap_or_default(), target),
        None => target.as_str() == "about:blank",
    }
}

/// 打开（或聚焦）设置窗
fn open_setup_window(app: &AppHandle) -> Result<(), String> {
    if let Some(existing) = app.get_webview_window(SETUP_WINDOW_LABEL) {
        let _ = existing.unminimize();
        let _ = existing.show();
        let _ = existing.set_focus();
        return Ok(());
    }
    WebviewWindowBuilder::new(app, SETUP_WINDOW_LABEL, WebviewUrl::App(SETUP_PAGE.into()))
        .title(format!("连接服务器 · {APP_TITLE}"))
        .inner_size(SETUP_WINDOW_WIDTH, SETUP_WINDOW_HEIGHT)
        .resizable(false)
        .build()
        .map_err(|e| format!("打开设置窗口失败：{e}"))?;
    Ok(())
}

/// 打开主窗口（已有则原地 navigate 到新地址并聚焦）
fn open_main_window(app: &AppHandle, server: &tauri::Url) -> Result<(), String> {
    if let Some(existing) = app.get_webview_window(MAIN_WINDOW_LABEL) {
        existing
            .navigate(server.clone())
            .map_err(|e| format!("跳转新服务器地址失败：{e}"))?;
        let _ = existing.unminimize();
        let _ = existing.show();
        let _ = existing.set_focus();
        return Ok(());
    }
    let app_for_guard = app.clone();
    WebviewWindowBuilder::new(app, MAIN_WINDOW_LABEL, WebviewUrl::External(server.clone()))
        .title(APP_TITLE)
        // B 站同款无边框：窗口行为（拖动/三按钮）由 TITLEBAR_INJECT 注入给 Web UI 既有件，
        // 页面加载失败时托盘菜单仍是退出兜底
        .decorations(false)
        .initialization_script(TITLEBAR_INJECT)
        .inner_size(MAIN_WINDOW_WIDTH, MAIN_WINDOW_HEIGHT)
        .min_inner_size(MAIN_WINDOW_MIN_WIDTH, MAIN_WINDOW_MIN_HEIGHT)
        .on_navigation(move |target| navigation_allowed_by_state(&app_for_guard, target))
        .build()
        .map_err(|e| format!("打开主窗口失败：{e}"))?;
    Ok(())
}

/// 托盘左键 / 「打开主窗口」共用逻辑：有配置开主窗，没配置进设置窗
fn open_main_or_setup(app: &AppHandle) -> Result<(), String> {
    let state = app.state::<ServerState>();
    let current = state.0.lock().expect("ServerState 锁中毒").clone();
    match current {
        Some(server) => open_main_window(app, &server),
        None => open_setup_window(app),
    }
}

/// 托盘：尽力实现，失败仅告警降级为无托盘（设置窗 --setup 仍是改地址兜底入口）
fn setup_tray(app: &tauri::App) -> Result<(), tauri::Error> {
    let open_main_item = MenuItem::with_id(app, TRAY_ID_OPEN_MAIN, "打开主窗口", true, None::<&str>)?;
    let change_server_item =
        MenuItem::with_id(app, TRAY_ID_CHANGE_SERVER, "切换服务器地址", true, None::<&str>)?;
    let quit_item = MenuItem::with_id(app, TRAY_ID_QUIT, "退出", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&open_main_item, &change_server_item, &quit_item])?;

    let mut builder = TrayIconBuilder::new()
        .menu(&menu)
        // 左键留给"打开/聚焦主窗口"（B 站同款），菜单走右键
        .show_menu_on_left_click(false)
        .tooltip(APP_TITLE);
    // default_window_icon 来自 bundle.icon（构建期经 tauri icon 生成），拿不到就不设图标
    if let Some(icon) = app.default_window_icon().cloned() {
        builder = builder.icon(icon);
    }
    builder
        .on_menu_event(|app, event| match event.id.as_ref() {
            TRAY_ID_OPEN_MAIN => {
                let _ = open_main_or_setup(app);
            }
            TRAY_ID_CHANGE_SERVER => {
                let _ = open_setup_window(app);
            }
            TRAY_ID_QUIT => app.exit(0),
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            } = event
            {
                let _ = open_main_or_setup(tray.app_handle());
            }
        })
        .build(app)?;
    Ok(())
}

/// 设置窗回显当前地址（没有配置返回 null，前端保持 placeholder）
#[tauri::command]
fn get_server_url(app: AppHandle) -> Option<String> {
    let dir = app.path().app_config_dir().ok()?;
    load_config(&dir).map(|cfg| cfg.server_url)
}

/// 设置窗「保存并连接」：规范化 → 落盘 → 更新受管状态 → 开/跳主窗口 → 关设置窗。
/// 顺序刻意先开主窗再关设置窗，避免出现"全部窗口已销毁"的中间态。
#[tauri::command]
fn save_server_url(
    app: AppHandle,
    state: State<'_, ServerState>,
    url: String,
) -> Result<(), String> {
    let normalized = normalize_server_url(&url)?;
    let dir = app
        .path()
        .app_config_dir()
        .map_err(|e| format!("无法定位配置目录：{e}"))?;
    save_config(&dir, &ServerConfig { server_url: normalized.clone() })?;

    let server: tauri::Url = normalized
        .parse()
        .map_err(|e| format!("规范化地址回读失败：{e}"))?;
    *state.0.lock().expect("ServerState 锁中毒") = Some(server.clone());
    open_main_window(&app, &server)?;

    if let Some(setup) = app.get_webview_window(SETUP_WINDOW_LABEL) {
        let _ = setup.close();
    }
    Ok(())
}

fn main() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![
            get_server_url,
            save_server_url,
            mpv::commands::mpv_open,
            mpv::commands::mpv_status,
            mpv::commands::mpv_overlay_info,
            mpv::commands::mpv_control,
            mpv::commands::mpv_close
        ])
        // 原生播放内核会话槽（ADR-0036）：播放窗是 user32 自建窗口，不经
        // Tauri 窗口系统，故 on_window_event 无需感知 mpv-player
        .manage(mpv::commands::MpvState::default())
        .on_window_event(|window, event| {
            // 控制层窗销毁（用户 Alt-F4 关控制层）→ 会话一并回收（C4.5 双层
            // 同生命周期的反向路径：正向回收在 player.rs run 清理序里）。
            // 注意此处不可动「关主窗=退出」判定语义（MPV_MIGRATION 坑 6 勿修复）
            if let WindowEvent::Destroyed = event {
                let app = window.app_handle();
                // 控制层窗销毁（用户 Alt-F4 关控制层）→ 会话一并回收（C4.5 双层
                // 同生命周期的反向路径：正向回收在 player.rs run 清理序里）。
                // label 带会话代数后缀（mpv-controls-N），按前缀识别。
                // 注意此处不可动「关主窗=退出」判定语义（MPV_MIGRATION 坑 6 勿修复）
                if window.label().starts_with(mpv::overlay::CONTROLS_WINDOW_PREFIX) {
                    mpv::commands::close_session(app.state::<mpv::commands::MpvState>().inner());
                }
                // 产品需求：关闭主窗口 = 退出应用（无托盘常驻）。
                // 实现为"任一窗口销毁后若再无窗口则整体退出"，对用户关闭任意最后一个窗口都成立，
                // 也兼容 macOS 默认不随最后一窗退出行为的差异。
                if app.get_webview_window(MAIN_WINDOW_LABEL).is_none()
                    && app.get_webview_window(SETUP_WINDOW_LABEL).is_none()
                {
                    app.exit(0);
                }
            }
        })
        .setup(|app| {
            // 配置里的地址即使被手工改坏也安全退化：parse 失败按未配置走设置窗
            let dir = app.path().app_config_dir()?;
            let initial = load_config(&dir).and_then(|cfg| cfg.server_url.parse().ok());
            // clone：initial 稍后还要在本闭包里判断走主窗还是设置窗，Mutex 入库会移走所有权
            app.manage(ServerState(Mutex::new(initial.clone())));

            if let Err(e) = setup_tray(app) {
                eprintln!("[qimeng-desktop] 托盘初始化失败，降级为无托盘运行：{e}");
            }

            // App 无 Deref<AppHandle> 实现，窗口辅助函数统一收 &AppHandle，经 handle() 转换
            let handle = app.handle();
            let force_setup = std::env::args().any(|arg| arg == SETUP_FLAG);
            if force_setup {
                open_setup_window(handle)?;
            } else if let Some(server) = initial {
                open_main_window(handle, &server)?;
            } else {
                open_setup_window(handle)?;
            }
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("绮梦影库桌面壳启动失败");
}
