// wire.go 是组装层的适配器集合：把业务包的同步实现适配到 httpapi
// 依赖注入的最小接口上。语义差（同步/异步、装配参数）在这一处消化，
// 两边的包互不感知（依赖倒置，见 httpapi.Scanner 注释）。
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"sync"

	"qimeng-media/server/internal/httpapi"
	"qimeng-media/server/internal/scanner"
	"qimeng-media/server/internal/store/db"
	"qimeng-media/server/internal/sysmon"
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

// EnrichAsset 单资产重富化（同步）：移动/重命名写入路径的伴随调用，
// 快速纯计算+少量写，无需异步化。
func (a *scannerAdapter) EnrichAsset(ctx context.Context, libraryID, assetID string) error {
	return a.sc.EnrichAsset(ctx, libraryID, assetID)
}

// sysStatusAdapter 把 sysmon.Collector 适配成 httpapi.Deps.SysStatus 闭包。
//
// 挂载点每次快照现查库表（DataDir + 全部库根）：库增删后磁盘面板自动
// 跟随，无需重启或注册回调；快照频率 = 面板轮询频率（秒级），单表查询
// 无性能顾虑。查库失败降级为只报 DataDir、错误并入聚合返回——面板部分
// 可用优于整体 500（与 sysmon.Snapshot 同一错误哲学）。
type sysStatusAdapter struct {
	collector *sysmon.Collector
	q         *db.Queries
	dataDir   string
	version   string
}

func newSysStatusAdapter(c *sysmon.Collector, q *db.Queries, dataDir, version string) *sysStatusAdapter {
	return &sysStatusAdapter{collector: c, q: q, dataDir: dataDir, version: version}
}

// snapshot 采集一次系统快照。CPUInterval=0：与上次调用差分（面板 3s
// 轮询即得 3s 窗口均值），首调返回 0 由前端渲染"采样中"。
func (a *sysStatusAdapter) snapshot(ctx context.Context) (sysmon.SystemStatus, error) {
	mounts := []string{a.dataDir}
	var libsErr error
	if libs, err := a.q.ListLibraries(ctx); err != nil {
		libsErr = fmt.Errorf("查询库挂载点: %w", err)
	} else {
		for _, l := range libs {
			mounts = append(mounts, l.RootPath)
		}
	}
	st, snapErr := a.collector.Snapshot(ctx, sysmon.Options{
		Mounts:  mounts,
		Version: a.version,
	})
	return st, errors.Join(libsErr, snapErr)
}
