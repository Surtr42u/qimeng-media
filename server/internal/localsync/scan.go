// scan.go：同步根目录树的只读扫描与条目分类（ADR-0030）。
//
// 扫描规则（WalkDir 词法序天然确定，逐轮顺序稳定）：
//   - 顶层（相对路径不含分隔符）点前缀条目：保留内部目录 .synced 整棵静默
//     剪枝（连 ignored 都不出——归档区不是同步对象）；其余记入 ignored
//     （目录同时 SkipDir）。
//   - 嵌套点前缀目录/文件：静默跳过（库子目录混存 .nfo/.DS_Store 是常态，
//     不值得在状态面板占位）。
//   - 符号链接（不跟随）：顶层记 ignored（用户放了个软链需要看见），
//     嵌套静默跳过。
//   - 其余普通文件进 entries；目录只作遍历载体不出结果。
package localsync

import (
	"io/fs"
	"path"
	"path/filepath"
	"strings"
	"time"

	"qimeng-media/server/internal/filing"
)

// txtExtension 是作者片段表的扩展名（同步根直接下的 *.txt 才走导入通道）。
const txtExtension = ".txt"

// slashSep 是本包内部路径的统一分隔符：Entry.RelPath 等对外形态一律
// filepath.ToSlash（斜杠分隔，与协议 LocalSyncItem.path 口径一致）。
const slashSep = '/'

// dotPrefix 是隐藏/内部条目的前缀：顶层点前缀条目按「明确忽略」处理
// （状态可见），嵌套点前缀条目静默跳过（与库目录混存 .nfo 等常态一致）。
const dotPrefix = "."

// ReservedInternalDirName 是同步根直接下的保留内部目录名（TXT 成功导入后
// 的归档处）：扫描时整棵静默剪枝，永不视为同步条目。同名字符串的另一个
// 使用方——httpapi 侧 TXT 归档移动——直接引用本常量（编译期单一来源，
// 对齐锚点：改此处即两处同步，禁止任何一侧再手抄第二份）。
const ReservedInternalDirName = ".synced"

// isTopLevel 判断斜杠分隔的相对路径是否为同步根直接子项（不含分隔符）。
func isTopLevel(relSlash string) bool {
	return !strings.ContainsRune(relSlash, slashSep)
}

// FileKind 是同步条目的分类，取值与 api/openapi.yaml LocalSyncItemKind
// enum 逐字一致（协议侧改动须同步这里，反之亦然）。
type FileKind string

// FileKind 三分类。
const (
	// KindMedia 扩展名在 filing 上传白名单内（走媒体入库通道）。
	KindMedia FileKind = "media"
	// KindTxt 同步根直接下的 .txt（走作者片段导入通道）。
	KindTxt FileKind = "txt"
	// KindOther 其余一切（明确忽略，原因见状态条目）。
	KindOther FileKind = "other"
)

// Entry 是扫描出的一个同步源条目（文件或被忽略的条目）。RelPath 为相对
// 同步根的路径，统一斜杠分隔；Size/Mtime 取自 Info()，作为跨轮稳定性
// 判定的观测输入；IsSymlink 为真表示条目是符号链接本体（不跟随）。
type Entry struct {
	RelPath   string
	Size      int64
	ModTime   time.Time
	IsSymlink bool
}

// ClassifyRelPath 按相对路径分类条目：.txt 且不含分隔符（同步根直接下）
// → txt；扩展名在 filing 上传白名单内 → media；其余 → other。扩展名比较
// 大小写不敏感（filing.AllowedExtension 内部归一小写；.TXT 与 .txt 同判）。
func ClassifyRelPath(rel string) FileKind {
	ext := path.Ext(rel)
	if strings.EqualFold(ext, txtExtension) && isTopLevel(rel) {
		return KindTxt
	}
	if filing.AllowedExtension(ext) {
		return KindMedia
	}
	return KindOther
}

