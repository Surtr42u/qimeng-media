package filing

import (
	"errors"
	"fmt"
	"io/fs"
	"os"
)

// RenameInRoot 在已授权根内改名，显式拒绝已存在目标（含悬空链接）。
// 调用方持库锁并负责业务路径校验；回滚也必须走相同保护，不能覆盖新投放原件。
// Lstat→Rename 仍不是跨进程原子 no-replace，外部并发写入边界见 SECURITY/ADR-0038。
func RenameInRoot(root *os.Root, source, target string) error {
	if _, err := root.Lstat(target); err == nil {
		return ErrMoveTargetExists
	} else if !errors.Is(err, fs.ErrNotExist) {
		return fmt.Errorf("%w: 检查根内目标: %w", ErrMoveDidNotCreateTarget, err)
	}
	if err := root.Rename(source, target); err != nil {
		return fmt.Errorf("%w: 根内改名: %w", ErrMoveDidNotCreateTarget, err)
	}
	return nil
}
