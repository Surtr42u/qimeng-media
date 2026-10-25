package httpapi

import "net/http"

// GetApiV1ExportQimengBackup 导出同一事务快照；附件协议留在适配层。
func (s *Server) GetApiV1ExportQimengBackup(w http.ResponseWriter, r *http.Request) {
	file, err := s.portable.Export(r.Context())
	if err != nil {
		s.internalErr(w, "导出v2便携备份", err)
		return
	}
	w.Header().Set("Content-Disposition", `attachment; filename="qimeng_backup.json"`)
	writeJSON(w, http.StatusOK, file)
}
