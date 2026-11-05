package httpapi

import (
	"errors"
	"net/http"
	"qimeng-media/server/internal/creatorprofile"
)

// 协议 CreatorProfileWrite 全字段必填：指针区分缺失/null 与显式清空或关闭。
// 领域 Write 不携带 HTTP presence 语义，完成校验后只映射一次。
type creatorProfileWriteRequest struct {
	Revision     *int64                       `json:"revision"`
	Nickname     *string                      `json:"nickname"`
	Biography    *string                      `json:"biography"`
	AvatarURL    *string                      `json:"avatarUrl"`
	HomepageURL  *string                      `json:"homepageUrl"`
	EvidenceURLs *[]string                    `json:"evidenceUrls"`
	Status       *string                      `json:"status"`
	AutoRefresh  *bool                        `json:"autoRefresh"`
	FeedURL      *string                      `json:"feedUrl"`
	TrackUpdates *bool                        `json:"trackUpdates"`
	XProfile     *creatorXProfileWriteRequest `json:"xProfile,omitempty"`
}

type creatorXProfileWriteRequest struct {
	URL       *string `json:"url"`
	Biography *string `json:"biography"`
}

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
	var body creatorProfileWriteRequest
	if !decodeJSON(w, r, &body) {
		return
	}
	if body.Revision == nil || body.Nickname == nil || body.Biography == nil ||
		body.AvatarURL == nil || body.HomepageURL == nil || body.EvidenceURLs == nil ||
		body.Status == nil || body.AutoRefresh == nil || body.FeedURL == nil || body.TrackUpdates == nil {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "作者档案须显式提供全部字段，且不能为 null")
		return
	}
	var xProfile *creatorprofile.XProfile
	if body.XProfile != nil {
		if body.XProfile.URL == nil || body.XProfile.Biography == nil {
			writeErr(w, http.StatusBadRequest, codeInvalidParam, "X资料须同时提供账号链接与简介原文")
			return
		}
		xProfile = &creatorprofile.XProfile{URL: *body.XProfile.URL, Biography: *body.XProfile.Biography}
	}
	p, err := s.creatorProfiles.Save(r.Context(), authorId, creatorprofile.Write{
		Revision: *body.Revision, Nickname: *body.Nickname, Biography: *body.Biography,
		AvatarURL: *body.AvatarURL, HomepageURL: *body.HomepageURL, EvidenceURLs: *body.EvidenceURLs,
		Status: *body.Status, AutoRefresh: *body.AutoRefresh, FeedURL: *body.FeedURL, TrackUpdates: *body.TrackUpdates,
		XProfile: xProfile,
	})
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
	// 固定代理 URL 对应可变来源；不让浏览器缓存绕过客户端的档案修订失效。
	w.Header().Set("Cache-Control", "no-store")
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
	w.Header().Set("X-Content-Type-Options", "nosniff")
	if _, err = w.Write(data); err != nil {
		s.logger.Debug("写出作者头像失败", "err", err)
	}
}
