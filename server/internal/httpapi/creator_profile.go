package httpapi

import (
	"errors"
	"net/http"
	"qimeng-media/server/internal/creatorprofile"
)

func (s *Server) creatorProfileErr(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, creatorprofile.ErrNotFound):
		writeErr(w, http.StatusNotFound, codeNotFound, "作者或头像不存在")
	case errors.Is(err, creatorprofile.ErrConflict):
		writeErr(w, http.StatusConflict, codeConflict, err.Error())
	case errors.Is(err, creatorprofile.ErrInvalid):
		writeErr(w, http.StatusBadRequest, codeInvalidParam, err.Error())
	default:
		s.internalErr(w, "作者档案", err)
	}
}
func (s *Server) GetApiV1AuthorsAuthorIdProfile(w http.ResponseWriter, r *http.Request, authorId string) {
	p, err := s.creatorProfiles.Get(r.Context(), authorId)
	if err != nil {
		s.creatorProfileErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, p)
}
func (s *Server) PutApiV1AuthorsAuthorIdProfile(w http.ResponseWriter, r *http.Request, authorId string) {
	var body creatorprofile.Write
	if !decodeJSON(w, r, &body) {
		return
	}
	p, err := s.creatorProfiles.Save(r.Context(), authorId, body)
	if err != nil {
		s.creatorProfileErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, p)
}
func (s *Server) PostApiV1AuthorsAuthorIdProfileRefresh(w http.ResponseWriter, r *http.Request, authorId string) {
	p, err := s.creatorProfiles.Refresh(r.Context(), authorId)
	if err != nil {
		s.creatorProfileErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, p)
}
func (s *Server) GetApiV1AuthorsAuthorIdProfileAvatar(w http.ResponseWriter, r *http.Request, authorId string) {
	data, kind, err := s.creatorProfiles.Avatar(r.Context(), authorId)
	if err != nil {
		if errors.Is(err, creatorprofile.ErrNotFound) {
			s.creatorProfileErr(w, err)
		} else {
			writeErr(w, http.StatusBadGateway, codeInvalidParam, "头像来源暂不可用")
		}
		return
	}
	w.Header().Set("Content-Type", kind)
	w.Header().Set("Cache-Control", "private, max-age=3600")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	if _, err = w.Write(data); err != nil {
		s.logger.Debug("写出作者头像失败", "err", err)
	}
}
