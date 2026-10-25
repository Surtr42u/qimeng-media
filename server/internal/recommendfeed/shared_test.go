package recommendfeed

import (
	"context"
	"errors"
	"fmt"
	"slices"
	"sync"
	"sync/atomic"
	"testing"
)

type observeOrderWait struct {
	context.Context
	entered chan struct{}
	once    sync.Once
}

func (c *observeOrderWait) Done() <-chan struct{} {
	c.once.Do(func() { close(c.entered) })
	return c.Context.Done()
}

func TestSharedOrderCancellationOnlyLeavesOneWaiter(t *testing.T) {
	c := NewCache()
	entered, release := make(chan struct{}), make(chan struct{})
	var releaseOnce sync.Once
	unblock := func() { releaseOnce.Do(func() { close(release) }) }
	defer func() { unblock(); c.Close() }()
	var calls atomic.Int32
	compute := func(ctx context.Context) ([]string, error) {
		calls.Add(1)
		close(entered)
		<-release
		return []string{"complete"}, ctx.Err()
	}
	call, err := c.beginOrder(t.Context(), Key{}, c.currentGeneration(), compute)
	if err != nil {
		t.Fatal(err)
	}
	<-entered
	joined, err := c.beginOrder(t.Context(), Key{}, c.currentGeneration(), compute)
	if err != nil || joined != call {
		t.Fatal("same key did not merge")
	}
	ctx, cancel := context.WithCancel(t.Context())
	waiting := &observeOrderWait{Context: ctx, entered: make(chan struct{})}
	done := make(chan error, 1)
	go func() { _, err := waitOrder(waiting, call); done <- err }()
	<-waiting.entered
	cancel()
	if err := <-done; !errors.Is(err, context.Canceled) {
		t.Fatal("wait did not cancel", err)
	}
	unblock()
	ids, err := waitOrder(t.Context(), joined)
	if err != nil || !slices.Equal(ids, []string{"complete"}) || calls.Load() != 1 {
		t.Fatal("one cancellation poisoned shared result", err)
	}
}

func TestAllWaitersCanceledStillCompleteAndLateInvalidationDoesNotPublish(t *testing.T) {
	c := NewCache()
	entered, release := make(chan struct{}), make(chan struct{})
	var releaseOnce sync.Once
	unblock := func() { releaseOnce.Do(func() { close(release) }) }
	defer func() { unblock(); c.Close() }()
	call, err := c.beginOrder(t.Context(), Key{}, c.currentGeneration(), func(context.Context) ([]string, error) { close(entered); <-release; return []string{"old"}, nil })
	if err != nil {
		t.Fatal(err)
	}
	<-entered
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	if _, err := waitOrder(ctx, call); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	c.Clear()
	unblock()
	<-call.done
	if call.err != nil || !slices.Equal(call.ids, []string{"old"}) {
		t.Fatal("invalidation failed old shared caller")
	}
	if _, ok := c.get(Key{}); ok {
		t.Fatal("invalidated result published")
	}
	ids, err := c.order(Key{}, func() ([]string, error) { return []string{"new"}, nil })
	if err != nil || !slices.Equal(ids, []string{"new"}) {
		t.Fatal("new generation blocked", err)
	}
}

func TestDifferentKeyBuildBudgetAndCanceledAdmission(t *testing.T) {
	for _, budget := range []int{1, 2} {
		t.Run(fmt.Sprint(budget), func(t *testing.T) {
			c := NewCache()
			c.maxBuilds = budget
			defer c.Close()
			entered := make(chan struct{}, budget)
			release := make(chan struct{})
			var active, peak atomic.Int32
			compute := func(ctx context.Context) ([]string, error) {
				n := active.Add(1)
				defer active.Add(-1)
				for old := peak.Load(); n > old; old = peak.Load() {
					if peak.CompareAndSwap(old, n) {
						break
					}
				}
				entered <- struct{}{}
				select {
				case <-release:
					return []string{"ok"}, nil
				case <-ctx.Done():
					return nil, ctx.Err()
				}
			}
			for i := 0; i < budget; i++ {
				if _, err := c.beginOrder(t.Context(), Key{Seed: i}, 0, compute); err != nil {
					t.Fatal(err)
				}
			}
			for range budget {
				<-entered
			}
			ctx, cancel := context.WithCancel(t.Context())
			waiting := &observeOrderWait{Context: ctx, entered: make(chan struct{})}
			done := make(chan error, 1)
			go func() {
				_, err := c.beginOrder(waiting, Key{Seed: 99}, 0, func(context.Context) ([]string, error) { t.Error("canceled admission computed"); return nil, nil })
				done <- err
			}()
			<-waiting.entered
			cancel()
			if err := <-done; !errors.Is(err, context.Canceled) {
				t.Fatal("budget wait did not cancel", err)
			}
			if peak.Load() != int32(budget) {
				t.Fatal("different-key budget exceeded")
			}
			close(release)
		})
	}
}

func TestOrderCloseCancelsAcceptedSQLAndRejectsNewWork(t *testing.T) {
	c := NewCache()
	if _, err := c.order(Key{Seed: 1}, func() ([]string, error) { return []string{"hot"}, nil }); err != nil {
		t.Fatal(err)
	}
	entered := make(chan struct{})
	call, err := c.beginOrder(t.Context(), Key{Seed: 2}, 0, func(ctx context.Context) ([]string, error) { close(entered); <-ctx.Done(); return nil, ctx.Err() })
	if err != nil {
		t.Fatal(err)
	}
	<-entered
	c.Close()
	c.Close()
	if _, err := waitOrder(t.Context(), call); !errors.Is(err, context.Canceled) {
		t.Fatal("close left calculation alive", err)
	}
	if _, err := c.orderContext(t.Context(), Key{Seed: 1}, 0, func(context.Context) ([]string, error) { t.Error("computed after close"); return nil, nil }); !errors.Is(err, ErrCacheClosed) {
		t.Fatal("closed cache accepted hot request", err)
	}
}

func TestCancellationRacingCompletionDoesNotCacheCancellation(t *testing.T) {
	c := NewCache()
	defer c.Close()
	for seed := 0; seed < 32; seed++ {
		release := make(chan struct{})
		key := Key{Seed: seed}
		call, err := c.beginOrder(t.Context(), key, 0, func(context.Context) ([]string, error) { <-release; return []string{"complete"}, nil })
		if err != nil {
			t.Fatal(err)
		}
		ctx, cancel := context.WithCancel(t.Context())
		var race sync.WaitGroup
		race.Go(cancel)
		race.Go(func() { close(release) })
		ids, err := waitOrder(ctx, call)
		race.Wait()
		if err != nil && !errors.Is(err, context.Canceled) {
			t.Fatal(err)
		}
		if err == nil && !slices.Equal(ids, []string{"complete"}) {
			t.Fatal("invalid racing result")
		}
		if _, err := waitOrder(t.Context(), call); err != nil {
			t.Fatal("cancellation published to another waiter", err)
		}
		ids, err = c.order(key, func() ([]string, error) { return []string{"unexpected recompute"}, nil })
		if err != nil || !slices.Equal(ids, []string{"complete"}) {
			t.Fatal("cancellation cached instead of order", err)
		}
	}
}
