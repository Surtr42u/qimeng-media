package httpapi

import (
	"database/sql"
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"github.com/google/uuid"

	"qimeng-media/server/internal/events"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

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
		writeErr(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
		return
	}
	out := make([]gen.Library, 0, len(libs))
	for _, l := range libs {
		counts, err := s.q.CountLibraryMedia(r.Context(), l.ID)
		if err != nil {
			s.logger.Error("统计库文件数失败", "err", err, "libraryId", l.ID)
			writeErr(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
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
		out = append(out, gen.Library{
			Id:         &l.ID,
			Name:       &l.Name,
			RootPath:   &l.RootPath,
			FileCount:  &fileCount,
			ImageCount: &imageCount,
			VideoCount: &videoCount,
			ScanState:  &state,
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
		writeErr(w, http.StatusBadRequest, "INVALID_PARAM", "name 与 rootPath 均必填")
		return
	}
	// openapi：rootPath 是"服务端可访问的绝对路径"。相对路径会让
	// 服务进程的工作目录悄悄改变媒体根，部署事故极难排查。
	if !filepath.IsAbs(req.RootPath) {
		writeErr(w, http.StatusBadRequest, "INVALID_PARAM", "rootPath 必须是绝对路径")
		return
	}
	info, err := os.Stat(req.RootPath)
	if err != nil || !info.IsDir() {
		// 不区分"不存在"与"无权限"：错误文案不泄露服务端文件系统细节
		//（SECURITY 红线 7；调用方只需知道"这个目录用不了"）。
		writeErr(w, http.StatusBadRequest, "PATH_NOT_FOUND", "目录不存在或不可访问")
		return
	}
	// 库根与数据目录互斥（任何方向的嵌套都拒绝）：数据目录里有缩略图缓存
	// （webp 是白名单格式）、回收站、数据库文件——落进库内会被扫描器自噬
	// （scanner 侧有第二道 SkipDir 防御，这里从源头拒绝配置错误）。
	if dirConflict(req.RootPath, s.cfg.DataDir) {
		writeErr(w, http.StatusBadRequest, "DATA_DIR_CONFLICT", "库目录不能包含也不能位于服务端数据目录内")
		return
	}
	lib, err := s.q.CreateLibrary(r.Context(), db.CreateLibraryParams{
		ID:        uuid.NewString(),
		Name:      req.Name,
		RootPath:  filepath.Clean(req.RootPath),
		CreatedAt: store.FormatTimestamp(s.now()),
	})
	if err != nil {
		// UNIQUE(root_path)：同根目录注册第二个库 → 409；其余 DB 错误
		// 是服务端故障 → 500（不能一律 409 掩盖真实故障）。
		if strings.Contains(err.Error(), "UNIQUE constraint failed") {
			writeErr(w, http.StatusConflict, "CONFLICT", "该目录已注册为库")
			return
		}
		s.logger.Error("注册库失败", "err", err)
		writeErr(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
		return
	}
	id, name, root := lib.ID, lib.Name, lib.RootPath
	state := gen.LibraryScanState("idle")
	writeJSON(w, http.StatusCreated, gen.Library{
		Id:        &id,
		Name:      &name,
		RootPath:  &root,
		ScanState: &state,
	})
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
		writeErr(w, http.StatusNotFound, "NOT_FOUND", "库不存在")
		return
	}
	if err != nil {
		s.logger.Error("查询库失败", "err", err, "libraryId", libraryID)
		writeErr(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
		return
	}
	if err := s.scanner.Scan(r.Context(), lib.ID); err != nil {
		if errors.Is(err, ErrScannerUnavailable) {
			writeErr(w, http.StatusServiceUnavailable, "SCANNER_UNAVAILABLE", "扫描器尚未装配")
			return
		}
		if errors.Is(err, ErrScanAlreadyRunning) {
			writeErr(w, http.StatusConflict, "SCAN_IN_PROGRESS", "该库扫描进行中")
			return
		}
		s.logger.Error("触发扫描失败", "err", err, "libraryId", lib.ID)
		writeErr(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
		return
	}
	s.scanStates.set(lib.ID, "scanning")
	w.WriteHeader(http.StatusAccepted)
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
	// /api/v1/events 事件清单）。
	if err := s.bus.Publish(events.Event{Topic: events.TopicLibraryChanged}); err != nil {
		s.logger.Warn("广播 library.changed 失败", "err", err)
	}
}
