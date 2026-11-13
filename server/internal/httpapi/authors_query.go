package httpapi

import (
	"encoding/base64"
	"encoding/json"
	"net/http"
	"strings"
	"unicode/utf8"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store/db"
)

const (
	authorQueryDefaultLimit = 20
	authorQueryMaxLimit     = 100
	authorQueryMaxQLength   = 200
	authorQueryMaxCursorLen = 4096
)

type authorQueryCursor struct {
	K string `json:"k"`
	I string `json:"i"`
	Q string `json:"q"`
}

func encodeAuthorQueryCursor(displayName, id, query string) string {
	data, _ := json.Marshal(authorQueryCursor{K: displayName, I: id, Q: query})
	return base64.RawURLEncoding.EncodeToString(data)
}

func decodeAuthorQueryCursor(value string) (authorQueryCursor, bool) {
	var cursor authorQueryCursor
	if len(value) > authorQueryMaxCursorLen {
		return cursor, false
	}
	data, err := base64.RawURLEncoding.DecodeString(value)
	if err != nil || json.Unmarshal(data, &cursor) != nil || cursor.I == "" {
		return authorQueryCursor{}, false
	}
	return cursor, true
}

func (s *Server) GetApiV1AuthorsQuery(w http.ResponseWriter, r *http.Request, params gen.GetApiV1AuthorsQueryParams) {
	limit := authorQueryDefaultLimit
	if params.Limit != nil {
		limit = *params.Limit
	}
	if limit < 1 || limit > authorQueryMaxLimit {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "limit 取值范围 1..100")
		return
	}

	query := ""
	if params.Q != nil {
		if utf8.RuneCountInString(*params.Q) > authorQueryMaxQLength {
			writeErr(w, http.StatusBadRequest, codeInvalidParam, "q 最长 200 个字符")
			return
		}
		query = strings.TrimSpace(*params.Q)
	}

	var cursor *authorQueryCursor
	if params.Cursor != nil && *params.Cursor != "" {
		parsed, ok := decodeAuthorQueryCursor(*params.Cursor)
		if !ok || parsed.Q != query {
			writeErr(w, http.StatusBadRequest, codeInvalidCursor, "分页游标不合法")
			return
		}
		cursor = &parsed
	}

	pageSize := int64(limit + 1)
	items := make([]gen.AuthorQueryItem, 0, limit)
	var lastName, lastID string
	var hasMore bool
	if cursor == nil {
		rows, err := s.q.SearchAuthorsFirstPage(r.Context(), db.SearchAuthorsFirstPageParams{
			Query: query, PageLimit: pageSize,
		})
		if err != nil {
			s.internalErr(w, "查询作者", err)
			return
		}
		hasMore = len(rows) > limit
		if hasMore {
			rows = rows[:limit]
		}
		for _, row := range rows {
			items = append(items, gen.AuthorQueryItem{
				Id: row.ID, DisplayName: row.DisplayName, Type: row.Type,
			})
			lastName, lastID = row.DisplayName, row.ID
		}
	} else {
		rows, err := s.q.SearchAuthorsAfterCursor(r.Context(), db.SearchAuthorsAfterCursorParams{
			Query: query, CursorName: cursor.K, CursorID: cursor.I, PageLimit: pageSize,
		})
		if err != nil {
			s.internalErr(w, "查询作者", err)
			return
		}
		hasMore = len(rows) > limit
		if hasMore {
			rows = rows[:limit]
		}
		for _, row := range rows {
			items = append(items, gen.AuthorQueryItem{
				Id: row.ID, DisplayName: row.DisplayName, Type: row.Type,
			})
			lastName, lastID = row.DisplayName, row.ID
		}
	}

	var nextCursor *string
	if hasMore {
		next := encodeAuthorQueryCursor(lastName, lastID, query)
		nextCursor = &next
	}
	writeJSON(w, http.StatusOK, gen.AuthorQueryPage{Items: items, NextCursor: nextCursor})
}
