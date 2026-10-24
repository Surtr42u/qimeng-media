package mediawrite

import (
	"context"
	"os"
	"path/filepath"
	"time"

	"qimeng-media/server/internal/config"
	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/safego"
)

// trashRetentionDays 返回生效的回收站保留天数：配置 >=1 用配置值，否则
// 回落 filing.DefaultTrashRetentionDays。最后一道兜底的原因：测试与内嵌
// 形态可能手工构造 Config 不走 config.Load 的默认值链（harness 即如此），
// 到期展示（GetApiV1Trash 的 ExpiresAt）与清扫判定必须同源取值。
func (s *Service) TrashRetentionDays() int {
	if s.cfg.Trash.RetentionDays >= 1 {
		return s.cfg.Trash.RetentionDays
	}
	return filing.DefaultTrashRetentionDays
}

// trashSweepInterval 返回生效的巡检间隔（同 trashRetentionDays 的兜底链）。
func (s *Service) trashSweepInterval() time.Duration {
	if s.cfg.Trash.SweepInterval > 0 {
		return s.cfg.Trash.SweepInterval
	}
	return config.DefaultTrashSweepInterval
}

// StartTrashSweeper 组合根（main）调用一次：到期清扫 daemon，ctx 取消即
// 退出（与 backup.Manager.Start 同款生命周期模式）。刻意没有 enabled
// 开关：到期清除是 DOMAIN_RULES §9 的核心语义而非可选增强，想"不清除"
// 把 retention_days 配成极大值即可。
func (s *Service) StartTrashSweeper(ctx context.Context) {
	interval := s.trashSweepInterval()
	safego.Go(s.logger, "回收站到期清扫", func() {
		ticker := time.NewTicker(interval)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				s.SweepTrashOnce()
			}
		}
	})
}

// sweepTrashOnce 一轮到期清扫：遍历回收站 meta → filing.TrashExpired 判定
// → 条目子树成对删除（与单条物理删除同款 RemoveAll 语义）→ 缩略图联动
// 清理 → 有清除才刷指标。单条失败记日志下轮重试；本轮零清除不刷指标
// （省一次全量遍历，gauge 维持上次值即可）。
func (s *Service) SweepTrashOnce() {
	unlockTrash := filing.LockLibrary(trashOperationGate)
	defer unlockTrash()

	entries, err := s.ListTrash()
	if err != nil {
		s.logger.Warn("回收站到期清扫遍历失败（下轮重试）", "err", err)
		return
	}
	retention := s.TrashRetentionDays()
	removed := 0
	for _, e := range entries {
		if !filing.TrashExpired(e.Meta, s.now(), retention) {
			continue
		}
		filing.InvalidateRestoreIntents(s.cfg.DataDir, e.Meta.LibraryID)
		if err := os.RemoveAll(filepath.Dir(e.MetaFile)); err != nil {
			filing.InvalidateRestoreIntents(s.cfg.DataDir, e.Meta.LibraryID)
			s.logger.Warn("回收站到期条目清除失败（下轮重试）", "id", e.ID, "err", err)
			continue
		}
		filing.InvalidateRestoreIntents(s.cfg.DataDir, e.Meta.LibraryID)
		removed++
		s.logger.Info("回收站条目到期物理清除", "id", e.ID,
			"originalPath", e.Meta.OriginalPath,
			"deletedAt", e.Meta.DeletedAt.Format(time.DateTime))
		// 条目永久消失：该资产缩略图缓存不再可达，联动清理
		//（thumbnail/cleanup.go 文件头；软删除→恢复路径刻意不清）。
		s.thumbs.DeleteAssetThumbs(e.Meta.AssetID)
	}
	if removed > 0 {
		// 到期清除=物理删除：修订号显式推进（与手动物理删除同一 bump 链）。
		s.bumpLibraryRevision()
		s.RefreshTrashMetrics()
	}
}
