package thumbnail

import (
	"crypto/sha256"
	"encoding/hex"
	"path/filepath"
	"strconv"
)

// Size 缩略图最长边像素数。进缓存键：同一资产每个尺寸一份独立缓存，
// 列表网格与详情预览各自命中、互不失效。
type Size int

// 预设档位。为什么以像素值而非语义名（"grid"等）进键：像素值是缩略图的真实身份，
// 调整档位像素 = 新键 = 新文件（旧文件自然成为孤儿，交给对账清理）；若用语义名，
// 改像素会让同名键被新内容覆盖，HTTP ETag 的 immutable 语义反而变复杂。
// SizeGrid 与 config.Thumbnail.LongSide 默认值 512 对齐（列表网格档）。
const (
	SizeSmall   Size = 256  // 小网格档（openapi size=sm）
	SizeGrid    Size = 512  // 列表网格档（默认档，与 config.Thumbnail.LongSide 默认值对齐）
	SizePreview Size = 1024 // 详情大图档（openapi size=lg）
)

// thumbsDirName 是 dataDir 下缩略图缓存根目录名。
const thumbsDirName = "thumbs"

// CacheKey 计算缩略图缓存键：SHA-256(assetID + ":" + size) 的 hex 编码
//【逐字遵守 DOMAIN_RULES §11：SHA-256(assetId+size) 前缀 hex，存数据目录】。
// 为什么插 ":" 分隔符：防止纯拼接的边界歧义——理论上 assetID="a"+size 串 "bc"
// 与 assetID="ab"+size 串 "c" 会撞键；固定分隔符彻底排除。
// 键的稳定性由单测黄金向量锁定：键是磁盘缓存与 HTTP 缓存头的公共名字，
// 算法意外变更会让全库缩略图一夜之间变成孤儿。
func CacheKey(assetID string, size Size) string {
	sum := sha256.Sum256([]byte(assetID + ":" + strconv.Itoa(int(size))))
	return hex.EncodeToString(sum[:])
}

// ThumbPath 返回缓存键对应的文件路径：dataDir/thumbs/{key[:2]}/{key}.webp。
// 为什么加 {key[:2]} 一层子目录：媒体库动辄数万资产 × 多档尺寸，单目录海量小文件
// 在常见文件系统上会查找退化；hex 键前两位天然均匀散列成 256 个子目录。
// 为什么不做 LRU/数量上限清理【逐字遵守 DOMAIN_RULES §11：永不因数量上限删除
// 有效缓存】：缩略图是几十 KB 级小文件，NAS 场景存储成本远低于重新抽帧的计算成本；
// 孤儿（资产已删但缩略图还在）由后续对账任务清理，本期只负责键计算与目录布局。
// 约定：key 必须是 CacheKey 的产物（64 位 hex），短键传入属编程错误。
func ThumbPath(dataDir string, key string) string {
	return filepath.Join(dataDir, thumbsDirName, key[:2], key+".webp")
}
