package authorattach

// upload_entries.go：上传写入条目元数据（重导入保护，REQ §3.3 第 10 条）。
// 记录「哪些作品行/来源行由上传写进了哪份片段的哪位作者」——出处元数据
// 而非第二真相：真相永远是片段本体，本元数据只在「同文件名重导」时用来
// 识别被整体替换冲掉的上传条目（REQ §4.2）。

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// LoadUploadEntries 读按片段文件名分组的上传条目（无记录 → 空 map 非 nil，
// 调用方可直接下标读写）。
func LoadUploadEntries(ctx context.Context, q *db.Queries) (map[string][]authoring.UploadEntry, error) {
	v, err := q.GetSetting(ctx, authoring.SettingKeyUploadEntries)
	if errors.Is(err, sql.ErrNoRows) {
		return map[string][]authoring.UploadEntry{}, nil
	}
	if err != nil {
		return nil, fmt.Errorf("authorattach: 读取上传条目: %w", err)
	}
	m := make(map[string][]authoring.UploadEntry)
	if err := json.Unmarshal([]byte(v), &m); err != nil {
		return nil, fmt.Errorf("authorattach: 解析上传条目 JSON: %w", err)
	}
	return m, nil
}

// SaveUploadEntries 把上传条目元数据序列化写回 kv_settings。
func SaveUploadEntries(ctx context.Context, q *db.Queries, now time.Time, m map[string][]authoring.UploadEntry) error {
	raw, err := json.Marshal(m)
	if err != nil {
		return fmt.Errorf("authorattach: 序列化上传条目: %w", err)
	}
	if err := q.UpsertSetting(ctx, db.UpsertSettingParams{
		Key:       authoring.SettingKeyUploadEntries,
		Value:     string(raw),
		UpdatedAt: store.FormatTimestamp(now),
	}); err != nil {
		return fmt.Errorf("authorattach: 写回上传条目: %w", err)
	}
	return nil
}
