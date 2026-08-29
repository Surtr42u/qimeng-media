// spa.go：Web SPA 构建产物（web/dist）静态托管。
// 为什么需要它：M2 起服务端随附托管前端构建产物（单端口部署），
// 产物缺失/未构建时回退内嵌验收页（M1 行为，见 page.go），服务不挂。
// 静态文件防路径穿越：目录用 os.Root 锚定（与 trash.go 的防 TOCTOU
// 同一标准库底座），os.Root 原生拒绝绝对路径与 ".." 段（Go 1.24+）。
package httpapi

import (
	"errors"
	"io/fs"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// spaAssetCacheControl 带内容哈希的构建产物（/assets/**、/icons/**）长缓存。
// 为什么 immutable 语义成立：vite 构建输出 hash 文件名，文件名即内容身份
// （"协议侧/约定"——这是 web 构建的约定，不是服务端与前端之间的协议，
// 前端构建改名 = 换 URL，永远不会请求到旧文件；与 media.go 的
// thumbCacheControl 常数风格一致）。
const spaAssetCacheControl = "public, max-age=31536000, immutable"

// spaIndexCacheControl 入口 HTML 不缓存：index.html 无 hash，构建后内容变化
// 必须立即可见（短缓存会推迟发布；no-cache 是构建类静态站点的标准做法）。
const spaIndexCacheControl = "no-cache"

// spaHandler 服务 web/dist 构建产物：命中文件直发，未命中的非文件路径
// 回退 index.html（SPA 前端路由）。
type spaHandler struct {
	root   *os.Root
	dist   string
	logger *slog.Logger
}

// newSPAHandler 把 staticDir 包装成 os.Root 并构造 handler。
// staticDir 相对或绝对路径均可（相对 server 启动工作目录——默认
// "../web/dist" 即依赖这一约定，见 config.WebConfig 注释）。
func newSPAHandler(staticDir string, logger *slog.Logger) (*spaHandler, error) {
	root, err := os.OpenRoot(staticDir)
	if err != nil {
		return nil, err
	}
	if logger == nil {
		logger = slog.Default()
	}
	return &spaHandler{root: root, dist: staticDir, logger: logger}, nil
}

// spaReady 探测静态目录是否可用于 SPA 托管：目录存在且 index.html 可读。
// 探测失败不算错误——调用方回退内嵌验收页（M1 行为不变，本机未构建
// web/dist 是常态；服务必须能带一份缺失产物正常启动）。
func spaReady(staticDir string) bool {
	if staticDir == "" {
		return false // 空配置 = 显式禁用 SPA 托管
	}
	info, err := os.Stat(filepath.Join(staticDir, "index.html"))
	return err == nil && !info.IsDir()
}

// ServeHTTP 一次请求的四条判定路径：
//  1. 文件存在（非目录）→ 直发（内容寻址缓存头见 serveFile）；
//  2. 路径命中目录（如 /assets/）→ 走回退；
//  3. 文件不存在但带扩展名（/assets/xxx.js 已被构建改名）→ 404：
//     回退 index.html 会让浏览器拿 HTML 当 JS/CSS 解析，报错反而难排查；
//  4. 其余（前端路由 /library/123）→ 回退 index.html。
//
// 文件系统错误（权限/IO）在打开文件失败时也走回退：日志留痕，页面可用性优先。
func (h *spaHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	rel := strings.TrimPrefix(r.URL.Path, "/")
	if rel == "" {
		rel = "index.html"
	}
	f, err := h.root.Open(rel)
	if err == nil {
		info, statErr := f.Stat()
		if statErr != nil || info.IsDir() {
			// 目录：关闭句柄，按"未命中"走回退（/assets/ 不带文件名的
			// 请求不会发生在真实页面里，回退兜底即可）。
			_ = f.Close()
		} else {
			defer func() { _ = f.Close() }()
			h.serveFile(w, r, rel, f)
			return
		}
	} else if !errors.Is(err, fs.ErrNotExist) {
		// 文件系统错误（权限/IO/目录被换）：不用 500 拒绝用户——
		// 回退 index.html 保证页面可用；错误留痕便于排查（文件被删/
		// 权限漂移这类"构建产物被破坏"场景日志里要有线索）。
		h.logger.Warn("SPA 静态文件打开失败，回退 index.html", "path", rel, "err", err)
	}
	// 回退条件：非 /api/ 前缀（API 404 必须保持 404，不能让前端路由吞掉
	// 拼错的接口地址——那是客户端 bug 的遮羞布）且非文件路径（带扩展名
	// 的未命中 = 文件确实不存在，404 语义）。
	if strings.HasPrefix(r.URL.Path, "/api/") || filepath.Ext(r.URL.Path) != "" {
		http.NotFound(w, r)
		return
	}
	h.serveIndexFallback(w, r)
}

// serveFile 按内容类型设置缓存头后直发文件。
// 缓存策略（web 构建约定）：/assets/**、/icons/** = hash 文件名 → immutable
// 长缓存；HTML 与其他文件 → no-cache（短缓存/不缓存）。
func (h *spaHandler) serveFile(w http.ResponseWriter, r *http.Request, rel string, f *os.File) {
	w.Header().Set("Cache-Control", spaCacheControl(rel))
	// modTime 参与 ServeContent 的 If-Modified-Since 条件请求；
	// 文件名带扩展名喂给 ServeContent 做 Content-Type 推断（js/css/svg/...）。
	info, statErr := f.Stat()
	var mod time.Time
	if statErr == nil {
		mod = info.ModTime()
	}
	http.ServeContent(w, r, filepath.Base(rel), mod, f)
}

// spaCacheControl 判定单文件缓存头（见 serveFile 注释）。
func spaCacheControl(rel string) string {
	if strings.HasPrefix(rel, "assets/") || strings.HasPrefix(rel, "icons/") {
		return spaAssetCacheControl
	}
	return spaIndexCacheControl
}

// serveIndexFallback 回退路径：优先发 dist/index.html（前端路由入口页），
// 读取失败（运行期 dist 被删/权限漂移）时日志留痕并落到内嵌验收页——
// 多一层兜底保证根路径永远有页面返回（页面可用性优先于页面内容）。
func (h *spaHandler) serveIndexFallback(w http.ResponseWriter, r *http.Request) {
	f, err := h.root.Open("index.html")
	if err == nil {
		defer func() { _ = f.Close() }()
		w.Header().Set("Cache-Control", spaIndexCacheControl)
		http.ServeContent(w, r, "index.html", time.Time{}, f)
		return
	}
	if !errors.Is(err, fs.ErrNotExist) {
		h.logger.Warn("SPA 回退页读取失败，使用内嵌验收页", "err", err)
	}
	serveIndex(w, r)
}
