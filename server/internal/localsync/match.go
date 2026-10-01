// match.go：同步根文件夹名 → 库 的匹配与拒绝判定（ADR-0030）。
//
// 匹配规则与 Android App 归档（InboxFileStore.sanitizeLibraryDirName）
// 逐字对齐：对每个库的 name 做 trim + 九个非法字符替换为 _，结果与文件夹名
// 精确相等（区分大小写）才算命中。双向都不许改字符集——App 端改了归档名，
// 服务端必须同步；服务端改了规则，App 端也会匹配不上（对齐锚点见 ADR-0030）。
package localsync

import (
	"strings"

	"qimeng-media/server/internal/scanner"
	"qimeng-media/server/internal/store/db"
)

// LibraryDirIllegalChars 是库文件夹名禁用的九个字符（反斜杠/正斜杠/冒号/
// 星号/问号/双引号/小于/大于/竖线）：Windows 文件名非法字符 + 路径分隔符，
// Android 归档侧同名规则逐字对齐（见文件头注释），改任一侧都是对齐事故。
const LibraryDirIllegalChars = `\/:*?"<>|`

// libraryDirSanitizeChar 是非法字符的替换目标（下划线，两侧同值）。
const libraryDirSanitizeChar = '_'

// 拒绝原因码：MatchResult.Reject 的全部取值（非空串 = 未命中可入库目标）。
const (
	// RejectNotFound 没有任何库的净化名与文件夹名相等。
	RejectNotFound = "not-found"
	// RejectAmbiguous 多个库的净化名与文件夹名相等，无法确定目标。
	RejectAmbiguous = "ambiguous"
	// RejectDisabled 唯一命中库已停用（enabled=0）。
	RejectDisabled = "disabled"
	// RejectCosKind 唯一命中库是 COS 库：COS 目录即作者结构，不经此通道。
	RejectCosKind = "cos-kind"
)

// MatchResult 是一次匹配的结果：Reject 为空串时 Library 是唯一命中库；
// 非空时按拒绝原因可能携带命中库（disabled/cos-kind 场景填命中库供状态
// 展示，not-found/ambiguous 场景为零值）。
type MatchResult struct {
	Library db.Library
	Reject  string
}

// SanitizeLibraryDirName 把库名净化为库文件夹名：TrimSpace 后逐字符把
// LibraryDirIllegalChars 中的字符替换为 libraryDirSanitizeChar。
// 中文/emoji 等合法字符原样保留，不做任何转写（与 filing.SanitizeFilename
// 的「剥离」语义刻意不同：文件夹名是映射关系，长度与可见字符不丢）。
func SanitizeLibraryDirName(name string) string {
	return strings.Map(func(r rune) rune {
		if strings.ContainsRune(LibraryDirIllegalChars, r) {
			return libraryDirSanitizeChar
		}
		return r
	}, strings.TrimSpace(name))
}

// MatchLibraryByDirName 按同步根文件夹名匹配库：全部库净化后与 dirName
// 精确比对（区分大小写）。0 命中 → not-found；>1 命中 → ambiguous；
// 唯一命中再查 enabled（0=停用 → disabled）与 kind（非 normal → cos-kind，
// 取值口径 = migrations/0005 的 CHECK 约束，常量单一来源 scanner.LibraryKindNormal）。
func MatchLibraryByDirName(dirName string, libs []db.Library) MatchResult {
	var matched []db.Library
	for _, lib := range libs {
		if SanitizeLibraryDirName(lib.Name) == dirName {
			matched = append(matched, lib)
		}
	}
	switch len(matched) {
	case 0:
		return MatchResult{Reject: RejectNotFound}
	case 1:
		lib := matched[0]
		if lib.Enabled == 0 {
			return MatchResult{Library: lib, Reject: RejectDisabled}
		}
		if lib.Kind != scanner.LibraryKindNormal {
			return MatchResult{Library: lib, Reject: RejectCosKind}
		}
		return MatchResult{Library: lib}
	default:
		return MatchResult{Reject: RejectAmbiguous}
	}
}
