// localsync.go 仅适配状态与触发协议；运行态/文件/TXT 业务在 syncchannel。
package httpapi

import (
	"errors"
	"net/http"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/syncchannel"
)

// lsItemToGen 装配协议 LocalSyncItem（可空字段按「有意义才填」口径置 nil，
// 避免 omitempty 指针序列化出空串/零值噪音）。
func lsItemToGen(it syncchannel.Item) gen.LocalSyncItem {
	kind := gen.LocalSyncItemKind(it.Kind)
	state := gen.LocalSyncItemState(it.State)
	out := gen.LocalSyncItem{
		Path:  ptr(it.RelPath),
		Kind:  &kind,
		State: &state,
	}
	if it.LibraryName != "" {
		out.LibraryName = ptr(it.LibraryName)
	}
	if it.Error != "" {
		out.Error = ptr(it.Error)
	}
	if it.Attempts > 0 {
		out.Attempts = ptr(it.Attempts)
	}
	if it.Size > 0 {
		out.SizeBytes = ptr(it.Size)
	}
	if !it.LastAttemptAt.IsZero() {
		out.LastAttemptAt = ptr(it.LastAttemptAt)
	}
	return out
}

// lsSnapshot 仅将业务快照映射为既有协议，可空字段保持原语义。
func (s *Server) lsSnapshot() gen.LocalSyncStatus {
	snapshot := s.sync.Snapshot()
	items := make([]gen.LocalSyncItem, 0, len(snapshot.Items))
	for _, item := range snapshot.Items {
		items = append(items, lsItemToGen(item))
	}
	recent := make([]gen.LocalSyncItem, 0, len(snapshot.Recent))
	for _, item := range snapshot.Recent {
		recent = append(recent, lsItemToGen(item))
	}
	st := gen.LocalSyncStatus{
		Enabled: ptr(snapshot.Enabled), Root: ptr(snapshot.Root), IntervalSeconds: ptr(snapshot.IntervalSeconds),
		Paused: ptr(snapshot.Paused), SyncedTotal: ptr(snapshot.SyncedTotal), LastError: ptr(snapshot.LastError),
		Items: &items, RecentSynced: &recent,
		Counts: &struct {
			Failed        *int `json:"failed,omitempty"`
			Ignored       *int `json:"ignored,omitempty"`
			WaitingStable *int `json:"waitingStable,omitempty"`
		}{Failed: ptr(snapshot.Failed), Ignored: ptr(snapshot.Ignored), WaitingStable: ptr(snapshot.Waiting)},
	}
	if !snapshot.LastCycleAt.IsZero() {
		st.LastCycleAt = ptr(snapshot.LastCycleAt)
	}
	return st
}

func (s *Server) GetApiV1LocalSyncStatus(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, s.lsSnapshot())
}

// codeLocalSyncDisabled 与 OpenAPI POST /local-sync/trigger 409 双向同步。
const codeLocalSyncDisabled = "LOCAL_SYNC_DISABLED"

func (s *Server) PostApiV1LocalSyncTrigger(w http.ResponseWriter, r *http.Request) {
	err := s.sync.Trigger()
	switch {
	case errors.Is(err, syncchannel.ErrDisabled):
		writeErr(w, http.StatusConflict, codeLocalSyncDisabled, "本机同步通道未启用：请配置 QIMENG_LOCAL_SYNC_ROOT（或 yaml local_sync.root）后重启服务")
	case err != nil:
		writeErr(w, http.StatusConflict, codeLocalSyncDisabled, "本机同步通道不可用（安全校验未通过或同步根不可达），详情见服务端日志")
	default:
		w.WriteHeader(http.StatusAccepted)
	}
}
