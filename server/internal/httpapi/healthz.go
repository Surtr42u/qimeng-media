package httpapi

import (
	"log/slog"
	"net/http"
)

// Healthz 是存活探针（GET /healthz），供 Docker healthcheck 与监控系统探活。
//
// 为什么刻意不检查任何依赖（数据库/媒体目录）：存活探针只回答"进程还在吗"，
// 依赖健康度属于将来的 readyz 端点——两者混在一起会让容器编排
// 在服务慢启动（等依赖就绪）时误判崩溃并反复重启。
func Healthz(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	// 响应体写失败（客户端已断开等）无更优处理，记 warn 留痕即可，
	// 不向上抛——探针响应失败对调用方而言就是"不健康"，信息已经传达。
	if _, err := w.Write([]byte(`{"status":"alive"}`)); err != nil {
		slog.Warn("写 healthz 响应失败", "error", err)
	}
}
