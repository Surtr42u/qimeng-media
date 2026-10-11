//go:build !windows

package filing

import (
	"errors"
	"syscall"
)

// crossDeviceErrno 走 errno 判定（EXDEV：Linux/macOS/BSD 同语义同常量）。
func crossDeviceErrno(err error) bool {
	return errors.Is(err, syscall.EXDEV)
}
