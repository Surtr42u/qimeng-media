// trash_sweeper.go：回收站到期自动清扫（DOMAIN_RULES §9「到期自动物理
// 清除」，2026-09-22 实现该规划项；此前 TrashExpired 判定函数已备但无
// 调用方，回收站只标 30 天到期、实际不清）。
package httpapi

import (
	"context"
	"os"
	"path/filepath"
	"time"

	"qimeng-media/server/internal/config"
	"qimeng-media/server/internal/filing"
)

// trashRetentionDays 返回生效的回收站保留天数：配置 >=1 用配置值，否则
// 回落 filing.DefaultTrashRetentionDays。最后一道兜底的原因：测试与内嵌
// 形态可能手工构造 Config 不走 config.Load 的默认值链（harness 即如此），
// 到期展示（GetApiV1Trash 的 ExpiresAt）与清扫判定必须同源取值。
func (s *Server) trashRetentionDays() int {
	if s.cfg.Trash.RetentionDays >= 1 {
		return s.cfg.Trash.RetentionDays
	}
	return filing.DefaultTrashRetentionDays
}

// trashSweepInterval 返回生效的巡检间隔（同 trashRetentionDays 的兜底链）。
func (s *Server) trashSweepInterval() time.Duration {
	if s.cfg.Trash.SweepInterval > 0 {
		return s.cfg.Trash.SweepInterval
	}
	return config.DefaultTrashSweepInterval
}

// StartTrashSweeper 组合根（main）调用一次：到期清扫 daemon，ctx 取消即
// 退出（与 backup.Manager.Start 同款生命周期模式）。刻意没有 enabled
// 开关：到期清除是 DOMAIN_RULES §9 的核心语义而非可选增强，想"不清除"
// 把 retention_days 配成极大值即可。
func (s *Server) StartTrashSweeper(ctx context.Context) {
	interval := s.trashSweepInterval()
	go func() {
		ticker := time.NewTicker(interval)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				s.sweepTrashOnce()
			}
		}
	}()
}

// sweepTrashOnce 一轮到期清扫：遍历回收站 meta → filing.TrashExpired 判定
// → 条目子树成对删除（与单条物理删除同款 RemoveAll 语义）→ 缩略图联动
// 清理 → 有清除才刷指标。单条失败记日志下轮重试；本轮零清除不刷指标
// （省一次全量遍历，gauge 维持上次值即可）。
func (s *Server) sweepTrashOnce() {
	entries, err := s.listTrash()
	if err != nil {
		s.logger.Warn("回收站到期清扫遍历失败（下轮重试）", "err", err)
		return
	}
	retention := s.trashRetentionDays()
	removed := 0
	for _, e := range entries {
		if !filing.TrashExpired(e.meta, s.now(), retention) {
			continue
		}
		if err := os.RemoveAll(filepath.Dir(e.metaFile)); err != nil {
			s.logger.Warn("回收站到期条目清除失败（下轮重试）", "id", e.id, "err", err)
			continue
		}
		removed++
		s.logger.Info("回收站条目到期物理清除", "id", e.id,
			"originalPath", e.meta.OriginalPath,
			"deletedAt", e.meta.DeletedAt.Format(time.DateTime))
		// 条目永久消失：该资产缩略图缓存不再可达，联动清理
		//（thumbnail/cleanup.go 文件头；软删除→恢复路径刻意不清）。
		s.thumbs.DeleteAssetThumbs(e.meta.AssetID)
	}
	if removed > 0 {
		// 到期清除=物理删除：修订号显式推进（与手动物理删除同一 bump 链）。
		s.bumpLibraryRevision()
		s.refreshTrashMetrics()
	}
}
