//go:build windows

package filing

import (
	"errors"
	"syscall"
)

// winErrorNotSameDevice 是 Win32 ERROR_NOT_SAME_DEVICE（MSDN 系统错误码 17）：
// os.Rename 跨盘时返回它（错误文本 "The system cannot move the file to a
// different disk drive."，2026-10-11 真机实测形态见 move.go 头部事故记档）。
// stdlib syscall 包未导出该常量名（x/sys/windows 才有），按 MSDN 定义自备——
// 名称即文档，不是魔法值。
const winErrorNotSameDevice syscall.Errno = 17

// crossDeviceErrno 走 errno 判定：比错误文本匹配稳（不受系统消息本地化影响）。
func crossDeviceErrno(err error) bool {
	return errors.Is(err, winErrorNotSameDevice)
}
