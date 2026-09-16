//! 服务器地址配置与规范化。
//!
//! 设计约束（项目纪律）：
//! - `normalize_server_url` / `navigation_allowed` 是纯函数（不碰 IO），行为由单元测试锁定；
//! - 配置文件放 Tauri 标准 app_config_dir（`config_dir/media.qimeng.desktop/server.json`），
//!   本模块只接收调用方传入的目录，不感知 Tauri 类型，便于单测与复用。

use serde::{Deserialize, Serialize};
use std::fs;
use std::path::Path;

/// 配置文件名（app_config_dir 下）
pub const CONFIG_FILE_NAME: &str = "server.json";

/// 服务器配置。字段故意只有一个：壳的职责只有"记住并加载这个地址"。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ServerConfig {
    pub server_url: String,
}

/// 规范化用户输入的服务器地址，返回可直接加载的规范形态（`scheme://host[:port]`，无路径无 query）。
///
/// 规则：trim 空白 → 无 scheme 补 `http://` → 校验 host 形态（禁路径、禁 query、禁 fragment、
/// 禁用户凭据）→ 去掉一切多余部分输出规范串。
/// 关于非 http/https 协议（如 `ftp://`）：**拒绝**。本壳只连 Go 服务端的 Web UI，
/// WebView 也不可能把 ftp 当作服务器地址正常加载，及早报错优于静默放行一个必然连不上的地址。
pub fn normalize_server_url(raw: &str) -> Result<String, String> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return Err("服务器地址不能为空".to_string());
    }

    // 先判 scheme 再补默认值：必须在对 Url::parse 之前做。
    // 否则 `192.168.1.8:8420` 会被 parse 把 `192.168.1.8` 当成 scheme 而解析失败。
    let with_scheme = match trimmed.split_once("://") {
        Some((scheme, _)) => {
            let scheme = scheme.to_ascii_lowercase();
            if scheme != "http" && scheme != "https" {
                return Err(format!("仅支持 http/https，不支持的协议：{scheme}"));
            }
            trimmed.to_string()
        }
        None => format!("http://{trimmed}"),
    };

    // tauri::Url 是 url crate 的公开 re-export，直接复用避免重复声明依赖
    let url = tauri::Url::parse(&with_scheme).map_err(|e| format!("地址无法解析：{e}"))?;

    if url.scheme() != "http" && url.scheme() != "https" {
        return Err(format!("仅支持 http/https，不支持的协议：{}", url.scheme()));
    }
    let host = url
        .host_str()
        .filter(|h| !h.is_empty())
        .ok_or_else(|| "地址缺少主机名（如 192.168.1.8:8420）".to_string())?;
    if !url.username().is_empty() || url.password().is_some() {
        return Err("地址里不要带用户名密码".to_string());
    }
    if !matches!(url.path(), "" | "/") {
        return Err("地址只填到主机（或主机:端口），不要带路径".to_string());
    }
    if url.query().is_some() {
        return Err("地址不要带 query 参数".to_string());
    }
    if url.fragment().is_some() {
        return Err("地址不要带 # 片段".to_string());
    }

    // 手工拼规范串而非用 url.as_str()：彻底丢掉路径/credentials 等残留，
    // 等价于"去结尾 /"；host_str 对 IPv6 自带 [] 包裹，可直接拼接。
    let mut out = format!("{}://{}", url.scheme(), host);
    if let Some(port) = url.port() {
        out.push_str(&format!(":{port}"));
    }
    Ok(out)
}

/// 导航守卫（纯函数）：主窗口只允许访问配置主机（http/https 同主机放行）。
///
/// - 按主机名（不含端口）比较：产品要求"同主机 http/https 放行"，
///   服务端改端口/重定向到 8443 之类不应把壳锁死。
/// - 仅放行 about:blank：WebView 初始化/刷新会经过它，拦掉会导致首屏加载失败；
///   其它 about: 变体没有业务场景，一并拒绝。
/// - 其余一切（外部主机、javascript:/data:/file: 等危险 scheme）一律拒绝。
pub fn navigation_allowed(server_host: &str, target: &tauri::Url) -> bool {
    if target.as_str() == "about:blank" {
        return true;
    }
    if target.scheme() != "http" && target.scheme() != "https" {
        return false;
    }
    match target.host_str() {
        Some(h) => h.eq_ignore_ascii_case(server_host),
        None => false,
    }
}

/// 读取配置。文件不存在 / JSON 损坏 / 地址非法 一律返回 None，
/// 由调用方走"首次启动"流程（手工改坏的配置自然退化成重新设置，不再单独报错）。
pub fn load_config(dir: &Path) -> Option<ServerConfig> {
    let text = fs::read_to_string(dir.join(CONFIG_FILE_NAME)).ok()?;
    let cfg: ServerConfig = serde_json::from_str(&text).ok()?;
    normalize_server_url(&cfg.server_url).ok()?;
    Some(cfg)
}

