package httpapi

import (
	"context"
	"database/sql"
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"

	"qimeng-media/server/internal/events"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/scanner"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
	"qimeng-media/server/internal/sysmon"
)

// libraryMetricsRefreshTimeout 是 library_files 指标刷新的后台查询上限：
// 刷新跑在业务请求路径上（变更点推送），必须限时，绝不能拖住删除/上传
// 的响应。与 readyzTimeout 同值是两个独立决策（探针上限 vs 后台刷新上限），
// 可各自调整。
const libraryMetricsRefreshTimeout = 3 * time.Second

// scanStateMap 是库扫描态的内存跟踪。
//
// 为什么在内存而不加列：scan_state 属于运行时瞬态（进程重启即失忆是
// 合理语义——重启后没有扫描在跑），为它动 migration 得不偿失；真实
// scanner 接线后若需要持久态再评估（届时走新增 migration，见
// AI_README_FIRST「迁移唯一」）。
type scanStateMap struct {
	mu sync.RWMutex
	m  map[string]string // libraryID -> "idle" | "scanning" | "error"
}

func newScanStateMap() *scanStateMap {
	return &scanStateMap{m: make(map[string]string)}
}

func (m *scanStateMap) get(id string) string {
	m.mu.RLock()
	defer m.mu.RUnlock()
	if v, ok := m.m[id]; ok {
		return v
	}
	return "idle"
}

func (m *scanStateMap) set(id, state string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.m[id] = state
}

// GetApiV1Libraries 库列表：每库附文件计数与扫描态。
func (s *Server) GetApiV1Libraries(w http.ResponseWriter, r *http.Request) {
	libs, err := s.q.ListLibraries(r.Context())
	if err != nil {
		s.logger.Error("查询库列表失败", "err", err)
		writeErr(w, http.StatusInternalServerError, codeInternal, "内部错误")
		return
	}
	out := make([]gen.Library, 0, len(libs))
	for _, l := range libs {
		counts, err := s.q.CountLibraryMedia(r.Context(), l.ID)
		if err != nil {
			s.logger.Error("统计库文件数失败", "err", err, "libraryId", l.ID)
			writeErr(w, http.StatusInternalServerError, codeInternal, "内部错误")
			return
		}
		var fileCount, imageCount, videoCount int
		for _, c := range counts {
			fileCount += int(c.Cnt)
			// animated_image 计入 imageCount：动图在浏览/相册语义里是
			// "图"（静帧封面），openapi 的 Library 只有图/视频两档。
			if c.MediaType == "image" || c.MediaType == "animated_image" {
				imageCount += int(c.Cnt)
			}
			if c.MediaType == "video" {
				videoCount += int(c.Cnt)
			}
		}
		state := gen.LibraryScanState(s.scanStates.get(l.ID))
		kind := gen.LibraryKind(l.Kind)
		enabled := l.Enabled == 1
		out = append(out, gen.Library{
			Id:         &l.ID,
			Name:       &l.Name,
			RootPath:   &l.RootPath,
			FileCount:  &fileCount,
			ImageCount: &imageCount,
			VideoCount: &videoCount,
			ScanState:  &state,
			Enabled:    &enabled,
			Kind:       &kind,
		})
	}
	writeJSON(w, http.StatusOK, out)
}

