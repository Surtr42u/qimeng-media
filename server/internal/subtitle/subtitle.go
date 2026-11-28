// Package subtitle 负责外挂字幕（sidecar）的目录约定与解析。
//
// 约定（2026-11-28 用户拍板）：字幕不放进媒体库内部，而是存放在库根的同级
// 目录「5  字幕」下，按库文件夹名镜像库内布局：
//
//	视频：<库根父目录>/<库文件夹名>/<资产相对路径>/<文件名>.mp4
//	字幕：<库根父目录>/5  字幕/<库文件夹名>/<资产相对路径>/<文件名>.zh.ass
//
// 文件名主干（stem）必须与视频文件名逐字节一致（含空格、+、括号与大小写），
// 语言码取 .ass 前最后一个扩展名（.zh.ass / .en.ass / .ja.ass）。
//
// 边界：
//   - 职责：仅解析路径与枚举目录，不缓存、不写库、不改文件；
//   - 禁止：把字幕当媒体索引（「5  字幕」是库根的同级目录，不在任何库根内部，
//     scanner 只遍历库根，天然不会收录；本包也不提供任何入库入口）。
//
// 本包不依赖 HTTP 或数据库；鉴权、响应头与发文件由 httpapi 层负责。
package subtitle

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// DirName 外挂字幕根目录名：数字 5 + 两个空格 + 「字幕」。
// 目录名逐字节参与路径解析（用户既有磁盘布局，改一个空格等于全部字幕失联），
// 禁止「看起来差不多」的手改。
const DirName = "5  字幕"

// 支持的字幕扩展名（不含点，小写）。同语言存在多个格式时按此顺序取用：
// 样式表达力从高到低，ass 最接近用户在剪辑端看到的成品。
var supportedFormats = []string{"ass", "ssa", "srt", "vtt"}

// FormatPriority 返回受支持格式的取用优先级（导出给协议/文档引用，避免
// 调用方手抄顺序）。顺序语义见 supportedFormats。
func FormatPriority() []string {
	out := make([]string, len(supportedFormats))
	copy(out, supportedFormats)
	return out
}

var (
	// ErrOutsideLibrary 资产绝对路径不在给定库根内（数据污染或调用方拼错）。
	ErrOutsideLibrary = errors.New("subtitle: 资产路径不在库根内")
	// ErrNoLibraryRoot 库根为空，无从推导字幕目录。
	ErrNoLibraryRoot = errors.New("subtitle: 库根为空")
)

// Track 一条可播放字幕轨。
type Track struct {
	// Lang 语言码（取自文件名 .ass 前最后一段扩展名，原样保留大小写）。
	Lang string
	// Format 格式扩展名（不含点，小写：ass/ssa/srt/vtt）。
	Format string
	// Path 字幕文件绝对路径。
	Path string
	// SizeBytes 文件字节数（协议 sizeBytes 的数据源）。
	SizeBytes int64
}

// Dir 返回资产对应的字幕目录（不保证存在）。
//
// libraryRoot 是库根绝对路径，absAsset 是资产绝对路径（两者同源：库根来自
// libraries.root_path，资产路径由库根 + rel_path 拼出）。资产路径越出库根时报
// ErrOutsideLibrary——字幕目录由相对路径推导，相对路径不可信时整条链都不成立。
func Dir(libraryRoot, absAsset string) (string, error) {
	root := filepath.Clean(libraryRoot)
	if root == "" || root == "." {
		return "", ErrNoLibraryRoot
	}
	rel, err := filepath.Rel(root, filepath.Clean(absAsset))
	if err != nil || rel == "." || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
		return "", fmt.Errorf("%w: %s", ErrOutsideLibrary, absAsset)
	}
	// 镜像布局：<库根父目录>/5  字幕/<库文件夹名>/<资产相对目录>
	return filepath.Join(filepath.Dir(root), DirName, filepath.Base(root), filepath.Dir(rel)), nil
}

