//! 壳侧诊断落盘（2026-10-06 闪退排查增设，保留为常驻诊断设施）。
//!
//! 背景：release 版 windows_subsystem=windows 无控制台，且 2026-10-06 走查
//! 闪退时进程"干净退场"——无 Rust panic（qimeng-panic.log 空）、无 WER/事件
//! 1000/转储，系统侧零痕迹，只能靠壳自留生命周期痕迹定位。qimeng-shell.log
//! 追加写 exe 旁（target/ 内，不入库），每行独立 open-append：进程任意时刻
//! 死掉不丢已写行；写失败静默（诊断绝不反向影响功能）。

use std::io::Write;
use std::time::{SystemTime, UNIX_EPOCH};

/// 记一条诊断痕迹（毫秒时间戳 + 线程名 + 消息）。
pub(crate) fn trace(msg: &str) {
    let secs = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis())
        .unwrap_or(0);
    let thread = std::thread::current().name().unwrap_or("<unnamed>").to_string();
    let line = format!("[{secs}] [{thread}] {msg}\r\n");
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            if let Ok(mut f) = std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .write(true)
                .open(dir.join("qimeng-shell.log"))
            {
                let _ = f.write_all(line.as_bytes());
            }
        }
    }
}