// PostApiV1Libraries 注册媒体目录。
//
// root_path 必须是服务端本地存在且可读的目录（openapi LibraryCreate
// 语义）：不存在/不是目录 → 400。这里不校验"可读"之外的内容——空目录
// 也是合法库（扫描后才有资产）。
func (s *Server) PostApiV1Libraries(w http.ResponseWriter, r *http.Request) {
	var req gen.PostApiV1LibrariesJSONRequestBody
	if !decodeJSON(w, r, &req) {
		return
	}
	if req.Name == "" || req.RootPath == "" {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "name 与 rootPath 均必填")
		return
	}
	// openapi：rootPath 是"服务端可访问的绝对路径"。相对路径会让
	// 服务进程的工作目录悄悄改变媒体根，部署事故极难排查。
	if !filepath.IsAbs(req.RootPath) {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "rootPath 必须是绝对路径")
		return
	}
	// 库根白名单（config.AllowedLibraryRoots）：空 = 不限制（本地零配置
	// 开发向后兼容）；非空时必须落在任一允许前缀内。放在 Stat 之前：
	// 白名单外的路径不探测文件系统，避免用「是否存在」侧信道探测任意目录。
	if !pathWithinAnyAllowedRoot(req.RootPath, s.cfg.AllowedLibraryRoots) {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "库路径不在允许的根目录白名单内")
		return
	}
	info, err := os.Stat(req.RootPath)
	if err != nil || !info.IsDir() {
		// 不区分"不存在"与"无权限"：错误文案不泄露服务端文件系统细节
		//（SECURITY 红线 7；调用方只需知道"这个目录用不了"）。
		writeErr(w, http.StatusBadRequest, codePathNotFound, "目录不存在或不可访问")
		return
	}
	// 库根与数据目录互斥（任何方向的嵌套都拒绝）：数据目录里有缩略图缓存
	// （webp 是白名单格式）、回收站、数据库文件——落进库内会被扫描器自噬
	// （scanner 侧有第二道 SkipDir 防御，这里从源头拒绝配置错误）。
	if dirConflict(req.RootPath, s.cfg.DataDir) {
		writeErr(w, http.StatusBadRequest, codeDataDirConflict, "库目录不能包含也不能位于服务端数据目录内")
		return
	}
	// kind：openapi 缺省 normal；仅接受协议枚举值（生成物 Valid 校验），
	// COS 作者库传 "cos"（DOMAIN_RULES §6 双体系，扫描分派依据）。
	kind := "normal"
	if req.Kind != nil {
		if !req.Kind.Valid() {
			writeErr(w, http.StatusBadRequest, codeInvalidParam, "kind 只允许 normal 或 cos")
			return
		}
		kind = string(*req.Kind)
	}
	lib, err := s.q.CreateLibrary(r.Context(), db.CreateLibraryParams{
		ID:        uuid.NewString(),
		Name:      req.Name,
		RootPath:  filepath.Clean(req.RootPath),
		Kind:      kind,
		CreatedAt: store.FormatTimestamp(s.now()),
	})
	if err != nil {
		// UNIQUE(root_path)：同根目录注册第二个库 → 409；其余 DB 错误
		// 是服务端故障 → 500（不能一律 409 掩盖真实故障）。
		if strings.Contains(err.Error(), "UNIQUE constraint failed") {
			writeErr(w, http.StatusConflict, codeConflict, "该目录已注册为库")
			return
		}
		s.logger.Error("注册库失败", "err", err)
		writeErr(w, http.StatusInternalServerError, codeInternal, "内部错误")
		return
	}
	id, name, root := lib.ID, lib.Name, lib.RootPath
	createdKind := gen.LibraryKind(lib.Kind)
	state := gen.LibraryScanState("idle")
	writeJSON(w, http.StatusCreated, gen.Library{
		Id:        &id,
		Name:      &name,
		RootPath:  &root,
		ScanState: &state,
		Kind:      &createdKind,
	})
}

// pathWithinAnyAllowedRoot 判定 path 是否落在 allowedRoots 任一根内（含根本身）。
// 空列表 = 不限制（返回 true），调用方无需先判 len。
// 路径先 Clean；大小写不敏感比较（Windows/NAS 文件系统普遍如此，与
// dirConflict 同风格）。前缀边界必须带分隔符：避免 /media 命中 /mediax
// （与 filing.PathWithinRoot 同一防御；此处叠加 EqualFold 以适配盘符/目录名大小写）。
func pathWithinAnyAllowedRoot(path string, allowedRoots []string) bool {
	if len(allowedRoots) == 0 {
		return true
	}
	pc := filepath.Clean(path)
	for _, root := range allowedRoots {
		if root == "" {
			continue
		}
		rc := filepath.Clean(root)
		if strings.EqualFold(pc, rc) {
			return true
		}
		prefix := rc + string(filepath.Separator)
		if len(pc) >= len(prefix) && strings.EqualFold(pc[:len(prefix)], prefix) {
			return true
		}
	}
	return false
}

// dirConflict 判断库根与数据目录是否存在任一方向的嵌套（含相同）。
// 大小写不敏感比较（Windows/NAS 文件系统普遍如此）。
func dirConflict(libraryRoot, dataDir string) bool {
	if libraryRoot == "" || dataDir == "" {
		return false
	}
	a := filepath.Clean(libraryRoot)
	b := filepath.Clean(dataDir)
	if strings.EqualFold(a, b) {
		return true
	}
	relAB, err1 := filepath.Rel(a, b)
	relBA, err2 := filepath.Rel(b, a)
	if err1 == nil && relAB != ".." && !strings.HasPrefix(relAB, ".."+string(filepath.Separator)) {
		return true // 数据目录在库内
	}
	if err2 == nil && relBA != ".." && !strings.HasPrefix(relBA, ".."+string(filepath.Separator)) {
		return true // 库根在数据目录内
	}
	return false
}

