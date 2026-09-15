// thumbnail_warmup.go：缩略图自动预生成（2026-09-15 批，对齐旧版「扫描完即有缩略图」
// 的无感体验）。旧版绮梦影库扫描期即本地预建缩略图缓存，浏览恒快；新版此前只有
// 懒生成（浏览到才生成），单机形态手机 SoC 上表现为「相册先出占位块、过一会儿
// 凭空冒出一批」。本文件三条触发路径：
//   - 服务端开机回填一次（覆盖历史积压，含旧版本升级上来的库）；
//   - 每轮扫描结束（FinishScan 钩子）补齐本轮新增/变更资产；
//   - 周期兜底扫（清漏：懒生成删除、缓存清理等漂移）。
// 生成走既有 WorkerPool（GenSlots 进程闸限并发）；投递端 200ms 节流给按需
// 请求留闸位——预热永远不与前台浏览抢 ffmpeg 预算（无感优先）。
package httpapi

import (
	"context"
	"net/http"
	"path/filepath"
	"time"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/thumbnail"
)

const (
	// thumbnailWarmupInterval 周期兜底间隔。漂移源（按需删除、缓存清理）频率
	// 极低，10 分钟量级的兜底足够；过密会让后台查询常驻跑空圈。
	thumbnailWarmupInterval = 10 * time.Minute
	// thumbnailWarmupSubmitPause 投递节流：队列消化节奏受 ffmpeg 实时长约束，
	// 固定小步投递让 genSlots 常有空位接按需请求（预热让路，无感优先）。
	thumbnailWarmupSubmitPause = 200 * time.Millisecond
	// thumbnailWarmupRetryPause 队列满时的重投等待（队列 256 容量，满 = 池在
	// 集中消化，等待后重投即可；任务体只是结构体，等待无成本）。
	thumbnailWarmupRetryPause = 250 * time.Millisecond
)

// StartThumbnailWarmup 组合根（main）调用一次：daemon goroutine，随进程退出。
// 开机先回填一轮，再进周期兜底循环。
func (s *Server) StartThumbnailWarmup() {
	go func() {
		s.warmupOnce("启动回填")
		for {
			time.Sleep(thumbnailWarmupInterval)
			s.warmupOnce("周期兜底")
		}
	}()
}

// WarmupAfterScan 扫描结束后的预热入口（FinishScan 钩子异步调用，不阻塞终态回写）。
func (s *Server) WarmupAfterScan() {
	go s.warmupOnce("扫描后预热")
}

// warmupOnce 一轮回填：全量拉启用库资产（ListThumbnailWarmup 轻查询），按缓存键
// os.Stat 挑缺 md 档的投递生成。md 是网格唯一预生成档——其余尺寸属低频显式请求，
// 保持懒生成即可（预生成预算全给浏览主路径）。
func (s *Server) warmupOnce(reason string) {
	rows, err := s.q.ListThumbnailWarmup(context.Background())
	if err != nil {
		s.logger.Warn("缩略图预热候选查询失败", "reason", reason, "err", err)
		return
	}
	var missing []thumbnail.Task
	for _, r := range rows {
		if s.thumbs.HasThumbnail(r.AssetID, thumbnail.SizeGrid) {
			continue
		}
		missing = append(missing, thumbnail.Task{
			AssetID:    r.AssetID,
			SourcePath: filepath.Join(r.RootPath, filepath.FromSlash(r.RelPath)),
			Kind:       thumbnail.Kind(r.MediaType),
			Sizes:      []thumbnail.Size{thumbnail.SizeGrid},
		})
	}
	if len(missing) == 0 {
		return
	}
	start := time.Now()
	submitted := 0
	for _, t := range missing {
		for {
			if err := s.thumbs.Submit(t); err == nil {
				break
			}
			time.Sleep(thumbnailWarmupRetryPause)
		}
		submitted++
		time.Sleep(thumbnailWarmupSubmitPause)
	}
	s.logger.Info("缩略图预热投递完成",
		"reason", reason, "candidates", len(rows), "missing", len(missing),
		"submitted", submitted, "elapsed", time.Since(start).Round(time.Second).String())
}

// GetApiV1ThumbnailsProgress 缩略图覆盖进度（缓存上限/进度页轮询用，2026-09-15 批）。
// 分子 = 缓存目录落盘文件数（多档并存按文件计——md 预生成档为主的现状下即覆盖数），
// 分母 = 启用库资产总数（与 ListThumbnailWarmup 候选同源同口径）。
func (s *Server) GetApiV1ThumbnailsProgress(w http.ResponseWriter, r *http.Request) {
	totalRow, err := s.q.CountEnabledLibraryAssets(context.Background())
	if err != nil {
		s.internalErr(w, "统计启用库资产", err)
		return
	}
	total := int(totalRow)
	onDisk, err := s.thumbs.CountOnDisk()
	if err != nil {
		s.internalErr(w, "统计缩略图缓存目录", err)
		return
	}
	writeJSON(w, http.StatusOK, gen.ThumbnailProgress{
		TotalAssets:  total,
		ThumbsOnDisk: int(onDisk),
	})
}
