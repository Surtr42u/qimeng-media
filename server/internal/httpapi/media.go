package httpapi

import (
	"database/sql"
	"errors"
	"io/fs"
	"net/http"
	"os"
	"path/filepath"
	"time"

	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/thumbnail"
)

// GetMediaOrigAssetId 原图/原视频直链。
//
// 签名已由 mediaSignature 中间件校验过（403 语义在那里），本 handler
// 只负责取数与发文件。发文件**必须**用 http.ServeContent：它原生处理
// Range/206/If-Modified-Since/If-Range/ContentType 推断与 Content-Length，
// 且基于 io.Seeker 零拷贝发送——任何"手写 read+write 循环"都会丢失
// Range 语义（视频拖进度条即碎）或把大文件整段读进内存。
func (s *Server) GetMediaOrigAssetId(w http.ResponseWriter, r *http.Request, assetID gen.AssetId, params gen.GetMediaOrigAssetIdParams) {
	row, err := s.q.GetAssetWithLibrary(r.Context(), assetID.String())
	if errors.Is(err, sql.ErrNoRows) {
		// 签名有效但资产不存在（已删除/链接来自旧数据）：404。
		writeErr(w, http.StatusNotFound, "NOT_FOUND", "资产不存在")
		return
	}
	if err != nil {
		s.logger.Error("查询资产失败", "err", err)
		writeErr(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
		return
	}
	// SECURITY 红线 1（路径穿越）的 handler 侧兜底：rel_path 来自库
	//（扫描器入库时已规范化），这里仍然强制过 PathWithinRoot——库数据
	// 被污染（外部工具改库/迁移 bug）时这是最后一道闸，绝不直接拼路径发文件。
	abs := filepath.Join(row.RootPath, filepath.FromSlash(row.RelPath))
	if !filing.PathWithinRoot(row.RootPath, abs) {
		s.logger.Error("资产相对路径越界，已拦截", "assetId", row.AssetID)
		writeErr(w, http.StatusBadRequest, "PATH_ESCAPE", "路径不合法")
		return
	}
	f, err := os.Open(abs)
	if errors.Is(err, fs.ErrNotExist) {
		// 库里有记录但文件已不在（外部删改）：404 引导重扫。
		writeErr(w, http.StatusNotFound, "FILE_MISSING", "文件不存在")
		return
	}
	if err != nil {
		s.logger.Error("打开媒体文件失败", "err", err)
		writeErr(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
		return
	}
	defer func() {
		if cerr := f.Close(); cerr != nil {
			s.logger.Warn("关闭媒体文件失败", "err", cerr)
		}
	}()
	// modTime 参与条件请求（If-Modified-Since）；解析失败传零值，
	// ServeContent 对零值 modTime 自动跳过时间条件。
	// 文件名喂给 ServeContent 做扩展名→ContentType 推断（jpg/mp4/...）。
	http.ServeContent(w, r, row.FileName, parseStoreTime(row.Mtime), f)
}

// GetMediaThumbAssetId 缩略图直链（懒生成）。
//
// M1 简化：首次请求同步生成（Ensure 幂等，命中缓存即返回；未命中时
// 本次请求等待 ffmpeg，图片几十毫秒、视频秒级——NAS 单用户可接受）。
// 后续里程碑把首屏预热接入 WorkerPool 后，这里可改为"未命中投递任务
// + 返回占位"。缓存响应 immutable：缓存键 = SHA-256(assetId+size)，
// 键即内容身份，不存在"同键变内容"，强 ETag + immutable 语义成立
// （DOMAIN_RULES §11：永不因数量上限删除有效缓存）。
func (s *Server) GetMediaThumbAssetId(w http.ResponseWriter, r *http.Request, assetID gen.AssetId, params gen.GetMediaThumbAssetIdParams) {
	row, err := s.q.GetAssetWithLibrary(r.Context(), assetID.String())
	if errors.Is(err, sql.ErrNoRows) {
		writeErr(w, http.StatusNotFound, "NOT_FOUND", "资产不存在")
		return
	}
	if err != nil {
		s.logger.Error("查询资产失败", "err", err)
		writeErr(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
		return
	}
	size := thumbSize(gen.Md) // openapi 默认 md
	if params.Size != nil {
		size = thumbSize(*params.Size)
	}
	abs := filepath.Join(row.RootPath, filepath.FromSlash(row.RelPath))
	if !filing.PathWithinRoot(row.RootPath, abs) {
		s.logger.Error("资产相对路径越界，已拦截", "assetId", row.AssetID)
		writeErr(w, http.StatusBadRequest, "PATH_ESCAPE", "路径不合法")
		return
	}
	if err := s.thumbs.Ensure(r.Context(), row.AssetID, abs, thumbnail.Kind(row.MediaType), []thumbnail.Size{size}); err != nil {
		// 生成失败（损坏文件/ffmpeg 异常）：给 404 占位语义——客户端
		// 对缩略图缺失的常规处理就是显示占位块，不该当服务器故障处理。
		s.logger.Warn("缩略图生成失败", "assetId", row.AssetID, "err", err)
		writeErr(w, http.StatusNotFound, "THUMBNAIL_FAILED", "缩略图不可用")
		return
	}
	key := thumbnail.CacheKey(row.AssetID, size)
	path := thumbnail.ThumbPath(s.cfg.DataDir, key)
	f, err := os.Open(path)
	if err != nil {
		s.logger.Error("打开缩略图缓存失败", "err", err)
		writeErr(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
		return
	}
	defer func() {
		if cerr := f.Close(); cerr != nil {
			s.logger.Warn("关闭缩略图文件失败", "err", cerr)
		}
	}()
	w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
	etag := `"` + key + `"`
	w.Header().Set("ETag", etag)
	// ServeContent 不处理 ETag 条件请求，If-None-Match 命中手动回 304
	//（省一次文件读取与传输；键即内容身份，命中即未变）。
	if r.Header.Get("If-None-Match") == etag {
		w.WriteHeader(http.StatusNotModified)
		return
	}
	// 文件名带 .webp 扩展名让 ServeContent 推断出 image/webp。
	http.ServeContent(w, r, "t.webp", time.Time{}, f)
}
