package authorattach

// manage.go：作者管理编排（删除/改名，ADR-0024 修订 5，2026-10-13）。
// 与 edit.go 同一契约：调用方开事务，本层只做片段手术与表写入的编排；
// 全部文本手术复用 authoring 包的纯函数（RemoveAuthorBlocks /
// RewriteAuthorBlockNames / AliasesForAuthorID），禁止在本层重写块边界逻辑。
//
// 删除三清语义（DOMAIN_RULES §6「删除作者保留文件」）：TXT 块整块移除 +
// asset_authors 关联删除 + 作者行删除；上传条目（重导入保护的比对依据）
// 中该作者条目同步清除——块没了、条目留着会在同文件名重导入时误报 409。
// 不触发统一重建：关联显式删除、其余作者块不受影响，重建结果与本操作终态
// 等价（重建从片段重放会得出同样的作者集与关联集）。
//
// 改名语义：TXT 真相原地重写（全部片段中该作者块的编号行别名列表整体替换，
// 作品/来源区只字不动）+ 身份迁移（新首别名派生 id 变化时：新行复制旧行的
// followed/created_at/origin → 关联改挂 → 删旧行；关注随迁）。上传条目
// 的 AuthorID/Names 同步改指，否则重导入保护对改名后的块误报 409。

import (
	"context"
	"fmt"
	"strings"
	"time"

	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/store/db"
)

// DeleteAuthor 删除常规作者（调用方事务内执行；404/400 由哨兵错误承载）。
func (s *Service) DeleteAuthor(ctx context.Context, qtx *db.Queries, now time.Time, authorID string) error {
	if _, err := s.ResolveRegularAuthor(ctx, qtx, authorID); err != nil {
		return err
	}
	fragments, err := LoadSources(ctx, qtx)
	if err != nil {
		return err
	}
	changed := false
	for i := range fragments {
		newContent, removed := authoring.RemoveAuthorBlocks(fragments[i].Content, authorID)
		if removed {
			fragments[i].Content = newContent
			changed = true
		}
	}
	if changed {
		if err := PersistSources(ctx, qtx, now, fragments); err != nil {
			return err
		}
	}
	if err := removeAuthorEntries(ctx, qtx, now, authorID); err != nil {
		return err
	}
	if err := qtx.DeleteAssetAuthorsByAuthorIds(ctx, []string{authorID}); err != nil {
		return fmt.Errorf("authorattach: 删除作者关联: %w", err)
	}
	rows, err := qtx.DeleteAuthor(ctx, authorID)
	if err != nil {
		return fmt.Errorf("authorattach: 删除作者行: %w", err)
	}
	if rows == 0 {
		// ResolveRegularAuthor 已确认存在；并发删除兜底为 404 语义。
		return ErrAuthorNotFound
	}
	return nil
}

