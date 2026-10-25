package recommendfeed

import (
	"context"
	"errors"
	"runtime"
)

// 单用户 App/Web 两个独立冷流最多各占一份输入；单核再收紧为一份。
// 等候准入只留在可取消的调用栈，不另注册无限量同飞体/候选缓冲。
const maxConcurrentOrderBuilds = 2

var ErrCacheClosed = errors.New("推荐顺序服务已关闭")

type sharedOrder struct {
	done chan struct{}
	ids  []string
	err  error
}

func (c *Cache) initLifecycle() {
	c.life, c.cancel = context.WithCancel(context.Background())
	c.builds = make(map[string]*sharedOrder)
	c.changed = make(chan struct{})
	c.maxBuilds = min(maxConcurrentOrderBuilds, runtime.GOMAXPROCS(0))
}

// Close 停止准入、取消服务级 SQL，并收齐已接纳计算；调用方须在关库前调用。
func (c *Cache) Close() {
	c.mu.Lock()
	c.closed = true
	c.cancel()
	c.mu.Unlock()
	c.workers.Wait()
}

func (c *Cache) orderContext(ctx context.Context, key Key, generation uint64, compute func(context.Context) ([]string, error)) ([]string, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if ids, ok := c.get(key); ok {
		return ids, nil
	}
	call, err := c.beginOrder(ctx, key, generation, compute)
	if err != nil {
		return nil, err
	}
	return waitOrder(ctx, call)
}

func waitOrder(ctx context.Context, call *sharedOrder) ([]string, error) {
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-call.done:
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		return call.ids, call.err
	}
}

// 只为已接纳的有限不同键保留单飞体；同键直接加入，不占第二份计算预算。
// singleflight.DoChan 仍负责实际计算；这层只持准入/生命周期与广播等待结果。
// singleflight 本身不限制不同键或管理服务关闭；若引入具备这些能力的共用调度器，
// 复查并整件替换本层。各键错误独立，不能用首错取消其他键的计算。
func (c *Cache) beginOrder(ctx context.Context, key Key, generation uint64, compute func(context.Context) ([]string, error)) (*sharedOrder, error) {
	flightKey := orderFlightKey(generation, key)
	for {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		c.mu.Lock()
		if c.closed {
			c.mu.Unlock()
			return nil, ErrCacheClosed
		}
		if call, ok := c.builds[flightKey]; ok {
			c.mu.Unlock()
			return call, nil
		}
		if len(c.builds) < c.maxBuilds {
			call := &sharedOrder{done: make(chan struct{})}
			c.builds[flightKey] = call
			c.workers.Add(1)
			c.mu.Unlock()
			go c.buildOrder(flightKey, key, generation, call, compute)
			return call, nil
		}
		changed := c.changed
		c.mu.Unlock()
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-c.life.Done():
			return nil, ErrCacheClosed
		case <-changed:
		}
	}
}

func (c *Cache) buildOrder(flightKey string, key Key, generation uint64, call *sharedOrder, compute func(context.Context) ([]string, error)) {
	defer c.workers.Done()
	result := <-c.sf.DoChan(flightKey, func() (any, error) {
		if ids, ok := c.get(key); ok {
			return ids, nil
		}
		ids, err := safeCompute(func() ([]string, error) {
			if err := c.life.Err(); err != nil {
				return nil, err
			}
			ids, err := compute(c.life)
			if err == nil {
				err = c.life.Err()
			}
			return ids, err
		})
		if err != nil {
			return nil, err
		}
		c.publishOrder(key, generation, ids)
		return ids, nil
	})
	call.err = result.Err
	if result.Err == nil {
		call.ids = result.Val.([]string)
	}
	// 先退役同飞体再通知完成；错误/超预算结果不缓存，下一请求必须能重新计算。
	c.mu.Lock()
	delete(c.builds, flightKey)
	close(c.changed)
	c.changed = make(chan struct{})
	c.mu.Unlock()
	close(call.done)
}
