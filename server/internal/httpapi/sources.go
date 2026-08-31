// sources.go：出处端点（相册按出处分组用 + 用户自定义出处管理）。
// 语义唯一权威：docs/DOMAIN_RULES §3（筛选体系）/§4（自定义出处）/
// §6（COS 隔离）——出处计数口径与资产列表一致（默认排除 COS 作者关联文件），
// name=null 表示无出处文件（显示层兜底"其他"）。
// 自定义出处（§4「用户手动添加的分区名自动加入识别」）持久化键与扫描器
// 构造期装载共用 authoring.SettingKeyCustomSources，改装后运行期经
// Scanner.UpdateCustomSources 同步——存储形态 = 匹配引擎输入形态。
package httpapi

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"sort"
	"strings"

	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// GetApiV1Sources 按出处规范名分组的文件计数，fileCount 降序。
// includeCos 默认 false（与 GET /assets 的 COS 隔离口径一致——
// COS 作者关联文件有独立入口，不进常规浏览/相册流）。
func (s *Server) GetApiV1Sources(w http.ResponseWriter, r *http.Request, params gen.GetApiV1SourcesParams) {
	includeCos := int64(0)
	if params.IncludeCos != nil && *params.IncludeCos {
		includeCos = 1
	}
	// ListSources 只有一个参数，sqlc 以裸参数生成（非 Params 结构体）。
	rows, err := s.q.ListSources(r.Context(), includeCos)
	if err != nil {
		s.internalErr(w, "查询出处分组", err)
		return
	}
	items := make([]gen.SourceCount, 0, len(rows))
	for _, row := range rows {
		fc := int(row.FileCount)
		items = append(items, gen.SourceCount{Name: nullableName(row.Name), FileCount: &fc})
	}
	writeJSON(w, http.StatusOK, items)
}

// nullableName 把 SQL 的 NULL 来源转成响应 null：无出处文件归入"其他"桶，
// 桶名留给显示层兜底（协议语义见 openapi.yaml 的 SourceCount.name 描述）。
func nullableName(ns sql.NullString) *string {
	if ns.Valid {
		v := ns.String
		return &v
	}
	return nil
}

// GetApiV1SourcesCustom 读取生效中的用户自定义出处名单（DOMAIN_RULES §4）。
// 无记录 = 空数组：匹配引擎侧"无自定义出处"即等价（内置检索表完整可用），
// 不是配置缺失的错误信号。
func (s *Server) GetApiV1SourcesCustom(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, gen.CustomSources{Names: s.customSourcesFromSettings(r.Context())})
}

// PutApiV1SourcesCustom 整体替换用户自定义出处名单。
//
// 语义（与匹配引擎 UpdateCustomSources 对齐）：提交的 names 就是生效名单，
// 服务端统一 trim + 去空 + 去重 + 名称升序后持久化（存储形态 = 生效形态，
// GET 回读同此语义）；空数组 = 清空全部用户自定义出处。
//
// 成功后两步：① 运行中匹配引擎同步替换（后续匹配立即生效）；
// ② 后台对全部库顺次重算存量富化——库内资产 size+mtime 未变时全量扫描
// 只会跳过（不会重 ingest），source 列不会自然更新，必须显式重算；
// 重算完成发 library.changed 事件，UI 收到后刷新即可。
func (s *Server) PutApiV1SourcesCustom(w http.ResponseWriter, r *http.Request) {
	var body gen.CustomSources
	if !decodeJSON(w, r, &body) {
		return
	}
	names := normalizeCustomSources(body.Names)
	raw, err := json.Marshal(names)
	if err != nil {
		s.internalErr(w, "序列化自定义出处", err)
		return
	}
	if err := s.q.UpsertSetting(r.Context(), db.UpsertSettingParams{
		Key:       authoring.SettingKeyCustomSources,
		Value:     string(raw),
		UpdatedAt: store.FormatTimestamp(s.now()),
	}); err != nil {
		s.internalErr(w, "保存自定义出处", err)
		return
	}
	if err := s.scanner.UpdateCustomSources(r.Context(), names); err != nil {
		// 扫描器未装配（noScanner 占位）：持久化已成功但匹配引擎没换——
		// 显式 503 告知"已保存、未生效"（装配后重启按存储值装载，
		// 见 Scanner.New 的 loadCustomSources），好过 204 后静默不生效。
		writeErr(w, http.StatusServiceUnavailable, "SCANNER_UNAVAILABLE", "自定义出处已保存但扫描器未装配，暂未生效")
		return
	}
	s.recomputeAfterCustomSources()
	w.WriteHeader(http.StatusNoContent)
}

// normalizeCustomSources 规范化自定义出处名单。比 matcher 内的去重多做
// 一步 trim：手输 " 火影 " 这类夹空格的形态不该成为独立出处名。
// 结果是持久化形态也是 matcher 输入形态，两处同一份切片，无二次加工。
func normalizeCustomSources(names []string) []string {
	seen := make(map[string]bool, len(names))
	out := make([]string, 0, len(names))
	for _, n := range names {
		n = strings.TrimSpace(n)
		if n == "" || seen[n] {
			continue
		}
		seen[n] = true
		out = append(out, n)
	}
	sort.Strings(out)
	return out
}

// customSourcesFromSettings 读 kv_settings 中的自定义出处（JSON 字符串数组）。
// 无记录/损坏降级空数组——与 scanner.loadCustomSources 同一容忍策略：
// 损坏时两侧都按空集处理，避免 GET 与扫描器状态不一致（改一次名单即自愈）。
func (s *Server) customSourcesFromSettings(ctx context.Context) []string {
	v, err := s.q.GetSetting(ctx, authoring.SettingKeyCustomSources)
	if err != nil {
		return []string{}
	}
	var names []string
	if err := json.Unmarshal([]byte(v), &names); err != nil {
		return []string{}
	}
	return names
}

// recomputeAfterCustomSources 后台重算全部库的存量富化。顺序执行（库少、
// 单库内部并发由 scanner 控制）；失败仅日志——重算是幂等覆盖写，重发
// PUT 即重试，不阻塞请求。后台 ctx：请求返回后 r.Context() 即取消，
// 重算必须比请求活得久（同 scannerAdapter.Scan 的取舍）。
func (s *Server) recomputeAfterCustomSources() {
	go func() {
		libs, err := s.q.ListLibraries(context.Background())
		if err != nil {
			s.logger.Error("自定义出处重算：读取库列表失败", "err", err)
			return
		}
		for _, l := range libs {
			if err := s.scanner.RecomputeEnrichment(context.Background(), l.ID); err != nil {
				if !errors.Is(err, ErrScannerUnavailable) {
					s.logger.Warn("自定义出处重算库失败", "libraryId", l.ID, "err", err)
				}
			}
		}
	}()
}
