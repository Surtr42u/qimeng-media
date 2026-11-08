// aiclient_admin.go：AI 客户端凭据管理与审计查询的 HTTP 面（ADR-0057 P2）。
//
// 这些端点只接受管理员会话：AI 客户端 Bearer 在接入层被 requiredAIScope
// 直接判为未映射（403 FORBIDDEN_SCOPE），即使持有 admin scope 也不能管理
// 凭据或读取审计——避免"AI 给自己发 token"的提权路径。明文 token 只在
// 创建/轮换响应出现一次，绝不写入日志/审计/数据库。
package httpapi

import (
	"errors"
	"net/http"
	"strings"
	"time"

	"qimeng-media/server/internal/aiauth"
	"qimeng-media/server/internal/httpapi/gen"
)

// requireAdminSession 是管理面的第二道闸（第一道是 scope 映射）：AI
// principal 在 ctx 中出现即拒绝，管理员会话继续。
func (s *Server) requireAdminSession(w http.ResponseWriter, r *http.Request) bool {
	if _, ok := aiauth.PrincipalFromContext(r.Context()); ok {
		writeErr(w, http.StatusForbidden, codeForbiddenScope, "该操作仅限管理员会话")
		return false
	}
	return true
}

// GetApiV1AiClients 列出 AI 客户端（不含 token 与哈希）。
func (s *Server) GetApiV1AiClients(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdminSession(w, r) {
		return
	}
	clients, err := s.aiAuth.ListClients(r.Context())
	if err != nil {
		s.writeAIAdminError(w, "列出 AI 客户端", err)
		return
	}
	items := make([]gen.AiClient, 0, len(clients))
	for _, client := range clients {
		items = append(items, aiClientToGen(client))
	}
	writeJSON(w, http.StatusOK, gen.AiClientList{Items: items})
}

// PostApiV1AiClients 创建客户端；明文 token 只在本次响应出现一次。
func (s *Server) PostApiV1AiClients(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdminSession(w, r) {
		return
	}
	var body gen.PostApiV1AiClientsJSONRequestBody
	if !decodeJSON(w, r, &body) {
		return
	}
	rawScopes := make([]string, 0, len(body.Scopes))
	for _, scope := range body.Scopes {
		rawScopes = append(rawScopes, string(scope))
	}
	scopes, err := aiauth.ParseScopes(rawScopes)
	if err != nil {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "scopes 含未知值或为空")
		return
	}
	var deviceIDs []string
	if body.DeviceIds != nil {
		deviceIDs = *body.DeviceIds
	}
	var expiresAt *time.Time
	if body.ExpiresInSeconds != nil {
		if *body.ExpiresInSeconds <= 0 {
			writeErr(w, http.StatusBadRequest, codeInvalidParam, "expiresInSeconds 须为正整数")
			return
		}
		value := s.now().UTC().Add(time.Duration(*body.ExpiresInSeconds) * time.Second)
		expiresAt = &value
	}
	client, token, err := s.aiAuth.CreateClient(r.Context(), aiauth.CreateClientInput{
		Label:     body.Label,
		Scopes:    scopes,
		DeviceIDs: deviceIDs,
		ExpiresAt: expiresAt,
	})
	if err != nil {
		s.writeAIAdminError(w, "创建 AI 客户端", err)
		return
	}
	writeJSON(w, http.StatusCreated, gen.AiClientSecret{
		Client: aiClientToGen(client),
		Token:  token,
	})
}

// DeleteApiV1AiClientsClientId 撤销客户端（幂等）。
func (s *Server) DeleteApiV1AiClientsClientId(w http.ResponseWriter, r *http.Request, clientId gen.AiClientId) {
	if !s.requireAdminSession(w, r) {
		return
	}
	id := strings.TrimSpace(clientId)
	if err := s.aiAuth.RevokeClient(r.Context(), id); err != nil {
		s.writeAIAdminError(w, "撤销 AI 客户端", err)
		return
	}
	client, err := s.aiAuth.GetClient(r.Context(), id)
	if err != nil {
		s.writeAIAdminError(w, "读取已撤销的 AI 客户端", err)
		return
	}
	writeJSON(w, http.StatusOK, aiClientToGen(client))
}

