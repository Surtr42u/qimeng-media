package httpapi

import (
	"embed"
	"net/http"
)

// static 内嵌极简验收页（M1 验收工具，永久保留作调试页）。
// 无框架纯 HTML + 原生 JS + 少量内联 CSS；嵌入二进制让单文件部署
// 也带得上它。
//
//go:embed static/index.html
var staticFS embed.FS

// serveIndex 输出验收页（GET /，免鉴权——纯静态壳子，页面内的数据
// 请求照常走 Bearer）。
func serveIndex(w http.ResponseWriter, r *http.Request) {
	b, err := staticFS.ReadFile("static/index.html")
	if err != nil {
		// embed 静态文件在编译期锁定，读取失败属程序错误；给最简兜底
		// 而不是 500 堆栈（页面本身不承载业务）。
		http.Error(w, "index unavailable", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")
	// 写失败只可能发生在客户端已断开时，静态页无重试语义，无补救动作，
	// 忽略即可（net/http 会关连接）。
	_, _ = w.Write(b)
}
