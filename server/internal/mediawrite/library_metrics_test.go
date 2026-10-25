package mediawrite

import (
	"context"
	"errors"
	"log/slog"
	"sync/atomic"
	"testing"
)

func TestLibraryMetricsCoalescesAndKeepsChangesDuringQuery(t *testing.T) {
	var truth, calls, gauge atomic.Int64
	values := make(chan int64, 8)
	entered, release := make(chan struct{}), make(chan struct{})
	waiting, tick := make(chan struct{}), make(chan struct{})
	r := newLibraryMetricsRefresher(func(ctx context.Context) error {
		call := calls.Add(1)
		value := truth.Load()
		if call == 2 {
			close(entered)
			select {
			case <-release:
			case <-ctx.Done():
				return ctx.Err()
			}
		}
		if call == 4 {
			values <- -1
			return errors.New("synthetic query failure")
		}
		gauge.Store(value)
		values <- value
		return nil
	}, slog.Default())
	r.wait = func(ctx context.Context) bool {
		select {
		case waiting <- struct{}{}:
		case <-ctx.Done():
			return false
		}
		select {
		case <-tick:
			return true
		case <-ctx.Done():
			return false
		}
	}
	r.start(context.Background())
	defer r.close()
	if <-values != 0 {
		t.Fatal("initial query missing")
	}
	r.start(context.Background()) // 防止重复启动另开查询体。
	for i := 0; i < 100; i++ {
		truth.Add(1)
		r.notify()
	}
	<-waiting
	tick <- struct{}{}
	<-entered
	truth.Add(1)
	r.notify()
	close(release)
	if <-values != 100 {
		t.Fatal("query snapshot changed")
	}
	<-waiting
	tick <- struct{}{}
	if <-values != 101 || calls.Load() != 3 {
		t.Fatal("lost in-query signal or failed to coalesce")
	}
	truth.Add(1)
	r.notify()
	<-waiting
	tick <- struct{}{}
	if <-values != -1 || gauge.Load() != 101 {
		t.Fatal("failure overwrote last successful gauge")
	}
	<-waiting
	tick <- struct{}{}
	if <-values != 102 {
		t.Fatal("failure not retried")
	}
}

func TestLibraryMetricsCloseCancelsAndDoesNotRestart(t *testing.T) {
	entered := make(chan struct{})
	var calls atomic.Int64
	r := newLibraryMetricsRefresher(func(ctx context.Context) error { calls.Add(1); close(entered); <-ctx.Done(); return ctx.Err() }, slog.Default())
	r.start(context.Background())
	<-entered
	r.close()
	r.close()
	r.notify()
	r.start(context.Background())
	if calls.Load() != 1 {
		t.Fatal("worker restarted after close")
	}
	neverStarted := newLibraryMetricsRefresher(func(context.Context) error { t.Error("started after close"); return nil }, slog.Default())
	neverStarted.close()
	neverStarted.start(context.Background())
}
