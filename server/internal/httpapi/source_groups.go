// source_groups.go：检索词表维护端点（ADR-0033）——用户/AI 可运维的
// 自定义出处组（canonical + variants + characters，含角色别名检索表）。
// PUT 整体替换 → kv 持久化 → 运行中引擎同步 → 后台全库存量重算（复用
// recomputeAfterCustomSources）。与 sources.go 的裸名名单（DOMAIN_RULES §4）
// 语义隔离：本文件维护的是带角色的出处组，canonical 与内置组同名 = 引擎侧
// 并入（扩变体/角色）。存储形态 = 匹配引擎输入形态（[]sourcematcher.SourceGroup
// JSON），scanner 构造期按存储值装载（loadCustomGroups）。
package httpapi

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"unicode/utf8"

	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/sourcematcher"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// 词表容量上限（openapi 侧 CustomSourceGroups/CustomSourceGroup/
// CustomSourceCharacter 的 maxItems/maxLength 双写同步——改一处必改两处；
// 上限防的是误操作灌库，不是业务容量规划）。
const (
	maxCustomGroups     = 256
	maxCustomVariants   = 64
	maxCustomCharacters = 128
	maxCustomAliases    = 32
	maxCustomWordRunes  = 100
)

// GetApiV1SourcesCustomGroups 读取生效中的用户自定义出处组（ADR-0033）。
// 无记录 = 空数组：内置 130 组检索表完整可用，非配置缺失。stopWords 恒
// 返回（用户层，空数组 = 用户层为空；内置冻结基线在引擎侧恒生效不在此层）。
func (s *Server) GetApiV1SourcesCustomGroups(w http.ResponseWriter, r *http.Request) {
	words := s.stopWordsFromSettings(r.Context())
	writeJSON(w, http.StatusOK, gen.CustomSourceGroups{Groups: s.customGroupsFromSettings(r.Context()), StopWords: &words})
}

// PutApiV1SourcesCustomGroups 整体替换用户自定义出处组（ADR-0033）。
//
// 语义（与匹配引擎 UpdateCustomGroups 对齐）：提交的 groups 就是生效名单，
// 服务端 trim + 去空 + 去重 + 重复 canonical 取首个后持久化（存储形态 =
// 生效形态，GET 回读同此语义）；空数组 = 清空。规范名自身恒参与匹配由
// 引擎合并层兜底（MergeGroups），存储形态保持提交原序。
//
// stopWords 字段（*[]string）只在非 nil 时处理：缺省/null = 停用词保持
// 现值（不读不写），显式空数组 = 清空追加层（内置冻结基线恒生效，见
// sourcematcher.builtinStopWords）；规范化后持久化 + 引擎同步，同 groups。
//
// 成功后两步（同 PutApiV1SourcesCustom）：① 运行中匹配引擎同步替换；
// ② 后台对全部常规库资产重算出处/角色——已入库资产 size+mtime 未变时
// 全量扫描只会跳过，词表变更必须显式重算传导（重算完成发 library.changed）。
func (s *Server) PutApiV1SourcesCustomGroups(w http.ResponseWriter, r *http.Request) {
	var body gen.CustomSourceGroups
	if !decodeJSON(w, r, &body) {
		return
	}
	groups, err := normalizeCustomGroups(body.Groups)
	if err != nil {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, err.Error())
		return
	}
	raw, err := json.Marshal(groups)
	if err != nil {
		s.internalErr(w, "序列化自定义出处组", err)
		return
	}
	if err := s.q.UpsertSetting(r.Context(), db.UpsertSettingParams{
		Key:       authoring.SettingKeyCustomSourceGroups,
		Value:     string(raw),
		UpdatedAt: store.FormatTimestamp(s.now()),
	}); err != nil {
		s.internalErr(w, "保存自定义出处组", err)
		return
	}
	if err := s.scanner.UpdateCustomGroups(r.Context(), groups); err != nil {
		// 扫描器未装配（noScanner 占位）：持久化已成功但匹配引擎没换——
		// 显式 503 告知"已保存、未生效"（装配后重启按存储值装载，见
		// Scanner.New 的 loadCustomGroups），同 PutApiV1SourcesCustom 语义。
		writeErr(w, http.StatusServiceUnavailable, codeScannerUnavailable, "自定义出处组已保存但扫描器未装配，暂未生效")
		return
	}
	// stopWords 为 *[]string：非 nil 才处理（缺省/null = 保持现值，不读不写；
	// 显式空数组 = 清空回内置基线）。规范化（复用 normalizeWords，自带 trim/
	// 去重/超长/乱码 400）后持久化 + 引擎同步，语义对齐 groups 路径。
	if body.StopWords != nil {
		words, err := normalizeWords(*body.StopWords)
		if err != nil {
			writeErr(w, http.StatusBadRequest, codeInvalidParam, err.Error())
			return
		}
		raw, err := json.Marshal(words)
		if err != nil {
			s.internalErr(w, "序列化停用词", err)
			return
		}
		if err := s.q.UpsertSetting(r.Context(), db.UpsertSettingParams{
			Key:       authoring.SettingKeyCustomStopWords,
			Value:     string(raw),
			UpdatedAt: store.FormatTimestamp(s.now()),
		}); err != nil {
			s.internalErr(w, "保存停用词", err)
			return
		}
		if err := s.scanner.UpdateStopWords(r.Context(), words); err != nil {
			// 扫描器未装配：停用词已持久化但引擎没换，同 groups 的 503 句式
			//（装配后重启按存储值装载，见 Scanner.New 的 loadStopWords）。
			writeErr(w, http.StatusServiceUnavailable, codeScannerUnavailable, "停用词已保存但扫描器未装配，暂未生效")
			return
		}
	}
	s.recomputeAfterCustomSources()
	w.WriteHeader(http.StatusNoContent)
}

