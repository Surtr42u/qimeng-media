// assets.go：资产列表端点（GET /assets）与浏览侧共享小助手（游标、
// 可空参数封装、筛选归一、AssetSummary 装配、签名直链、sqlc 标量抹平）。
// 资产详情端点（GET /assets/{assetId}）拆在 assets_detail.go；列表条目的
// 批量字段装配（fillList* 族）拆在 assets_list_fill.go。
package httpapi

import (
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"reflect"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
	openapi_types "github.com/oapi-codegen/runtime/types"

	"qimeng-media/server/internal/auth"
	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/search"
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
	return jsonValue(items)
}

// jsonValue 把任意可 JSON 序列化的集合值编成 SQL json_each 消费的 JSON
// 文本；nil 或空集返回 NULL（筛选未启用）。多值筛选（sources/works）与
// 组合筛选（characters 的「组合的数组」）共用。
func jsonValue(v any) any {
	if v == nil {
		return sql.NullString{}
	}
	if rv := reflect.ValueOf(v); rv.Kind() == reflect.Slice && rv.Len() == 0 {
		return sql.NullString{}
	}
	b, err := json.Marshal(v)
	if err != nil {
		// []string / [][]string 的 Marshal 不会失败；防御性兜底为 NULL。
		return sql.NullString{}
	}
	return sql.NullString{String: string(b), Valid: true}
}

// assetFilters 是从 openapi 查询参数归一出的筛选集，供三种查询
// （Asc/Desc/Count）共享——填写逻辑只写一份，避免三处漂移。
type assetFilters struct {
	LibraryID      any
	MediaType      any
	SourcesJson    any
	SourceIsOther  int64
	IncludeCos     int64
	CosOnly        int64
	CosWorksJson   any
	CharactersJson any
	AuthorID       any
	TagIdsJson     any
	TagMode        any
	Favorite       any
	Liked          any
	MtimeFrom      any
	MtimeTo        any
	YearFrom       any
	YearTo         any
	ViewRange      any
	PlayRange      any
	SizeRange      any
	QJson          any
	// Directory 目录过滤（openapi GET /assets directory）：*请*用
	// sql.NullString{Valid:true} 直接落值，禁止走 nullStr——nullStr 把
	// "" 映射 NULL，而本参数的 "" 是合法值（=库根）；缺省（nil）才是
	// 不过滤。归一化在 handler 侧完成后传入 newAssetFilters。
	Directory any
}

