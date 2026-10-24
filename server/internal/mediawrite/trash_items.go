package mediawrite

import (
	"os"
	"path"
	"time"
)

// TrashItem 是列表只读投影，不含服务器绝对路径；HTTP 再映射为协议对象。
type TrashItem struct {
	ID, Filename, OriginalPath string
	DeletedAt, ExpiresAt       time.Time
	SizeBytes                  int64
}

func (s *Service) TrashItems() ([]TrashItem, error) {
	entries, err := s.ListTrash()
	if err != nil {
		return nil, err
	}
	items := make([]TrashItem, 0, len(entries))
	for i := len(entries) - 1; i >= 0; i-- {
		e := entries[i]
		var size int64
		if info, err := os.Stat(e.File); err == nil {
			size = info.Size()
		}
		items = append(items, TrashItem{ID: e.ID, Filename: path.Base(e.Meta.OriginalPath), OriginalPath: e.Meta.OriginalPath,
			DeletedAt: e.Meta.DeletedAt, ExpiresAt: e.Meta.DeletedAt.AddDate(0, 0, s.TrashRetentionDays()), SizeBytes: size})
	}
	return items, nil
}
