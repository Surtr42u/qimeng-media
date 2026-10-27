package scanner

import (
	"context"
	"encoding/json"
	"sync"

	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/config"
	"qimeng-media/server/internal/sysmon"
)

// 每次准入读取持久化值；不取消已运行任务，降低上限只影响后续准入。
func (s *Scanner) scanWorkerLimit(ctx context.Context) int {
	value, err := s.q.GetSetting(ctx, authoring.SettingKeyClientConfig)
	if err != nil {
		return sysmon.AutoScanWorkers(ctx)
	}
	var stored struct {
		Scan struct{ Workers int } `json:"scan"`
	}
	if json.Unmarshal([]byte(value), &stored) != nil || stored.Scan.Workers < config.ClientScanWorkersMin || stored.Scan.Workers > config.ClientScanWorkersMax {
		return sysmon.AutoScanWorkers(ctx)
	}
	if stored.Scan.Workers == 0 {
		return sysmon.AutoScanWorkers(ctx)
	}
	return stored.Scan.Workers
}

// scanAdmission是所有全量扫描入口共用的可取消闸门；只持有计数和唤醒通道。
type scanAdmission struct {
	mu      sync.Mutex
	active  int
	changed chan struct{}
}

func (g *scanAdmission) acquire(ctx context.Context, limit func(context.Context) int) (func(), error) {
	for {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		maximum := limit(ctx)
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		g.mu.Lock()
		if g.changed == nil {
			g.changed = make(chan struct{})
		}
		if g.active < maximum {
			g.active++
			close(g.changed)
			g.changed = make(chan struct{})
			g.mu.Unlock()
			return g.release, nil
		}
		changed := g.changed
		g.mu.Unlock()
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-changed:
		}
	}
}

func (g *scanAdmission) release() {
	g.mu.Lock()
	g.active--
	close(g.changed)
	g.changed = make(chan struct{})
	g.mu.Unlock()
}
