// clientlogs.go：客户端异常上报通道（维护页「客户端异常」排查表数据源）。
//
// 存储：kv_settings 表，键 authoring.SettingKeyClientLogs，值为
// gen.ClientLogEntry 数组的 JSON（与 openapi ClientLogEntry 结构一致），
// 存储顺序旧→新；环形缓冲容量 200 条，超出丢最旧（GET 返回新→旧）。
//
// 取舍（诚实口径）：kv_settings 单键读改写非原子，多客户端并发 POST 极端
// 情况下可能丢个别条目——上报通道是低频排障辅助，丢条目可接受，不值得为它
// 引入事务键拆分（一条一行的 kv schema 改造）；协议与实现都按"尽力保留最新
// 200 条"理解。
//
// 校验口径（协议写明）：批量条数 1~50、level 三枚举、message 超 2000 字
// 一律 400（不截断——截断会静默破坏排障现场）。
package httpapi

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"unicode/utf8"

	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// clientLogsCapacity 环形缓冲容量：只保留最新 200 条（超出丢最旧）。
// 200 对排障表够翻近期的错，又把 kv 值体积压在几 KB 量级（单条 ≤2KB message
// 上限 × 200 ≈ 最坏 400KB，kv 单值可承受）。
const clientLogsCapacity = 200

// 客户端上报的校验边界（openapi ClientLogBatch/ClientLogEntry 约束锚点）。
const (
	clientLogsBatchMin, clientLogsBatchMax = 1, 50
	clientLogMessageMaxRunes               = 2000
)

// loadClientLogsAsc 读环形缓冲（存储序=旧→新）；无记录 → nil。
// kv 值损坏（手工改库/半截写入）与 config 侧同口径：解析失败按空表处理
// 返回 nil（GET 显示空、POST 从空表追加后覆盖写回自然自愈），不 500。
func (s *Server) loadClientLogsAsc(ctx context.Context) ([]gen.ClientLogEntry, error) {
	v, err := s.q.GetSetting(ctx, authoring.SettingKeyClientLogs)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var items []gen.ClientLogEntry
	if err := json.Unmarshal([]byte(v), &items); err != nil {
		return nil, nil
	}
	return items, nil
}

// PostApiV1ClientLogs 批量上报客户端异常：校验 → 追加环形缓冲（超出丢最旧）
// → 204。读改写非原子（见包注释取舍说明）。
func (s *Server) PostApiV1ClientLogs(w http.ResponseWriter, r *http.Request) {
	var body gen.ClientLogBatch
	if !decodeJSON(w, r, &body) {
		return
	}
	if len(body.Events) < clientLogsBatchMin || len(body.Events) > clientLogsBatchMax {
		writeErr(w, http.StatusBadRequest, "INVALID_PARAM", "events 条数须在 1–50")
		return
	}
	for _, e := range body.Events {
		switch {
		case !e.Level.Valid():
			writeErr(w, http.StatusBadRequest, "INVALID_PARAM", "level 须为 error/warn/info")
			return
		case utf8.RuneCountInString(e.Message) > clientLogMessageMaxRunes:
			writeErr(w, http.StatusBadRequest, "INVALID_PARAM", "message 超 2000 字（不截断，整批拒绝）")
			return
		}
	}
	existing, err := s.loadClientLogsAsc(r.Context())
	if err != nil {
		s.internalErr(w, "读取客户端异常缓冲", err)
		return
	}
	existing = append(existing, body.Events...)
	if overflow := len(existing) - clientLogsCapacity; overflow > 0 {
		existing = existing[overflow:] // 丢最旧
	}
	raw, err := json.Marshal(existing)
	if err != nil {
		s.internalErr(w, "序列化客户端异常缓冲", err)
		return
	}
	if err := s.q.UpsertSetting(r.Context(), db.UpsertSettingParams{
		Key:       authoring.SettingKeyClientLogs,
		Value:     string(raw),
		UpdatedAt: store.FormatTimestamp(s.now()),
	}); err != nil {
		s.internalErr(w, "保存客户端异常上报", err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// GetApiV1ClientLogs 异常列表（新→旧）：存储序反转输出。
// Items 恒非 nil（空表输出 [] 而非 null）——生成类型的 nil 切片会被
// 序列化成 JSON null，客户端对 null 数组不设防会直接崩（P1 修复点）。
func (s *Server) GetApiV1ClientLogs(w http.ResponseWriter, r *http.Request) {
	items, err := s.loadClientLogsAsc(r.Context())
	if err != nil {
		s.internalErr(w, "读取客户端异常缓冲", err)
		return
	}
	// 新→旧：原地反转存储序（环形缓冲尾部=最新）。
	for i, j := 0, len(items)-1; i < j; i, j = i+1, j-1 {
		items[i], items[j] = items[j], items[i]
	}
	if items == nil {
		items = []gen.ClientLogEntry{}
	}
	writeJSON(w, http.StatusOK, gen.ClientLogPage{Items: items})
}
