// thumbnail_warmup.go：缩略图自动预生成（2026-09-15 批，对齐旧版「扫描完即有缩略图」
// 的无感体验）。旧版绮梦影库扫描期即本地预建缩略图缓存，浏览恒快；新版此前只有
// 懒生成（浏览到才生成），单机形态手机 SoC 上表现为「相册先出占位块、过一会儿
// 凭空冒出一批」。本文件三条触发路径：
//   - 服务端开机回填一次（覆盖历史积压，含旧版本升级上来的库）；
//   - 每轮扫描结束（FinishScan 钩子）补齐本轮新增/变更资产；
//   - 周期兜底扫（清漏：懒生成删除、缓存清理等漂移）。
//
// 生成走既有 WorkerPool（GenSlots 进程闸限并发）；投递端 200ms 节流给按需
// 请求留闸位——预热永远不与前台浏览抢 ffmpeg 预算（无感优先）。开机另有
// 静默窗（waitForBootQuietWindow，2026-09-18 批）避开登录期争抢。
package httpapi

import (
	"context"
	"errors"
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

// waitForBootQuietWindow 开机静默窗：等到「进程启动 + 配置延迟」之后才返回，
// warmup 全在自己的 goroutine 里，阻塞等待无碍。为什么：单机形态（ADR-0015）
// 服务端起监听与 App 登录几乎同时发生，ffmpeg 批量回填若立刻开跑会和登录后的
// 首屏请求/按需缩略图生成抢 CPU/IO，表现为「登录后前几十秒整机发闷」（2026-09-18
// 反馈）；启动回填与窗内的扫描后预热都顺延到窗尾执行（周期兜底在 10min 后到点，
// 窗早已过、即时返回）。配置 0 或负 = 关闭等待；默认值见 config
// DefaultThumbnailWarmupDelay。
func (s *Server) waitForBootQuietWindow(reason string) {
	delay := s.cfg.Thumbnail.WarmupDelay
	if delay <= 0 {
		return
	}
	remain := delay - time.Since(s.startedAt)
	if remain <= 0 {
		return
	}
	s.logger.Info("缩略图回填等待开机静默窗结束", "reason", reason,
		"wait", remain.Round(time.Second).String())
	time.Sleep(remain)
}

// warmupOnce 一轮回填：全量拉启用库资产（ListThumbnailWarmup 轻查询），按缓存键
// os.Stat 挑缺 md 档的投递生成。md 是网格唯一预生成档——其余尺寸属低频显式请求，
// 保持懒生成即可（预生成预算全给浏览主路径）。
func (s *Server) warmupOnce(reason string) {
	s.waitForBootQuietWindow(reason)
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
			err := s.thumbs.Submit(t)
			if err == nil {
				break
			}
			// 池已关闭（停机路径 thumbs.Close，main 优雅收尾）：放弃整轮
			// 投递——对关闭的池无限重试只会每 250ms 空转到进程退出。
			if errors.Is(err, thumbnail.ErrPoolClosed) {
				s.logger.Info("缩略图工作池已关闭，中止本轮预热投递", "reason", reason)
				return
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

// thumbnailProgressCacheTTL 覆盖进度的进程内缓存 TTL。分子=覆盖资产数，需对
// 全库逐资产 Stat（与 warmupOnce 候选判定同一 HasThumbnail 出口，数千次 Stat
// ≈百毫秒级），进度页轮询为秒级——30s 缓存让稳态计算成本趋零；缓存粒度对
// 「回填全程 30 分钟量级」的进度条足够顺滑，预热/懒生成导致的覆盖变化最迟
// 30s 透出（可感知性为零）。
const thumbnailProgressCacheTTL = 30 * time.Second

// thumbnailProgressSnapshot 覆盖进度的缓存快照（读侧经 Server.thumbProgressMu
// 双检访问；at 是计算时刻 = TTL 基准）。
type thumbnailProgressSnapshot struct {
	covered int
	total   int
	at      time.Time
}

// GetApiV1ThumbnailsProgress 缩略图覆盖进度（缓存上限/进度页轮询用，2026-09-15 批；
// 2026-10-04 口径修正：分子从「目录文件数」改为「已覆盖资产数」——v4 缓存键换代后
// 旧键文件成为孤儿仍留在目录（永不因数量上限删除，对账清理未上线），目录计数把
// 孤儿也计入导致进度虚高满格；逐资产判定与 warmupOnce 候选同源（同一 HasThumbnail
// 出口，md 档），孤儿天然不进分子，进度回归真实覆盖。
// 分母 = 启用库资产总数（与 ListThumbnailWarmup 候选同源同口径）。
// 计算经 30s TTL 缓存（thumbnailProgressCacheTTL），并发轮询由 mutex 双检合并。
func (s *Server) GetApiV1ThumbnailsProgress(w http.ResponseWriter, r *http.Request) {
	if snap := s.loadFreshThumbProgress(); snap != nil {
		writeJSON(w, http.StatusOK, gen.ThumbnailProgress{
			TotalAssets:  snap.total,
			ThumbsOnDisk: snap.covered,
		})
		return
	}
	s.thumbProgressMu.Lock()
	defer s.thumbProgressMu.Unlock()
	if snap := s.thumbProgressCache; snap != nil && time.Since(snap.at) < thumbnailProgressCacheTTL {
		writeJSON(w, http.StatusOK, gen.ThumbnailProgress{
			TotalAssets:  snap.total,
			ThumbsOnDisk: snap.covered,
		})
		return
	}
	totalRow, err := s.q.CountEnabledLibraryAssets(context.Background())
	if err != nil {
		s.internalErr(w, "统计启用库资产", err)
		return
	}
	total := int(totalRow)
	rows, err := s.q.ListThumbnailWarmup(context.Background())
	if err != nil {
		s.internalErr(w, "列出缩略图预热候选", err)
		return
	}
	covered := 0
	for _, row := range rows {
		// 与 warmupOnce 候选判定同出口（md 档，SizeGrid 经 thumbDst 换算生效像素），
		// 保证「进度条走完」与「预热不再投递」互为充要，UI 口径与真实覆盖恒一致。
		if s.thumbs.HasThumbnail(row.AssetID, thumbnail.SizeGrid) {
			covered++
		}
	}
	s.thumbProgressCache = &thumbnailProgressSnapshot{covered: covered, total: total, at: time.Now()}
	writeJSON(w, http.StatusOK, gen.ThumbnailProgress{
		TotalAssets:  total,
		ThumbsOnDisk: covered,
	})
}

// loadFreshThumbProgress 读未过期缓存快照；无缓存/已过期返回 nil（加锁重算路径
// 的无锁快路径：多数轮询命中缓存，不与重算互斥）。
func (s *Server) loadFreshThumbProgress() *thumbnailProgressSnapshot {
	s.thumbProgressMu.Lock()
	defer s.thumbProgressMu.Unlock()
	snap := s.thumbProgressCache
	if snap == nil || time.Since(snap.at) >= thumbnailProgressCacheTTL {
		return nil
	}
	return snap
}
