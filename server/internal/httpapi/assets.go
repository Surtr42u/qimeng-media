package httpapi

import (
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"reflect"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
	openapi_types "github.com/oapi-codegen/runtime/types"

	"qimeng-media/server/internal/auth"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
	"qimeng-media/server/internal/thumbnail"
)

// ---- 游标 ----
//
// cursor 是对客户端不透明的 base64(JSON{"k": sort_key, "i": asset_id})。
// k/i 即上一页最后一行在当前排序下的 (sort_key, asset_id)——keyset 的
// 全部信息；排序键与方向不编进游标（翻页中途改排序 = 新查询新游标，
// 服务端不做跨排序续读）。

type pageCursor struct {
	K string `json:"k"`
	I string `json:"i"`
}

func encodeCursor(k, id string) string {
	b, _ := json.Marshal(pageCursor{K: k, I: id})
	return base64.RawURLEncoding.EncodeToString(b)
}

func decodeCursor(s string) (pageCursor, error) {
	var c pageCursor
	b, err := base64.RawURLEncoding.DecodeString(s)
	if err != nil {
		return c, err
	}
	err = json.Unmarshal(b, &c)
	return c, err
}

// ---- 可空参数封装 ----
// sqlc 对混合类型筛选参数生成 interface{} 字段；统一在这里把三种可空
// 形态（字符串/整数/布尔）装进 sql.Null*，零值 Valid=false = 筛选未启用。

func nullStr(s string) any {
	return sql.NullString{String: s, Valid: s != ""}
}

func nullInt(n *int) any {
	if n == nil {
		return sql.NullInt64{}
	}
	return sql.NullInt64{Int64: int64(*n), Valid: true}
}

func nullBool(b *bool) any {
	if b == nil {
		return sql.NullBool{}
	}
	return sql.NullBool{Bool: *b, Valid: true}
}

// jsonString 把字符串集合编成 SQL json_each 消费的 JSON 数组文本；
// 空集返回 NULL（筛选未启用）。
func jsonString(items []string) any {
	if len(items) == 0 {
		return sql.NullString{}
	}
	b, err := json.Marshal(items)
	if err != nil {
		// []string 的 Marshal 不会失败；防御性兜底为 NULL。
		return sql.NullString{}
	}
	return sql.NullString{String: string(b), Valid: true}
}

// assetFilters 是从 openapi 查询参数归一出的筛选集，供三种查询
// （Asc/Desc/Count）共享——填写逻辑只写一份，避免三处漂移。
type assetFilters struct {
	LibraryID      any
	MediaType      any
	Source         any
	SourceIsOther  int64
	IncludeCos     int64
	CharactersJson any
	AuthorID       any
	TagIdsJson     any
	TagMode        any
	Favorite       any
	MtimeFrom      any
	MtimeTo        any
	YearFrom       any
	YearTo         any
	ViewRange      any
	PlayRange      any
	SizeRange      any
}

// newAssetFilters 把 openapi 参数映射成筛选集。语义备注：
//   - source="其他" 翻译成 source_is_other 标志（NULL 出处的资产桶），
//     避免 SQL 里出现非 ASCII 字面量（sqlc 解析器对多字节文本敏感，
//     见 browse.sql 文件头）；
//   - includeCos 默认 false = 排除 COS 作者关联文件（DOMAIN_RULES §6）；
//   - character 'a+b' 拆成集合，SQL 语义 = 全部命中（组合出镜）；
//   - dateFrom/dateTo 是本地日历日，换算成与 mtime 存储格式同构的
//     UTC 毫秒时间戳文本再做字典序比较（dateTo 含当日全天）。
func newAssetFilters(params gen.GetApiV1AssetsParams) assetFilters {
	var f assetFilters
	if params.LibraryId != nil {
		f.LibraryID = nullStr(*params.LibraryId)
	}
	if params.MediaType != nil {
		f.MediaType = nullStr(string(*params.MediaType))
	}
	if params.Source != nil {
		if *params.Source == "其他" {
			f.SourceIsOther = 1
		} else {
			f.Source = nullStr(*params.Source)
		}
	}
	if params.IncludeCos != nil && *params.IncludeCos {
		f.IncludeCos = 1
	}
	if params.Character != nil && *params.Character != "" {
		f.CharactersJson = jsonString(splitCharacters(*params.Character))
	}
	if params.AuthorId != nil {
		f.AuthorID = nullStr(*params.AuthorId)
	}
	if params.TagIds != nil && len(*params.TagIds) > 0 {
		f.TagIdsJson = jsonString(*params.TagIds)
	}
	f.TagMode = "fuzzy"
	if params.TagMode != nil && *params.TagMode == gen.Exact {
		f.TagMode = "exact"
	}
	if params.Favorite != nil {
		f.Favorite = nullBool(params.Favorite)
	}
	if params.DateFrom != nil {
		f.MtimeFrom = nullStr(store.FormatTimestamp(params.DateFrom.Time.UTC()))
	}
	if params.DateTo != nil {
		f.MtimeTo = nullStr(store.FormatTimestamp(params.DateTo.Time.UTC().Add(24*time.Hour - time.Millisecond)))
	}
	if params.YearFrom != nil {
		f.YearFrom = nullInt(params.YearFrom)
	}
	if params.YearTo != nil {
		f.YearTo = nullInt(params.YearTo)
	}
	if params.ViewRange != nil {
		f.ViewRange = nullStr(string(*params.ViewRange))
	}
	if params.PlayRange != nil {
		f.PlayRange = nullStr(string(*params.PlayRange))
	}
	if params.SizeRange != nil {
		f.SizeRange = nullStr(string(*params.SizeRange))
	}
	return f
}

