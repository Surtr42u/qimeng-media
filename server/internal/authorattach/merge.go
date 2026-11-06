// merge.go：作者合并编排（POST /authors/merge，2026-11-06）。
//
// 与 manage.go 同一契约：调用方开事务，本层只做片段手术、表写入与上传条目
// 迁移的编排；全部文本手术复用 authoring 包的纯函数（ParseAuthorBlocks /
// RemoveAuthorBlocks / MergeAuthorBlock / AppendAuthorBlock），禁止在本层
// 重写块边界逻辑。
//
// 合并语义（与 OpenAPI 描述、docs/domain/authors.md「作者合并」同源）：
//   - 源身份删除、目标身份保留；这是不可撤销操作（没有撤销端点）。
//   - TXT 片段是唯一真相：源块从全部片段移除，源块载荷（别名/作品行/来源词）
//     并入目标块；目标块保留在目标片段原位置。目标无块时在首个源块所在片段
//     新建目标块（编号行取目标身份的往返安全别名）。
//   - asset_authors 迁移：目标已有同一资产的行原样保留（origin/created_at
//     不伪造），只删源行；其余源行改挂目标并保留原 provenance（ADR-0032）。
//   - 关注并集、附属档案取舍（目标非空档案优先，源档案仅迁入空目标）、上传
//     写入条目改指目标，全部与片段/关联/作者行删除同事务。
//   - 删除源作者行使用低层 DeleteAuthor，不调用会再次清关联/条目的
//     DeleteAuthor 编排（那会破坏已迁移到目标的数据）。
package authorattach

import (
	"context"
	"errors"
	"fmt"
	"sort"
	"strings"
	"time"

	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/creatorprofile"
	"qimeng-media/server/internal/store/db"
)

// ErrAuthorMergeSelf：source 与 target 是同一身份——合并到自己没有意义，
// handler 映射 400 INVALID_PARAM（不伪造 404/409）。
var ErrAuthorMergeSelf = errors.New("authorattach: cannot merge an author into itself")

// MergeResult 是一次作者合并的实际变更量（与 openapi AuthorMergeResult
// 对应；协议侧改动须同步此处字段，反之亦然）。
type MergeResult struct {
	SourceAuthorID         string
	TargetAuthorID         string
	AssetsMoved            int
	AssociationsDeduped    int
	WorksMerged            int
	SourcesMerged          int
	AliasesMerged          int
	FollowsMerged          bool
	ProfileMoved           bool
	SourceProfileDiscarded bool
	UploadEntriesMoved     int
	DeletedSource          bool
}

