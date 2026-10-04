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
	"fmt"
	"net/http"
	"sort"

	"golang.org/x/sync/errgroup"

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

// facetFilters 四维候选聚合的统一收窄参数：把十来个可选请求参数归一为
// SQL 谓词形态后的单载体（与 assets_filters.go 的 assetFilters 同型——
// 归一入口唯一，各维度查询按需取字段，避免长参数表在字段增减时静默错位）。
type facetFilters struct {
	mediaType      any
	charactersJson any
	cosWork        any
	authorID       any
	source         any
	sourceIsOther  int64
	qJson          any
	favoriteSubset int64
	historySubset  int64
	// partition 两旗（partitionFlags 的产物，部分维度查询直接消费）。
	includeCos int64
	cosOnly    int64
}

// newFacetFilters 把 GET /assets/facets 的可选参数归一为谓词形态（唯一入口）。
func newFacetFilters(params gen.GetApiV1AssetsFacetsParams) facetFilters {
	f := facetFilters{}
	f.includeCos, f.cosOnly = partitionFlags(params.Partition)

	if params.MediaType != nil {
		f.mediaType = nullStr(string(*params.MediaType))
	}
	if params.Character != nil && *params.Character != "" {
		f.charactersJson = jsonString(splitCharacters(*params.Character))
	}
	if params.Work != nil && *params.Work != "" {
		f.cosWork = nullStr(*params.Work)
	}
	if params.AuthorId != nil && *params.AuthorId != "" {
		f.authorID = nullStr(*params.AuthorId)
	}
	// source 参数与 GET /assets 同口径：「其他」→ source_is_other 旗。
	if params.Source != nil && *params.Source != "" {
		if *params.Source == sourceOtherLabel {
			f.sourceIsOther = 1
		} else {
			f.source = nullStr(*params.Source)
		}
	}
	if params.Q != nil && *params.Q != "" {
		f.qJson = jsonString(search.ParseQuery(*params.Q))
	}
	// 子集约束（非四维之一，openapi 端点 description）：favorite=1 → 只
	// 统计收藏资产，history=1 → 只统计有 open 事件的资产；对全部四维（含
	// 分区栏）统一生效，不存在排自身问题。恒传 0/1——谓词形态
	// sqlc.arg(x)=0 OR EXISTS（facets.sql 文件头），传 NULL 会落三值逻辑
	// 整行排除。显式 false 与缺省同义（协议「缺省不约束」）。
	if params.Favorite != nil && *params.Favorite {
		f.favoriteSubset = 1
	}
	if params.History != nil && *params.History {
		f.historySubset = 1
	}
	return f
}