// newAssetFilters 把 openapi 参数映射成筛选集。语义备注：
//   - source/character/work 多值（协议 2026-09-09）：数组内 OR、与其余
//     筛选维 AND；source="其他" 翻译成 source_is_other 标志（NULL 出处
//     的资产桶），避免 SQL 里出现非 ASCII 字面量（sqlc 解析器对多字节
//     文本敏感，见 browse.sql 文件头）；单值=单元素数组向后兼容；
//   - character 每元素 'a+b' 拆成组合（组内全部命中），数组编成「组合
//     的数组」（组合间 OR）；facets 端点的 character 是单值参数，其
//     SQL（facets.sql）保持扁平数组形态，两文件谓词注释互指勿混；
//   - includeCos 默认 false = 排除 COS 作者关联文件（DOMAIN_RULES §6）；
//     收藏流特例见下方 favorite 分支（2026-09-05 用户拍板）；
//   - dateFrom/dateTo 是本地日历日，换算成与 mtime 存储格式同构的
//     UTC 毫秒时间戳文本再做字典序比较（dateTo 含当日全天）；
//   - directory 由 handler 预校验归一（filing.NormalizeRelPath）后传入：
//     nil=缺省不过滤；非 nil 含空串=库根（目录语义允许空，与资产路径
//     必须非空不同——同 filing.go move 的 targetDir 先例）。
func newAssetFilters(params gen.GetApiV1AssetsParams, directory *string) assetFilters {
	var f assetFilters
	if params.LibraryId != nil {
		f.LibraryID = nullStr(*params.LibraryId)
	}
	if params.MediaType != nil {
		f.MediaType = nullStr(string(*params.MediaType))
	}
	if params.Source != nil {
		// 多值 source（协议 2026-09-09）：数组内 OR。'其他' 桶翻译成
		// source_is_other 旗（见上），其余出处名进 JSON 数组（IN json_each）。
		var sources []string
		for _, src := range *params.Source {
			if src == sourceOtherLabel {
				f.SourceIsOther = 1
			} else if src != "" {
				sources = append(sources, src)
			}
		}
		f.SourcesJson = jsonString(sources)
	}
	if params.IncludeCos != nil && *params.IncludeCos {
		f.IncludeCos = 1
	}
	// COS 分区三态开关（browse.sql 注释）：include_cos=1 不限制（all）；
	// cos_only=1 只要 COS（cos）；两者皆 0 排除 COS（regular，历史默认口径）。
	// cos_only 协议默认 false = 常规分区，必须恒传 0/1——三态谓词里
	// cos_only 为 NULL 会让非 COS 行落到三值逻辑 NULL 被整行排除。
	// cosOnly 与 includeCos 同真时 cosOnly 优先（协议注释口径）。
	f.CosOnly = 0
	if params.CosOnly != nil && *params.CosOnly {
		f.CosOnly = 1
		f.IncludeCos = 0
	}
	// 收藏流缺省「全部」（2026-09-05 用户拍板 1A）：favorite=true 且调用方
	// 未显式传分区参数 → 含 COS（收藏页与 /history 同步改为常规∪COS）。
	// 协议侧 /assets includeCos schema default 保持 false（非收藏流缺省排除
	// 不变，DOMAIN_RULES §6 隔离口径只对首页推荐等流保留），此分支为收藏流
	// 特例；/history 的缺省全部是协议 schema default=true，两处口径不同勿混。
	if params.Favorite != nil && *params.Favorite &&
		params.IncludeCos == nil && params.CosOnly == nil {
		f.IncludeCos = 1
	}
	if params.Work != nil {
		// COS 作品名多值（协议 2026-09-09）：数组内 OR（migration 0008，
		// `作者/作品/文件` 的第二段，COS 分区下的「角色」维度，DOMAIN_RULES §6）。
		var works []string
		for _, w := range *params.Work {
			if w != "" {
				works = append(works, w)
			}
		}
		f.CosWorksJson = jsonString(works)
	}
	if params.Character != nil && len(*params.Character) > 0 {
		// 角色多值（协议 2026-09-09）：每个元素是旧单值表达式（'a+b' 组合
		// 出镜，组内 AND），数组内 OR——编成「组合的数组」（jsonValue）。
		combos := make([][]string, 0, len(*params.Character))
		for _, c := range *params.Character {
			combos = append(combos, splitCharacters(c))
		}
		f.CharactersJson = jsonValue(combos)
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
	if params.Liked != nil {
		f.Liked = nullBool(params.Liked)
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
	if params.Q != nil {
		// 全文搜索：词法与语义（空格分词、多词 AND）见 search.ParseQuery；
		// 谓词语义（instr 子串）见 browse.sql 的 q_json 注释。
		f.QJson = jsonString(search.ParseQuery(*params.Q))
	}
	if directory != nil {
		// 空串=库根，必须 Valid（rel_path=file_name 的尾段不变量，
		// browse.sql directory 谓词注释）；绝不能过 nullStr（"" → NULL）。
		f.Directory = sql.NullString{String: *directory, Valid: true}
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

// ---- 列表行装配（Asc/Desc 去重） ----

// listRowView 抹平 sqlc Asc/Desc 两胞胎 Row（同一 SQL 按方向生成两个
// 字段同名同型的独立结构体），让截断探测与行→AssetSummary 装配只写
// 一份（代码卫生：禁止复制粘贴）。只搬列表装配用到的字段；SortKey 是
// sqlc 对动态排序列生成的 interface{} 列（sort_key 恒为 TEXT 非空，
// 见 toString）。
type listRowView struct {
	AssetID    string
	FileName   string
	MediaType  string
	SizeBytes  int64
	Mtime      string
	CreatedAt  string
	Source     sql.NullString
	IsFavorite bool
	LikeCount  int64
	DurationMs sql.NullInt64
	SortKey    any
}

// listRowViewFromAsc 两胞胎 Row 的字段搬运，sqlc 固有成本：Asc/Desc 两
// 结构体字段同名同型却无法用泛型收敛（接口无法约束结构体字段），只能
// 各写一份逐字段复制。
func listRowViewFromAsc(r db.ListAssetsFilteredAscRow) listRowView {
	return listRowView{AssetID: r.AssetID, FileName: r.FileName, MediaType: r.MediaType,
		SizeBytes: r.SizeBytes, Mtime: r.Mtime, CreatedAt: r.CreatedAt, Source: r.Source,
		IsFavorite: r.IsFavorite, LikeCount: r.LikeCount, DurationMs: r.DurationMs, SortKey: r.SortKey}
}

// listRowViewFromDesc 同 listRowViewFromAsc，Desc 侧。
func listRowViewFromDesc(r db.ListAssetsFilteredDescRow) listRowView {
	return listRowView{AssetID: r.AssetID, FileName: r.FileName, MediaType: r.MediaType,
		SizeBytes: r.SizeBytes, Mtime: r.Mtime, CreatedAt: r.CreatedAt, Source: r.Source,
		IsFavorite: r.IsFavorite, LikeCount: r.LikeCount, DurationMs: r.DurationMs, SortKey: r.SortKey}
}

// buildListPage 收敛"多取 1 行探测 hasMore + 截断 + 行→AssetSummary
// 装配 + 下一页游标锚点"——Asc/Desc 两分支只差查询本身，这段后处理
// 完全对称。lastKey/lastID 是本页最后一行在当前排序下的锚点（keyset
// 分页的全部信息）。
func buildListPage(s *Server, rows []listRowView, limit int) (items []gen.AssetSummary, lastKey, lastID string, hasMore bool) {
	hasMore = len(rows) > limit
	if hasMore {
		rows = rows[:limit] // 多取的那 1 行只用来证明还有下一页，不进本页
	}
	items = make([]gen.AssetSummary, 0, len(rows))
	for i := range rows {
		row := &rows[i]
		item := buildSummary(s, row.AssetID, row.FileName, row.MediaType,
			row.SizeBytes, row.Mtime, row.CreatedAt, row.Source, row.IsFavorite, row.LikeCount, nil, nil)
		if row.DurationMs.Valid {
			item.DurationMs = ptr(row.DurationMs.Int64) // 卡片时长角标数据（仅视频有值）
		}
		items = append(items, item)
		lastKey, lastID = toString(row.SortKey), row.AssetID
	}
	return items, lastKey, lastID, hasMore
}

// GetApiV1Assets 资产列表：动态筛选 + 排序 + keyset 分页。
// 语义唯一权威是 docs/DOMAIN_RULES §3；SQL 侧的取舍见
// internal/store/queries/browse.sql 文件头。
// 超函数警戒线（>100 行）理由：oapi-codegen 生成的接口签名 + 单请求直线
// 流（参数归一→游标→SQL→装配→响应），无嵌套分支复杂度；拆段只会把
// limit/sort/cur 等一串局部状态提升为结构体在函数间传递，可读性反而下降。
func (s *Server) GetApiV1Assets(w http.ResponseWriter, r *http.Request, params gen.GetApiV1AssetsParams) {
	limit, ok := resolvePageLimit(w, params.Limit)
	if !ok {
		return
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
			writeErr(w, http.StatusBadRequest, codeInvalidCursor, "分页游标不合法")
			return
		}
		cur = c
	}
	// q（全文搜索）：语义与词法见 search.ParseQuery 与 DOMAIN_RULES §3；
	// 谓词在 browse.sql 三查询内，与全部筛选叠加生效（AND）。
	// groupByDate：AssetPage 响应结构无分组字段，日期分组标签
	//（DOMAIN_RULES §8）由客户端按 modifiedAt 折叠，服务端无动作。
	// directory（目录过滤，文件管理页目录树「文件行」数据源）：空串=库根
	//（与 GET /dirs 的 DirTree 根节点 path="" 语义对齐）；非空过
	// NormalizeRelPath（SECURITY 红线 1：一切来自请求的库内相对路径统一
	// 入口，move 的 targetDir 同此先例），失败 400 INVALID_PARAM。
	var directory *string
	if params.Directory != nil {
		dir := *params.Directory
		if dir != "" {
			norm, err := filing.NormalizeRelPath(dir)
			if err != nil {
				writeErr(w, http.StatusBadRequest, codeInvalidParam, "目录路径不合法")
				return
			}
			dir = norm
		}
		directory = &dir
	}
	filters := newAssetFilters(params, directory)

	// Asc/Desc 两分支只保留"组参数 + 查询 + 行搬运"，截断探测与
	// AssetSummary 装配收敛在 buildListPage（两分支的语义差异全在 SQL 侧）。
	var rows []listRowView
	if asc {
		p := db.ListAssetsFilteredAscParams{
			Sort: sortKey, RowLimit: int64(limit) + 1, // 多取 1 行探测下一页
			CursorKey: nullStr(cur.K),
			CursorID:  sql.NullString{String: cur.I, Valid: cur.K != ""},
		}
		applyFilters(&p, filters)
		raw, err := s.q.ListAssetsFilteredAsc(r.Context(), p)
		if err != nil {
			s.logger.Error("查询资产列表失败", "err", err)
			writeErr(w, http.StatusInternalServerError, codeInternal, "内部错误")
			return
		}
		rows = make([]listRowView, len(raw))
		for i := range raw {
			rows[i] = listRowViewFromAsc(raw[i])
		}
	} else {
		p := db.ListAssetsFilteredDescParams{
			Sort: sortKey, RowLimit: int64(limit) + 1, // 多取 1 行探测下一页
			CursorKey: nullStr(cur.K),
			CursorID:  sql.NullString{String: cur.I, Valid: cur.K != ""},
		}
		applyFilters(&p, filters)
		raw, err := s.q.ListAssetsFilteredDesc(r.Context(), p)
		if err != nil {
			s.logger.Error("查询资产列表失败", "err", err)
			writeErr(w, http.StatusInternalServerError, codeInternal, "内部错误")
			return
		}
		rows = make([]listRowView, len(raw))
		for i := range raw {
			rows[i] = listRowViewFromDesc(raw[i])
		}
	}
	items, lastKey, lastID, hasMore := buildListPage(s, rows, limit)

	// authorNames：列表响应的卡片作者行数据（常规∪COS），页大小一次
	// 批量查询二次装配（协议 GET /assets 描述；搜索走本查询自然获得）。
	s.fillListAuthorNames(r.Context(), items)

	// cosWork：COS 卡片标题数据源（COS 作品子目录名），同页大小批量装配。
	s.fillListCosWork(r.Context(), items)

	// likedToday：点赞按钮初始态（当日已赞判定，likes 表当日行存在性），
	// 同为页大小一次批量查询二次装配。
	if err := s.fillListLikedToday(r.Context(), items); err != nil {
		s.internalErr(w, "查询当日点赞态", err)
		return
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
			writeErr(w, http.StatusInternalServerError, codeInternal, "内部错误")
			return
		}
		t := int(total)
		page.TotalMatched = &t
	}
	writeJSON(w, http.StatusOK, page)
}

// buildSummary 组装 AssetSummary（含签名缩略图直链，网格 md 档）。
// viewCount/playCount 是 AssetSummary 的新增可选字段（openapi）：
// 仅排行榜等需要展示计数的端点传实测值，浏览列表传 nil（字段省略，
// 保持列表查询轻量——计数聚合不在列表 SQL 里）；detail 端点有自己的
// 统计聚合路径，也传 nil。
func buildSummary(s *Server, assetID, fileName, mediaType string, sizeBytes int64,
	mtime, createdAt string, source sql.NullString, isFavorite bool, likeCount int64,
	viewCount, playCount *int) gen.AssetSummary {
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
		ViewCount:  viewCount,
		PlayCount:  playCount,
	}
}

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
	return s.signedMediaURL(mediaPathThumb+assetID) + "&size=" + size
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

// sourceOtherLabel 「其他」出处桶的用户面名称（DOMAIN_RULES §4：未匹配出处的
// 归「其他」）。GET /assets 与 GET /assets/facets 的 source 参数用它回传
// source_is_other 标志，displaySource 用它兜底 NULL 显示——三处同一常量，
// 改词必须三处同步（代码卫生约束第 2/3 条）。
const sourceOtherLabel = "其他"

// displaySource 把 NULL 出处显示为"其他"（DOMAIN_RULES §4 分区语义）。
func displaySource(src sql.NullString) string {
	if src.Valid && src.String != "" {
		return src.String
	}
	return sourceOtherLabel
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
