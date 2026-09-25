package authorattach

// vocabulary.go：通用来源词表的 kv 存取（ADR-0024）。来源=获取渠道平台名
// （如「老王论坛」），仅记录永不参与匹配；词表以用户手动维护为准（PUT 恒
// 写键，清空也写空数组），出厂态（键不存在）由读取路径做一次性自动预填
// （EnsureSourceVocabulary + vocabulary_prefill.go 的统计口径）。与 §4 资产
// 出处分区（custom_sources）互不相干，禁止混用（DOMAIN_RULES §6）。

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

// loadSourceVocabularyStored 读词表 kv 原始值：ok=false = 键不存在
// （从未写入过——预填的一次性判定锚点，LoadSourceVocabulary 对外仍折叠为
// 空数组，只有 EnsureSourceVocabulary 需要区分）。
func loadSourceVocabularyStored(ctx context.Context, q *db.Queries) (list []string, ok bool, err error) {
	v, err := q.GetSetting(ctx, authoring.SettingKeyAuthorSourceVocabulary)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, false, nil
	}
	if err != nil {
		return nil, false, fmt.Errorf("authorattach: 读取来源词表: %w", err)
	}
	if err := json.Unmarshal([]byte(v), &list); err != nil {
		return nil, false, fmt.Errorf("authorattach: 解析来源词表 JSON: %w", err)
	}
	return list, true, nil
}

// LoadSourceVocabulary 读通用来源词表（无记录 → 空数组非 nil——协议 200
// 空数组语义，调用方直接回显）。
func LoadSourceVocabulary(ctx context.Context, q *db.Queries) ([]string, error) {
	list, ok, err := loadSourceVocabularyStored(ctx, q)
	if err != nil {
		return nil, err
	}
	if !ok || list == nil {
		return []string{}, nil
	}
	return list, nil
}

// EnsureSourceVocabulary 读通用来源词表，键不存在时执行一次性自动预填
// （GET /authors/source-vocabulary 读取路径）：从全部已导入片段统计被多位
// 作者共用的通用平台名（PrefillSourceVocabulary 纯函数）。有结果才写 kv
// ——写键即视为已预填，此后（含用户 PUT 覆盖/清空，PUT 恒写键）永不再
// 预填；无结果不写键，未来片段导入后下次读取自然重试。
func EnsureSourceVocabulary(ctx context.Context, q *db.Queries, now time.Time) ([]string, error) {
	stored, ok, err := loadSourceVocabularyStored(ctx, q)
	if err != nil {
		return nil, err
	}
	if ok {
		if stored == nil {
			return []string{}, nil
		}
		return stored, nil
	}
	sources, err := LoadSources(ctx, q)
	if err != nil {
		return nil, err
	}
	prefilled := PrefillSourceVocabulary(parseAllBlocks(sources))
	if len(prefilled) == 0 {
		return []string{}, nil
	}
	if err := SaveSourceVocabulary(ctx, q, now, prefilled); err != nil {
		return nil, err
	}
	return prefilled, nil
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