// normalizeCustomGroups 把协议载荷规范化为引擎输入形态（[]sourcematcher.
// SourceGroup）：全串 trim、剔空、变体/别名/角色去重（先见序）、重复
// canonical 取首个、canonical 并入自身变体与别名（存储形态 = 生效形态，
// 引擎侧 MergeGroups 另有同义兜底）。超容量上限或单串超长返回错误
// （400 INVALID_PARAM，上限与 openapi maxItems/maxLength 双写同步）。
func normalizeCustomGroups(in []gen.CustomSourceGroup) ([]sourcematcher.SourceGroup, error) {
	if len(in) > maxCustomGroups {
		return nil, fmt.Errorf("出处组数量超上限 %d", maxCustomGroups)
	}
	out := make([]sourcematcher.SourceGroup, 0, len(in))
	seen := make(map[string]bool, len(in))
	for _, g := range in {
		canonical := strings.TrimSpace(g.Canonical)
		if canonical == "" {
			continue
		}
		if err := checkWordLen(canonical, "出处组规范名"); err != nil {
			return nil, err
		}
		if err := checkNotMojibake(canonical, "出处组规范名"); err != nil {
			return nil, err
		}
		if seen[canonical] {
			continue
		}
		seen[canonical] = true
		sg := sourcematcher.SourceGroup{Canonical: canonical}
		if g.Variants != nil {
			if len(*g.Variants) > maxCustomVariants {
				return nil, fmt.Errorf("出处组 %q 变体数量超上限 %d", canonical, maxCustomVariants)
			}
			variants, err := normalizeWords(*g.Variants)
			if err != nil {
				return nil, err
			}
			sg.Variants = variants
		}
		sg.Variants = appendWordIfMissing(sg.Variants, canonical)
		if g.Characters != nil {
			if len(*g.Characters) > maxCustomCharacters {
				return nil, fmt.Errorf("出处组 %q 角色数量超上限 %d", canonical, maxCustomCharacters)
			}
			chars := make([]sourcematcher.CharEntry, 0, len(*g.Characters))
			seenChar := make(map[string]bool, len(*g.Characters))
			for _, c := range *g.Characters {
				cc := strings.TrimSpace(c.Canonical)
				if cc == "" || seenChar[cc] {
					continue
				}
				if err := checkWordLen(cc, "角色规范名"); err != nil {
					return nil, err
				}
				if err := checkNotMojibake(cc, "角色规范名"); err != nil {
					return nil, err
				}
				seenChar[cc] = true
				ce := sourcematcher.CharEntry{Canonical: cc}
				if c.Aliases != nil {
					if len(*c.Aliases) > maxCustomAliases {
						return nil, fmt.Errorf("角色 %q 别名数量超上限 %d", cc, maxCustomAliases)
					}
					aliases, err := normalizeWords(*c.Aliases)
					if err != nil {
						return nil, err
					}
					ce.Aliases = aliases
				}
				ce.Aliases = appendWordIfMissing(ce.Aliases, cc)
				chars = append(chars, ce)
			}
			sg.Characters = chars
		}
		out = append(out, sg)
	}
	return out, nil
}

