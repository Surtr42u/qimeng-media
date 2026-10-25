package httpapi

import (
	"errors"
	"net/http"
	"qimeng-media/server/internal/events"
	"qimeng-media/server/internal/portablebackup"
)

// PostApiV1ImportQimengBackup 保留64MiB独立请求上限，业务由 portablebackup 编排。
func (s *Server) PostApiV1ImportQimengBackup(w http.ResponseWriter, r *http.Request) {
	var req portablebackup.BackupImport
	if !decodeJSONWithLimit(w, r, &req, portablebackup.ImportMaxBody) {
		return
	}
	result, err := s.portable.Import(r.Context(), &req)
	if err != nil {
		if errors.Is(err, portablebackup.ErrInvalidBackup) {
			writeErr(w, http.StatusBadRequest, codeBadRequest, err.Error())
			return
		}
		var stage *portablebackup.StageError
		if errors.As(err, &stage) {
			s.internalErr(w, stage.Stage, stage.Err)
		} else {
			s.internalErr(w, "导入旧版格式备份", err)
		}
		return
	}
	writeJSON(w, http.StatusOK, result)
}

func (s *Server) notifyPortableImport() {
	s.invalidateRecommendCache()
	s.bumpLibraryRevision()
	if err := s.bus.Publish(events.Event{Topic: events.TopicLibraryChanged}); err != nil {
		s.logger.Warn("导入成功后的库变更通知失败", "err", err)
	}
}
