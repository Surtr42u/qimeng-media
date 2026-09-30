// revision.go：库内容修订号端点（GET /api/v1/library/revision）与 bump 链。
//
// 语义（协议见 openapi.yaml 该端点 description）：全局单计数器，任何改变
// 资产集合的写入都使其自增；客户端记录上一轮拉取值，未变即整轮跳过全量
// 列表拉取。自增触发点收口在两处：
//   - library.changed 事件订阅（server.go New）：覆盖扫描/watch 增量/
//     relink/enrich、上传、回收站移入/恢复、整理移动、库删除与开关——
//     凡发布该事件的写入路径一次订阅全覆盖，scanner 零新增依赖；
//   - 不发事件的写入路径显式调用 bumpLibraryRevision：回收站物理删除
//     （单条/清空/到期清扫）、qimeng-backup 导入（import.go 只失效推荐
//     缓存不广播事件）。
package httpapi

import (
	"context"
	"net/http"

	"qimeng-media/server/internal/httpapi/gen"
)

// GetApiV1LibraryRevision 返回当前库内容修订号。鉴权：无 security: [] 豁免，
// 走 topRouter 默认分支的 Bearer 保护（与 /api/v1/assets 同级）。
func (s *Server) GetApiV1LibraryRevision(w http.ResponseWriter, r *http.Request) {
	rev, err := s.rev.Get(r.Context())
	if err != nil {
		s.internalErr(w, "读取库内容修订号", err)
		return
	}
	writeJSON(w, http.StatusOK, gen.LibraryRevision{Revision: rev})
}

// bumpLibraryRevision 尽力而为推进修订号（事件订阅协程与显式 bump 调用点
// 共用，全仓唯一 Increment 出口）。失败只告警不阻塞业务：丢一次自增的
// 后果是客户端多做一轮本可跳过的同步（安全方向的失败）；调用方不得因
// 本函数失败而改变自身操作的成功语义。
func (s *Server) bumpLibraryRevision() {
	if _, err := s.rev.Increment(context.Background()); err != nil {
		s.logger.Warn("推进库内容修订号失败（客户端可能多拉一轮列表，下次变更自愈）", "err", err)
	}
}
