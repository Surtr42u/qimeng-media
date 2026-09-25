package authorattach

// vocabulary.go：通用来源词表的 kv 存取（ADR-0024）。来源=获取渠道平台名
// （如「老王论坛」），仅记录永不参与匹配；词表是用户手动维护的小清单、
// 来源建议的唯一数据源。与 §4 资产出处分区（custom_sources）互不相干，
// 禁止混用（DOMAIN_RULES §6）。

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

// LoadSourceVocabulary 读通用来源词表（无记录 → 空数组非 nil——协议 200
// 空数组语义，调用方直接回显）。
func LoadSourceVocabulary(ctx context.Context, q *db.Queries) ([]string, error) {
	v, err := q.GetSetting(ctx, authoring.SettingKeyAuthorSourceVocabulary)
	if errors.Is(err, sql.ErrNoRows) {
		return []string{}, nil
	}
	if err != nil {
		return nil, fmt.Errorf("authorattach: 读取来源词表: %w", err)
	}
	var list []string
	if err := json.Unmarshal([]byte(v), &list); err != nil {
		return nil, fmt.Errorf("authorattach: 解析来源词表 JSON: %w", err)
	}
	if list == nil {
		list = []string{}
	}
	return list, nil
}

// SaveSourceVocabulary 整体替换保存词表（空数组=清空，kv 留空壳记录）。
func SaveSourceVocabulary(ctx context.Context, q *db.Queries, now time.Time, list []string) error {
	if list == nil {
		list = []string{}
	}
	raw, err := json.Marshal(list)
	if err != nil {
		return fmt.Errorf("authorattach: 序列化来源词表: %w", err)
	}
	if err := q.UpsertSetting(ctx, db.UpsertSettingParams{
		Key:       authoring.SettingKeyAuthorSourceVocabulary,
		Value:     string(raw),
		UpdatedAt: store.FormatTimestamp(now),
	}); err != nil {
		return fmt.Errorf("authorattach: 写回来源词表: %w", err)
	}
	return nil
}
