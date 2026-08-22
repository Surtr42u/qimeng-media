package filing

import (
	"errors"
	"fmt"
	"path"
	"strings"
)

// 文件名清洗哨兵错误。
var (
	// ErrFilenameEmpty 清洗后名字为空（原名全部由非法字符构成）。
	ErrFilenameEmpty = errors.New("filing: 清洗后文件名为空")
	// ErrFilenameReserved 文件名是 Windows 保留设备名（见 reserved.go 注释）。
	ErrFilenameReserved = errors.New("filing: 文件名是 Windows 保留设备名")
)

// SanitizeFilename 清洗用户上传的文件名（仅文件名，不含目录部分）。
//
// 规则（docs/SECURITY.md「上传安全·文件名清洗」）：
//   - 剥离路径分隔符 / 与 \：上传名里带分隔符等于试图越权指定子目录；
//   - 剥离控制字符（<0x20，外加 0x7F DEL——DEL 在终端同样造成显示/解析混乱）；
//   - 剥离 Windows 非法字符 : * ? " < > |；
//   - 剥离首尾空格与首尾点：Windows API 会静默剥掉文件名末尾的空格和点，导致
//     写盘后的真实文件名与数据库记录不一致（记录 "a.jpg." 实际文件是 "a.jpg"），
//     扫描对账永远差一个文件，必须提前剥掉。代价是 Unix 隐藏文件的首点也被剥
//     （".gitignore" → "gitignore"）——媒体库场景不存在隐藏文件，取舍可接受；
//   - Windows 保留设备名直接报错而不是自动改名（自动改名会让用户找不到自己的文件）。
//
// 中文、emoji 等一切非 ASCII 合法字符原样保留，不做任何转写。
func SanitizeFilename(name string) (string, error) {
	var b strings.Builder
	b.Grow(len(name))
	for _, r := range name {
		switch {
		case r == '/' || r == '\\':
			// 剥离路径分隔符
		case r < 0x20 || r == 0x7F:
			// 剥离控制字符
		case strings.ContainsRune(`:*?"<>|`, r):
			// 剥离 Windows 非法字符
		default:
			b.WriteRune(r)
		}
	}
	cleaned := strings.Trim(b.String(), " .")
	if cleaned == "" {
		return "", ErrFilenameEmpty
	}
	if isReservedDeviceName(cleaned) {
		return "", ErrFilenameReserved
	}
	return cleaned, nil
}

// ResolveConflict 在 exists(name) 为真时生成 "基名 (2).ext"、"基名 (3).ext"……
// 直到 exists 返回 false，序号从 2 起（Windows/各大网盘惯例）。中文/emoji 同样适用。
//
// 无扩展名文件生成 "name (2)"；形如 ".gitignore"（整个名字被 Ext 视为一个扩展名）
// 的名字同样按无扩展名处理，避免产出 ".gitignore (2).gitignore" 这种结果。
//
// 不设序号上限：exists 反映真实文件系统状态，冲突数必然有限；传入恒真函数导致
// 长循环属于调用方 bug。
func ResolveConflict(name string, exists func(string) bool) string {
	if exists == nil || !exists(name) {
		return name
	}
	ext := path.Ext(name)
	base := strings.TrimSuffix(name, ext)
	if base == "" {
		// 名字整体是"扩展名"形态（如 ".gitignore"）→ 按无扩展名处理
		base, ext = name, ""
	}
	for i := 2; ; i++ {
		candidate := fmt.Sprintf("%s (%d)%s", base, i, ext)
		if !exists(candidate) {
			return candidate
		}
	}
}
