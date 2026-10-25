package recommendfeed

import (
	"context"
	"errors"
	"log/slog"
	"time"

	"qimeng-media/server/internal/recommend"
	"qimeng-media/server/internal/store/db"
)

// ErrWarmupInvalidated 表示预热期间发生结构性失效，停止复用旧输入；普通请求可冷算。
var ErrWarmupInvalidated = errors.New("推荐预热期间缓存已失效")

// WarmSeeds 只在本轮预热生命周期内复用完整输入，不建立常驻候选缓存。
// base 的修订/日界/媒体/COS/偏好与 now 均固定；每个 seed 仍生成独立完整顺序。
func (c *Cache) WarmSeeds(ctx context.Context, q *db.Queries, base Key, seeds []int, prefs *recommend.Weights, now time.Time) error {
	return c.warmSeedsContext(ctx, base, seeds, prefs, now, func(buildCtx context.Context) ([]recommend.Item, error) {
		return loadItems(buildCtx, q, base)
	})
}

func (c *Cache) warmSeeds(base Key, seeds []int, prefs *recommend.Weights, now time.Time, load func() ([]recommend.Item, error)) error {
	return c.warmSeedsContext(context.Background(), base, seeds, prefs, now, func(context.Context) ([]recommend.Item, error) { return load() })
}

func (c *Cache) warmSeedsContext(ctx context.Context, base Key, seeds []int, prefs *recommend.Weights, now time.Time, load func(context.Context) ([]recommend.Item, error)) error {
	generation := c.currentGeneration()
	var prepared *recommend.Prepared
	for _, seed := range seeds {
		if generation != c.currentGeneration() {
			return ErrWarmupInvalidated
		}
		key := base
		key.Seed = seed
		_, err := c.orderContext(ctx, key, generation, func(buildCtx context.Context) ([]string, error) {
			if prepared == nil {
				start := time.Now()
				items, err := load(buildCtx)
				if err != nil {
					return nil, err
				}
				queryElapsed := time.Since(start)
				prepared = recommend.Prepare(items, recommend.Params{Prefs: prefs, Now: now})
				slog.Info("推荐预热共享输入装载完成", "candidates", len(items), "elapsedMs", queryElapsed.Milliseconds(),
					"prepareMs", (time.Since(start) - queryElapsed).Milliseconds())
			}
			return prepared.IDs(seed, 0), nil
		})
		if err != nil {
			return err
		}
		// 预热专用中止信号只能在共享计算外返回，不能让前台同飞请求收到它。
		if generation != c.currentGeneration() {
			return ErrWarmupInvalidated
		}
	}
	return nil
}