// PostApiV1LibrariesLibraryIdScan 触发全量扫描（异步）。
//
// noScanner 占位实现同步返回 ErrScannerUnavailable → 503，客户端能
// 明确知道"扫描器未装配"而不是"库不存在"或"服务器坏了"；真实 scanner
// 按 Scanner 接口契约异步执行，本 handler 永远不等待扫描完成。
func (s *Server) PostApiV1LibrariesLibraryIdScan(w http.ResponseWriter, r *http.Request, libraryID gen.LibraryId) {
	lib, err := s.q.GetLibrary(r.Context(), libraryID)
	if errors.Is(err, sql.ErrNoRows) {
		writeErr(w, http.StatusNotFound, codeNotFound, "库不存在")
		return
	}
	if err != nil {
		s.logger.Error("查询库失败", "err", err, "libraryId", libraryID)
		writeErr(w, http.StatusInternalServerError, codeInternal, "内部错误")
		return
	}
	if err := s.scanner.Scan(r.Context(), lib.ID); err != nil {
		if errors.Is(err, ErrScannerUnavailable) {
			writeErr(w, http.StatusServiceUnavailable, codeScannerUnavailable, "扫描器尚未装配")
			return
		}
		if errors.Is(err, ErrScanAlreadyRunning) {
			writeErr(w, http.StatusConflict, codeScanInProgress, "该库扫描进行中")
			return
		}
		s.logger.Error("触发扫描失败", "err", err, "libraryId", lib.ID)
		writeErr(w, http.StatusInternalServerError, codeInternal, "内部错误")
		return
	}
	s.scanStates.set(lib.ID, "scanning")
	w.WriteHeader(http.StatusAccepted)
}

// DeleteApiV1LibrariesLibraryId 删除库（低频管理操作，openapi DELETE 语义）。
//
// 删除 = 移除库登记行；该库全部 assets 及其关联（标签/作者关联、收藏、点赞、
// 每日展示、每日统计物化）经外键 ON DELETE CASCADE 一并清除（migrations/0001）。
// 两个刻意的不变量（与单资产物理删除同语义）：
//   - view_events 事件流保留：历史统计数据，注释见 0001 migration（只追加表无外键）；
//   - 磁盘媒体文件与回收站条目不动：文件删除只走回收站（铁律 4）。
//
// 已知限制：扫描进行中删除 → 扫描 goroutine 结束时 upsert 因外键失败自然终止
// （scanState 留在内存最终被覆盖），调试场景可接受；生产如需"扫描锁内禁删"
// 在此加 scanStates 检查即可。
func (s *Server) DeleteApiV1LibrariesLibraryId(w http.ResponseWriter, r *http.Request, libraryID gen.LibraryId) {
	// 先查存在性：DeleteLibrary 对不存在的 id 影响 0 行且不报错，无法事后区分 404。
	if _, err := s.q.GetLibrary(r.Context(), libraryID); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			writeErr(w, http.StatusNotFound, codeNotFound, "库不存在")
			return
		}
		s.logger.Error("查询库失败", "err", err, "libraryId", libraryID)
		writeErr(w, http.StatusInternalServerError, codeInternal, "内部错误")
		return
	}
	if err := s.q.DeleteLibrary(r.Context(), libraryID); err != nil {
		s.logger.Error("删除库失败", "err", err, "libraryId", libraryID)
		writeErr(w, http.StatusInternalServerError, codeInternal, "内部错误")
		return
	}
	s.scanStates.set(libraryID, "idle")
	// 删库改变推荐候选集（recommend_cache.go 结构性失效口径），缓存失效
	s.invalidateRecommendCache()
	if err := s.bus.Publish(events.Event{Topic: events.TopicLibraryChanged}); err != nil {
		s.logger.Warn("广播 library.changed 失败", "err", err)
	}
	w.WriteHeader(http.StatusNoContent)
}