// appendWordIfMissing canonical 自并入（存储形态自含规范名，引擎侧另有同义
// 兜底）；sg.Variants/ce.Aliases 均为本函数新建切片，原地追加安全。
func appendWordIfMissing(list []string, w string) []string {
	for _, s := range list {
		if s == w {
			return list
		}
	}
	return append(list, w)
}

// normalizeWords 逐串 trim + 剔空 + 精确去重（先见序）；单串超长或含乱码
// 替换符报错。
func normalizeWords(in []string) ([]string, error) {
	out := make([]string, 0, len(in))
	seen := make(map[string]bool, len(in))
	for _, w := range in {
		w = strings.TrimSpace(w)
		if w == "" {
			continue
		}
		if err := checkWordLen(w, "词条"); err != nil {
			return nil, err
		}
		if err := checkNotMojibake(w, "词条"); err != nil {
			return nil, err
		}
		if seen[w] {
			continue
		}
		seen[w] = true
		out = append(out, w)
	}
	return out, nil
}

// checkWordLen 单串 rune 上限校验（错误文案带类别便于定位是哪个字段超长）。
func checkWordLen(w, kind string) error {
	if utf8.RuneCountInString(w) > maxCustomWordRunes {
		return fmt.Errorf("%s %q 超 %d 字符上限", kind, w, maxCustomWordRunes)
	}
	return nil
}

// checkNotMojibake 拒绝含替换符（U+FFFD）的词条（2026-10-03 事故加固：
// 客户端用非 UTF-8 编码发中文词表时，JSON 解码器把坏字节静默替换成 U+FFFD，
// 乱码 canonical 被当作新组存储——词表看似保存成功实则全部失配，且覆盖
// 掉此前的正确词条。显式 400 让坏载荷在入口报错，而不是静默写死词条）。
func checkNotMojibake(w, kind string) error {
	if strings.ContainsRune(w, '\uFFFD') {
		return fmt.Errorf("%s %q 含无效字符（疑似非 UTF-8 乱码），请以 UTF-8 重新提交", kind, w)
	}
	return nil
}

// customGroupsFromSettings 读 kv_settings 中的自定义出处组（引擎形态 JSON），
// 转回协议形态返回。无记录/损坏降级空数组——与 scanner.loadCustomGroups
// 同一容忍策略：两侧同态，改一次词表即自愈。
func (s *Server) customGroupsFromSettings(ctx context.Context) []gen.CustomSourceGroup {
	v, err := s.q.GetSetting(ctx, authoring.SettingKeyCustomSourceGroups)
	if err != nil {
		return []gen.CustomSourceGroup{}
	}
	var groups []sourcematcher.SourceGroup
	if err := json.Unmarshal([]byte(v), &groups); err != nil {
		return []gen.CustomSourceGroup{}
	}
	out := make([]gen.CustomSourceGroup, 0, len(groups))
	for _, g := range groups {
		gg := gen.CustomSourceGroup{Canonical: g.Canonical}
		if len(g.Variants) > 0 {
			variants := append([]string(nil), g.Variants...)
			gg.Variants = &variants
		}
		if len(g.Characters) > 0 {
			chars := make([]gen.CustomSourceCharacter, 0, len(g.Characters))
			for _, c := range g.Characters {
				cc := gen.CustomSourceCharacter{Canonical: c.Canonical}
				if len(c.Aliases) > 0 {
					aliases := append([]string(nil), c.Aliases...)
					cc.Aliases = &aliases
				}
				chars = append(chars, cc)
			}
			gg.Characters = &chars
		}
		out = append(out, gg)
	}
	return out
}

// stopWordsFromSettings 读 kv_settings 中的停用词追加层（JSON 字符串数组），
// 无记录/损坏降级空数组——与 customGroupsFromSettings 同一容忍策略：两侧
// 同态，改一次词表即自愈。返回的是用户层（内置冻结基线在引擎侧恒生效）。
func (s *Server) stopWordsFromSettings(ctx context.Context) []string {
	v, err := s.q.GetSetting(ctx, authoring.SettingKeyCustomStopWords)
	if err != nil {
		return []string{}
	}
	var words []string
	if err := json.Unmarshal([]byte(v), &words); err != nil {
		return []string{}
	}
	if words == nil {
		return []string{}
	}
	return words
}
