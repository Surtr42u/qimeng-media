// wire.go 是组装层的扫描适配器：把 internal/scanner 的同步扫描实现
// 适配到 httpapi.Scanner 的"触发即返回"契约上。
//
// 为什么需要适配层而不是让 scanner 直接实现接口：scanner.Scan 是同步
// 阻塞语义（大库几十秒），而扫描端点契约是 202 立即返回、进度走 SSE。
// 两者的语义差（同步/异步、db.Library/libraryID）收窄在这一处消化，
// 两边的包互不感知（依赖倒置，见 httpapi.Scanner 注释）。
package main

import (
	"context"
	"log/slog"
	"sync"

	"qimeng-media/server/internal/httpapi"
	"qimeng-media/server/internal/scanner"
	"qimeng-media/server/internal/store/db"
)

// scannerAdapter 实现 httpapi.Scanner。
type scannerAdapter struct {
	sc  *scanner.Scanner
	q   *db.Queries
	srv *httpapi.Server // FinishScan 终态回调
	log *slog.Logger

	// running 防止同一库并发触发（CAS 集合）。scanner 内部也有闸门，
	// 但那是阻塞语义的——异步触发路径需要在 goroutine 启动前就给出
	// "进行中"的明确答案，否则客户端会收到 202 却没有任何扫描发生。
	mu      sync.Mutex
	running map[string]struct{}
}

func newScannerAdapter(sc *scanner.Scanner, q *db.Queries, srv *httpapi.Server, log *slog.Logger) *scannerAdapter {
	return &scannerAdapter{sc: sc, q: q, srv: srv, log: log, running: make(map[string]struct{})}
}

// Scan 触发一次异步全量扫描，立即返回。
func (a *scannerAdapter) Scan(_ context.Context, libraryID string) error {
	a.mu.Lock()
	if _, busy := a.running[libraryID]; busy {
		a.mu.Unlock()
		return httpapi.ErrScanAlreadyRunning
	}
	a.running[libraryID] = struct{}{}
	a.mu.Unlock()

	lib, err := a.q.GetLibrary(context.Background(), libraryID)
	if err != nil {
		a.release(libraryID)
		return err
	}

	// 独立 context：请求 ctx 在 202 返回后即取消，扫描必须比请求活得久。
	go func() {
		_, err := a.sc.Scan(context.Background(), lib)
		a.release(libraryID)
		if err != nil {
			a.log.Error("全量扫描失败", "libraryId", libraryID, "err", err)
		}
		a.srv.FinishScan(libraryID, err != nil)
	}()
	return nil
}

func (a *scannerAdapter) release(libraryID string) {
	a.mu.Lock()
	delete(a.running, libraryID)
	a.mu.Unlock()
}
