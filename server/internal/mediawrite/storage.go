package mediawrite

import (
	"context"
	"math"

	"qimeng-media/server/internal/sysmon"
)

func (s *Service) storageFloor() int64 {
	if s.cfg.Upload.MinFreeBytes > 0 {
		return s.cfg.Upload.MinFreeBytes
	}
	return sysmon.DefaultMinFreeBytes
}

func (s *Service) checkUploadStorage(ctx context.Context, root string, size int64) error {
	needed := s.storageFloor()
	if size > math.MaxInt64-needed {
		return fail(StorageUnavailable, "上传容量溢出", sysmon.ErrStorageFull)
	}
	if size > 0 {
		needed += size
	}
	if err := sysmon.CheckStorage(ctx, root, needed); err != nil {
		return fail(StorageUnavailable, "上传存储不可用", err)
	}
	return nil
}

// CheckStorage 是就绪探针的只读编排；禁用库不影响就绪。
func (s *Service) CheckStorage(ctx context.Context) error {
	if err := s.checkUploadStorage(ctx, s.cfg.DataDir, 0); err != nil {
		return err
	}
	libraries, err := s.q.ListLibraries(ctx)
	if err != nil {
		return err
	}
	for _, library := range libraries {
		if library.Enabled == 1 {
			if err := s.checkUploadStorage(ctx, library.RootPath, 0); err != nil {
				return err
			}
		}
	}
	return nil
}
