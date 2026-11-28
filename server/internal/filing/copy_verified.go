package filing

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
)

// 本文件是「复制 + 摘要复核」原语（2026-11-28 本机直接入库批）。
//
// 与 MoveFile 的分工：MoveFile 是回收站/恢复用的搬移（rename 优先、跨卷复制后
// 直接删源，不做摘要）；本机入库要求「跨分区复制 → 长度 + SHA-256 校验 → 才删源」，
// 且 move=false 时只复制不删源。两者失败语义同为「源不动、目标端半截副本当场
// 清理」，但校验强度不同，故保留两个入口而不合并。

// Digest 文件摘要（尺寸 + SHA-256 十六进制小写）。
type Digest struct {
	Size   int64
	SHA256 string
}

// ErrSourceNotRegular 源路径不是普通文件（目录/设备/符号链接目标异常）。
var ErrSourceNotRegular = errors.New("source is not a regular file")

// ErrCopyVerifyFailed 复制完成但目标复核（大小/摘要）不匹配。
var ErrCopyVerifyFailed = errors.New("copied file failed size/digest verification")

// CopyFileVerified 把 src 复制到 dst 并返回源摘要。
//
// dst 以 O_EXCL 创建（绝不覆盖既有文件）；最终路径不存在时也仍先写 dst 本体，
// 调用方负责选一个唯一的临时名再原子改名。复制过程边写边算 SHA-256，结束后
// 回读目标复核大小与摘要（防磁盘/驱动层静默损坏），不匹配即删除目标并报错。
// 成功后保留源文件修改时间（与 MoveFile 的跨卷回落同口径，资产 mtime 取落盘值）。
// 任何失败都清理本次创建的目标，源文件不动。本函数不删除源。
func CopyFileVerified(src, dst string) (Digest, error) {
	in, err := os.Open(src) // #nosec G304 -- 调用方已用 PathWithinRoot 限定读根内绝对路径
	if err != nil {
		return Digest{}, err
	}
	defer func() {
		if cerr := in.Close(); cerr != nil {
			// 只读句柄：关闭失败不影响已写入的数据结果（与 copyFile 同口径）。
			_ = cerr
		}
	}()
	info, err := in.Stat()
	if err != nil {
		return Digest{}, err
	}
	if !info.Mode().IsRegular() {
		return Digest{}, ErrSourceNotRegular
	}
	out, err := os.OpenFile(dst, os.O_WRONLY|os.O_CREATE|os.O_EXCL, info.Mode().Perm()) // #nosec G304 -- dst 由调用方在库根/临时目录内以唯一名给定
	if err != nil {
		if errors.Is(err, os.ErrExist) {
			return Digest{}, fmt.Errorf("%w: %s", ErrMoveTargetExists, dst)
		}
		return Digest{}, err
	}
	hasher := sha256.New()
	written, err := io.Copy(io.MultiWriter(out, hasher), in)
	if err == nil && written != info.Size() {
		err = fmt.Errorf("复制字节数 %d 与源大小 %d 不符", written, info.Size())
	}
	closeErr := out.Close()
	if err != nil || closeErr != nil {
		cleanupFailedCopy(dst)
		return Digest{}, errors.Join(err, closeErr)
	}
	srcDigest := Digest{Size: info.Size(), SHA256: hex.EncodeToString(hasher.Sum(nil))}
	if err := os.Chtimes(dst, info.ModTime(), info.ModTime()); err != nil {
		cleanupFailedCopy(dst)
		return Digest{}, err
	}
	dstDigest, err := FileSHA256(dst)
	if err != nil {
		cleanupFailedCopy(dst)
		return Digest{}, err
	}
	if dstDigest != srcDigest {
		cleanupFailedCopy(dst)
		return Digest{}, fmt.Errorf("%w: src=%+v dst=%+v", ErrCopyVerifyFailed, srcDigest, dstDigest)
	}
	return srcDigest, nil
}

// FileSHA256 计算文件内容的尺寸 + SHA-256（只读；不跟随符号链接之外的任何处理）。
func FileSHA256(path string) (Digest, error) {
	f, err := os.Open(path) // #nosec G304 -- 调用方已限定在读根/库根内
	if err != nil {
		return Digest{}, err
	}
	defer func() {
		if cerr := f.Close(); cerr != nil {
			_ = cerr
		}
	}()
	info, err := f.Stat()
	if err != nil {
		return Digest{}, err
	}
	if !info.Mode().IsRegular() {
		return Digest{}, ErrSourceNotRegular
	}
	hasher := sha256.New()
	if _, err := io.Copy(hasher, f); err != nil {
		return Digest{}, err
	}
	return Digest{Size: info.Size(), SHA256: hex.EncodeToString(hasher.Sum(nil))}, nil
}

// IsCrossDeviceError 暴露跨挂载点判定（本机入库需要在复制前区分同卷 rename
// 与跨卷复制两条路径；判定口径与 MoveFile 完全同源，不另写一套文本匹配）。
func IsCrossDeviceError(err error) bool { return isCrossDeviceErr(err) }
