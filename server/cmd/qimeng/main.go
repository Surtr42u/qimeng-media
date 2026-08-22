// qimeng 是绮梦媒体库服务端入口。
//
// M0 阶段只做最小可运行闭环：配置加载 → JSON 日志 → /healthz → 优雅退出。
// 业务模块（scanner/thumbnail/store 等）在后续里程碑装配进来，
// 装配位置固定在 main：依赖注入只在入口发生，业务包之间不互相 new。
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/go-chi/chi/v5"

	"qimeng-media/server/internal/config"
	"qimeng-media/server/internal/httpapi"
)

// shutdownTimeout 是优雅退出的最长等待时间。
// 为什么定 10s：覆盖慢客户端把响应读完 + 在途缩略图任务让出，
// 同时给容器编排（默认 30s 强杀）留出余量。
const shutdownTimeout = 10 * time.Second

func main() {
	// 临时 logger 兜底启动早期错误：真正的 JSON logger 要等配置加载完才能建，
	// 在此之前出错也得有地方可看（stderr 直写）。
	bootLogger := slog.New(slog.NewTextHandler(os.Stderr, nil))

	configPath := flag.String("config", "config.yaml", "配置文件路径（yaml，可不存在；环境变量始终优先于文件）")
	flag.Parse()

	cfg, err := config.Load(*configPath)
	if err != nil {
		bootLogger.Error("加载配置失败", "error", err, "configPath", *configPath)
		os.Exit(1)
	}

	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{
		Level: cfg.SlogLevel(),
	}))
	slog.SetDefault(logger)
	r := chi.NewRouter()
	// healthz 处理函数属于接口层（httpapi），main 只做装配挂载——
	// 后续 oapi-codegen 生成的路由也从 httpapi 接入，依赖方向保持 httpapi 在最外层。
	r.Get("/healthz", httpapi.Healthz)

	srv := &http.Server{
		Addr: cfg.Listen,
		Handler: r,
		// 读超时只限 header：媒体直链依赖长连接与大响应体，
		// 不能设全局 ReadTimeout/WriteTimeout 一刀切掐掉大文件传输。
		ReadHeaderTimeout: 10 * time.Second,
	}

	// signal.NotifyContext：Ctrl+C / SIGTERM 时 ctx 取消，主流程进入优雅关闭。
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	// errCh 把 goroutine 里的监听错误传回主流程——不允许 err 悄悄丢失。
	errCh := make(chan error, 1)
	go func() {
		logger.Info("HTTP 服务启动", "listen", cfg.Listen)
		// http.ErrServerClosed 是 Shutdown 的正常返回，不算故障。
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errCh <- fmt.Errorf("HTTP 服务异常退出: %w", err)
		}
	}()

	select {
	case err := <-errCh:
		logger.Error("服务启动失败", "error", err)
		os.Exit(1)
	case <-ctx.Done():
		logger.Info("收到退出信号，开始优雅关闭")
	}

	shutdownCtx, cancel := context.WithTimeout(context.Background(), shutdownTimeout)
	defer cancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		logger.Error("优雅关闭超时或失败", "error", err)
		os.Exit(1)
	}
	logger.Info("已完全退出")
}
