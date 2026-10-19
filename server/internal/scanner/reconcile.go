// reconcile.go 负责扫描快照与当前文件/身份的短事务对账；不得持库锁遍历或探测视频。
package scanner

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"

	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// reconcile 扫描收尾对账：对"库内有记录但文件树没看到"的消失集执行
// 移动合并或删除，计数直接累计进 res（合并会把 walk 阶段预记的 Added
// 回退——被合并的新路径不是"新增"，是老身份换了住址）。
func (s *Scanner) reconcile(ctx context.Context, lib db.Library, existing []db.Asset, seen map[string]struct{}, added []db.Asset, res *ScanResult) error {
	// 候选按size+mtime分桶并预排序；每个候选最多复核一次，避免批量改名N²次SQL/Stat。
	buckets := make(map[mergeKey][]db.Asset)
	for _, cand := range added {
		key := mergeKey{cand.SizeBytes, cand.Mtime}
		buckets[key] = append(buckets[key], cand)
	}
	for _, bucket := range buckets {
		sort.Slice(bucket, func(i, j int) bool {
			return bucket[i].CreatedAt > bucket[j].CreatedAt || (bucket[i].CreatedAt == bucket[j].CreatedAt && bucket[i].AssetID > bucket[j].AssetID)
		})
	}
	for _, gone := range existing {
		if _, ok := seen[gone.RelPath]; ok {
			continue
		}
		err := filing.WithLibraryGate(lib.ID, func() error {
			current, err := s.q.GetAsset(ctx, gone.AssetID)
			if errors.Is(err, sql.ErrNoRows) {
				return nil
			}
			if err != nil {
				return err
			}
			if current.RelPath != gone.RelPath || current.UpdatedAt != gone.UpdatedAt {
				return nil
			}
			_, err = os.Stat(filepath.Join(lib.RootPath, filepath.FromSlash(gone.RelPath)))
			if err == nil {
				return nil
			}
			if !errors.Is(err, os.ErrNotExist) {
				return err
			}
			cand, found, err := s.takeLiveMergeCandidate(ctx, lib, gone, buckets)
			if err != nil {
				return err
			}
			if !found {
				if err := s.deleteGone(ctx, lib, gone); err != nil {
					return err
				}
				res.Removed++
				return nil
			}
			if err := s.applyMoveMerge(ctx, lib, gone, cand); err != nil {
				return err
			}
			res.Moved++
			res.Added--
			return nil
		})
		if err != nil {
			return err
		}
	}
	return nil
}

type mergeKey struct {
	size  int64
	mtime string
}

// takeLiveMergeCandidate 只从匹配桶按既有tie-break消费，锁内复核候选仍是同一身份/路径。
func (s *Scanner) takeLiveMergeCandidate(ctx context.Context, lib db.Library, gone db.Asset, buckets map[mergeKey][]db.Asset) (db.Asset, bool, error) {
	key := mergeKey{gone.SizeBytes, gone.Mtime}
	for len(buckets[key]) > 0 {
		bucket := buckets[key]
		cand := bucket[0]
		buckets[key] = bucket[1:]
		if cand.AssetID == gone.AssetID {
			continue
		}
		current, err := s.q.GetAsset(ctx, cand.AssetID)
		if errors.Is(err, sql.ErrNoRows) {
			continue
		}
		if err != nil {
			return db.Asset{}, false, err
		}
		if current.RelPath != cand.RelPath || current.UpdatedAt != cand.UpdatedAt {
			continue
		}
		fi, err := os.Stat(filepath.Join(lib.RootPath, filepath.FromSlash(cand.RelPath)))
		if errors.Is(err, os.ErrNotExist) {
			continue
		}
		if err != nil {
			return db.Asset{}, false, err
		}
		if fi.Size() != cand.SizeBytes || store.FormatTimestamp(fi.ModTime()) != cand.Mtime {
			continue
		}
		if len(bucket) > 1 {
			s.logger.Warn("scanner: 移动合并快照有多个候选，选择最新有效身份", "oldPath", gone.RelPath, "newPath", cand.RelPath, "candidates", len(bucket))
		}
		return cand, true, nil
	}
	return db.Asset{}, false, nil
}

