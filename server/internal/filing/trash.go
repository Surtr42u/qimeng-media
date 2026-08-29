package filing

import (
	"errors"
	"fmt"
	"path/filepath"
	"time"
)

// 回收站常量。
const (
	// TrashRootName 数据目录下回收站子目录名（docs/SECURITY.md「回收站」：/data/trash/）。
	TrashRootName = "trash"
	// TrashMetaSuffix 元数据文件统一后缀：与被删文件同目录、同名 + 后缀。
	TrashMetaSuffix = ".meta.json"
	// DefaultTrashRetentionDays 默认保留天数（docs/DOMAIN_RULES.md §9：默认 30 天）。
	DefaultTrashRetentionDays = 30
)

// TrashMeta 是与被删文件同目录存放的元数据 JSON。meta 文件是回收站的 source
// of truth：枚举回收站 = 遍历全部 *.meta.json；恢复 = 读 meta 反算原路径。
// 文件本体只是 payload，与 meta 同生命周期（删除/恢复/清理永远成对操作）。
type TrashMeta struct {
	AssetID      string    `json:"asset_id"`
	LibraryID    string    `json:"library_id"`    // 恢复目标库（删除时刻快照；恢复按它查库根）
	OriginalPath string    `json:"original_path"` // 库内相对路径（Clean 后、/ 分隔）
	MediaType    string    `json:"media_type"`    // image/video（恢复重建库行快照，避免按扩展名重猜）
	DeletedAt    time.Time `json:"deleted_at"`
}

// TrashPathFor 计算文件删除后进回收站的落点（纯路径计算，不落盘）。
//
// 布局：<dataDir>/trash/<19位UnixNano>/<assetID>/<原相对路径结构>
//
//	文件：<dataDir>/trash/<stamp>/<assetID>/作者/作品/a.jpg
//	元数据：<dataDir>/trash/<stamp>/<assetID>/作者/作品/a.jpg.meta.json（同目录同名）
//
// 设计理由：
//   - 时间戳子目录解决同名冲突：同一文件删了又恢复又删（或不同资产不同时间删到
//     同一路径），删除时刻必然不同，天然分桶互不覆盖。用 19 位零填充 UnixNano 而
//     非格式化时间字符串：int64 最大值恰为 19 位，零填充定宽使字典序 == 时间序
//     （遍历即按删除时间排序），且无时区歧义、无非法字符风险；
//   - assetID 层保证同一时刻批量删除时不同资产的同名文件互不覆盖；
//   - 保留原相对路径结构：恢复时直接拼回库根；用户浏览回收站也能认出出处
//     （"作者/作品/a.jpg" 而非 "a-8f3a2b.jpg"）；
//   - meta 与文件同目录同名 + TrashMetaSuffix 后缀：恢复/清理/对账永远在同一个
//     目录里找成对的两个文件，不存在 meta 与文件分离的中间态。
//
// originalRelPath 会先过 NormalizeRelPath：数据库路径理论上可信，但"统一入口"是
// 本包存在的意义（见 doc.go）——即使库里混入脏数据（人工改库、迁移 bug），也绝不
// 能让回收站自身被路径穿越。
func TrashPathFor(dataDir, originalRelPath string, deletedAt time.Time, assetID string) (trashFilePath, metaFilePath string, err error) {
	rel, err := NormalizeRelPath(originalRelPath)
	if err != nil {
		return "", "", err
	}
	if assetID == "" {
		return "", "", errors.New("filing: assetID 为空")
	}
	stamp := fmt.Sprintf("%019d", deletedAt.UnixNano())
	trashFilePath = filepath.Join(dataDir, TrashRootName, stamp, assetID, filepath.FromSlash(rel))
	metaFilePath = trashFilePath + TrashMetaSuffix
	return trashFilePath, metaFilePath, nil
}

// RestorePaths 从回收站元数据反算库内原相对路径。只信 meta、不做字符串猜测；
// OriginalPath 会再过一次 NormalizeRelPath 防御性校验（meta 可能被人工编辑或
// 损坏），返回错误时调用方应把该条目标记为不可恢复并保留原文件。
func RestorePaths(meta TrashMeta) (origRel string, err error) {
	return NormalizeRelPath(meta.OriginalPath)
}

// TrashExpired 判断回收站条目是否已超过保留天数（docs/DOMAIN_RULES.md §9，
// 到期由服务端后台物理清除）。
//
// 用 AddDate 而非 24h 乘法：DST 切换日 24h 乘法会偏差 1 小时，日历语义按
// AddDate 才正确。retentionDays < 1 视为未配置 → 永不判过期：回收站过期清除
// 是物理删除，配置事故（误配 0）绝不能触发"清空回收站"这个危险方向，宁可堆积
// 等待修正配置。
func TrashExpired(meta TrashMeta, now time.Time, retentionDays int) bool {
	if retentionDays < 1 {
		return false
	}
	return !now.Before(meta.DeletedAt.AddDate(0, 0, retentionDays))
}
