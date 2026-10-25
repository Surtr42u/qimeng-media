package thumbnail

import (
	"context"
	"os"
)

func (g *Generator) initLifecycle() {
	g.lifecycle.Do(func() {
		g.life, g.cancel = context.WithCancel(context.Background())
		g.budget = newRepresentationBudget(defaultQueueSize)
		g.closed = make(chan struct{})
	})
}

func (g *Generator) missingRepresentations(assetID, version string, sizes []Size) []string {
	keys := make([]string, 0, len(sizes))
	for _, size := range sizes {
		dst := g.thumbDst(assetID, size, version)
		if _, err := os.Stat(dst); err != nil {
			keys = append(keys, dst)
		}
	}
	return keys
}

// CloseContext 将停止准入与请求取消分离；只有一个收尾体，重入共享完成通知。
// 根 context 中止闸位及外部转码，默认 Close 最多等待既有单尺寸超时。
func (g *Generator) CloseContext(ctx context.Context) error {
	g.initLifecycle()
	g.closing.Do(func() {
		g.budget.close()
		g.detached.stop()
		g.generating.stop()
		g.cancel()
		go func() {
			if g.pool != nil {
				g.pool.Close()
			}
			g.detached.wg.Wait()
			g.generating.wg.Wait()
			close(g.closed)
		}()
	})
	select {
	case <-g.closed:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}
