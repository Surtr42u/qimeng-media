// facets.go：相册四维胶囊栏聚合端点（GET /assets/facets）。
//
// 语义唯一权威：api/openapi.yaml 该端点 description（排自身口径——计某一维
// 候选时忽略该维自身已选值、其余维度全部生效）+ DOMAIN_RULES §3（筛选）/
// §6（COS 隔离）。SQL 实现见 store/queries/facets.sql，其分区谓词与 q 谓词
// 和 browse.sql 逐字一致（双侧注释互指，改一处必须同步另一处）。
//
// 维度与参数的对应：
//   - 分区 partition  → include_cos/cos_only 两旗（partitionFlags）；
//   - 作者 authorId   → 直接回传 GET /assets 的 authorId；
//   - 角色 character  → 常规分区=匹配引擎角色名（asset_characters）；
//     COS 分区=COS 作品名（migration 0008 cos_work 列，参数别名 work，
//     同属「角色」维，排除自身时两者一起忽略）；
//   - 类型 mediaType  → MediaType 枚举（含 animated_image=动图）。
package httpapi

import (
	"net/http"

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

// GetApiV1AssetsFacets 四维候选聚合。五个查询各排除自身维度、应用其余全部
// 当前选择（排自身口径，facets.sql 文件头有逐查询的维度表）。
func (s *Server) GetApiV1AssetsFacets(w http.ResponseWriter, r *http.Request, params gen.GetApiV1AssetsFacetsParams) {
	includeCos, cosOnly := partitionFlags(params.Partition)
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
	var qJson any
	if params.Q != nil && *params.Q != "" {
		qJson = jsonString(search.ParseQuery(*params.Q))
	}
	ctx := r.Context()

	// 分区栏：固定 all/regular/cos 三项。分区维排自身=全量报告，一次查询
	// 同时给出全量与 COS 计数（常规 = 全量 - COS，facets.sql 单趟合并）。
	part, err := s.q.FacetPartitionCounts(ctx, db.FacetPartitionCountsParams{
		MediaType:      mediaType,
		CharactersJson: charactersJson,
		CosWork:        cosWork,
		AuthorID:       authorID,
		QJson:          qJson,
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

	// 作者栏（排自身：忽略 authorId）。常规 TXT 作者与 COS 作者一起列出，
	// key=authorId 可直接回传 GET /assets。
	authorRows, err := s.q.FacetAuthorCounts(ctx, db.FacetAuthorCountsParams{
		IncludeCos:     includeCos,
		CosOnly:        cosOnly,
		MediaType:      mediaType,
		CharactersJson: charactersJson,
		CosWork:        cosWork,
		QJson:          qJson,
	})
	if err != nil {
		s.internalErr(w, "聚合作者维度", err)
		return
	}
	authors := make([]gen.FacetBucket, 0, len(authorRows))
	for _, row := range authorRows {
		authors = append(authors, gen.FacetBucket{
			Key: row.AuthorID, Name: row.AuthorName, FileCount: int(row.FileCount),
		})
	}

	// 角色栏（排自身：忽略 character 与 work——两参数同属「角色」维）。
	// COS 分区下候选=作品名（旧版「COS 角色=作品名」口径，DOMAIN_RULES §6）；
	// 其余分区候选=匹配引擎角色名。NULL 作品（无作品子目录）不列入，
	// 前端按需兜底（openapi characters description）。
	var characters []gen.FacetBucket
	if params.Partition != nil && *params.Partition == gen.PartitionCos {
		workRows, err := s.q.FacetCosWorkCounts(ctx, db.FacetCosWorkCountsParams{
			IncludeCos: includeCos,
			CosOnly:    cosOnly,
			MediaType:  mediaType,
			AuthorID:   authorID,
			QJson:      qJson,
		})
		if err != nil {
			s.internalErr(w, "聚合角色维度", err)
			return
		}
		characters = make([]gen.FacetBucket, 0, len(workRows))
		for _, row := range workRows {
			characters = append(characters, gen.FacetBucket{
				Key: row.WorkName.String, Name: row.WorkName.String, FileCount: int(row.FileCount),
			})
		}
	} else {
		charRows, err := s.q.FacetCharacterCounts(ctx, db.FacetCharacterCountsParams{
			IncludeCos: includeCos,
			CosOnly:    cosOnly,
			MediaType:  mediaType,
			AuthorID:   authorID,
			QJson:      qJson,
		})
		if err != nil {
			s.internalErr(w, "聚合角色维度", err)
			return
		}
		characters = make([]gen.FacetBucket, 0, len(charRows))
		for _, row := range charRows {
			characters = append(characters, gen.FacetBucket{
				Key: row.CharacterName, Name: row.CharacterName, FileCount: int(row.FileCount),
			})
		}
	}

	// 类型栏（排自身：忽略 mediaType）。固定四项、无数据的类型补 0；
	// all = 三桶之和（单趟 GROUP BY 后求和，与 SQL 无二次往返）。
	typeRows, err := s.q.FacetMediaTypeCounts(ctx, db.FacetMediaTypeCountsParams{
		IncludeCos:     includeCos,
		CosOnly:        cosOnly,
		CharactersJson: charactersJson,
		CosWork:        cosWork,
		AuthorID:       authorID,
		QJson:          qJson,
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
