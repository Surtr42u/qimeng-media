package filing

import (
	"errors"
	"path"
	"path/filepath"
	"strings"
	"unicode/utf8"
)

// 本文件是 SECURITY 红线 #1「路径穿越」的统一实现。
//
// 一切来自请求的库内相对路径必须先过 NormalizeRelPath 再 join 库根；
// 一切"已经是绝对路径"的场景（scanner、静态文件服务）用 PathWithinRoot 兜底。
// handler 禁止自行拼接路径（docs/SECURITY.md 红线清单 #1）。

// 路径校验哨兵错误。handler 应统一映射为 400，且响应与日志不得回显内部
// 绝对路径（SECURITY 红线 #7 信息泄露）。
var (
	ErrEmptyPath       = errors.New("filing: 路径为空或无有效段")
	ErrAbsolutePath    = errors.New("filing: 不允许绝对路径")
	ErrPathEscape      = errors.New("filing: 路径包含 .. 逃逸")
	ErrReservedName    = errors.New("filing: 路径包含 Windows 保留设备名")
	ErrInvalidUTF8Path = errors.New("filing: 路径不是有效的 UTF-8 文本")
	ErrNulInPath       = errors.New("filing: 路径包含 NUL 字节")
)

// NormalizeRelPath 规范化用户提供的"库内相对路径"，输出保证 join 库根后仍在库根内。
//
// 拒绝清单：空路径 / 纯分隔符（含指向库根本身的 "."）/ Unix 绝对路径 / Windows 盘符 /
// UNC 路径（含 \\.\ 、\\?\ 设备路径）/ 任何形式的 .. 逃逸 / Windows 保留设备名 /
// NUL 字节 / 非 UTF-8。
//
// 安全设计要点：
//   - 百分号解码只做一次（decodePercentOnce）：解码后的字符串就是最终交给文件
//     系统的字面值，文件系统不会再做百分号解码，因此以"解码一次后的结果"做全部
//     判断即可收敛到单一真相。若解码两次，%252e%252e%252f 第一次解码为 %2e%2e%2f
//     （看似无害），第二次才是 ../ —— 用第一次的结果判断、用第二次的结果写盘，
//     就被绕过了；反之一次都不解码，..%2f 会以字面形式躲过 ".." 检查，下游任何
//     环节（生成直链再解码等）都会把它还原成 ../。只解一次，判断值 == 写盘值。
//   - 刻意用 path.Clean（固定按 / 处理）而非 filepath.Clean：后者在 Windows 上
//     会把 / 转成 \ 并自行处理盘符，行为随平台漂移会让跨平台 CI 的安全测试失真。
//     盘符/UNC 检查在本函数显式完成，不依赖平台差异。
func NormalizeRelPath(raw string) (string, error) {
	decoded := decodePercentOnce(raw)
	if !utf8.ValidString(decoded) {
		return "", ErrInvalidUTF8Path
	}
	// NUL：文件系统 API 遇 NUL 直接报错，且 C 字符串以 NUL 截断是经典路径
	// 篡改手法（前半段合法校验、后半段被截断丢弃），直接拒绝。
	if strings.ContainsRune(decoded, 0) {
		return "", ErrNulInPath
	}
	// 反斜杠统一视为分隔符：Windows 客户端可能提交 \ 分隔的路径，同时 ..\
	// 逃逸因此被并入 ../ 检测，不会因为混用分隔符而漏网。
	s := strings.ReplaceAll(decoded, `\`, "/")
	if s == "" {
		return "", ErrEmptyPath
	}
	// 绝对路径：以 / 开头覆盖 Unix 绝对路径、//server/share UNC 与 \\.\ \\?\ 设备
	// 路径（反斜杠已统一为 /）；X: 覆盖 Windows 盘符（含大小写两种盘符字母）。
	if strings.HasPrefix(s, "/") {
		return "", ErrAbsolutePath
	}
	if len(s) >= 2 && isASCIILetter(s[0]) && s[1] == ':' {
		return "", ErrAbsolutePath
	}
	cleaned := path.Clean(s)
	if cleaned == "." || cleaned == "" {
		// "./"、"a/.." 等 Clean 后收敛为库根本身——对"库内相对路径"而言
		// 等价于空路径，删除/下载/移动"整个库根"都不是合法操作。
		return "", ErrEmptyPath
	}
	// path.Clean 的性质：一切可折叠的 x/.. 都已折叠，剩余的 .. 必然全部位于
	// 头部（".." 或 "../..."），只需检查头部即覆盖一切逃逸形式。
	if cleaned == ".." || strings.HasPrefix(cleaned, "../") {
		return "", ErrPathEscape
	}
	for _, seg := range strings.Split(cleaned, "/") {
		if isReservedDeviceName(seg) {
			return "", ErrReservedName
		}
	}
	return cleaned, nil
}

// PathWithinRoot 判定 p（Clean 后）是否位于 root 目录内（含 root 本身）。
// 供 scanner、静态文件服务等一切"拿绝对路径卡库根边界"的场景复用；
// 与 NormalizeRelPath 组合构成红线 #1 的完整闭环。
func PathWithinRoot(root, p string) bool {
	// 刻意用 filepath（平台分隔符）而非 path：本函数的输入是"文件系统上的绝对
	// 路径"，必须按所在平台的真实分隔符比较才有意义。
	rc := filepath.Clean(root)
	pc := filepath.Clean(p)
	if rc == "" {
		return false // 空 root 无从界定边界
	}
	if pc == rc {
		return true
	}
	// 为什么不能裸用 strings.HasPrefix(pc, rc)："/media" 是 "/mediax" 的前缀，
	// 裸前缀比较会把 /mediax/evil.png 误判在 /media 库内（目录名边界被绕过）。
	// 必须补一个分隔符再比较，保证命中的一定是完整路径段边界。
	return strings.HasPrefix(pc, rc+string(filepath.Separator))
}

// isASCIILetter 判断字节是否 ASCII 字母（盘符不区分大小写）。
func isASCIILetter(c byte) bool {
	return ('a' <= c && c <= 'z') || ('A' <= c && c <= 'Z')
}

// decodePercentOnce 把 %XX（两个十六进制位）解码为对应字节，只解一轮。
// 非法的 % 序列（如 "100%" 中的孤立百分号）原样保留，避免误杀含 % 的合法文件名。
func decodePercentOnce(s string) string {
	if !strings.Contains(s, "%") {
		return s
	}
	var b strings.Builder
	b.Grow(len(s))
	for i := 0; i < len(s); i++ {
		c := s[i]
		if c == '%' && i+2 < len(s) {
			hi, ok1 := unhex(s[i+1])
			lo, ok2 := unhex(s[i+2])
			if ok1 && ok2 {
				b.WriteByte(hi<<4 | lo)
				i += 2
				continue
			}
		}
		b.WriteByte(c)
	}
	return b.String()
}

func unhex(c byte) (byte, bool) {
	switch {
	case '0' <= c && c <= '9':
		return c - '0', true
	case 'a' <= c && c <= 'f':
		return c - 'a' + 10, true
	case 'A' <= c && c <= 'F':
		return c - 'A' + 10, true
	}
	return 0, false
}
