package uploadsess

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// 默认预算面向个人手机/PC，配置可覆盖；按声明总量预留，不等磁盘写满才拒绝。
const (
	DefaultMaxSessions            = 32
	DefaultMaxReservedBytes int64 = 8 << 30
)

var (
	ErrCapacity        = errors.New("上传会话数量或暂存总容量已达上限")
	ErrSessionConflict = errors.New("上传任务 ID 已用于不同参数")
)

type Metadata struct {
	ID, RequestHash string
	SourceMtime     *int64
}

type Limits struct {
	MaxSessions      int
	MaxReservedBytes int64
}

func DefaultLimits() Limits { return Limits{DefaultMaxSessions, DefaultMaxReservedBytes} }

// Reuse 只读复用相同参数的活动会话；等待分片写锁时不持全局注册表锁。
func (m *Manager) Reuse(id, hash string) (Snapshot, bool, error) {
	s, ok := m.lookup(id)
	if !ok {
		return Snapshot{}, false, nil
	}
	if s.metadata.RequestHash != hash {
		return Snapshot{}, true, ErrSessionConflict
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	return Snapshot{ID: s.id, Offset: s.offset, Size: s.size}, true, nil
}

// 活动会话按声明总量预留；重启/清理失败留下的孤儿按实际字节计入同一预算。
// 调用方持 m.mu，创建的空临时件和注册表发布不会出现配额检查间隙。
func (m *Manager) checkCapacityLocked(size int64) error {
	if size < 1 || len(m.sessions) >= m.limits.MaxSessions {
		return ErrCapacity
	}
	remaining := m.limits.MaxReservedBytes
	live := make(map[string]bool, len(m.sessions))
	for _, active := range m.sessions {
		if active.size > remaining {
			return ErrCapacity
		}
		remaining -= active.size
		live[active.tempPath] = true
	}
	entries, err := os.ReadDir(m.tempRoot())
	if err != nil && !os.IsNotExist(err) {
		return fmt.Errorf("%w: %v", ErrTempRoot, err)
	}
	for _, entry := range entries {
		name := entry.Name()
		if !strings.HasPrefix(name, tempPrefix) || !strings.HasSuffix(name, tempSuffix) || live[filepath.Join(m.tempRoot(), name)] {
			continue
		}
		info, err := entry.Info()
		if err != nil {
			return fmt.Errorf("%w: %v", ErrTempRoot, err)
		}
		if info.Size() > remaining {
			return ErrCapacity
		}
		remaining -= info.Size()
	}
	if size > remaining {
		return ErrCapacity
	}
	return nil
}

// SetLimits 只由组合阶段调用；非正值回落安全默认值，不能意外取消上限。
func (m *Manager) SetLimits(limits Limits) {
	if limits.MaxSessions <= 0 {
		limits.MaxSessions = DefaultMaxSessions
	}
	if limits.MaxReservedBytes <= 0 {
		limits.MaxReservedBytes = DefaultMaxReservedBytes
	}
	m.mu.Lock()
	m.limits = limits
	m.mu.Unlock()
}
