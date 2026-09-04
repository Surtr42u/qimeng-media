// facets.go：相册四维胶囊栏聚合端点（GET /assets/facets）。
//
// 语义唯一权威：api/openapi.yaml 该端点 description（排自身口径——计某一维
// 候选时忽略该维自身已选值、其余维度全部生效）+ DOMAIN_RULES §3（筛选）/
// §6（COS 隔离）。SQL 实现见 store/queries/facets.sql，其分区谓词/source
// 谓词/q 谓词和 browse.sql 逐字一致（双侧注释互指，改一处必须同步另一处）。
//
// 维度与参数的对应（旧版「全部」页 分区/作品/角色/类型 四栏语义）：
//   - 分区 partition  → include_cos/cos_only 两旗（partitionFlags）；
//   - 作者行          → 常规出处分组（kind=source，GET /assets 的 source
//     参数，含「其他」桶）+ COS 作者（kind=author，authorId 参数）——
//     常规分区只有出处、COS 分区只有 COS 作者、全部分区两者合并
//     （旧版 groupBySource(!isCos) ∪ groupByCosAuthor(isCos) 的合并口径；
//     常规 TXT 作者不进本行）；
//   - 角色行          → 常规分区=匹配引擎角色名（kind=character）、
//     COS 分区=COS 作品名（kind=work，migration 0008 cos_work 列）、
//     全部分区=两者合并（character 与 work 同属「角色」行，排除自身时
//     两者一起忽略）；
//   - 类型 mediaType  → MediaType 枚举（含 animated_image=动图）。
package httpapi

import (
	"net/http"
	"sort"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/search"
	"qimeng-media/server/internal/store/db"
)

// partitionFlags 把 partition 枚举映射成 facets/browse 共用的两旗形态
// （facets.sql 文件头注释的映射契约：all→1/0、regular→0/0、cos→0/1）。
// partition 缺省 = all：本端点是纯聚合，中性缺省是不限制；浏览端点的
// regular 缺省是另一口径（openapi 两处 description 各自写明，勿混）。
func partitionFlags(p *gen.Partition) (includeCos, cosOnly int64) {
	switch {
	case p == nil || *p == gen.PartitionAll:
		return 1, 0
	case *p == gen.PartitionCos:
		return 0, 1
	default: // gen.PartitionRegular
		return 0, 0
	}
}

// facetMediaTypeLabels 类型胶囊的中文显示名（key 恒为 MediaType 枚举值，
// 可直接回传 GET /assets；显示名与 DOMAIN_RULES §11 媒体类型口径对应）。
var facetMediaTypeLabels = map[gen.MediaType]string{
	gen.MediaTypeImage:         "图片",
	gen.MediaTypeAnimatedImage: "动图",
	gen.MediaTypeVideo:         "视频",
}

// facetSourceBuckets 作者行的出处分组候选（常规/全部分区调用）。
// 「其他」（NULL source）桶排在末尾（旧版 renderSourcePills 的「其他
// 永远排在列表最下面」），key=sourceOtherLabel 可直接回传 GET /assets。
// 尾部两个 int64 是子集约束旗（恒 0/1，见 GetApiV1AssetsFacets 注释）。
func (s *Server) facetSourceBuckets(w http.ResponseWriter, r *http.Request, mediaType, charactersJson, cosWork, qJson any, favoriteSubset, historySubset int64) ([]gen.FacetBucket, bool) {
	rows, err := s.q.FacetSourceCounts(r.Context(), db.FacetSourceCountsParams{
		MediaType:      mediaType,
		CharactersJson: charactersJson,
		CosWork:        cosWork,
		QJson:          qJson,
		FavoriteSubset: favoriteSubset,
		HistorySubset:  historySubset,
	})
	if err != nil {
		s.internalErr(w, "聚合作者行出处分组", err)
		return nil, false
	}
	buckets := make([]gen.FacetBucket, 0, len(rows)+1)
	var otherCount int
	otherSeen := false
	for _, row := range rows {
		if !row.SourceName.Valid {
			otherCount = int(row.FileCount)
			otherSeen = true
			continue
		}
		k := row.SourceName.String
		buckets = append(buckets, gen.FacetBucket{
			Key: k, Name: k, FileCount: int(row.FileCount),
			Kind: ptr(gen.FacetBucketKindSource),
		})
	}
	if otherSeen {
		buckets = append(buckets, gen.FacetBucket{
			Key: sourceOtherLabel, Name: sourceOtherLabel, FileCount: otherCount,
			Kind: ptr(gen.FacetBucketKindSource),
		})
	}
	return buckets, true
}

