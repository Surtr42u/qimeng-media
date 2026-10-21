package authorimport

import (
	"context"
	"errors"
	"fmt"

	"qimeng-media/server/internal/authorattach"
	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// 与 OpenAPI POST /authors/import-txt conflictResolution 枚举双向同步。
const (
	Keep   = "keep"
	Remove = "remove"
)

// Conflict 携带重导入会遗漏的登记条目；调用方决定交互或显式 keep。
type Conflict struct {
	Filename string
	Missing  []authoring.UploadEntry
}

func (e *Conflict) Error() string {
	return fmt.Sprintf("重导入 %q 会丢弃上传写入条目（涉及 %d 位作者）", e.Filename, len(e.Missing))
}

// protectUploadEntries 重导入保护（事务内调用，覆盖同名片段前）：
// entries[thisName] 与新 content 比对，missing = 新内容缺少的上传条目。
//   - missing 空：照常（条目保留不动——保守口径：条目仍在上传写入名下，
//     后续同文件名重导继续受保护）；
//   - missing 非空且无 resolution：返回 txtImportConflictError（handler 409）；
//   - keep：把缺失条目并回 content（*content 就地更新，后续走既有覆盖+重建），
//     返回并回的行数（作品行+来源行）；
//   - remove：按用户明示移除——删除该片段的上传条目元数据（此后同文件名
//     重导不再受保护），content 原样替换落库。
func (s *Service) protectUploadEntries(ctx context.Context, qtx *db.Queries, thisName string, content *string, resolution string) (int, error) {
	entries, err := authorattach.LoadUploadEntries(ctx, qtx)
	if err != nil {
		return 0, err
	}
	missing := authoring.MissingUploadEntries(*content, entries[thisName])
	if len(missing) == 0 {
		return 0, nil
	}
	switch resolution {
	case "":
		return 0, &Conflict{Filename: thisName, Missing: missing}
	case Keep:
		*content = authoring.MergeUploadEntries(*content, missing)
		merged := 0
		for _, m := range missing {
			merged += len(m.Works) + len(m.Sources)
		}
		return merged, nil
	case Remove:
		delete(entries, thisName)
		if err := authorattach.SaveUploadEntries(ctx, qtx, s.now(), entries); err != nil {
			return 0, err
		}
		return 0, nil
	default:
		// 不可达：handler 已做枚举校验（400），此处防御兜底。
		return 0, fmt.Errorf("导入作者 TXT: 未知 conflictResolution %q", resolution)
	}
}

// ErrSourceNotFound 是 DELETE 移除不存在的片段时的哨兵错误
// （handler 映射 404；事务内判断，确保移除+重建原子）。
var ErrSourceNotFound = errors.New("txt source not found")

// removeTxtSource 移除一个片段并从剩余片段统一重建（DELETE 路径，事务内
// 调用）。删关联目标 = 删除前全量片段的作者集——作者只在被删片段出现时，
// 其旧关联随删除清空；重建来源 = 删除后剩余片段。作者行保留不级联删除
// （openapi：重建后零关联的常规作者保留）。片段没了，其上传条目元数据
// 一并清除（元数据按片段名分组，留着即死数据）。
func (s *Service) removeTxtSource(ctx context.Context, qtx *db.Queries, filename string) error {
	before, err := authorattach.LoadSources(ctx, qtx)
	if err != nil {
		return err
	}
	idx := -1
	for i := range before {
		if before[i].Filename == filename {
			idx = i
			break
		}
	}
	if idx < 0 {
		return ErrSourceNotFound
	}
	remaining := append(append([]authorattach.Source(nil), before[:idx]...), before[idx+1:]...)
	if err := authorattach.PersistSources(ctx, qtx, s.now(), remaining); err != nil {
		return err
	}
	entries, err := authorattach.LoadUploadEntries(ctx, qtx)
	if err != nil {
		return err
	}
	if _, ok := entries[filename]; ok {
		delete(entries, filename)
		if err := authorattach.SaveUploadEntries(ctx, qtx, s.now(), entries); err != nil {
			return err
		}
	}
	_, err = s.rebuildAll(ctx, qtx, mergeTxtSources(before), mergeTxtSources(remaining), nil, store.OriginTXT)
	return err
}
