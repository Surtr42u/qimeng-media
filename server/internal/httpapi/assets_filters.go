// assets_filters.go：资产列表筛选的参数归一层（游标编解码、可空参数
// 封装、JSON 集合序列化、assetFilters 结构与 applyFilters 反射落值）。
// 从 assets.go 按职责拆出：筛选语义的唯一组装来源是 newAssetFilters，
// 三种查询（Asc/Desc/Count）共享同一份字段集，避免「改一漏二」漂移。
package httpapi

import (
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"reflect"
	"strings"
	"time"

	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/search"
	"qimeng-media/server/internal/store"
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

// parseRequestCursor 从请求参数解析分页游标；缺省/空串 = 首页（零游标），
// 非法游标写 400 INVALID_CURSOR 并返回 ok=false，调用方必须立即 return。
// 收在筛选文件是因为游标与筛选同属「查询入参归一」，handler 只关心结果。
func parseRequestCursor(w http.ResponseWriter, cursor *string) (pageCursor, bool) {
	if cursor == nil || *cursor == "" {
		return pageCursor{}, true
	}
	c, err := decodeCursor(*cursor)
	if err != nil {
		writeErr(w, http.StatusBadRequest, codeInvalidCursor, "分页游标不合法")
		return pageCursor{}, false
	}
	return c, true
}

// normalizeDirectoryParam 归一目录过滤参数（文件管理页目录树「文件行」
// 数据源）：空串=库根（与 GET /dirs 的 DirTree 根节点 path="" 语义对齐）；
// 非空过 NormalizeRelPath（SECURITY 红线 1：一切来自请求的库内相对路径
// 统一入口，move 的 targetDir 同此先例），失败 400 INVALID_PARAM。
// nil=缺省不过滤；返回的非 nil 指针含空串=库根，供 newAssetFilters 落值。
func normalizeDirectoryParam(w http.ResponseWriter, directory *string) (*string, bool) {
	if directory == nil {
		return nil, true
	}
	dir := *directory
	if dir != "" {
		norm, err := filing.NormalizeRelPath(dir)
		if err != nil {
			writeErr(w, http.StatusBadRequest, codeInvalidParam, "目录路径不合法")
			return nil, false
		}
		dir = norm
	}
	return &dir, true
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
