package httpapi

import (
	"net/http"

	"qimeng-media/server/internal/httpapi/gen"
)

// M1 未接线的端点统一 501。
//
// 为什么显式列出而不是 404：openapi 已定义全部路径（协议宪法），三端
// SDK 会先生成调用代码；501 + NOT_IMPLEMENTED 让客户端把"功能未到
// 里程碑"与"路径拼错"区分开，调试体验完全不同。每个里程碑接线时把
// 对应方法从这里移走、实现到各自文件。

func (s *Server) GetApiV1Authors(w http.ResponseWriter, r *http.Request) {
	notImplemented(w) // M2：作者体系
}

func (s *Server) PostApiV1AuthorsImportTxt(w http.ResponseWriter, r *http.Request) {
	notImplemented(w)
}

func (s *Server) PutApiV1AuthorsAuthorIdFollow(w http.ResponseWriter, r *http.Request, authorID string) {
	notImplemented(w)
}

func (s *Server) GetApiV1StatsOverview(w http.ResponseWriter, r *http.Request) {
	notImplemented(w) // M3：统计总览
}

func (s *Server) GetApiV1StatsTrends(w http.ResponseWriter, r *http.Request, params gen.GetApiV1StatsTrendsParams) {
	notImplemented(w) // M3：趋势
}

func (s *Server) PostApiV1ImportQimengBackup(w http.ResponseWriter, r *http.Request) {
	notImplemented(w) // M5：旧版迁移
}
