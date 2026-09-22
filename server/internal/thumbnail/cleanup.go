// cleanup.go：资产缩略图缓存的删除联动（DOMAIN_RULES §11，2026-09-22）。
//
// 背景：此前只有懒生成与预热两条写路径，删除路径不清缓存——资产物理
// 消失后 thumbs/ 下留死文件（任务 S 实测 870 个孤儿 ≈12%）。全量对账
// （全库资产 × 全档位键 反查磁盘）需要资产清单查询配合，本期先落
// "删除时机联动"：回收站单条物理删除/清空回收站/到期清扫/删除库四个
// 入口调 DeleteAssetThumbs；扫描器外部删除（文件在库外被移走，
// scanner deleteGone/removeIfPresent）产出的孤儿仍待对账任务兜底。
package thumbnail

import (
	"errors"
	"io/fs"
	"os"
)

// cleanupExts 是删除联动要清理的扩展名全集。为什么两种都删：静图格式由
// 启动期 ffmpeg 探测决定（stillformat.go），进程生命周期内固定但部署间
// 可翻转（webp↔jpeg）——当前部署是 webp 时，历史上 jpeg 时期的缓存文件
// 仍在磁盘上，只删当前格式会留一半孤儿。
var cleanupExts = [...]string{".webp", ".jpg"}

// DeleteAssetThumbs 删除该资产全部档位的缩略图缓存文件（幂等：文件不存在
// 即视为成功）。尽力而为语义：单个删除失败只记日志不上抛——调用方（物理
// 删除/清空/到期清扫/删库）的主操作已成功，缓存残留由对账兜底，不该让
// 缓存清理失败反向打断业务响应。
//
// 档位集合除三个预设档外，当前生效的 md 档像素（GridLongSide，可被
// long_side 配置覆盖）若 ≠ SizeGrid 则两个键都删——配置漂移前的旧键文件
// 同属该资产的孤儿。误删面为零：键含 assetID（SHA-256），不同资产不可能
// 同键；多删的只是不存在路径上的 os.Remove（ErrNotExist 忽略）。
func (g *Generator) DeleteAssetThumbs(assetID string) {
	sizes := []Size{SizeSmall, SizePreview, Size(g.GridLongSide())}
	if Size(g.GridLongSide()) != SizeGrid {
		sizes = append(sizes, SizeGrid) // long_side 改配前/后的两个 md 键都清
	}
	for _, size := range sizes {
		key := CacheKey(assetID, size)
		for _, ext := range cleanupExts {
			p := ThumbPath(g.dataDir, key, ext)
			if err := os.Remove(p); err != nil && !errors.Is(err, fs.ErrNotExist) {
				g.logger.Warn("清理缩略图缓存失败（残留待对账兜底）", "assetId", assetID, "path", p, "err", err)
			}
		}
	}
}
