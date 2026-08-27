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

func (s *Server) DeleteApiV1AssetsAssetId(w http.ResponseWriter, r *http.Request, assetID gen.AssetId) {
	notImplemented(w) // M2：删除=回收站（filing 包已备好底座）
}

func (s *Server) PostApiV1AssetsUpload(w http.ResponseWriter, r *http.Request, params gen.PostApiV1AssetsUploadParams) {
	notImplemented(w) // M2：流式上传 + 四道校验
}

func (s *Server) PostApiV1AssetsAssetIdMove(w http.ResponseWriter, r *http.Request, assetID gen.AssetId) {
	notImplemented(w) // M2：移动/重命名
}

func (s *Server) PutApiV1AssetsAssetIdTags(w http.ResponseWriter, r *http.Request, assetID gen.AssetId) {
	notImplemented(w) // M2：标签管理
}

func (s *Server) GetApiV1AssetsAssetIdTimelineTags(w http.ResponseWriter, r *http.Request, assetID gen.AssetId) {
	notImplemented(w) // M2：时间轴标签
}

func (s *Server) PostApiV1AssetsAssetIdTimelineTags(w http.ResponseWriter, r *http.Request, assetID gen.AssetId) {
	notImplemented(w)
}

func (s *Server) DeleteApiV1AssetsAssetIdTimelineTagsTagId(w http.ResponseWriter, r *http.Request, assetID gen.AssetId, tagID string) {
	notImplemented(w)
}

func (s *Server) GetApiV1Authors(w http.ResponseWriter, r *http.Request) {
	notImplemented(w) // M2：作者体系
}

func (s *Server) PostApiV1AuthorsImportTxt(w http.ResponseWriter, r *http.Request) {
	notImplemented(w)
}

func (s *Server) PutApiV1AuthorsAuthorIdFollow(w http.ResponseWriter, r *http.Request, authorID string) {
	notImplemented(w)
}

func (s *Server) GetApiV1Dirs(w http.ResponseWriter, r *http.Request, params gen.GetApiV1DirsParams) {
	notImplemented(w) // M2：目录树
}

func (s *Server) PostApiV1Dirs(w http.ResponseWriter, r *http.Request) {
	notImplemented(w)
}

func (s *Server) GetApiV1Trash(w http.ResponseWriter, r *http.Request) {
	notImplemented(w) // M2：回收站
}

func (s *Server) DeleteApiV1Trash(w http.ResponseWriter, r *http.Request) {
	notImplemented(w)
}

func (s *Server) PostApiV1TrashTrashIdRestore(w http.ResponseWriter, r *http.Request, trashID gen.TrashId) {
	notImplemented(w)
}

func (s *Server) DeleteApiV1TrashTrashId(w http.ResponseWriter, r *http.Request, trashID gen.TrashId) {
	notImplemented(w)
}

func (s *Server) GetApiV1Tags(w http.ResponseWriter, r *http.Request) {
	notImplemented(w) // M2：标签池
}

func (s *Server) PostApiV1Tags(w http.ResponseWriter, r *http.Request) {
	notImplemented(w)
}

func (s *Server) DeleteApiV1TagsTagId(w http.ResponseWriter, r *http.Request, tagID string) {
	notImplemented(w)
}

func (s *Server) GetApiV1Recommendations(w http.ResponseWriter, r *http.Request, params gen.GetApiV1RecommendationsParams) {
	notImplemented(w) // M2/M3：推荐流（10 维算法）
}

func (s *Server) GetApiV1Rankings(w http.ResponseWriter, r *http.Request, params gen.GetApiV1RankingsParams) {
	notImplemented(w) // M3：排行榜
}

func (s *Server) GetApiV1RecommendationsPrefs(w http.ResponseWriter, r *http.Request) {
	notImplemented(w) // M3：推荐偏好
}

func (s *Server) PutApiV1RecommendationsPrefs(w http.ResponseWriter, r *http.Request) {
	notImplemented(w)
}

func (s *Server) GetApiV1StatsOverview(w http.ResponseWriter, r *http.Request) {
	notImplemented(w) // M3：统计总览
}

func (s *Server) GetApiV1StatsTrends(w http.ResponseWriter, r *http.Request, params gen.GetApiV1StatsTrendsParams) {
	notImplemented(w) // M3：趋势
}

func (s *Server) GetApiV1SystemStatus(w http.ResponseWriter, r *http.Request) {
	notImplemented(w) // M2：系统面板（sysmon 采集层已随 M1 提前交付，接线见 PROJECT_PLAN M2）
}

func (s *Server) PostApiV1ImportQimengBackup(w http.ResponseWriter, r *http.Request) {
	notImplemented(w) // M5：旧版迁移
}

func (s *Server) GetMetrics(w http.ResponseWriter, r *http.Request) {
	notImplemented(w) // M2：Prometheus 指标（OBSERVABILITY.md 内置监控，随 M2 监控仪表盘接线）
}
