package authorattach

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"time"

	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/store/db"
)

// RecordAssetWorks makes existing regular links reproducible from TXT without
// rewriting author rows, association timestamps, or provenance.
// The caller owns the transaction, including any asset filename change.
func (s *Service) RecordAssetWorks(ctx context.Context, qtx *db.Queries, now time.Time, assetID string) error {
	asset, err := qtx.GetAsset(ctx, assetID)
	if err != nil {
		return err
	}
	authors, err := qtx.ListAssetAuthorRefs(ctx, assetID)
	if err != nil {
		return err
	}
	for _, author := range authors {
		if author.Type != "regular" {
			continue
		}
		if _, err := s.Apply(ctx, qtx, now, AttachRequest{AssetID: assetID, FinalName: asset.FileName, AuthorID: author.ID, recordWorksOnly: true}); err != nil {
			// Portable v2 deliberately retains legacy opaque IDs. TXT has no ID
			// column, so an unrepresentable identity must retain its real link.
			if errors.Is(err, ErrDisplayNameIdentityMismatch) {
				s.logger().Warn("历史作者身份无法往返TXT，保留既有关联并由v2备份传输", "err", err)
				continue
			}
			return err
		}
	}
	return nil
}

// RenameAssetWorks 在资产文件名变化的同一事务内把 TXT 作品行由旧名替换为
// 新名（调用方持事务，失败返回 error 由调用方回滚；本函数绝不自行 Begin）。
//
// 与 RecordAssetWorks 的关键差异：RecordAssetWorks 只追加当前文件名（旧行
// 留给统一重建按「匹配不到任何文件」自然失效，2026-11 前的已知缺口），
// RenameAssetWorks 是**替换**——先对资产当前关联的每个 regular 作者删除旧
// 文件名作品行（旧行可能因历史污染存在于任意片段；不存在则 no-op），再沿
// appendAssetWorkLines/Apply(recordWorksOnly) 的既有路径幂等追加新文件名。
// 块顺序、编号行、来源区一律不动；只有作品行集合变化。
//
// 上传条目元数据同事务维护：实际新增行走 recordAddedLines 登记，旧行随片段
// 内容消失由 pruneFragmentEntries/authoring.PruneUploadEntries 修剪（复用
// authorattach 既有实现，不复制逻辑）。
func (s *Service) RenameAssetWorks(ctx context.Context, qtx *db.Queries, now time.Time, assetID, oldFileName, newFileName string) error {
	oldFileName = strings.TrimSpace(oldFileName)
	newFileName = strings.TrimSpace(newFileName)
	if oldFileName == "" || newFileName == "" {
		return errors.New("authorattach: 改名前后的文件名不能为空")
	}
	if oldFileName == newFileName {
		// 纯目录移动：工作目录/作者关联不随目录变化，TXT 作品行无需动。
		return nil
	}
	refs, err := qtx.ListAssetAuthorRefs(ctx, assetID)
	if err != nil {
		return fmt.Errorf("authorattach: 查询资产作者: %w", err)
	}
	authors := make([]AuthorRef, 0, len(refs))
	for _, ref := range refs {
		if ref.Type == authoring.AuthorTypeRegular {
			authors = append(authors, AuthorRef{ID: ref.ID, DisplayName: ref.DisplayName})
		}
	}
	if len(authors) == 0 {
		return nil // 无常规作者关联：TXT 没有需要维护的作品行。
	}

	fragments, err := LoadSources(ctx, qtx)
	if err != nil {
		return err
	}
	changed := make(map[string]string, len(fragments))
	for _, author := range authors {
		for i := range fragments {
			newContent, removed := authoring.RemoveWorks(fragments[i].Content, author.ID, []string{oldFileName})
			if len(removed) == 0 {
				continue
			}
			fragments[i].Content = newContent
			changed[fragments[i].Filename] = newContent
		}
	}

	// 病态存量（显示名推不出身份且片段无块）不能新建漂移块：跳过该作者的
	// 追加，但前面已完成的旧行移除仍保留——与 RecordAssetWorks 对
	// ErrDisplayNameIdentityMismatch 的兼容口径一致，改名不因此失败。
	appendable := make([]AuthorRef, 0, len(authors))
	for _, author := range authors {
		if _, _, ok := findAuthorBlock(fragments, author.ID); !ok {
			if _, ok := displayNameAliases(author.DisplayName, author.ID); !ok {
				s.logger().Warn("历史作者身份无法往返TXT，保留既有关联并由v2备份传输",
					"authorId", author.ID, "displayName", author.DisplayName)
				continue
			}
		}
		appendable = append(appendable, author)
	}
	fragments, added, err := appendAssetWorkLines(fragments, appendable, newFileName, now, changed)
	if err != nil {
		return err
	}
	if len(changed) > 0 {
		if err := PersistSources(ctx, qtx, now, fragments); err != nil {
			return err
		}
	}
	for fragment, entries := range added {
		for _, entry := range entries {
			if err := recordAddedLines(ctx, qtx, now, fragment, entry); err != nil {
				return err
			}
		}
	}
	for fragment, content := range changed {
		if err := s.pruneFragmentEntries(ctx, qtx, now, fragment, content); err != nil {
			return err
		}
	}
	return nil
}

