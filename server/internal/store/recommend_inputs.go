package store

import (
	"context"
	"database/sql"
	"encoding/json"

	"qimeng-media/server/internal/store/db"
)

// 至多一半资产的候选才使用索引探测；宽集合顺序聚合更便宜。
// 这是执行策略，不是推荐阈值；基准须同时覆盖窄/宽集合再调整。
const recommendSparseDivisor = 2

// RecommendationInputs 是评分所需的完整候选与标签，不包含评分/缓存逻辑。
type RecommendationInputs struct {
	Rows        []db.ListAssetsRecommendInputRow
	TagsByAsset map[string][]string
}

// LoadRecommendationInputs 选择读取计划，避免把窄集合优化强加给宽集合。
// 未限媒体的常规流保留原两查询；显式媒体/COS 流观测规模后选择。规模
// 仅为性能提示，实际查询重新判断资格，禁止按观测数截断或跳过新候选。
func LoadRecommendationInputs(ctx context.Context, q *db.Queries, day string, media sql.NullString, cosOnly int64) (RecommendationInputs, error) {
	sparse := false
	if media.Valid || cosOnly != 0 {
		scope, err := q.CountRecommendScope(ctx, db.CountRecommendScopeParams{MediaType: media, CosOnly: cosOnly})
		if err != nil {
			return RecommendationInputs{}, err
		}
		sparse = scope.CandidateAssets <= scope.TotalAssets/recommendSparseDivisor
	}
	var rows []db.ListAssetsRecommendInputRow
	if sparse {
		raw, err := q.ListAssetsRecommendCandidateInput(ctx, db.ListAssetsRecommendCandidateInputParams{Day: day, MediaType: media, CosOnly: cosOnly})
		if err != nil {
			return RecommendationInputs{}, err
		}
		rows = make([]db.ListAssetsRecommendInputRow, len(raw))
		for i, row := range raw {
			rows[i] = db.ListAssetsRecommendInputRow(row)
		}
	} else {
		var err error
		rows, err = q.ListAssetsRecommendInput(ctx, db.ListAssetsRecommendInputParams{Day: day, MediaType: media, CosOnly: cosOnly})
		if err != nil {
			return RecommendationInputs{}, err
		}
	}
	tags, err := loadRecommendationTags(ctx, q, rows, sparse)
	if err != nil {
		return RecommendationInputs{}, err
	}
	return RecommendationInputs{Rows: rows, TagsByAsset: tags}, nil
}

func loadRecommendationTags(ctx context.Context, q *db.Queries, rows []db.ListAssetsRecommendInputRow, sparse bool) (map[string][]string, error) {
	if len(rows) == 0 {
		return map[string][]string{}, nil
	}
	if !sparse {
		tags, err := q.ListAllAssetTags(ctx)
		if err != nil {
			return nil, err
		}
		return groupRecommendationTags(tags), nil
	}
	ids := make([]string, len(rows))
	for i := range rows {
		ids[i] = rows[i].AssetID
	}
	payload, err := json.Marshal(ids)
	if err != nil {
		return nil, err
	}
	tags, err := q.ListTagsForAssets(ctx, string(payload))
	if err != nil {
		return nil, err
	}
	return groupRecommendationTags(tags), nil
}

// 两种同构 sqlc 标签行按值转换，不额外复制全量切片，保留原映射容量和顺序。
func groupRecommendationTags[T db.ListAllAssetTagsRow | db.ListTagsForAssetsRow](tags []T) map[string][]string {
	byAsset := make(map[string][]string, len(tags))
	for _, value := range tags {
		tag := db.ListTagsForAssetsRow(value)
		byAsset[tag.AssetID] = append(byAsset[tag.AssetID], tag.Name)
	}
	return byAsset
}