// MergeAuthors 把 source 合并进 target（调用方事务内执行；任一失败整体回滚）。
// 404/400 由哨兵错误承载：不存在 → ErrAuthorNotFound、COS/非 regular →
// ErrAuthorNotRegular、同一身份 → ErrAuthorMergeSelf；目标显示名推不出自身
// 身份且必须新建块/改写上传条目时 → ErrDisplayNameIdentityMismatch（提示先
// 改名，绝不写身份漂移的块或条目）。
func (s *Service) MergeAuthors(ctx context.Context, qtx *db.Queries, now time.Time, sourceID, targetID string) (MergeResult, error) {
	sourceID, targetID = strings.TrimSpace(sourceID), strings.TrimSpace(targetID)
	if sourceID == "" || targetID == "" {
		return MergeResult{}, ErrAuthorNotFound
	}
	if sourceID == targetID {
		return MergeResult{}, ErrAuthorMergeSelf
	}
	rows, err := resolveRegularAuthorRows(ctx, qtx, []string{sourceID, targetID})
	if err != nil {
		return MergeResult{}, err
	}
	source, target := rows[sourceID], rows[targetID]

	result := MergeResult{SourceAuthorID: sourceID, TargetAuthorID: targetID}

	// ---- TXT 片段：收集源载荷 → 移除源块 → 并入目标块 ----
	fragments, err := LoadSources(ctx, qtx)
	if err != nil {
		return MergeResult{}, err
	}
	sourcePayload, sourceFragments := collectAuthorBlocks(fragments, sourceID)
	targetIdx, _, targetFound := findAuthorBlock(fragments, targetID)
	contentChanged := false
	for i := range fragments {
		newContent, removed := authoring.RemoveAuthorBlocks(fragments[i].Content, sourceID)
		if !removed {
			continue
		}
		fragments[i].Content = newContent
		contentChanged = true
	}
	var targetAliases []string
	targetFragment := ""
	targetBlockPresent := false
	switch {
	case targetFound:
		merged, stats, found := authoring.MergeAuthorBlock(
			fragments[targetIdx].Content, targetID,
			sourcePayload.AuthorNames, sourcePayload.Works, sourcePayload.Sources)
		if !found {
			// 目标块在移除源块后消失只可能是内部不一致（source != target 时
			// RemoveAuthorBlocks 不会碰目标身份）；失败回滚，绝不落半份。
			return MergeResult{}, fmt.Errorf("authorattach: 合并后目标块 %s 消失", targetID)
		}
		if merged != fragments[targetIdx].Content {
			fragments[targetIdx].Content = merged
			contentChanged = true
		}
		result.WorksMerged = stats.WorksMerged
		result.SourcesMerged = stats.SourcesMerged
		result.AliasesMerged = stats.AliasesMerged
		targetAliases = stats.Aliases
		targetFragment = fragments[targetIdx].Filename
		targetBlockPresent = true
	case len(sourceFragments) > 0:
		// 目标无 TXT 块（格式 C / 按名新建产物）而源有：在首个源块所在片段
		// 新建目标块。别名列表必须往返恒等（回读 id == target），推不出目标
		// 身份的病理存量直接失败（与 ReplaceAuthorSources/Apply 同款防线）。
		baseAliases, ok := authoring.AliasesForAuthorID(target.DisplayName, targetID)
		if !ok {
			return MergeResult{}, fmt.Errorf("authorattach: 目标作者 %s 显示名 %q 与身份不自洽（请先改名）: %w",
				targetID, target.DisplayName, ErrDisplayNameIdentityMismatch)
		}
		targetAliases = mergeLines(baseAliases, sourcePayload.AuthorNames)
		dest := sourceFragments[0]
		fragments[dest].Content = authoring.AppendAuthorBlock(
			fragments[dest].Content, targetAliases, sourcePayload.Sources, sourcePayload.Works)
		targetFragment = fragments[dest].Filename
		targetBlockPresent = true
		contentChanged = true
		result.AliasesMerged = len(targetAliases) - len(baseAliases)
		result.WorksMerged = len(sourcePayload.Works)
		result.SourcesMerged = len(sourcePayload.Sources)
	}
	if contentChanged {
		if err := PersistSources(ctx, qtx, now, fragments); err != nil {
			return MergeResult{}, err
		}
	}

	// ---- 上传写入条目：source 改指 target（与 target 条目合并去重） ----
	movedEntries, err := mergeAuthorEntries(ctx, qtx, now, fragments, sourceID, targetID, targetFragment, targetBlockPresent, targetAliases, target.DisplayName)
	if err != nil {
		return MergeResult{}, err
	}
	result.UploadEntriesMoved = movedEntries

	// ---- asset_authors：迁移 source→target，双端都关联时保留 target 行 ----
	result.AssetsMoved, result.AssociationsDeduped, err = mergeAssetAuthors(ctx, qtx, sourceID, targetID)
	if err != nil {
		return MergeResult{}, err
	}

	// ---- 关注并集 ----
	if target.Followed == 0 && source.Followed == 1 {
		affected, err := qtx.SetAuthorFollow(ctx, db.SetAuthorFollowParams{Followed: 1, ID: targetID})
		if err != nil {
			return MergeResult{}, fmt.Errorf("authorattach: 合并作者关注: %w", err)
		}
		if affected == 0 {
			// 目标行在本事务内被并发删除的兜底（正常不可能）。
			return MergeResult{}, ErrAuthorNotFound
		}
		result.FollowsMerged = true
	}

	// ---- 附属档案：目标非空档案优先；源档案只在目标为空时迁入 ----
	sourceProfile, err := creatorprofile.Load(ctx, qtx, sourceID)
	if err != nil {
		return MergeResult{}, err
	}
	targetProfile, err := creatorprofile.Load(ctx, qtx, targetID)
	if err != nil {
		return MergeResult{}, err
	}
	if !creatorprofile.IsEmpty(sourceProfile) {
		if creatorprofile.IsEmpty(targetProfile) {
			if err := creatorprofile.Move(ctx, qtx, sourceID, targetID, now); err != nil {
				return MergeResult{}, err
			}
			result.ProfileMoved = true
		} else {
			// 人工资料优先：源档案随源身份删除，不静默覆盖目标档案。
			result.SourceProfileDiscarded = true
		}
	}

	// ---- 删除源作者行（块/关联/档案迁移已完成；0021/0022 触发器清理源档案
	// 并推进源身份版本楔；不调用 DeleteAuthor 编排以免二次清关联/条目） ----
	deleted, err := qtx.DeleteAuthor(ctx, sourceID)
	if err != nil {
		return MergeResult{}, fmt.Errorf("authorattach: 删除源作者行: %w", err)
	}
	if deleted == 0 {
		return MergeResult{}, ErrAuthorNotFound
	}
	result.DeletedSource = true
	return result, nil
}