// facetCosAuthorBuckets 作者行的 COS 作者候选（COS/全部分区调用）。
func (s *Server) facetCosAuthorBuckets(w http.ResponseWriter, r *http.Request, includeCos, cosOnly int64, mediaType, charactersJson, cosWork, qJson any, favoriteSubset, historySubset int64) ([]gen.FacetBucket, bool) {
	rows, err := s.q.FacetAuthorCounts(r.Context(), db.FacetAuthorCountsParams{
		IncludeCos:     includeCos,
		CosOnly:        cosOnly,
		MediaType:      mediaType,
		CharactersJson: charactersJson,
		CosWork:        cosWork,
		QJson:          qJson,
		FavoriteSubset: favoriteSubset,
		HistorySubset:  historySubset,
	})
	if err != nil {
		s.internalErr(w, "聚合作者行 COS 作者", err)
		return nil, false
	}
	buckets := make([]gen.FacetBucket, 0, len(rows))
	for _, row := range rows {
		buckets = append(buckets, gen.FacetBucket{
			Key: row.AuthorID, Name: row.AuthorName, FileCount: int(row.FileCount),
			Kind: ptr(gen.FacetBucketKindAuthor),
		})
	}
	return buckets, true
}

// mergeFacetBuckets 合并两组候选并按 fileCount 降序（同数按显示名稳定
// 排序）。作者行（出处∪COS 作者）与全部分区角色行（角色∪作品）共用；
// 各组内部已有序，合并排序保持确定性输出。
func mergeFacetBuckets(groups ...[]gen.FacetBucket) []gen.FacetBucket {
	total := 0
	for _, g := range groups {
		total += len(g)
	}
	merged := make([]gen.FacetBucket, 0, total)
	for _, g := range groups {
		merged = append(merged, g...)
	}
	sort.SliceStable(merged, func(i, j int) bool {
		if merged[i].FileCount != merged[j].FileCount {
			return merged[i].FileCount > merged[j].FileCount
		}
		return merged[i].Name < merged[j].Name
	})
	return merged
}

