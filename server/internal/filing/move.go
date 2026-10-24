// move.go 跨挂载点文件移动（回收站进/出共用，2026-09-22 彩排实测补齐）。
//
// 为什么存在：os.Rename 只在同文件系统内原子有效，跨挂载点返回 EXDEV
// "invalid cross-device link"。回收站把文件从媒体库根（如容器 /media）
// 移进 DataDir/trash（如容器 /data）——Docker 卷分离与 NAS 上"媒体一个卷、
// 应用数据一个卷"的生产布局都让两者必然处于不同挂载点，不做回落则
// 回收站在这些形态下完全不可用（铁律 4：删除=移入回收站）。
// **2026-10-11 补充**：Windows 双盘布局（媒体库 C:、数据 D:）是本机日常形态，
// 其错误文本与 Linux 不同——判定口径见 MoveFile 注释（errno 主判 + 文本兜底）。
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

// ErrMoveTargetExists 表示目标路径已被占用。搬移语义从不覆盖既有用户文件。
var ErrMoveTargetExists = errors.New("move destination already exists")

// ErrMoveDidNotCreateTarget 表示本次尝试在目标端没有创建文件；调用方不能把目标
// 当作本次产生的半截副本清理，否则可能删除并发的外部写入。
var ErrMoveDidNotCreateTarget = errors.New("move did not create destination")

// copyBody 是复制主体的包内可注入替身（生产恒为 io.Copy）：磁盘满/写失败
// 等"复制中途失败"无法用真实文件系统在单测稳定注入，测试替换它制造半截
// 副本，锁定"失败即清理目标端"的行为。生产代码禁止替换。
var copyBody = io.Copy

// MoveFile 把 src 移动到 dst：优先 rename（同挂载点原子零拷贝）；跨挂载点
// 回落为「复制成功后删源」的同语义实现——复制中途失败时源文件保持原状，
// 目标端半截副本当场清理（见 copyFile），不留幽灵残缺文件。dst 的父目录
// 必须已存在（与 os.Rename 契约一致，调用方负责 MkdirAll）。
//
// 跨挂载点判定（isCrossDeviceErr）：**errno 主判**（move_windows.go /
// move_unix.go 分平台取 ERROR_NOT_SAME_DEVICE / EXDEV）+ 错误文本兜底。
// 沿革：2026-09-22 初版只认 Linux 文本 "invalid cross-device link"，理由是
// 双平台 syscall 包对 EXDEV 的暴露不一致；2026-10-11 事故暴露该口径在
// **Windows 双盘布局**（媒体库 C:、DataDir/回收站 D:）下必然漏判——Windows
// 文本是 "The system cannot move the file to a different disk drive."，
// 于是删除（移入回收站）与本机同步通道搬移全部 rename 失败且不走回落。
// 现口径：errno 为主（不受系统消息本地化影响），文本兜底保留以兼容包装形态
// （含 Linux 与 Windows 两种文本；大小写不敏感）。
func MoveFile(src, dst string) error {
	// 先拒绝已存在的目标，避免 Unix os.Rename 或跨卷 copy 回落覆盖用户文件。
	// 预检查缩小冲突窗口；跨卷复制由 O_EXCL 再防一道。调用方仍应在业务层做冲突解析/并发协调。
	if _, err := os.Lstat(dst); err == nil { // #nosec G703 -- trash/localsync 调用方已用 NormalizeRelPath/PathWithinRoot 校验落点；此层仅执行跨根移动，不能把两个根折为一个。
		return fmt.Errorf("%w: %s", ErrMoveTargetExists, dst)
	} else if !errors.Is(err, fs.ErrNotExist) {
		return fmt.Errorf("%w: inspect destination %s: %w", ErrMoveDidNotCreateTarget, dst, err)
	}

	err := os.Rename(src, dst) // #nosec G703 -- 同 MoveFile 入口的调用方根边界校验；跨卷/回收站必须允许已授权的不同根。
	if err == nil {
		return nil
	}
	if !isCrossDeviceErr(err) {
		return fmt.Errorf("%w: rename %s to %s: %w", ErrMoveDidNotCreateTarget, src, dst, err)
	}
	if err := copyFile(src, dst); err != nil {
		return err
	}
	return os.Remove(src)
}

// isCrossDeviceErr 跨挂载点错误判定：errno 主判（分平台文件实现）+ 文本兜底。
// 文本兜底按小写比对，兼容两种 OS 的错误文本与常见包装层。
func isCrossDeviceErr(err error) bool {
	if err == nil {
		return false
	}
	if crossDeviceErrno(err) {
		return true
	}
	msg := strings.ToLower(err.Error())
	return strings.Contains(msg, "invalid cross-device link") ||
		strings.Contains(msg, "different disk drive")
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
	// 只读源句柄：数据成败由 copyBody 与 out.Close 决定，in 的 Close 失败不
	// 影响任何数据结果（Q6 忽略错误清理批登记的豁免——读句柄无落盘语义），
	// Debug 级留痕即可。
	defer func() {
		if err := in.Close(); err != nil {
			slog.Debug("filing: 关闭复制源句柄失败（只读句柄，不影响结果）", "error", err)
		}
	}()
	info, err := in.Stat()
	if err != nil {
		return err
	}
	// O_EXCL 是目标端的第二道保护：若目标在 Lstat 与跨卷复制之间被创建，
	// 直接失败而不是截断/覆盖竞争者文件。
	out, err := os.OpenFile(dst, os.O_WRONLY|os.O_CREATE|os.O_EXCL, info.Mode().Perm()) // #nosec G703 -- 只接受 MoveFile 已校验调用链的目标，O_EXCL 防止覆盖既有文件；外部换链边界见 SECURITY。
	if err != nil {
		if errors.Is(err, fs.ErrExist) {
			return fmt.Errorf("%w: %s", ErrMoveTargetExists, dst)
		}
		return err
	}
	if _, err := copyBody(out, in); err != nil {
		// 先关句柄再删（Windows 上未关闭的句柄会挡住 os.Remove）；
		// 同时保留复制与收尾错误，不因关闭失败丢失最初的复制错误。
		copyErr := errors.Join(err, out.Close())
		cleanupFailedCopy(dst)
		return copyErr
	}
	if err := out.Close(); err != nil {
		cleanupFailedCopy(dst)
		return err
	}
	// 恢复意图用 size+mtime 复核落点，跨卷复制也必须保持源时间。
	if err := os.Chtimes(dst, info.ModTime(), info.ModTime()); err != nil {
		cleanupFailedCopy(dst)
		return err
	}
	return nil
}

// cleanupFailedCopy 删除本次复制创建的半截副本（best-effort：删除失败只记
// 警告不上抛——主错误（复制失败）才是调用方该看到的。目标以 O_EXCL 创建，
// 不会清理在复制前已经存在的用户文件；filing 包无 logger 管道，此处用默认 logger）。
func cleanupFailedCopy(dst string) {
	if err := os.Remove(dst); err != nil && !errors.Is(err, fs.ErrNotExist) { // #nosec G703 -- dst 为 copyFile 在已授权根内以 O_EXCL 创建的本次失败副本，外部换链属于 SECURITY 既有边界。
		slog.Warn("filing: 清理复制失败的半截副本失败（残留需由调用方按保守规则清理）",
			"path", dst, "err", err)
	}
}
