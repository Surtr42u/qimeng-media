// thumbnail_warmup.go 仅适配回填服务生命周期、变更通知与进度协议。
package httpapi

import (
	"context"
	"net/http"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/safego"
)

func (s *Server) StartThumbnailWarmup(ctx context.Context) {
	safego.Go(s.logger, "缩略图预热", func() { s.thumbWarmup.Run(ctx) })
}

func (s *Server) WarmupAfterScan() { s.thumbWarmup.Notify() }

func (s *Server) WaitThumbnailWarmup(ctx context.Context) error { return s.thumbWarmup.Wait(ctx) }

func (s *Server) GetApiV1ThumbnailsProgress(w http.ResponseWriter, r *http.Request) {
	snap, err := s.thumbWarmup.Progress(r.Context())
	if err != nil {
		s.internalErr(w, "统计缩略图覆盖", err)
		return
	}
	writeJSON(w, http.StatusOK, gen.ThumbnailProgress{TotalAssets: snap.Total, ThumbsOnDisk: snap.Covered})
}