// GetApiV1AssetsFacets 四维候选聚合。各查询排除自身维度、应用其余全部
// 当前选择（排自身口径，facets.sql 文件头有逐查询的维度表）。
func (s *Server) GetApiV1AssetsFacets(w http.ResponseWriter, r *http.Request, params gen.GetApiV1AssetsFacetsParams) {
	includeCos, cosOnly := partitionFlags(params.Partition)
	isCosPartition := params.Partition != nil && *params.Partition == gen.PartitionCos
	isRegularPartition := params.Partition != nil && *params.Partition == gen.PartitionRegular

	var mediaType any
	if params.MediaType != nil {
		mediaType = nullStr(string(*params.MediaType))
	}
	var charactersJson any
	if params.Character != nil && *params.Character != "" {
		charactersJson = jsonString(splitCharacters(*params.Character))
	}
	var cosWork any
	if params.Work != nil && *params.Work != "" {
		cosWork = nullStr(*params.Work)
	}
	var authorID any
	if params.AuthorId != nil && *params.AuthorId != "" {
		authorID = nullStr(*params.AuthorId)
	}
	// source 参数与 GET /assets 同口径：「其他」→ source_is_other 旗。
	var source any
	var sourceIsOther int64
	if params.Source != nil && *params.Source != "" {
		if *params.Source == sourceOtherLabel {
			sourceIsOther = 1
		} else {
			source = nullStr(*params.Source)
		}
	}
	var qJson any
	if params.Q != nil && *params.Q != "" {
		qJson = jsonString(search.ParseQuery(*params.Q))
	}
	// 子集约束（非四维之一，openapi 端点 description）：favorite=1 → 只
	// 统计收藏资产，history=1 → 只统计有 open 事件的资产；对全部四维（含
	// 分区栏）统一生效，不存在排自身问题。恒传 0/1——谓词形态
	// sqlc.arg(x)=0 OR EXISTS（facets.sql 文件头），传 NULL 会落三值逻辑
	// 整行排除。显式 false 与缺省同义（协议「缺省不约束」）。
	favoriteSubset := int64(0)
	if params.Favorite != nil && *params.Favorite {
		favoriteSubset = 1
	}
	historySubset := int64(0)
	if params.History != nil && *params.History {
		historySubset = 1
	}
	ctx := r.Context()

	// 分区栏：固定 all/regular/cos 三项。分区维排自身=全量报告，一次查询
	// 同时给出全量与 COS 计数（常规 = 全量 - COS，facets.sql 单趟合并）。
	// 子集约束传入时分区芯片 = 子集内的 all/regular/cos。
	part, err := s.q.FacetPartitionCounts(ctx, db.FacetPartitionCountsParams{
		MediaType:      mediaType,
		CharactersJson: charactersJson,
		CosWork:        cosWork,
		Source:         source,
		SourceIsOther:  sourceIsOther,
		AuthorID:       authorID,
		QJson:          qJson,
		FavoriteSubset: favoriteSubset,
		HistorySubset:  historySubset,
	})
	if err != nil {
		s.internalErr(w, "聚合分区维度", err)
		return
	}
	cosCount := toInt(part.CosCount)
	partitions := []gen.FacetBucket{
		{Key: "all", Name: "全部", FileCount: int(part.AllCount)},
		{Key: "regular", Name: "常规", FileCount: int(part.AllCount) - cosCount},
		{Key: "cos", Name: "COS", FileCount: cosCount},
	}

	// 作者行（旧版「作品」行：排自身=source 与 authorId 一起忽略）：
	//   常规分区=出处分组；COS 分区=COS 作者；全部分区=两者合并。
	var authors []gen.FacetBucket
	if !isCosPartition {
		srcBuckets, ok := s.facetSourceBuckets(w, r, mediaType, charactersJson, cosWork, qJson, favoriteSubset, historySubset)
		if !ok {
			return
		}
		authors = srcBuckets
	}
	if !isRegularPartition {
		cosBuckets, ok := s.facetCosAuthorBuckets(w, r, includeCos, cosOnly, mediaType, charactersJson, cosWork, qJson, favoriteSubset, historySubset)
		if !ok {
			return
		}
		authors = mergeFacetBuckets(authors, cosBuckets)
	}
	if !isCosPartition && !isRegularPartition {
		// 全部分区合并后把「其他」重新挪到末尾（mergeFacetBuckets 按计数
		// 降序会把它排到前面——「其他」桶在旧版恒排最底部）。
		for i := len(authors) - 1; i >= 0; i-- {
			if authors[i].Kind != nil && *authors[i].Kind == gen.FacetBucketKindSource &&
				authors[i].Key == sourceOtherLabel {
				other := authors[i]
				authors = append(authors[:i], authors[i+1:]...)
				authors = append(authors, other)
				break
			}
		}
	}

	// 角色行（排自身：忽略 character 与 work——两参数同属「角色」行）。
	// 常规分区=匹配引擎角色名；COS 分区=COS 作品名（旧版「COS 角色=作品
	// 名」口径，DOMAIN_RULES §6）；全部分区=两者合并。NULL 作品（无作品
	// 子目录）不列入，前端按需兜底（openapi characters description）。
	var characters []gen.FacetBucket
	if !isCosPartition {
		charRows, err := s.q.FacetCharacterCounts(ctx, db.FacetCharacterCountsParams{
			IncludeCos:     includeCos,
			CosOnly:        cosOnly,
			MediaType:      mediaType,
			Source:         source,
			SourceIsOther:  sourceIsOther,
			AuthorID:       authorID,
			QJson:          qJson,
			FavoriteSubset: favoriteSubset,
			HistorySubset:  historySubset,
		})
		if err != nil {
			s.internalErr(w, "聚合角色维度", err)
			return
		}
		characters = make([]gen.FacetBucket, 0, len(charRows))
		for _, row := range charRows {
			characters = append(characters, gen.FacetBucket{
				Key: row.CharacterName, Name: row.CharacterName, FileCount: int(row.FileCount),
				Kind: ptr(gen.FacetBucketKindCharacter),
			})
		}
	}
	if !isRegularPartition {
		workRows, err := s.q.FacetCosWorkCounts(ctx, db.FacetCosWorkCountsParams{
			IncludeCos:     includeCos,
			CosOnly:        cosOnly,
			MediaType:      mediaType,
			Source:         source,
			SourceIsOther:  sourceIsOther,
			AuthorID:       authorID,
			QJson:          qJson,
			FavoriteSubset: favoriteSubset,
			HistorySubset:  historySubset,
		})
		if err != nil {
			s.internalErr(w, "聚合角色维度", err)
			return
		}
		works := make([]gen.FacetBucket, 0, len(workRows))
		for _, row := range workRows {
			works = append(works, gen.FacetBucket{
				Key: row.WorkName.String, Name: row.WorkName.String, FileCount: int(row.FileCount),
				Kind: ptr(gen.FacetBucketKindWork),
			})
		}
		characters = mergeFacetBuckets(characters, works)
	}

	// 类型栏（排自身：忽略 mediaType）。固定四项、无数据的类型补 0；
	// all = 三桶之和（单趟 GROUP BY 后求和，与 SQL 无二次往返）。
	typeRows, err := s.q.FacetMediaTypeCounts(ctx, db.FacetMediaTypeCountsParams{
		IncludeCos:     includeCos,
		CosOnly:        cosOnly,
		CharactersJson: charactersJson,
		CosWork:        cosWork,
		Source:         source,
		SourceIsOther:  sourceIsOther,
		AuthorID:       authorID,
		QJson:          qJson,
		FavoriteSubset: favoriteSubset,
		HistorySubset:  historySubset,
	})
	if err != nil {
		s.internalErr(w, "聚合类型维度", err)
		return
	}
	counts := make(map[gen.MediaType]int, len(facetMediaTypeLabels))
	total := 0
	for _, row := range typeRows {
		mt := gen.MediaType(row.MediaType)
		counts[mt] = int(row.FileCount)
		total += int(row.FileCount)
	}
	types := []gen.FacetBucket{
		{Key: "all", Name: "全部", FileCount: total},
		{Key: string(gen.MediaTypeImage), Name: facetMediaTypeLabels[gen.MediaTypeImage], FileCount: counts[gen.MediaTypeImage]},
		{Key: string(gen.MediaTypeAnimatedImage), Name: facetMediaTypeLabels[gen.MediaTypeAnimatedImage], FileCount: counts[gen.MediaTypeAnimatedImage]},
		{Key: string(gen.MediaTypeVideo), Name: facetMediaTypeLabels[gen.MediaTypeVideo], FileCount: counts[gen.MediaTypeVideo]},
	}

	writeJSON(w, http.StatusOK, gen.AssetFacets{
		Partitions: partitions,
		Authors:    authors,
		Characters: characters,
		Types:      types,
	})
}
