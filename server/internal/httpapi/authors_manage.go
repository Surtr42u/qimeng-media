// authors_manage.go：作者管理端点（按名新建 / 改名 / 删除，ADR-0024 修订 5，
// 2026-10-13 用户拍板）。作者管理页（App/Web）的行级操作通道：
//   - POST /authors：仅新建作者行（复用或新建；不写片段不关联文件）；
//   - PUT /authors/{authorId}：改名（TXT 块原地重写 + 身份迁移）；
//   - DELETE /authors/{authorId}：删除（TXT 块 + 关联 + 作者行三清，文件保留）。
//
// 单步事务与镜像刷新编排下沉 authoredit 用例（Q1 事务下沉；ADR-0019）：
// 本文件只做 HTTP 接线、纯协议校验与哨兵映射。语义细节见 api/openapi.yaml
// 三个端点 description 与 DOMAIN_RULES §6。
package httpapi

import (
	"context"
	"errors"
	"fmt"
	"net/http"

	"qimeng-media/server/internal/authorattach"
	"qimeng-media/server/internal/authoredit"
	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store/db"
)

// authorFromListRow 把 ListAuthors 行装配为协议 Author（列表端点与作者管理
// 端点响应共用单源，防字段口径漂移；GetApiV1Authors 同款装配随迁至此）。
func authorFromListRow(row db.ListAuthorsRow) gen.Author {
	t := gen.AuthorType(row.Type)
	fileCount := int(row.FileCount)
	viewCount := toInt(row.ViewCount) // toInt 抹平 sqlc interface{}
	followed := row.Followed == 1
	return gen.Author{
		Id:          &row.ID,
		DisplayName: &row.DisplayName,
		Type:        &t,
		FileCount:   &fileCount,
		Followed:    &followed,
		ViewCount:   ptr(viewCount),
	}
}

// findAuthorRow 从 ListAuthors 全量里取单行（作者表百级，不为单查开新查询
// ——与 authorattach.resolveRegularAuthors 同拍板）。ok=false = 不存在。
func (s *Server) findAuthorRow(ctx context.Context, id string) (db.ListAuthorsRow, bool, error) {
	rows, err := s.q.ListAuthors(ctx)
	if err != nil {
		return db.ListAuthorsRow{}, false, err
	}
	for i := range rows {
		if rows[i].ID == id {
			return rows[i], true, nil
		}
	}
	return db.ListAuthorsRow{}, false, nil
}

// writeAuthorManageErr 作者管理端点（新建/改名/删除）的哨兵错误映射：
// 不存在 404；COS 作者 400（由目录扫描自动管理）；身份撞名 409（不合并）；
// 非法名/显示名不自洽 400（留痕说明，客户端可直接透出）。
func (s *Server) writeAuthorManageErr(w http.ResponseWriter, what string, err error) {
	switch {
	case errors.Is(err, authorattach.ErrAuthorNotFound):
		writeErr(w, http.StatusNotFound, codeNotFound, msgAuthorNotFound)
	case errors.Is(err, authorattach.ErrAuthorNotRegular):
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "COS 作者由目录扫描自动管理（删除/改名请调整库内目录）")
	case errors.Is(err, authorattach.ErrAuthorIDConflict):
		writeErr(w, http.StatusConflict, codeConflict, "目标名字已存在作者（本端点不做合并，请先处理既有作者）")
	case errors.Is(err, authorattach.ErrInvalidAuthorName):
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "作者名非法（规范化后无有效别名）")
	case errors.Is(err, authorattach.ErrDisplayNameIdentityMismatch):
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "该作者显示名与身份不自洽，请先改名再操作")
	default:
		s.internalErr(w, what, err)
	}
}