// PutApiV1LibrariesLibraryIdEnabled 库启用/停用开关（openapi PUT 语义）。
//
// 停用仅作用于展示面（browse 列表/计数、推荐/排行输入池谓词，migration 0007）：
// 资产及关联、事件流、统计物化、回收站、磁盘文件全部保留；详情/直链不过滤。
// 更新后广播 library.changed 让各端刷新缓存。
func (s *Server) PutApiV1LibrariesLibraryIdEnabled(w http.ResponseWriter, r *http.Request, libraryID gen.LibraryId) {
	var req gen.PutApiV1LibrariesLibraryIdEnabledJSONRequestBody
	if !decodeJSON(w, r, &req) {
		return
	}
	if _, err := s.q.GetLibrary(r.Context(), libraryID); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			writeErr(w, http.StatusNotFound, codeNotFound, "库不存在")
			return
		}
		s.logger.Error("查询库失败", "err", err, "libraryId", libraryID)
		writeErr(w, http.StatusInternalServerError, codeInternal, "内部错误")
		return
	}
	enabled := 0
	if req.Enabled {
		enabled = 1
	}
	if err := s.q.SetLibraryEnabled(r.Context(), db.SetLibraryEnabledParams{
		Enabled: int64(enabled),
		ID:      libraryID,
	}); err != nil {
		s.logger.Error("更新库开关失败", "err", err, "libraryId", libraryID)
		writeErr(w, http.StatusInternalServerError, codeInternal, "内部错误")
		return
	}
	// enabled 是推荐候选查询的 WHERE 条件（recommend.sql kill-switch），
	// 开关切换后推荐缓存失效
	s.invalidateRecommendCache()
	if err := s.bus.Publish(events.Event{Topic: events.TopicLibraryChanged}); err != nil {
		s.logger.Warn("广播 library.changed 失败", "err", err)
	}
	w.WriteHeader(http.StatusNoContent)
}

// FinishScan 由扫描适配层在扫描结束时回调（置终态并广播库变更）。
// main 的适配器把真实扫描器的完成钩子接到这里（导出方法：适配器在
// cmd 包，跨包调用必须是导出的）。
func (s *Server) FinishScan(libraryID string, failed bool) {
	state := "idle"
	if failed {
		state = "error"
	}
	s.scanStates.set(libraryID, state)
	// 库内容可能已变化：广播 library.changed 让各端刷新（openapi
	// /api/v1/events 事件清单）。扫描完成是 library_files 指标的刷新点
	//（成败都刷：失败时磁盘现状同样变了，刷新反而更准）。
	s.refreshLibraryFileMetrics()
	// 扫描增删/变更资产会改变推荐候选集与打分输入，推荐缓存失效
	s.invalidateRecommendCache()
	if err := s.bus.Publish(events.Event{Topic: events.TopicLibraryChanged}); err != nil {
		s.logger.Warn("广播 library.changed 失败", "err", err)
	}
	// 自动预生成缩略图（2026-09-15 批）：扫描终态后异步补齐新增/变更资产的
	// 缩略图（对齐旧版「扫描完即有缩略图」；后台 goroutine，不阻塞终态回写）
	s.WarmupAfterScan()
}

// refreshLibraryFileMetrics 把 library_files{type} gauge 刷新为库内现状：
// ListLibraries + 逐库 CountLibraryMedia（现成查询），image/animated_image
// 归 image 档——与 GetApiV1Libraries 的映射口径一致，两侧改动须双同步。
// 变更点推送刷新（扫描完成/上传入库/删除进回收站/恢复四个时机各调一次），
// 不做定时轮询：治理面板数据允许秒级陈旧，不值得为它加常驻扫描。
// 失败只记日志：指标刷新失败不影响业务路径的成功响应。
func (s *Server) refreshLibraryFileMetrics() {
	ctx, cancel := context.WithTimeout(context.Background(), libraryMetricsRefreshTimeout)
	defer cancel()
	var image, video int64
	libs, err := s.q.ListLibraries(ctx)
	if err != nil {
		s.logger.Warn("刷新 library_files 指标失败", "err", err)
		return
	}
	for _, l := range libs {
		counts, err := s.q.CountLibraryMedia(ctx, l.ID)
		if err != nil {
			s.logger.Warn("刷新 library_files 指标失败", "err", err, "libraryId", l.ID)
			return
		}
		for _, c := range counts {
			switch c.MediaType {
			case scanner.MediaTypeImage, scanner.MediaTypeAnimatedImage:
				image += c.Cnt
			case scanner.MediaTypeVideo:
				video += c.Cnt
			}
		}
	}
	sysmon.Default.SetLibraryFiles(sysmon.FileImage, image)
	sysmon.Default.SetLibraryFiles(sysmon.FileVideo, video)
}
