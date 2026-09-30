// move.go 跨挂载点文件移动（回收站进/出共用，2026-09-22 彩排实测补齐）。
//
// 为什么存在：os.Rename 只在同文件系统内原子有效，跨挂载点返回 EXDEV
// "invalid cross-device link"。回收站把文件从媒体库根（如容器 /media）
// 移进 DataDir/trash（如容器 /data）——Docker 卷分离与 NAS 上"媒体一个卷、
// 应用数据一个卷"的生产布局都让两者必然处于不同挂载点，不做回落则
// 回收站在这些形态下完全不可用（铁律 4：删除=移入回收站）。
package filing

import (
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log/slog"
	"os"
	"strings"
)

// copyBody 是复制主体的包内可注入替身（生产恒为 io.Copy）：磁盘满/写失败
// 等"复制中途失败"无法用真实文件系统在单测稳定注入，测试替换它制造半截
// 副本，锁定"失败即清理目标端"的行为。生产代码禁止替换。
var copyBody = io.Copy

// MoveFile 把 src 移动到 dst：优先 rename（同挂载点原子零拷贝）；EXDEV
// 回落为「复制成功后删源」的同语义实现——复制中途失败时源文件保持原状，
// 目标端半截副本当场清理（见 copyFile），不留幽灵残缺文件。dst 的父目录
// 必须已存在（与 os.Rename 契约一致，调用方负责 MkdirAll）。
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

// RemovePartialCopy MoveFile 失败后的目标端残留清理（回收站三调用点共用，
// 2026-10-01 F2 根修，best-effort）：只在 dst 严格小于 src（确定是复制
// 中途死亡的半截副本）时删除。为什么不盲删——MoveFile 还有一种失败形态
// 是"复制已完整、仅删源失败"，此时 dst 是完整可用副本（如恢复/回滚场景里
// 刚搬回库内的文件），删它=销毁已成功搬运的用户数据；宁留勿删，疑似完整
// 的交还调用方按主错误路径自行处置。dst 不存在（copyFile 内部清理已跑过/
// 从未创建）视为已清理，返回 nil。调用方约定：dst 是本次移动新建的目标
// 路径（回收站 stamp 路径/冲突自动改名后的恢复路径），无"误删既有文件"
// 的歧义。
func RemovePartialCopy(src, dst string) error {
	si, err := os.Stat(src)
	if err != nil {
		return fmt.Errorf("stat 源文件 %s: %w", src, err)
	}
	di, err := os.Stat(dst)
	if errors.Is(err, fs.ErrNotExist) {
		return nil
	}
	if err != nil {
		return fmt.Errorf("stat 目标残留 %s: %w", dst, err)
	}
	if di.Size() >= si.Size() {
		return nil // 疑似完整副本（删源失败的失败形态），绝不能删
	}
	return os.Remove(dst)
}

// copyFile 按 src 的权限位在 dst 创建完整副本。任何 dst 已落盘后的失败
// （复制中断/收尾写失败）都当场删掉半截副本——源文件未动重试零损失，
// 目标端不留列表/清扫/指标全看不见的幽灵残缺件（2026-10-01 F2 根修）。
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
	if _, err := copyBody(out, in); err != nil {
		// 先关句柄再删（Windows 上未关闭的句柄会挡住 os.Remove）；
		// 收尾关闭已无意义，主错误是复制失败，保留它上抛。
		_ = out.Close()
		cleanupFailedCopy(dst)
		return err
	}
	if err := out.Close(); err != nil {
		cleanupFailedCopy(dst)
		return err
	}
	return nil
}

// cleanupFailedCopy 删除复制失败的半截副本（best-effort：删除失败只记
// 警告不上抛——主错误（复制失败）才是调用方该看到的，清理失败掩盖它只会
// 让排障方向错位；filing 包无 logger 管道，此处用默认 logger）。
func cleanupFailedCopy(dst string) {
	if err := os.Remove(dst); err != nil && !errors.Is(err, fs.ErrNotExist) {
		slog.Warn("filing: 清理复制失败的半截副本失败（残留，重试移动会被 O_TRUNC 覆盖）",
			"path", dst, "err", err)
	}
}