// splitCharacters 按 '+'/'x'/'&'/空格 分隔拆多角色表达
// （DOMAIN_RULES §4：分隔符天然支持）。
func splitCharacters(s string) []string {
	f := strings.FieldsFunc(s, func(r rune) bool {
		return r == '+' || r == 'x' || r == 'X' || r == '&' || r == ' '
	})
	out := make([]string, 0, len(f))
	for _, v := range f {
		if v != "" {
			out = append(out, v)
		}
	}
	return out
}

// GetApiV1Assets 资产列表：动态筛选 + 排序 + keyset 分页。
// 语义唯一权威是 docs/DOMAIN_RULES §3；SQL 侧的取舍见
// internal/store/queries/browse.sql 文件头。
func (s *Server) GetApiV1Assets(w http.ResponseWriter, r *http.Request, params gen.GetApiV1AssetsParams) {
	limit := 60 // openapi 默认
	if params.Limit != nil {
		if *params.Limit < 1 || *params.Limit > 200 {
			writeErr(w, http.StatusBadRequest, "INVALID_PARAM", "limit 取值范围 1..200")
			return
		}
		limit = *params.Limit
	}
	sortKey := "default"
	if params.Sort != nil {
		sortKey = string(*params.Sort)
	}
	asc := params.Order != nil && *params.Order == gen.Asc

	var cur pageCursor
	if params.Cursor != nil && *params.Cursor != "" {
		c, err := decodeCursor(*params.Cursor)
		if err != nil {
			writeErr(w, http.StatusBadRequest, "INVALID_CURSOR", "分页游标不合法")
			return
		}
		cur = c
	}
	// q（全文搜索）：FTS5 索引在 M2/M3 接入，M1 收到即忽略、不报错——
	// 前端可以先带参数，服务端能力就绪后自动生效。
	// groupByDate：AssetPage 响应结构无分组字段，日期分组标签
	//（DOMAIN_RULES §8）由客户端按 modifiedAt 折叠，服务端无动作。
	filters := newAssetFilters(params)

	var items []gen.AssetSummary
	var lastKey, lastID string
	hasMore := false
	if asc {
		p := db.ListAssetsFilteredAscParams{
			Sort: sortKey, RowLimit: int64(limit) + 1, // 多取 1 行探测下一页
			CursorKey: nullStr(cur.K),
			CursorID:  sql.NullString{String: cur.I, Valid: cur.K != ""},
		}
		applyFilters(&p, filters)
		rows, err := s.q.ListAssetsFilteredAsc(r.Context(), p)
		if err != nil {
			s.logger.Error("查询资产列表失败", "err", err)
			writeErr(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
			return
		}
		hasMore = len(rows) > limit
		if hasMore {
			rows = rows[:limit]
		}
		items = make([]gen.AssetSummary, 0, len(rows))
		for i := range rows {
			row := &rows[i]
			items = append(items, buildSummary(s, row.AssetID, row.FileName, row.MediaType,
				row.SizeBytes, row.Mtime, row.CreatedAt, row.Source, row.IsFavorite, row.LikeCount))
			lastKey, lastID = toString(row.SortKey), row.AssetID
		}
	} else {
		p := db.ListAssetsFilteredDescParams{
			Sort: sortKey, RowLimit: int64(limit) + 1,
			CursorKey: nullStr(cur.K),
			CursorID:  sql.NullString{String: cur.I, Valid: cur.K != ""},
		}
		applyFilters(&p, filters)
		rows, err := s.q.ListAssetsFilteredDesc(r.Context(), p)
		if err != nil {
			s.logger.Error("查询资产列表失败", "err", err)
			writeErr(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
			return
		}
		hasMore = len(rows) > limit
		if hasMore {
			rows = rows[:limit]
		}
		items = make([]gen.AssetSummary, 0, len(rows))
		for i := range rows {
			row := &rows[i]
			items = append(items, buildSummary(s, row.AssetID, row.FileName, row.MediaType,
				row.SizeBytes, row.Mtime, row.CreatedAt, row.Source, row.IsFavorite, row.LikeCount))
			lastKey, lastID = toString(row.SortKey), row.AssetID
		}
	}

	page := gen.AssetPage{Items: &items}
	if hasMore {
		next := encodeCursor(lastKey, lastID)
		page.NextCursor = &next
	}
	// totalMatched：独立 COUNT（同筛选矩阵，不含游标/排序）。首屏才查
	//——翻页时总数不变，省一次全量计数。
	if cur.K == "" {
		var cp db.CountAssetsFilteredParams
		applyFilters(&cp, filters)
		total, err := s.q.CountAssetsFiltered(r.Context(), cp)
		if err != nil {
			s.logger.Error("统计资产总数失败", "err", err)
			writeErr(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
			return
		}
		t := int(total)
		page.TotalMatched = &t
	}
	writeJSON(w, http.StatusOK, page)
}

// buildSummary 组装 AssetSummary（含签名缩略图直链，网格 md 档）。
func buildSummary(s *Server, assetID, fileName, mediaType string, sizeBytes int64,
	mtime, createdAt string, source sql.NullString, isFavorite bool, likeCount int64) gen.AssetSummary {
	id := uuidOrNil(assetID)
	mt := gen.MediaType(mediaType)
	mod := parseStoreTime(mtime)
	added := parseStoreTime(createdAt)
	src := displaySource(source)
	fav := isFavorite
	like := int(likeCount)
	thumb := s.thumbURL(assetID, "md")
	return gen.AssetSummary{
		Id:         &id,
		FileName:   &fileName,
		MediaType:  &mt,
		SizeBytes:  &sizeBytes,
		ModifiedAt: &mod,
		AddedAt:    &added,
		Source:     &src,
		IsFavorite: &fav,
		LikeCount:  &like,
		ThumbUrl:   &thumb,
	}
}

// ---- 参数分发（Asc/Desc/Count 三个 sqlc 参数结构体同构，按字段名对齐复制） ----
//
// 17 个筛选字段只有一个组装来源（newAssetFilters），但 sqlc 为三种查询
// 生成了三个独立结构体（ListAssetsFilteredAsc/Desc、CountAssetsFiltered），
// 筛选字段一一同名同型。这里按 assetFilters 的字段名反射落进目标结构体，
// 取代三份逐字段复制的 applyFiltersAsc/Desc/Count：协议加一个筛选参数
// 只需改 newAssetFilters 与 SQL，不再有"改一漏二"的静默漂移。
// 目标结构体缺字段（三胞胎不同步）或类型不匹配会在运行期立即 panic
// ——编程错误当场暴露（测试兜底），好过漏赋值悄悄丢筛选条件。

func applyFilters(dst any, f assetFilters) {
	dv := reflect.ValueOf(dst).Elem()
	fv := reflect.ValueOf(f)
	ft := fv.Type()
	for i := 0; i < fv.NumField(); i++ {
		name := ft.Field(i).Name
		df := dv.FieldByName(name)
		if !df.IsValid() {
			panic(fmt.Sprintf("httpapi: 筛选目标结构体缺少字段 %s（sqlc 三参数结构不同步）", name))
		}
		df.Set(fv.Field(i)) // 类型不一致时 Set 直接 panic（编程错误，测试兜底）
	}
}

// GetApiV1AssetsAssetId 资产详情：组装全部关联数据与签名直链。
func (s *Server) GetApiV1AssetsAssetId(w http.ResponseWriter, r *http.Request, assetID gen.AssetId) {
	row, err := s.q.GetAssetWithLibrary(r.Context(), assetID.String())
	if errors.Is(err, sql.ErrNoRows) {
		writeErr(w, http.StatusNotFound, "NOT_FOUND", "资产不存在")
		return
	}
	if err != nil {
		s.logger.Error("查询资产失败", "err", err)
		writeErr(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
		return
	}
	ctx := r.Context()

	// 标签 / 作者 / 角色
	tagRows, err := s.q.ListAssetTagRefs(ctx, row.AssetID)
	if err != nil {
		s.internalErr(w, "查询资产标签", err)
		return
	}
	tags := make([]gen.Tag, 0, len(tagRows))
	for _, t := range tagRows {
		fc := 0 // 关联文件数：标签池级统计属 /tags 端点职责，详情处无意义
		id, name := t.ID, t.Name
		tags = append(tags, gen.Tag{Id: &id, Name: &name, FileCount: &fc})
	}
	authorRows, err := s.q.ListAssetAuthorRefs(ctx, row.AssetID)
	if err != nil {
		s.internalErr(w, "查询资产作者", err)
		return
	}
	authors := make([]gen.Author, 0, len(authorRows))
	for _, a := range authorRows {
		fc := 0
		id, name := a.ID, a.DisplayName
		at := gen.AuthorType(a.Type)
		authors = append(authors, gen.Author{Id: &id, DisplayName: &name, Type: &at, FileCount: &fc})
	}
	charNames, err := s.q.ListAssetCharacterNames(ctx, row.AssetID)
	if err != nil {
		s.internalErr(w, "查询资产角色", err)
		return
	}

	// 统计：view/play 计数、最近浏览、累计停留秒、点赞、收藏
	viewCount, playCount := 0, 0
	cntRows, err := s.q.CountAssetEvents(ctx, row.AssetID)
	if err != nil {
		s.internalErr(w, "聚合资产事件", err)
		return
	}
	for _, c := range cntRows {
		switch c.Kind {
		case "open":
			viewCount = int(c.Cnt)
		case "play":
			playCount = int(c.Cnt)
		}
	}
	lastViewed, err := s.q.LastViewedAt(ctx, row.AssetID)
	if err != nil {
		s.internalErr(w, "查询最近浏览时间", err)
		return
	}
	seconds, err := s.q.SumBrowseSeconds(ctx, row.AssetID)
	if err != nil {
		s.internalErr(w, "累计停留秒数", err)
		return
	}
	likeCount, err := s.q.CountAssetLikes(ctx, row.AssetID)
	if err != nil {
		s.internalErr(w, "统计点赞数", err)
		return
	}
	isFav, err := s.q.IsFavorite(ctx, row.AssetID)
	if err != nil {
		s.internalErr(w, "查询收藏态", err)
		return
	}

	// 签名直链（exp 默认 6h；orig 永不发转码副本，thumb 用大图档）。
	// gen.AssetDetail 是 allOf 展平后的单层结构，先把 Summary 基础字段
	// 复制过来再补扩展字段。
	base := buildSummary(s, row.AssetID, row.FileName, row.MediaType, row.SizeBytes,
		row.Mtime, row.CreatedAt, row.Source, isFav > 0, likeCount)
	detail := gen.AssetDetail{
		// AssetSummary 基础字段（allOf 展开）
		AddedAt:    base.AddedAt,
		FileName:   base.FileName,
		Id:         base.Id,
		IsFavorite: base.IsFavorite,
		LikeCount:  base.LikeCount,
		MediaType:  base.MediaType,
		ModifiedAt: base.ModifiedAt,
		SizeBytes:  base.SizeBytes,
		Source:     base.Source,
		// AssetDetail 扩展字段
		Authors:            &authors,
		Characters:         &charNames,
		Tags:               &tags,
		Directory:          ptr(dirOf(row.RelPath)),
		RelPath:            ptr(row.RelPath),
		ViewCount:          ptr(viewCount),
		PlayCount:          ptr(playCount),
		TotalBrowseSeconds: ptr(toInt(seconds)),
	}
	orig := s.signedMediaURL("/media/orig/" + row.AssetID)
	detail.OrigUrl = &orig
	thumb := s.thumbURL(row.AssetID, "lg")
	detail.ThumbUrl = &thumb
	if row.DurationMs.Valid {
		detail.DurationMs = ptr(row.DurationMs.Int64)
	}
	if row.Width.Valid {
		detail.Width = ptr(int(row.Width.Int64))
	}
	if row.Height.Valid {
		detail.Height = ptr(int(row.Height.Int64))
	}
	if lv, ok := lastViewed.(string); ok && lv != "" {
		t := parseStoreTime(lv)
		detail.LastViewedAt = &t
	}
	writeJSON(w, http.StatusOK, detail)
}

func (s *Server) internalErr(w http.ResponseWriter, what string, err error) {
	s.logger.Error(what+"失败", "err", err)
	writeErr(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
}

// signedMediaURL 生成带 exp/sig 的签名直链（auth 包协议）。
// path 必须与校验侧 r.URL.Path 同形态——纯路径（不含查询串），uuid 与
// hex 签名字符集不含需转义的字符，两形态天然一致。查询参数（如 thumb
// 的 size）不参与签名：size 属于缓存选择而非授权面，签名始终只锚定路径。
func (s *Server) signedMediaURL(path string) string {
	exp, sig := auth.SignMediaURL(path, s.now().Add(s.ttl), s.secret)
	return path + "?exp=" + strconv.FormatInt(exp, 10) + "&sig=" + sig
}

// thumbURL 生成缩略图签名直链（size 作为普通查询参数附在签名之后）。
//
// size 档位（openapi sm/md/lg）到像素的换算见 thumbSize；档位像素的
// 单一来源是 thumbnail 包的 Size 常量（cachekey.go），md 档像素由
// Thumbnail 配置 LongSide 决定（未配置回落 SizeGrid）。
func (s *Server) thumbURL(assetID, size string) string {
	return s.signedMediaURL("/media/thumb/"+assetID) + "&size=" + size
}

// thumbSize 把 openapi size 枚举映射到 thumbnail.Size：
// sm→SizeSmall、lg→SizePreview；md（默认档）→ Generator.GridLongSide()
// （LongSide 配置的接线出口，保证请求缓存键与生成尺寸一致）。
func (s *Server) thumbSize(size gen.GetMediaThumbAssetIdParamsSize) thumbnail.Size {
	switch size {
	case gen.Sm:
		return thumbnail.SizeSmall
	case gen.Lg:
		return thumbnail.SizePreview
	default:
		return thumbnail.Size(s.thumbs.GridLongSide()) // md（默认档）
	}
}

// parseStoreTime 解析库内时间戳文本；失败返回零值（脏数据不炸接口）。
func parseStoreTime(s string) time.Time {
	t, err := time.Parse(store.TimestampLayout, s)
	if err != nil {
		return time.Time{}
	}
	return t
}

// displaySource 把 NULL 出处显示为"其他"（DOMAIN_RULES §4 分区语义）。
func displaySource(src sql.NullString) string {
	if src.Valid && src.String != "" {
		return src.String
	}
	return "其他"
}

// uuidOrNil 解析资产 ID；库中主键由服务端生成，解析失败属数据损坏——
// 零 UUID 兜底让响应仍可序列化。
func uuidOrNil(s string) openapi_types.UUID {
	u, err := uuid.Parse(s)
	if err != nil {
		return openapi_types.UUID{}
	}
	return openapi_types.UUID(u)
}

// dirOf 取库内相对路径的目录部分（'/' 分隔；根目录文件返回空串）。
func dirOf(rel string) string {
	if i := strings.LastIndex(rel, "/"); i >= 0 {
		return rel[:i]
	}
	return ""
}

// toString 抹平 sqlc interface{} 列（sort_key 恒为 TEXT 非空——
// 分支全部指向 NOT NULL 列或 printf 输出，见 browse.sql）。
func toString(v any) string {
	if s, ok := v.(string); ok {
		return s
	}
	return ""
}

// toInt 抹平 sqlc interface{} 标量（SUM/MAX 的整型返回）。
func toInt(v any) int {
	switch n := v.(type) {
	case int64:
		return int(n)
	case float64:
		return int(n)
	default:
		return 0
	}
}

// ptr 是小型取址助手（Go 无泛型字面量取址，逐处 var 太啰嗦）。
func ptr[T any](v T) *T { return &v }