// collectAuthorBlocks 收集 authorID 在全部片段中的块载荷（别名/作品行/
// 来源词按片段序、块序保序去重）并返回其出现的片段下标（首遇去重）。
func collectAuthorBlocks(fragments []Source, authorID string) (authoring.AuthorBlock, []int) {
	var payload authoring.AuthorBlock
	var indexes []int
	for i := range fragments {
		found := false
		for _, block := range authoring.ParseAuthorBlocks(fragments[i].Content) {
			if len(block.AuthorNames) == 0 || authoring.GenerateAuthorID(block.AuthorNames[0]) != authorID {
				continue
			}
			found = true
			payload.AuthorNames = mergeLines(payload.AuthorNames, block.AuthorNames)
			payload.Works = mergeLines(payload.Works, block.Works)
			payload.Sources = mergeLines(payload.Sources, block.Sources)
		}
		if found {
			indexes = append(indexes, i)
		}
	}
	return payload, indexes
}

// mergeAssetAuthors 迁移 source 的资产关联到 target：先计数，再用
// UPDATE OR IGNORE 改挂非冲突行（冲突行因主键保留 target 原有 provenance），
// 最后删除仍留在 source 下的行（即双端都关联的重复行）。返回迁移数/去重数。
func mergeAssetAuthors(ctx context.Context, qtx *db.Queries, sourceID, targetID string) (int, int, error) {
	total, err := qtx.CountAssetAuthorsByAuthorID(ctx, sourceID)
	if err != nil {
		return 0, 0, fmt.Errorf("authorattach: 统计源作者关联: %w", err)
	}
	if total == 0 {
		return 0, 0, nil
	}
	shared, err := qtx.CountSharedAssetAuthors(ctx, db.CountSharedAssetAuthorsParams{
		SourceAuthorID: sourceID, TargetAuthorID: targetID,
	})
	if err != nil {
		return 0, 0, fmt.Errorf("authorattach: 统计双端重复关联: %w", err)
	}
	if err := qtx.MergeAssetAuthors(ctx, db.MergeAssetAuthorsParams{
		TargetAuthorID: targetID, SourceAuthorID: sourceID,
	}); err != nil {
		return 0, 0, fmt.Errorf("authorattach: 迁移作者关联: %w", err)
	}
	if err := qtx.DeleteAssetAuthorsByAuthorID(ctx, sourceID); err != nil {
		return 0, 0, fmt.Errorf("authorattach: 删除重复作者关联: %w", err)
	}
	return int(total - shared), int(shared), nil
}