// deleteGone 删除一条消失记录（view_events 无外键，事件流天然保留）。
func (s *Scanner) deleteGone(ctx context.Context, lib db.Library, gone db.Asset) error {
	if err := s.q.DeleteAsset(ctx, gone.AssetID); err != nil {
		return fmt.Errorf("scanner: 删除消失资产 %s: %w", gone.RelPath, err)
	}
	s.logger.Info("scanner: 文件消失，删除记录（view_events 事件流保留）",
		"libraryId", lib.ID, "relPath", gone.RelPath, "assetId", gone.AssetID)
	return nil
}

// applyMoveMerge 把消失记录的身份搬到新路径（ARCHITECTURE §6 启发式）。
// 顺序严格：先删新插入的记录释放 (library_id, rel_path) 唯一索引，
// 再 UPDATE 旧身份的路径属性；颠倒会撞唯一约束。
// cos 库在改路径后按新 rel 重算作者关联（作者目录整体改名是移动合并的
// 典型场景：旧目录文件消失+新目录文件出现且 size+mtime 一致）；normal
// 库不重算——文件名不变，出处/角色匹配结果不变（文件头注释）。
func (s *Scanner) applyMoveMerge(ctx context.Context, lib db.Library, gone, cand db.Asset) error {
	if gone.AssetID == cand.AssetID {
		return nil
	}
	// 删除候选与迁移旧身份必须一起提交；失败回滚不能级联损失候选关联。
	if s.conn == nil {
		return errors.New("scanner: 移动合并需要事务连接")
	}
	if err := s.runAssetTx(ctx, func(q *db.Queries) error {
		if err := q.DeleteAsset(ctx, cand.AssetID); err != nil {
			return err
		}
		_, err := q.MoveAssetPath(ctx, db.MoveAssetPathParams{
			RelPath: cand.RelPath, FileName: cand.FileName,
			UpdatedAt: store.FormatTimestamp(s.now()), AssetID: gone.AssetID,
		})
		return err
	}); err != nil {
		return fmt.Errorf("scanner: 移动合并 %s→%s: %w", gone.RelPath, cand.RelPath, err)
	}

	if lib.Kind == LibraryKindCos {
		// 新路径的作者目录可能不同于旧目录：重算保证映射正确；失败只
		// 警告——重扫自愈（重扫按目录重建关联，见 ingestCosFile）。
		if err := s.recomputeCosAuthor(ctx, gone.AssetID, cand.RelPath); err != nil {
			s.logger.Warn("scanner: 移动合并后重算 COS 作者失败（重扫自愈）",
				"assetId", gone.AssetID, "err", err)
		}
	}
	// 审计日志（adr/0004「合并操作记录审计日志（可追溯）」）。
	s.logger.Info("scanner: 移动合并（身份保留，路径属性更新）",
		"assetId", gone.AssetID, "oldPath", gone.RelPath, "newPath", cand.RelPath)
	return nil
}

// pickMergeCandidate 在候选池中为消失记录找移动合并候选。
// 纯函数（领域规则，便于单测）：匹配条件 = size 与 mtime 字符串完全一致
// （rename/move 不改文件内容与 mtime；任何一项变了都视为"删旧+增新"）。
// 返回 (选中下标, 匹配数)；无候选时下标为 -1。
// 多候选歧义（同名同尺寸批量拷贝后原件消失）：取 (created_at, asset_id)
// 字典序最新者——created_at 同毫秒时 UUIDv7 的时间前缀保证 asset_id
// 字典序仍是时间序，排序确定性可复现。匹配数>1 由调用方 warn 留痕。
func pickMergeCandidate(candidates []db.Asset, gone db.Asset) (best, matches int) {
	best = -1
	for i, c := range candidates {
		if c.SizeBytes != gone.SizeBytes || c.Mtime != gone.Mtime {
			continue
		}
		matches++
		if best < 0 || c.CreatedAt > candidates[best].CreatedAt ||
			(c.CreatedAt == candidates[best].CreatedAt && c.AssetID > candidates[best].AssetID) {
			best = i
		}
	}
	return best, matches
}