// GetApiV1AssetsFacets 四维候选聚合。排自身的实现口径 = 调用方每维独立
// 请求、请求时省略该维自身参数；服务端把全部收窄参数照常应用于每次查询
// （2026-09-09 协议批：作者行 source/authorId 亦然，见 facets.sql 文件头
// 逐查询的维度表）。参数归一见 newFacetFilters，各维度装配见
// facetPartitionBuckets / facetAuthorBuckets / facetCharacterBuckets /
// facetMediaTypeBuckets。
//
// 六个聚合查询经 errgroup 并行发射（2026-10 前为四函数串行调用链、作者/
// 角色行内部还各串两个查询，最坏六次往返叠乘）。查询相互独立已核实：
// 全部只读、共享的筛选载体 f 只读、装配在全部完成后进行、任一查询的
// 输入不依赖另一查询的结果；SQLite WAL 读并发天然支持。错误语义与原串行
// 版一致：首个失败的 err 经 Wait 返回，阶段名随错误链传递（日志可定位），
// 客户端拿到统一的 500（原先各函数自行写响应的文案本就同一模板）。
func (s *Server) GetApiV1AssetsFacets(w http.ResponseWriter, r *http.Request, params gen.GetApiV1AssetsFacetsParams) {
	f := newFacetFilters(params)
	isCosPartition := params.Partition != nil && *params.Partition == gen.PartitionCos
	isRegularPartition := params.Partition != nil && *params.Partition == gen.PartitionRegular
	ctx := r.Context()

	var (
		partRow    db.FacetPartitionCountsRow
		srcRows    []db.FacetSourceCountsRow
		authorRows []db.FacetAuthorCountsRow
		charRows   []db.FacetCharacterCountsRow
		workRows   []db.FacetCosWorkCountsRow
		typeRows   []db.FacetMediaTypeCountsRow
	)
	g := errgroup.Group{}
	g.Go(func() error {
		row, err := s.q.FacetPartitionCounts(ctx, db.FacetPartitionCountsParams{
			MediaType:      f.mediaType,
			CharactersJson: f.charactersJson,
			CosWork:        f.cosWork,
			Source:         f.source,
			SourceIsOther:  f.sourceIsOther,
			AuthorID:       f.authorID,
			QJson:          f.qJson,
			FavoriteSubset: f.favoriteSubset,
			HistorySubset:  f.historySubset,
		})
		if err != nil {
			// 阶段名进错误链：internalErr 的日志仍可定位到具体维度（成功路径
			// 绝不能包装——fmt.Errorf 对 nil err 会产出 %!w(<nil>) 假错误）。
			return fmt.Errorf("聚合分区维度: %w", err)
		}
		partRow = row
		return nil
	})
	// 作者行/角色行的两个查询按分区条件跳过（口径见各装配函数注释），
	// 与原串行版取值完全一致。
	if !isCosPartition {
		g.Go(func() error {
			rows, err := s.q.FacetSourceCounts(ctx, db.FacetSourceCountsParams{
				MediaType:      f.mediaType,
				CharactersJson: f.charactersJson,
				CosWork:        f.cosWork,
				Source:         f.source,
				SourceIsOther:  f.sourceIsOther,
				AuthorID:       f.authorID,
				QJson:          f.qJson,
				FavoriteSubset: f.favoriteSubset,
				HistorySubset:  f.historySubset,
			})
			if err != nil {
				return fmt.Errorf("聚合作者行出处分组: %w", err)
			}
			srcRows = rows
			return nil
		})
	}
	if !isRegularPartition {
		g.Go(func() error {
			rows, err := s.q.FacetAuthorCounts(ctx, db.FacetAuthorCountsParams{
				IncludeCos:     f.includeCos,
				CosOnly:        f.cosOnly,
				MediaType:      f.mediaType,
				CharactersJson: f.charactersJson,
				CosWork:        f.cosWork,
				Source:         f.source,
				SourceIsOther:  f.sourceIsOther,
				AuthorID:       f.authorID,
				QJson:          f.qJson,
				FavoriteSubset: f.favoriteSubset,
				HistorySubset:  f.historySubset,
			})
			if err != nil {
				return fmt.Errorf("聚合作者行 COS 作者: %w", err)
			}
			authorRows = rows
			return nil
		})
	}
	if !isCosPartition {
		g.Go(func() error {
			rows, err := s.q.FacetCharacterCounts(ctx, db.FacetCharacterCountsParams{
				IncludeCos:     f.includeCos,
				CosOnly:        f.cosOnly,
				MediaType:      f.mediaType,
				Source:         f.source,
				SourceIsOther:  f.sourceIsOther,
				AuthorID:       f.authorID,
				QJson:          f.qJson,
				FavoriteSubset: f.favoriteSubset,
				HistorySubset:  f.historySubset,
			})
			if err != nil {
				return fmt.Errorf("聚合角色维度: %w", err)
			}
			charRows = rows
			return nil
		})
	}
	if !isRegularPartition {
		g.Go(func() error {
			rows, err := s.q.FacetCosWorkCounts(ctx, db.FacetCosWorkCountsParams{
				IncludeCos:     f.includeCos,
				CosOnly:        f.cosOnly,
				MediaType:      f.mediaType,
				Source:         f.source,
				SourceIsOther:  f.sourceIsOther,
				AuthorID:       f.authorID,
				QJson:          f.qJson,
				FavoriteSubset: f.favoriteSubset,
				HistorySubset:  f.historySubset,
			})
			if err != nil {
				return fmt.Errorf("聚合角色维度(COS 作品): %w", err)
			}
			workRows = rows
			return nil
		})
	}
	g.Go(func() error {
		rows, err := s.q.FacetMediaTypeCounts(ctx, db.FacetMediaTypeCountsParams{
			IncludeCos:     f.includeCos,
			CosOnly:        f.cosOnly,
			CharactersJson: f.charactersJson,
			CosWork:        f.cosWork,
			Source:         f.source,
			SourceIsOther:  f.sourceIsOther,
			AuthorID:       f.authorID,
			QJson:          f.qJson,
			FavoriteSubset: f.favoriteSubset,
			HistorySubset:  f.historySubset,
		})
		if err != nil {
			return fmt.Errorf("聚合类型维度: %w", err)
		}
		typeRows = rows
		return nil
	})
	if err := g.Wait(); err != nil {
		s.internalErr(w, "聚合相册候选", err)
		return
	}
	writeJSON(w, http.StatusOK, gen.AssetFacets{
		Partitions: facetPartitionBuckets(partRow),
		Authors:    facetAuthorBuckets(srcRows, authorRows, isCosPartition, isRegularPartition),
		Characters: facetCharacterBuckets(charRows, workRows, isCosPartition, isRegularPartition),
		Types:      facetMediaTypeBuckets(typeRows),
	})
}

// facetPartitionBuckets 分区栏：固定 all/regular/cos 三项。分区维排自身=
// 全量报告，一次查询同时给出全量与 COS 计数（常规 = 全量 - COS，facets.sql
// 单趟合并）。子集约束传入时分区芯片 = 子集内的 all/regular/cos。
func facetPartitionBuckets(part db.FacetPartitionCountsRow) []gen.FacetBucket {
	cosCount := toInt(part.CosCount)
	return []gen.FacetBucket{
		{Key: "all", Name: "全部", FileCount: int(part.AllCount)},
		{Key: "regular", Name: "常规", FileCount: int(part.AllCount) - cosCount},
		{Key: "cos", Name: "COS", FileCount: cosCount},
	}
}