// PostApiV1AiClientsClientIdRotate 轮换 token；旧 token 立即失效。
func (s *Server) PostApiV1AiClientsClientIdRotate(w http.ResponseWriter, r *http.Request, clientId gen.AiClientId) {
	if !s.requireAdminSession(w, r) {
		return
	}
	client, token, err := s.aiAuth.RotateClient(r.Context(), strings.TrimSpace(clientId))
	if err != nil {
		s.writeAIAdminError(w, "轮换 AI 客户端 token", err)
		return
	}
	writeJSON(w, http.StatusOK, gen.AiClientSecret{
		Client: aiClientToGen(client),
		Token:  token,
	})
}

// GetApiV1AiAudit 审计查询（新建倒序 keyset 分页）。
func (s *Server) GetApiV1AiAudit(w http.ResponseWriter, r *http.Request, params gen.GetApiV1AiAuditParams) {
	if !s.requireAdminSession(w, r) {
		return
	}
	filter := aiauth.AuditFilter{Limit: aiauth.AuditDefaultLimit}
	if params.ClientId != nil {
		filter.ClientID = *params.ClientId
	}
	if params.Action != nil {
		filter.ActionPrefix = *params.Action
	}
	if params.Limit != nil {
		filter.Limit = *params.Limit
	}
	if filter.Limit < 1 || filter.Limit > aiauth.AuditMaxLimit {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "limit 须在 1–200 之间")
		return
	}
	if params.Cursor != nil {
		filter.Cursor = *params.Cursor
	}
	page, err := s.aiAuth.ListAudit(r.Context(), filter)
	if err != nil {
		s.writeAIAdminError(w, "查询 AI 审计", err)
		return
	}
	items := make([]gen.AiAuditEntry, 0, len(page.Items))
	for _, record := range page.Items {
		entry := gen.AiAuditEntry{
			Id:         record.ID,
			ClientId:   record.ClientID,
			Method:     record.Method,
			Path:       record.Path,
			Action:     record.Action,
			StatusCode: record.StatusCode,
			CreatedAt:  record.CreatedAt,
		}
		if record.RequestID != "" {
			value := record.RequestID
			entry.RequestId = &value
		}
		items = append(items, entry)
	}
	var next *string
	if page.NextCursor != "" {
		value := page.NextCursor
		next = &value
	}
	writeJSON(w, http.StatusOK, gen.AiAuditList{Items: items, NextCursor: next})
}

// aiClientToGen 装配公开视图；nil 时间保持省略（协议允许 null/缺省）。
func aiClientToGen(client aiauth.Client) gen.AiClient {
	scopes := make([]gen.AiClientScope, 0, len(client.Scopes))
	for _, scope := range client.Scopes {
		scopes = append(scopes, gen.AiClientScope(scope))
	}
	deviceIDs := client.DeviceIDs
	if deviceIDs == nil {
		deviceIDs = []string{}
	}
	return gen.AiClient{
		Id:         client.ID,
		Label:      client.Label,
		Scopes:     scopes,
		DeviceIds:  deviceIDs,
		CreatedAt:  client.CreatedAt,
		ExpiresAt:  client.ExpiresAt,
		RevokedAt:  client.RevokedAt,
		LastUsedAt: client.LastUsedAt,
	}
}

// writeAIAdminError 把凭据管理哨兵映射为协议错误码。
func (s *Server) writeAIAdminError(w http.ResponseWriter, what string, err error) {
	switch {
	case errors.Is(err, aiauth.ErrClientNotFound):
		writeErr(w, http.StatusNotFound, codeNotFound, "AI 客户端不存在")
	case errors.Is(err, aiauth.ErrClientRevoked):
		writeErr(w, http.StatusConflict, codeStateConflict, "AI 客户端已撤销，不能轮换")
	case errors.Is(err, aiauth.ErrInvalidCursor):
		writeErr(w, http.StatusBadRequest, codeInvalidCursor, "分页游标不合法")
	case errors.Is(err, aiauth.ErrInvalidInput),
		errors.Is(err, aiauth.ErrUnknownScope),
		errors.Is(err, aiauth.ErrNoScopes):
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "AI 客户端参数不合法")
	default:
		s.internalErr(w, what, err)
	}
}