/// 保存配置（目录不存在则创建）。错误信息面向设置窗直接展示，用中文。
pub fn save_config(dir: &Path, cfg: &ServerConfig) -> Result<(), String> {
    fs::create_dir_all(dir).map_err(|e| format!("无法创建配置目录：{e}"))?;
    let text = serde_json::to_string_pretty(cfg).map_err(|e| format!("配置序列化失败：{e}"))?;
    fs::write(dir.join(CONFIG_FILE_NAME), text).map_err(|e| format!("配置写入失败：{e}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ok(input: &str) -> String {
        normalize_server_url(input).expect("应为合法地址")
    }

    fn err(input: &str) -> String {
        normalize_server_url(input).expect_err("应为非法地址")
    }

    #[test]
    fn 无scheme自动补http() {
        assert_eq!(ok("192.168.1.8:8420"), "http://192.168.1.8:8420");
        assert_eq!(ok("127.0.0.1:8420"), "http://127.0.0.1:8420");
    }

    #[test]
    fn trim首尾空白() {
        assert_eq!(ok("  192.168.1.8:8420\t\n"), "http://192.168.1.8:8420");
    }

    #[test]
    fn 去结尾斜杠与已有scheme保持() {
        assert_eq!(ok("http://192.168.1.8:8420/"), "http://192.168.1.8:8420");
        assert_eq!(ok("https://nas.local:8420"), "https://nas.local:8420");
        // 省略端口（交给 scheme 默认端口）也应放行
        assert_eq!(ok("https://nas.local"), "https://nas.local");
    }

    #[test]
    fn host与scheme大小写归一() {
        // url crate 会把 host 与 scheme 小写化
        assert_eq!(ok("HTTP://NAS.Local:8420"), "http://nas.local:8420");
    }

    #[test]
    fn ipv6形式可解析() {
        assert_eq!(ok("[::1]:8420"), "http://[::1]:8420");
        assert_eq!(ok("http://[::1]:8420/"), "http://[::1]:8420");
    }

    #[test]
    fn 空串非法() {
        assert!(!err("").is_empty());
        assert!(!err("   ").is_empty());
    }

    #[test]
    fn 带路径非法() {
        let msg = err("http://x/y");
        assert!(msg.contains("路径"), "应提示路径问题：{msg}");
        assert!(!err("http://192.168.1.8:8420/ui/").is_empty());
    }

    #[test]
    fn 带query或fragment非法() {
        assert!(!err("http://x?a=1").is_empty());
        assert!(!err("http://x#frag").is_empty());
    }

    #[test]
    fn 带凭据非法() {
        assert!(!err("http://user:pass@192.168.1.8:8420").is_empty());
    }

    #[test]
    fn 只有scheme无host非法() {
        assert!(!err("http://").is_empty());
    }

    #[test]
    fn 非http协议拒绝() {
        // ftp 拒绝的理由见 normalize_server_url 文档注释
        let msg = err("ftp://x");
        assert!(msg.contains("http"), "应提示仅支持 http/https：{msg}");
        assert!(!err("file:///etc/passwd").is_empty());
    }

    #[test]
    fn 端口越界非法() {
        assert!(!err("192.168.1.8:99999").is_empty());
    }

    // ---------- navigation_allowed ----------

    fn url(s: &str) -> tauri::Url {
        tauri::Url::parse(s).expect("测试 URL 应合法")
    }

    #[test]
    fn 同主机http与https都放行() {
        assert!(navigation_allowed("192.168.1.8", &url("http://192.168.1.8:8420/")));
        assert!(navigation_allowed(
            "192.168.1.8",
            &url("https://192.168.1.8:8443/login")
        ));
        // 同主机不同端口放行（守卫按主机比较，理由见函数文档）
        assert!(navigation_allowed("nas.local", &url("http://nas.local:9000/x")));
    }

    #[test]
    fn 其它主机拦截() {
        assert!(!navigation_allowed("192.168.1.8", &url("http://evil.example.com/phish")));
        assert!(!navigation_allowed("nas.local", &url("http://127.0.0.1:8420/")));
    }

    #[test]
    fn 非http协议拦截_但about_blank放行() {
        assert!(navigation_allowed("192.168.1.8", &url("about:blank")));
        assert!(!navigation_allowed("192.168.1.8", &url("about:config")));
        assert!(!navigation_allowed("192.168.1.8", &url("ftp://192.168.1.8/x")));
        assert!(!navigation_allowed("192.168.1.8", &url("file:///C:/x.html")));
        assert!(!navigation_allowed(
            "192.168.1.8",
            &url("javascript:alert(1)")
        ));
    }

    #[test]
    fn host比较忽略大小写() {
        assert!(navigation_allowed("NAS.local", &url("http://nas.local:8420/")));
    }

    // ---------- 配置读写 ----------

    #[test]
    fn 配置roundtrip() {
        let dir = std::env::temp_dir().join(format!("qm_desktop_test_{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        // 无配置 → None
        assert!(load_config(&dir).is_none());
        // 保存后可读回
        save_config(&dir, &ServerConfig {
            server_url: "http://192.168.1.8:8420".to_string(),
        })
        .expect("保存应成功");
        assert_eq!(
            load_config(&dir),
            Some(ServerConfig {
                server_url: "http://192.168.1.8:8420".to_string(),
            })
        );
        // 坏 JSON / 坏地址 → None（走首次启动兜底）
        fs::write(dir.join(CONFIG_FILE_NAME), "{oops").unwrap();
        assert!(load_config(&dir).is_none());
        fs::write(dir.join(CONFIG_FILE_NAME), r#"{"server_url":"ftp://x"}"#).unwrap();
        assert!(load_config(&dir).is_none());
        let _ = fs::remove_dir_all(&dir);
    }
}