// Tracks 列出资产可用的字幕轨：同一语言存在多个格式时只保留优先级最高的一条
// （ass > ssa > srt > vtt）——多格式同语言若同时下发给播放器会叠加渲染，
// 同一句字幕显示两遍。
//
// 目录或字幕文件不存在不是错误（零字幕是常态），返回空列表。结果按语言码
// 升序，语言码比较不区分大小写（BCP 47 语言标签本身大小写不敏感）。
func Tracks(libraryRoot, absAsset string) ([]Track, error) {
	dir, err := Dir(libraryRoot, absAsset)
	if err != nil {
		return nil, err
	}
	stem := strings.TrimSuffix(filepath.Base(absAsset), filepath.Ext(absAsset))
	entries, err := os.ReadDir(dir)
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("subtitle: 读取字幕目录: %w", err)
	}
	best := make(map[string]Track, len(entries))
	for _, entry := range entries {
		// 目录、符号链接一律跳过：字幕是普通文件，链接会让「字幕目录内」
		// 的路径校验失去意义（与 filing.PathWithinRoot 的禁链接口径一致）。
		if !entry.Type().IsRegular() {
			continue
		}
		lang, format, ok := parseName(entry.Name(), stem)
		if !ok {
			continue
		}
		info, statErr := entry.Info()
		if statErr != nil {
			// 并发删除（扫描/手工整理）按不存在处理，不因单条失败炸掉整个列表。
			if errors.Is(statErr, os.ErrNotExist) {
				continue
			}
			return nil, fmt.Errorf("subtitle: 读取字幕文件信息: %w", statErr)
		}
		key := strings.ToLower(lang)
		prev, dup := best[key]
		if dup && formatRank(prev.Format) <= formatRank(format) {
			continue
		}
		best[key] = Track{
			Lang:      lang,
			Format:    format,
			Path:      filepath.Join(dir, entry.Name()),
			SizeBytes: info.Size(),
		}
	}
	tracks := make([]Track, 0, len(best))
	for _, t := range best {
		tracks = append(tracks, t)
	}
	sort.Slice(tracks, func(i, j int) bool {
		li, lj := strings.ToLower(tracks[i].Lang), strings.ToLower(tracks[j].Lang)
		if li != lj {
			return li < lj
		}
		return tracks[i].Format < tracks[j].Format
	})
	return tracks, nil
}

// Resolve 按语言码取一条字幕轨；语言码比较不区分大小写，未命中返回 ok=false
// （调用方映射 404，不区分「文件不存在」与「语言不存在」——两者对客户端同义）。
// 语言码只用于与目录项比对，不参与任何路径拼接，天然不存在穿越面。
func Resolve(libraryRoot, absAsset, lang string) (Track, bool, error) {
	if lang == "" {
		return Track{}, false, nil
	}
	tracks, err := Tracks(libraryRoot, absAsset)
	if err != nil {
		return Track{}, false, err
	}
	for _, t := range tracks {
		if strings.EqualFold(t.Lang, lang) {
			return t, true, nil
		}
	}
	return Track{}, false, nil
}

// parseName 判断目录项是否为 stem 对应的字幕并拆出语言码/格式。
// 规则：<stem>.<lang>.<ext>，stem 逐字节匹配（含大小写），ext 必须在支持清单内，
// 语言码非空。stem 自身可含点（video.v2.mp4），故按前缀切而不是找最后一个点。
func parseName(name, stem string) (lang, format string, ok bool) {
	rest, found := strings.CutPrefix(name, stem+".")
	if !found {
		return "", "", false
	}
	ext := strings.ToLower(filepath.Ext(rest))
	for _, f := range supportedFormats {
		if ext != "."+f {
			continue
		}
		lang = strings.TrimSuffix(rest, filepath.Ext(rest))
		if lang == "" {
			return "", "", false
		}
		return lang, f, true
	}
	return "", "", false
}

// formatRank 格式在 supportedFormats 中的位次（未知格式排最后，仅防御）。
func formatRank(format string) int {
	for i, f := range supportedFormats {
		if f == format {
			return i
		}
	}
	return len(supportedFormats)
}