// facetAuthorBuckets 作者行（旧版「作品」行；2026-09-09 协议批起
// source/authorId 对本行照常收窄——作者集合页固定传 authorId 即可让
// 作品维候选按作者收窄；排自身由相册页调用方每维独立请求时省略自身
// 参数实现）：常规分区=出处分组；COS 分区=COS 作者；全部分区=两者合并。
// 纯装配：两路查询已在 GetApiV1AssetsFacets 并行完成（分区条件跳过口径
// 与原串行版一致）。
func facetAuthorBuckets(srcRows []db.FacetSourceCountsRow, authorRows []db.FacetAuthorCountsRow, isCosPartition, isRegularPartition bool) []gen.FacetBucket {
	var authors []gen.FacetBucket
	if !isCosPartition {
		authors = facetSourceBuckets(srcRows)
	}
	if !isRegularPartition {
		authors = mergeFacetBuckets(authors, facetCosAuthorBuckets(authorRows))
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
	return authors
}

// facetCharacterBuckets 角色行（排自身：忽略 character 与 work——两参数
// 同属「角色」行）。常规分区=匹配引擎角色名；COS 分区=COS 作品名（旧版
// 「COS 角色=作品名」口径，DOMAIN_RULES §6）；全部分区=两者合并。
// NULL 作品（无作品子目录）不列入，前端按需兜底（openapi characters
// description）。纯装配：两路查询已并行完成。
func facetCharacterBuckets(charRows []db.FacetCharacterCountsRow, workRows []db.FacetCosWorkCountsRow, isCosPartition, isRegularPartition bool) []gen.FacetBucket {
	var characters []gen.FacetBucket
	if !isCosPartition {
		characters = make([]gen.FacetBucket, 0, len(charRows))
		for _, row := range charRows {
			characters = append(characters, gen.FacetBucket{
				Key: row.CharacterName, Name: row.CharacterName, FileCount: int(row.FileCount),
				Kind: ptr(gen.FacetBucketKindCharacter),
			})
		}
	}
	if !isRegularPartition {
		works := make([]gen.FacetBucket, 0, len(workRows))
		for _, row := range workRows {
			works = append(works, gen.FacetBucket{
				Key: row.WorkName.String, Name: row.WorkName.String, FileCount: int(row.FileCount),
				Kind: ptr(gen.FacetBucketKindWork),
			})
		}
		characters = mergeFacetBuckets(characters, works)
	}
	return characters
}

// facetMediaTypeBuckets 类型栏（排自身：忽略 mediaType）。固定四项、
// 无数据的类型补 0；all = 三桶之和（单趟 GROUP BY 后求和，与 SQL 无二次往返）。
// 纯装配：查询已并行完成。
func facetMediaTypeBuckets(typeRows []db.FacetMediaTypeCountsRow) []gen.FacetBucket {
	counts := make(map[gen.MediaType]int, len(facetMediaTypeLabels))
	total := 0
	for _, row := range typeRows {
		mt := gen.MediaType(row.MediaType)
		counts[mt] = int(row.FileCount)
		total += int(row.FileCount)
	}
	return []gen.FacetBucket{
		{Key: "all", Name: "全部", FileCount: total},
		{Key: string(gen.MediaTypeImage), Name: facetMediaTypeLabels[gen.MediaTypeImage], FileCount: counts[gen.MediaTypeImage]},
		{Key: string(gen.MediaTypeAnimatedImage), Name: facetMediaTypeLabels[gen.MediaTypeAnimatedImage], FileCount: counts[gen.MediaTypeAnimatedImage]},
		{Key: string(gen.MediaTypeVideo), Name: facetMediaTypeLabels[gen.MediaTypeVideo], FileCount: counts[gen.MediaTypeVideo]},
	}
}

// facetSourceBuckets 作者行的出处分组候选（常规/全部分区调用）。
// 「其他」（NULL source）桶排在末尾（旧版 renderSourcePills 的「其他
// 永远排在列表最下面」），key=sourceOtherLabel 可直接回传 GET /assets。
// source/sourceIsOther/authorID 是普通收窄键（2026-09-09 协议批：作者
// 集合页固定传 authorId 让作品维候选按作者收窄；排自身由相册页调用方
// 省略自身参数实现，见 openapi facets description）。纯装配。
func facetSourceBuckets(rows []db.FacetSourceCountsRow) []gen.FacetBucket {
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
	return buckets
}

// facetCosAuthorBuckets 作者行的 COS 作者候选（COS/全部分区调用）。
// source/sourceIsOther/authorID 同 facetSourceBuckets：普通收窄键。纯装配。
func facetCosAuthorBuckets(rows []db.FacetAuthorCountsRow) []gen.FacetBucket {
	buckets := make([]gen.FacetBucket, 0, len(rows))
	for _, row := range rows {
		buckets = append(buckets, gen.FacetBucket{
			Key: row.AuthorID, Name: row.AuthorName, FileCount: int(row.FileCount),
			Kind: ptr(gen.FacetBucketKindAuthor),
		})
	}
	return buckets
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
