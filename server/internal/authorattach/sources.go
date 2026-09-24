package authorattach

// sources.go：TXT 片段存取的单一来源（kv_settings.imported_txt_sources）。
// httpapi 侧的 loadTxtSources/persistTxtSources 语义收口到这里，消灭
// {filename, content} 结构的双份定义（ADR-0019 增量迁移）。

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

// Source 是 kv_settings 中 imported_txt_sources 数组的一个片段（重导入同
// 文件名覆盖其 content）。ImportedAt 为 RFC3339 时间戳
// （store.FormatTimestamp 产出），统一时间戳格式保证「字典序 == 时间序」，
// MostRecent 直接按字符串比较；旧片段无该字段 → 零值 = 最旧。
type Source struct {
	Filename   string `json:"filename"`
	Content    string `json:"content"`
	ImportedAt string `json:"importedAt,omitempty"`
}

// LoadSources 读全部已导入片段（无记录/空数组 → nil，照抄 httpapi
// loadTxtSources 的既有语义，调用方 len==0 判空）。
func LoadSources(ctx context.Context, q *db.Queries) ([]Source, error) {
	v, err := q.GetSetting(ctx, authoring.SettingKeyImportedTxtSources)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("authorattach: 读取已导入片段: %w", err)
	}
	var sources []Source
	if err := json.Unmarshal([]byte(v), &sources); err != nil {
		return nil, fmt.Errorf("authorattach: 解析已导入片段 JSON: %w", err)
	}
	return sources, nil
}

// PersistSources 把片段数组序列化写回 kv_settings（空数组也写入——删到零
// 片段时保留空壳，与 httpapi persistTxtSources 口径一致）。
func PersistSources(ctx context.Context, q *db.Queries, now time.Time, sources []Source) error {
	raw, err := json.Marshal(sources)
	if err != nil {
		return fmt.Errorf("authorattach: 序列化已导入片段: %w", err)
	}
	if err := q.UpsertSetting(ctx, db.UpsertSettingParams{
		Key:       authoring.SettingKeyImportedTxtSources,
		Value:     string(raw),
		UpdatedAt: store.FormatTimestamp(now),
	}); err != nil {
		return fmt.Errorf("authorattach: 写回已导入片段: %w", err)
	}
	return nil
}

// MostRecent 返回最近导入的片段：importedAt 字典序最大（统一时间戳格式下
// 字典序 == 时间序），平局取数组靠后（后写入者覆盖语义）；无片段 false。
func MostRecent(sources []Source) (Source, bool) {
	var best Source
	found := false
	for _, s := range sources {
		// >=：平局时取数组靠后的那份，与「同文件名重导覆盖」直觉一致。
		if !found || s.ImportedAt >= best.ImportedAt {
			best, found = s, true
		}
	}
	return best, found
}