// ScanTree 只读扫描同步根：返回待处理/观测的普通文件（entries）与顶层
// 明确忽略条目（ignored）。根不存在/不可读经 err 返回（调用方记通道级
// 错误）；单个条目 Info 失败同样上抛中止本轮（缺字段会让稳定性判定失真）。
func ScanTree(root string) (entries []Entry, ignored []Entry, err error) {
	err = filepath.WalkDir(root, func(p string, d fs.DirEntry, werr error) error {
		if werr != nil {
			return werr
		}
		if p == root {
			return nil // 根自身不是条目
		}
		rel, rerr := filepath.Rel(root, p)
		if rerr != nil {
			return rerr
		}
		relSlash := filepath.ToSlash(rel)
		top := isTopLevel(relSlash)
		name := d.Name()
		hidden := strings.HasPrefix(name, dotPrefix)
		symlink := d.Type()&fs.ModeSymlink != 0
		switch {
		case top && hidden && name == ReservedInternalDirName && d.IsDir():
			// 保留归档目录：整棵静默剪枝，不进任何结果集。
			return fs.SkipDir
		case top && hidden:
			// 顶层其余点前缀条目：明确忽略（目录整棵剪枝）。
			e, eerr := lsEntryFrom(root, p, d)
			if eerr != nil {
				return eerr
			}
			ignored = append(ignored, e)
			if d.IsDir() {
				return fs.SkipDir
			}
			return nil
		case !top && hidden:
			// 嵌套点前缀：静默跳过（目录整棵剪枝）。
			if d.IsDir() {
				return fs.SkipDir
			}
			return nil
		case symlink:
			// 符号链接不跟随（WalkDir 本身不 descent 进链接目录）：顶层
			// 记 ignored 让用户看见，嵌套静默跳过。
			if top {
				e, eerr := lsEntryFrom(root, p, d)
				if eerr != nil {
					return eerr
				}
				ignored = append(ignored, e)
			}
			return nil
		case d.IsDir():
			return nil // 普通目录只作遍历载体
		default:
			e, eerr := lsEntryFrom(root, p, d)
			if eerr != nil {
				return eerr
			}
			entries = append(entries, e)
			return nil
		}
	})
	if err != nil {
		return nil, nil, err
	}
	return entries, ignored, nil
}

// lsEntryFrom 从目录项构造 Entry；Info/Rel 失败上抛中止本轮扫描
// （带缺字段的条目做稳定性判定会误判，宁可整轮失败下轮重试）。
func lsEntryFrom(root, p string, d fs.DirEntry) (Entry, error) {
	info, err := d.Info()
	if err != nil {
		return Entry{}, err
	}
	rel, err := filepath.Rel(root, p)
	if err != nil {
		return Entry{}, err
	}
	return Entry{
		RelPath:   filepath.ToSlash(rel),
		Size:      info.Size(),
		ModTime:   info.ModTime(),
		IsSymlink: d.Type()&fs.ModeSymlink != 0,
	}, nil
}

// RootOverlapError 检查同步根与服务端数据目录、各库根是否互为包含（含
// 相等——filing.PathWithinRoot 对相等路径返回 true）。重叠即安全红线：
// 同步 move 入库会把「库根下的源」移到「库根里的目标」，自我吞并；同步根
// 覆盖 DataDir 则会把缩略图/回收站/数据库文件当媒体扫。命中返回中文说明
// （点名涉及的路径，供通道级错误与日志），无重叠返回空串。
func RootOverlapError(root, dataDir string, libRoots []string) string {
	rootAbs, err := filepath.Abs(root)
	if err != nil {
		return "解析同步根绝对路径失败: " + err.Error()
	}
	if dataDir != "" {
		if reason := lsOverlapPair(rootAbs, dataDir, "服务端数据目录"); reason != "" {
			return reason
		}
	}
	for _, libRoot := range libRoots {
		if libRoot == "" {
			continue
		}
		if reason := lsOverlapPair(rootAbs, libRoot, "库根"); reason != "" {
			return reason
		}
	}
	return ""
}

// lsOverlapPair 判定一对目录是否互为包含（双向 PathWithinRoot，相等即
// 双向都命中）。label 是另一方的中文身份说明（库根/数据目录）。
func lsOverlapPair(rootAbs, other, label string) string {
	otherAbs, err := filepath.Abs(other)
	if err != nil {
		return "解析" + label + "绝对路径失败: " + err.Error()
	}
	if filing.PathWithinRoot(rootAbs, otherAbs) || filing.PathWithinRoot(otherAbs, rootAbs) {
		return "同步根与" + label + "重叠: " + otherAbs
	}
	return ""
}
