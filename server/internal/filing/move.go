// move.go 跨挂载点文件移动（回收站进/出共用，2026-09-22 彩排实测补齐）。
//
// 为什么存在：os.Rename 只在同文件系统内原子有效，跨挂载点返回 EXDEV
// "invalid cross-device link"。回收站把文件从媒体库根（如容器 /media）
// 移进 DataDir/trash（如容器 /data）——Docker 卷分离与 NAS 上"媒体一个卷、
// 应用数据一个卷"的生产布局都让两者必然处于不同挂载点，不做回落则
// 回收站在这些形态下完全不可用（铁律 4：删除=移入回收站）。
package filing

import (
	"io"
	"os"
	"strings"
)

// MoveFile 把 src 移动到 dst：优先 rename（同挂载点原子零拷贝）；EXDEV
// 回落为「复制成功后删源」的同语义实现——复制中途失败时源文件保持原状，
// 最坏残留一个不完整副本，不丢用户文件。dst 的父目录必须已存在（与
// os.Rename 契约一致，调用方负责 MkdirAll）。
//
// EXDEV 判定用错误文本匹配而非 syscall 常量：本项目在 Windows（开发/测试）
// 与 Linux（NAS/容器）双平台编译，两端 syscall 包对 EXDEV 的暴露不一致；
// Linux 内核与 modernc 驱动对该错误文本稳定，且与项目内约束错误的字符串
// 匹配惯例一致（httpapi/libraries.go 的 UNIQUE、engagement.go 的 FOREIGN KEY）。
func MoveFile(src, dst string) error {
	err := os.Rename(src, dst)
	if err == nil {
		return nil
	}
	if !strings.Contains(err.Error(), "invalid cross-device link") {
		return err
	}
	if err := copyFile(src, dst); err != nil {
		return err
	}
	return os.Remove(src)
}

// copyFile 按 src 的权限位在 dst 创建完整副本。
func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer func() { _ = in.Close() }()
	info, err := in.Stat()
	if err != nil {
		return err
	}
	out, err := os.OpenFile(dst, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, info.Mode().Perm())
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		_ = out.Close()
		return err
	}
	return out.Close()
}