// mergeAuthorEntries 把 source 的上传条目改指 target，并把 Names/DisplayName
// 同步成目标块的往返安全别名（块缺失时重建出的编号行回读 id == targetID）。
//
// 条目跟着目标块走：targetBlockPresent（目标块存在）时，全部 source 条目
// 汇入目标块所在片段（targetFragment；匿名片段的空文件名是合法片段名，不能
// 用空串判断块是否存在）的 target 条目，作品行/来源词保序去重，而不是留在
// 已无目标块的源片段——否则同文件名重导入源片段会把已并入目标块的行误报
// 为缺失（409）。目标块不存在时按既有 renameAuthorEntries 语义原地改指
// （无块可归属，条目继续保护该片段的写入）。
// 无 source 条目零写入、不要求目标显示名自洽。
func mergeAuthorEntries(ctx context.Context, qtx *db.Queries, now time.Time, fragments []Source, sourceID, targetID, targetFragment string, targetBlockPresent bool, targetAliases []string, targetDisplay string) (int, error) {
	entries, err := LoadUploadEntries(ctx, qtx)
	if err != nil {
		return 0, err
	}
	// 按片段数组序处理（确定性顺序），再兜底处理 entries 中已不在当前片段
	// 数组里的历史键——source 条目无论落在哪都必须改指/清除。
	order := make([]string, 0, len(fragments)+len(entries))
	seen := make(map[string]bool, len(fragments))
	for i := range fragments {
		if !seen[fragments[i].Filename] {
			seen[fragments[i].Filename] = true
			order = append(order, fragments[i].Filename)
		}
	}
	var extra []string
	for fragment := range entries {
		if !seen[fragment] {
			extra = append(extra, fragment)
		}
	}
	sort.Strings(extra)
	order = append(order, extra...)

	// 先从各片段摘除全部 source 条目，按片段汇总其作品行/来源词。
	sourceByFragment := make(map[string]authoring.UploadEntry)
	var sourceOrder []string
	moved := 0
	for _, fragment := range order {
		list := entries[fragment]
		if len(list) == 0 {
			continue
		}
		kept := make([]authoring.UploadEntry, 0, len(list))
		hadSource := false
		for _, e := range list {
			if e.AuthorID != sourceID {
				kept = append(kept, e)
				continue
			}
			hadSource = true
			moved++
			carried := sourceByFragment[fragment]
			carried.AuthorID = targetID
			carried.Works = mergeLines(carried.Works, e.Works)
			carried.Sources = mergeLines(carried.Sources, e.Sources)
			sourceByFragment[fragment] = carried
		}
		if !hadSource {
			continue
		}
		sourceOrder = append(sourceOrder, fragment)
		if len(kept) == 0 {
			delete(entries, fragment)
		} else {
			entries[fragment] = kept
		}
	}
	if moved == 0 {
		return 0, nil
	}
	names := targetAliases
	if len(names) == 0 {
		var ok bool
		names, ok = displayNameAliases(targetDisplay, targetID)
		if !ok {
			return 0, fmt.Errorf("authorattach: 目标作者 %s 显示名 %q 与身份不自洽（请先改名）: %w",
				targetID, targetDisplay, ErrDisplayNameIdentityMismatch)
		}
	}
	display := strings.Join(names, authoring.DisplayNameSeparator)

	if !targetBlockPresent {
		// 无目标块：按片段原地改指（renameAuthorEntries 同语义）。
		for _, fragment := range sourceOrder {
			entries[fragment] = mergeTargetEntry(entries[fragment], sourceByFragment[fragment], names, display)
		}
	} else {
		// 目标块存在：全部 source 条目并入目标块所在片段，作品行/来源词保序去重。
		carried := authoring.UploadEntry{AuthorID: targetID}
		for _, fragment := range sourceOrder {
			carried.Works = mergeLines(carried.Works, sourceByFragment[fragment].Works)
			carried.Sources = mergeLines(carried.Sources, sourceByFragment[fragment].Sources)
		}
		entries[targetFragment] = mergeTargetEntry(entries[targetFragment], carried, names, display)
	}
	if err := SaveUploadEntries(ctx, qtx, now, entries); err != nil {
		return 0, err
	}
	return moved, nil
}

// mergeTargetEntry 把 incoming 并入 fragment 条目列表中 targetID 的既有条目
// （没有则追加），并同步 Names/DisplayName。Works/Sources 保序去重。
func mergeTargetEntry(list []authoring.UploadEntry, incoming authoring.UploadEntry, names []string, display string) []authoring.UploadEntry {
	for i := range list {
		if list[i].AuthorID != incoming.AuthorID {
			continue
		}
		list[i].Works = mergeLines(list[i].Works, incoming.Works)
		list[i].Sources = mergeLines(list[i].Sources, incoming.Sources)
		list[i].DisplayName = display
		list[i].Names = append([]string(nil), names...)
		return list
	}
	incoming.DisplayName = display
	incoming.Names = append([]string(nil), names...)
	return append(list, incoming)
}
