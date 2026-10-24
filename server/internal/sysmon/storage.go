package sysmon

import (
	"context"
	"errors"
	"io"
	"os"

	"github.com/shirou/gopsutil/v4/disk"
)

// DefaultMinFreeBytes 为数据库/WAL/正常运行保留 256 MiB，部署配置可覆盖。
const DefaultMinFreeBytes int64 = 256 << 20

var ErrStorageFull = errors.New("存储可用空间不足")

// CheckStorage 只读检查目录可访问与卷余量，不写探针文件，不枚举媒体内容。
// 预检后外部进程仍可占盘，不能替代流写错误处理或宣称验证了卷可写性。
func CheckStorage(ctx context.Context, root string, required int64) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	dir, err := os.Open(root)
	if err != nil {
		return err
	}
	_, readErr := dir.Readdirnames(1)
	closeErr := dir.Close()
	if readErr != nil && !errors.Is(readErr, io.EOF) {
		return errors.Join(readErr, closeErr)
	}
	if closeErr != nil {
		return closeErr
	}
	usage, err := disk.UsageWithContext(ctx, root)
	if err != nil {
		return err
	}
	if required > 0 && usage.Free < uint64(required) {
		return ErrStorageFull
	}
	return ctx.Err()
}