// writeAuthorOrInternal 装配单作者响应：不存在（并发删除竞态）归 404。
// ctx 贯穿请求（Q1：响应装配查询同样可被请求取消，不得用 Background 截断
// 传播——取消虽仍会 500/中断本次装配，但不再多占一次无谓的全量查询）。
func (s *Server) writeAuthorOrInternal(ctx context.Context, w http.ResponseWriter, what, authorID string) {
	row, ok, err := s.findAuthorRow(ctx, authorID)
	if err != nil {
		s.internalErr(w, what, err)
		return
	}
	if !ok {
		writeErr(w, http.StatusNotFound, codeNotFound, msgAuthorNotFound)
		return
	}
	writeJSON(w, http.StatusOK, authorFromListRow(row))
}

// PostApiV1Authors 按名新建作者（仅作者行）：规范身份命中已有常规作者则复用
// （返回既有行真实计数），未命中新建（origin=client）；不写 TXT 片段、不关联
// 文件（与 TXT 格式 C 同形态的零关联行，统一重建不删）。COS 行撞名 409。
// 单事务由 authoredit 用例持有。
func (s *Server) PostApiV1Authors(w http.ResponseWriter, r *http.Request) {
	var body gen.PostApiV1AuthorsJSONRequestBody
	if !decodeJSON(w, r, &body) {
		return
	}
	// 名字合法性先按原始输入校验（trim 由用例在写入前做）：空名/非法名
	// 不开事务、零副作用。
	if reason := authoring.NewAuthorNameRejectReason(body.DisplayName); reason != "" {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, fmt.Sprintf("作者名非法：%s", reason))
		return
	}
	created, err := s.authorEdit.CreateAuthor(r.Context(), body.DisplayName)
	if err != nil {
		s.writeAuthorManageErr(w, "新建作者", err)
		return
	}
	s.writeAuthorOrInternal(r.Context(), w, "新建作者", created.ID)
}

// PutApiV1AuthorsAuthorId 修改作者显示名：TXT 块原地重写（全部片段的编号行
// 别名列表整体替换）+ 身份迁移（新首别名派生 id 变化时行/关联/关注/上传条目
// 一并随迁）；同身份仅更新显示名（幂等）。新身份撞既有作者 409（不合并）。
// 单事务与提交后镜像刷新由 authoredit 用例持有。
func (s *Server) PutApiV1AuthorsAuthorId(w http.ResponseWriter, r *http.Request, authorId string) {
	var body gen.PutApiV1AuthorsAuthorIdJSONRequestBody
	if !decodeJSON(w, r, &body) {
		return
	}
	if reason := authoring.NewAuthorNameRejectReason(body.DisplayName); reason != "" {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, fmt.Sprintf("作者名非法：%s", reason))
		return
	}
	ref, err := s.authorEdit.RenameAuthor(r.Context(), authorId, body.DisplayName)
	if err != nil {
		s.writeAuthorManageErr(w, "修改作者显示名", err)
		return
	}
	// 显示名可能已变：列表/推荐缓存里的 authorNames 失效（确定性动作）；
	// 镜像已由用例在提交成功后刷新，这里不重复刷。
	s.invalidateRecommendCache()
	s.writeAuthorOrInternal(r.Context(), w, "修改作者显示名", ref.ID)
}

// DeleteApiV1AuthorsAuthorId 删除作者（TXT 块 + 关联 + 作者行三清；媒体文件
// 与资产本体保留，仅解除关联——DOMAIN_RULES §6「删除作者保留文件」）。
// 单事务与提交后镜像刷新由 authoredit 用例持有。
func (s *Server) DeleteApiV1AuthorsAuthorId(w http.ResponseWriter, r *http.Request, authorId string) {
	if err := s.authorEdit.DeleteAuthor(r.Context(), authorId); err != nil {
		s.writeAuthorManageErr(w, "删除作者", err)
		return
	}
	// 关联删除：推荐输入变了（确定性动作）；镜像已由用例在提交成功后刷新。
	s.invalidateRecommendCache()
	w.WriteHeader(http.StatusNoContent)
}
