package scanner

import (
	"path/filepath"
	"strings"
)

// MediaType 三类媒体的库内存储值（migrations/0001 assets.media_type CHECK
// 约束；DOMAIN_RULES §11）。scanner 是这些值的唯一写入方之一，集中定义
// 防止字符串漂移。
const (
	MediaTypeImage         = "image"
	MediaTypeAnimatedImage = "animated_image"
	MediaTypeVideo         = "video"
)

// 图片/视频扩展名白名单（DOMAIN_RULES §9 上传白名单与扫描识别共用一张表；
// 大小写不敏感，匹配前统一转小写）。
// gif 单列：动图与静图是不同 media_type（ANIMATED_IMAGE，DOMAIN_RULES §11
// 「动图取首帧静帧」的缩略图语义依赖这个区分）。
var imageExts = map[string]struct{}{
	".jpg": {}, ".jpeg": {}, ".png": {}, ".webp": {}, ".avif": {},
}

var videoExts = map[string]struct{}{
	".mp4": {}, ".mkv": {}, ".webm": {}, ".mov": {}, ".m4v": {}, ".avi": {},
}

// ClassifyMedia 按扩展名识别媒体类型。返回 ok=false 表示非媒体文件
// （调用方直接跳过，不算错误——库目录里混存 nfo/txt/字幕是常态）。
// 大小写不敏感：NAS 上大写扩展名（IMG_001.JPG）极常见。
func ClassifyMedia(name string) (mediaType string, ok bool) {
	ext := strings.ToLower(filepath.Ext(name))
	if ext == ".gif" {
		return MediaTypeAnimatedImage, true
	}
	if _, isImg := imageExts[ext]; isImg {
		return MediaTypeImage, true
	}
	if _, isVid := videoExts[ext]; isVid {
		return MediaTypeVideo, true
	}
	return "", false
}

// systemDirNames 是绝不扫描的 NAS/系统目录名（小写比较）。
// 为什么要点名列出：它们不是 '.' 开头，躲得过隐藏目录规则，却是文件系统
// 自己产生的"用户眼里不存在"的目录——
//   - #recycle：Synology 回收站
//   - $recycle.bin：Windows 回收站
//   - _gsdata_：群晖 Synology Drive 的同步元数据目录
//   - system volume information：Windows 卷索引目录
var systemDirNames = map[string]struct{}{
	"#recycle":                  {},
	"$recycle.bin":              {},
	"_gsdata_":                  {},
	"system volume information": {},
}

// IsHiddenName '.' 开头的名字（Unix 隐藏约定；Windows 资源管理器的隐藏
// 属性在 WalkDir 层拿不到，但 NAS 主流是 SMB/EXT4，点前缀是事实标准，
// ffmpeg 临时文件（.qimeng-*.webp）也靠它被排除）。
func IsHiddenName(name string) bool {
	return strings.HasPrefix(name, ".")
}

// SkipDir 报告目录是否应整棵跳过：隐藏目录（. 开头）或系统目录。
// 用于 WalkDir 返回 fs.SkipDir 与 fsnotify 递归注册时排除。
func SkipDir(name string) bool {
	if IsHiddenName(name) {
		return true
	}
	_, isSystem := systemDirNames[strings.ToLower(name)]
	return isSystem
}
