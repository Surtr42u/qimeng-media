// Package subtitle 负责外挂字幕（sidecar）的目录约定、语言/格式解析与正文归一。
//
// 约定（2026-11-28 用户拍板）：字幕不放进媒体库内部，而是存放在库根的同级
// 目录「5  字幕」下，按库文件夹名镜像库内布局：
//
//	视频：<库根父目录>/<库文件夹名>/<资产相对路径>/<文件名>.mp4
//	字幕：<库根父目录>/5  字幕/<库文件夹名>/<资产相对路径>/<文件名>.zh.ass
//
// 文件名主干（stem）必须与视频文件名逐字节一致（含空格、+、括号与大小写）。
// 语言段（可选）在最后一个点与扩展名之间：放宽 zh_cn/chs/GB 等常见写法，
// 统一规范成 BCP 47（见 lang.go）；没有语言段的 `xxx.ass` 记为 und。
// 支持格式：ass/ssa/srt/vtt 原样下发；sub/sbv/lrc/smi 由服务端转成 WebVTT
// 再下发（见 convert.go）；正文编码统一转 UTF-8（见 encoding.go）。
//
// 边界：
//   - 职责：路径推导、目录枚举、语言/格式归并与正文转换；
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

// MaxFileBytes 单个字幕文件读取上限（8 MiB）。字幕是纯文本，正常在几十 KB
// 量级；上限只用于防止损坏/恶意文件把内存打满（SECURITY 资源预算口径），
// 超限由 handler 映射 413。
const MaxFileBytes = 8 << 20

// 支持的字幕扩展名（不含点，小写）。顺序即同语言多格式的取用优先级：
// 原生格式优先（样式表达力从高到低），再是服务端转换格式（转换会丢样式/定位，
// 只保留时间轴与文本）。
var supportedFormats = []string{"ass", "ssa", "srt", "vtt", "sub", "sbv", "lrc", "smi"}

// nativeFormats 原样下发的格式（其余支持格式一律转 WebVTT）。
var nativeFormats = map[string]bool{"ass": true, "ssa": true, "srt": true, "vtt": true}

// ErrOutsideLibrary 资产绝对路径不在给定库根内（数据污染或调用方拼错）。
var ErrOutsideLibrary = errors.New("subtitle: 资产路径不在库根内")

// ErrNoLibraryRoot 库根为空，无从推导字幕目录。
var ErrNoLibraryRoot = errors.New("subtitle: 库根为空")

// Track 一条可播放字幕轨（同一语言只保留优先级最高的一条）。
type Track struct {
	// Lang 规范化 BCP 47 语言码（列表按此去重；未标注为 und）。
	Lang string
	// Label 人类可读标签（协议 subtitles[].label 的唯一来源，如 中文（简体）/English）。
	Label string
	// IsDefault 是否首选轨（简体中文优先、其次繁体；都没有则全部 false）。
	IsDefault bool
	// Format 源文件格式（ass/ssa/srt/vtt/sub/sbv/lrc/smi）。
	Format string
	// ServeFormat 下发格式：原生格式同 Format，转换格式恒为 vtt。
	ServeFormat string
	// Path 字幕文件绝对路径。
	Path string
	// SizeBytes 源文件字节数（协议 sizeBytes；转换轨也报原件大小）。
	SizeBytes int64
}

// Converted 报告该轨是否由服务端转换后下发。
func (t Track) Converted() bool { return t.ServeFormat != t.Format }

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

// Tracks 列出资产可用的字幕轨：语言段按 BCP 47 归并（zh/zh_cn/chs 视为同一语言
// zh-Hans），同语言多格式只保留优先级最高的一条（ass > ssa > srt > vtt >
// sub > sbv > lrc > smi）——多格式同语言同时下发给播放器会叠加渲染，同一句
// 字幕显示两遍。
//
// 目录或字幕文件不存在不是错误（零字幕是常态），返回空列表。结果按语言码升序。
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
		prev, dup := best[lang]
		if dup && formatRank(prev.Format) <= formatRank(format) {
			continue
		}
		serve := format
		if !nativeFormats[format] {
			serve = formatVTT
		}
		best[lang] = Track{
			Lang:        lang,
			Label:       LangLabel(lang),
			Format:      format,
			ServeFormat: serve,
			Path:        filepath.Join(dir, entry.Name()),
			SizeBytes:   info.Size(),
		}
	}
	tracks := make([]Track, 0, len(best))
	for _, t := range best {
		tracks = append(tracks, t)
	}
	sort.Slice(tracks, func(i, j int) bool { return tracks[i].Lang < tracks[j].Lang })
	markDefault(tracks)
	return tracks, nil
}

// markDefault 标记首选轨：简体中文 > 繁体中文，都没有则不预选。
func markDefault(tracks []Track) {
	for _, want := range preferredLangs {
		for i := range tracks {
			if tracks[i].Lang == want {
				tracks[i].IsDefault = true
				return
			}
		}
	}
}

// Resolve 按语言码取一条字幕轨。语言码先规范化（?lang=zh_cn 与 zh-Hans 等价），
// 因此大小写、下划线/连字符混用都能命中；未命中返回 ok=false（调用方映射 404，
// 不区分「文件不存在」与「语言不存在」——两者对客户端同义）。
// 语言码只用于与目录项比对，不参与任何路径拼接，天然不存在穿越面。
func Resolve(libraryRoot, absAsset, lang string) (Track, bool, error) {
	if strings.TrimSpace(lang) == "" {
		return Track{}, false, nil
	}
	want := NormalizeLang(lang)
	tracks, err := Tracks(libraryRoot, absAsset)
	if err != nil {
		return Track{}, false, err
	}
	for _, t := range tracks {
		if t.Lang == want {
			return t, true, nil
		}
	}
	return Track{}, false, nil
}

// parseName 判断目录项是否为 stem 对应的字幕并拆出语言码/格式。
// 规则：<stem>.<lang>.<ext> 或 <stem>.<ext>（无语言段 → und）。
// stem 逐字节匹配（含大小写），stem 自身可含点（video.v2.mp4），故按前缀切。
func parseName(name, stem string) (lang, format string, ok bool) {
	rest, found := strings.CutPrefix(name, stem+".")
	if !found {
		return "", "", false
	}
	ext := strings.ToLower(filepath.Ext(rest))
	if ext == "" {
		// 无语言段：video.ass → und（不能静默吞掉）
		if format, ok = lookupFormat(strings.ToLower(rest)); ok {
			return NormalizeLang(""), format, true
		}
		return "", "", false
	}
	format, ok = lookupFormat(strings.TrimPrefix(ext, "."))
	if !ok {
		return "", "", false
	}
	langPart := rest[:len(rest)-len(ext)]
	if langPart == "" {
		// video..ass：空语言段（双点）视为异常命名，不猜测。
		return "", "", false
	}
	return NormalizeLang(langPart), format, true
}

// lookupFormat 校验扩展名是否在支持清单内（入参为小写、不含点）。
func lookupFormat(ext string) (string, bool) {
	for _, f := range supportedFormats {
		if f == ext {
			return f, true
		}
	}
	return "", false
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