// DropAssetWorks 在资产被删除的同一事务内移除其作品行（调用方持事务，
// 本函数绝不自行 Begin）：遍历全部片段，对资产当前关联的每个 regular 作者
// 删除 currentFileName 作品行，随后按片段内容修剪上传条目元数据。
//
// 回收站物理删除路径的特殊性：MoveToTrash 早已删除 assets 行，asset_authors
// 由外键级联清空，此刻已无法反查作者。为保证「TXT 始终与资产文件名一致」，
// 当资产行确认不存在时退回文件名级清理：仅当库内不再有任何同名资产时，
// 清除所有片段块中的该文件名作品行（同名资产仍存活则保留，避免误伤）。
// 调用方（mediawrite 回收站）还须跳过「其他可恢复的同名回收站条目」。
func (s *Service) DropAssetWorks(ctx context.Context, qtx *db.Queries, now time.Time, assetID, currentFileName string) error {
	currentFileName = strings.TrimSpace(currentFileName)
	if currentFileName == "" {
		return errors.New("authorattach: 待删除作品行的文件名不能为空")
	}
	refs, err := qtx.ListAssetAuthorRefs(ctx, assetID)
	if err != nil {
		return fmt.Errorf("authorattach: 查询资产作者: %w", err)
	}
	authorIDs := make([]string, 0, len(refs))
	for _, ref := range refs {
		if ref.Type == authoring.AuthorTypeRegular {
			authorIDs = append(authorIDs, ref.ID)
		}
	}
	if len(authorIDs) > 0 {
		return s.removeWorkLines(ctx, qtx, now, currentFileName, authorIDs)
	}
	// 关联为空可能是「资产存在但无常规作者」，也可能是回收站物理删除时
	// 资产行/关联已被级联清空。后者需要文件名级兜底。
	if _, err := qtx.GetAsset(ctx, assetID); err == nil {
		return nil
	} else if !errors.Is(err, sql.ErrNoRows) {
		return fmt.Errorf("authorattach: 查询资产: %w", err)
	}
	remaining, err := qtx.ListAssetIDsByFileName(ctx, currentFileName)
	if err != nil {
		return fmt.Errorf("authorattach: 查询同名资产: %w", err)
	}
	if len(remaining) > 0 {
		return nil // 同名资产仍在库：作品行仍被使用，不得删除。
	}
	fragments, err := LoadSources(ctx, qtx)
	if err != nil {
		return err
	}
	return s.removeWorkLines(ctx, qtx, now, currentFileName, fragmentAuthorIDs(fragments))
}

// removeWorkLines 从全部片段中逐个 authorID 删除 fileName 作品行并修剪对应
// 片段的上传条目。authorIDs 为空/行不存在时零写入。
func (s *Service) removeWorkLines(ctx context.Context, qtx *db.Queries, now time.Time, fileName string, authorIDs []string) error {
	if len(authorIDs) == 0 {
		return nil
	}
	fragments, err := LoadSources(ctx, qtx)
	if err != nil {
		return err
	}
	changed := make(map[string]string, len(fragments))
	for _, authorID := range authorIDs {
		for i := range fragments {
			newContent, removed := authoring.RemoveWorks(fragments[i].Content, authorID, []string{fileName})
			if len(removed) == 0 {
				continue
			}
			fragments[i].Content = newContent
			changed[fragments[i].Filename] = newContent
		}
	}
	if len(changed) == 0 {
		return nil
	}
	if err := PersistSources(ctx, qtx, now, fragments); err != nil {
		return err
	}
	for fragment, content := range changed {
		if err := s.pruneFragmentEntries(ctx, qtx, now, fragment, content); err != nil {
			return err
		}
	}
	return nil
}

// fragmentAuthorIDs 返回全部片段块按身份规则回读的作者 id（保序去重）。
// 回收站物理删除时关联行已级联清空，只能从 TXT 真相反推需要清理的块。
func fragmentAuthorIDs(fragments []Source) []string {
	seen := make(map[string]bool)
	var ids []string
	for _, fragment := range fragments {
		for _, block := range authoring.ParseAuthorBlocks(fragment.Content) {
			if len(block.AuthorNames) == 0 {
				continue
			}
			id := authoring.GenerateAuthorID(block.AuthorNames[0])
			if id == "" || seen[id] {
				continue
			}
			seen[id] = true
			ids = append(ids, id)
		}
	}
	return ids
}