// RenameAuthor 修改作者显示名（调用方事务内执行；返回迁移后的作者引用）。
// 名字规范化沿用 CanonicalAuthorNames（别名分隔与去备注口径同按名新建）。
// 两分支共同不变量：**块重写先于/独立于身份迁移**——显示名的事实源是片段块
// （统一重建以块首遇显示名为准），只改表不改块会在下次重建被冲回。
// 新显示名与现状（多别名显示名按 const 连接后全等）一致 → 幂等零写入。
func (s *Service) RenameAuthor(ctx context.Context, qtx *db.Queries, now time.Time, authorID, newDisplayName string) (AuthorRef, error) {
	oldDisplay, err := s.ResolveRegularAuthor(ctx, qtx, authorID)
	if err != nil {
		return AuthorRef{}, err
	}
	names := authoring.CanonicalAuthorNames(newDisplayName)
	if len(names) == 0 {
		return AuthorRef{}, ErrInvalidAuthorName
	}
	newID := authoring.GenerateAuthorID(names[0])
	newDisplay := strings.Join(names, authoring.DisplayNameSeparator)
	identityChanged := newID != authorID

	// 新身份撞既有行（常规或 COS）→ 409（本端点不做作者合并）。先于一切写入，
	// 保证冲突零副作用。
	if identityChanged {
		rows, err := qtx.ListAuthors(ctx)
		if err != nil {
			return AuthorRef{}, fmt.Errorf("authorattach: 查询作者表: %w", err)
		}
		for i := range rows {
			if rows[i].ID == newID {
				return AuthorRef{}, ErrAuthorIDConflict
			}
		}
	}

	// TXT 真相原地重写：全部片段中旧 id 的块编号行改为新别名列表
	// （作品/来源区只字不动；编号数字保留）。
	fragments, err := LoadSources(ctx, qtx)
	if err != nil {
		return AuthorRef{}, err
	}
	changed := false
	for i := range fragments {
		if nc, found := authoring.RewriteAuthorBlockNames(fragments[i].Content, authorID, names); found && nc != fragments[i].Content {
			fragments[i].Content = nc
			changed = true
		}
	}
	if changed {
		if err := PersistSources(ctx, qtx, now, fragments); err != nil {
			return AuthorRef{}, err
		}
	}

	if !identityChanged {
		// 同身份：仅显示名（块已重写；无块作者行直改显示名）。
		if newDisplay != oldDisplay {
			if _, err := qtx.UpdateAuthorDisplayName(ctx, db.UpdateAuthorDisplayNameParams{
				DisplayName: newDisplay, ID: authorID,
			}); err != nil {
				return AuthorRef{}, fmt.Errorf("authorattach: 更新作者显示名: %w", err)
			}
		}
		return AuthorRef{ID: authorID, DisplayName: newDisplay}, nil
	}

	// 身份迁移：先落新行（复制 followed/created_at/origin，type 恒 regular）
	// → 关联改挂新身份 → 删旧行（FK 有向序：父行存在于每一步）。
	if _, err := qtx.CopyAuthorWithNewID(ctx, db.CopyAuthorWithNewIDParams{
		ID: newID, DisplayName: newDisplay, ID_2: authorID,
	}); err != nil {
		return AuthorRef{}, fmt.Errorf("authorattach: 落新身份行: %w", err)
	}
	if _, err := qtx.RenameAssetAuthors(ctx, db.RenameAssetAuthorsParams{
		AuthorID: newID, AuthorID_2: authorID,
	}); err != nil {
		return AuthorRef{}, fmt.Errorf("authorattach: 迁移作者关联: %w", err)
	}
	if _, err := qtx.DeleteAuthor(ctx, authorID); err != nil {
		return AuthorRef{}, fmt.Errorf("authorattach: 删除旧身份行: %w", err)
	}

	if err := renameAuthorEntries(ctx, qtx, now, authorID, names, newDisplay); err != nil {
		return AuthorRef{}, err
	}
	return AuthorRef{ID: newID, DisplayName: newDisplay}, nil
}

// removeAuthorEntries 清除上传条目中该作者的全部条目（全片段；空条目组
// 整组剔除）。无命中零写入。
func removeAuthorEntries(ctx context.Context, qtx *db.Queries, now time.Time, authorID string) error {
	entries, err := LoadUploadEntries(ctx, qtx)
	if err != nil {
		return err
	}
	changed := false
	for fragment, list := range entries {
		kept := make([]authoring.UploadEntry, 0, len(list))
		for _, e := range list {
			if e.AuthorID == authorID {
				changed = true
				continue
			}
			kept = append(kept, e)
		}
		if len(kept) == 0 {
			delete(entries, fragment)
			continue
		}
		entries[fragment] = kept
	}
	if !changed {
		return nil
	}
	return SaveUploadEntries(ctx, qtx, now, entries)
}

// renameAuthorEntries 把上传条目中旧身份的条目改指新身份（AuthorID 与
// Names/DisplayName 同步——Names 是缺失块重建的编号行来源，必须与重写后的
// 块别名一致，否则 MergeUploadEntries 重建出的块回读 id 与迁移后 id 分裂）。
func renameAuthorEntries(ctx context.Context, qtx *db.Queries, now time.Time, oldID string, names []string, newDisplay string) error {
	entries, err := LoadUploadEntries(ctx, qtx)
	if err != nil {
		return err
	}
	changed := false
	for fragment, list := range entries {
		for i := range list {
			if list[i].AuthorID != oldID {
				continue
			}
			list[i].AuthorID = authoring.GenerateAuthorID(names[0])
			list[i].DisplayName = newDisplay
			list[i].Names = append([]string(nil), names...)
			changed = true
		}
		entries[fragment] = list
	}
	if !changed {
		return nil
	}
	return SaveUploadEntries(ctx, qtx, now, entries)
}
