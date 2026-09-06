package httpapi

import (
	"database/sql"
	"errors"
	"io"
	"io/fs"
	"net/http"
	"os"
	"path/filepath"
	"time"

	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/scanner"
	"qimeng-media/server/internal/sysmon"
	"qimeng-media/server/internal/thumbnail"
)

// contentAddressedCacheControl 内容寻址资源的长缓存策略：max-age 一年
// + immutable。缩略图直链（缓存键 = SHA-256，见 GetMediaThumbAssetId 注释，
// DOMAIN_RULES §11）与 vite hash 构建产物（spa.go）两类消费方共用此单源
// ——键/文件名即内容身份，不存在"同键变内容"，激进缓存语义才成立。
const contentAddressedCacheControl = "public, max-age=31536000, immutable"

// countingResponseWriter 包装直链响应并累计实际写出的字节数（media_bytes_total
// 是流量语义：304 空体计 0、Range 只计所发区间——不是文件大小语义）。
// ServeContent 内部只经 Write 输出 body，覆写 Write 即覆盖全部字节出口。
type countingResponseWriter struct {
	http.ResponseWriter
	n int64
}

func (c *countingResponseWriter) Write(p []byte) (int, error) {
	n, err := c.ResponseWriter.Write(p)
	c.n += int64(n)
	return n, err
}

// ReadFrom 透传 io.ReaderFrom：ServeContent 内部用 io.CopyN 输出 body，
// wrapper 不实现该接口会让底层连接的 sendfile 零拷贝退化成用户态缓冲拷贝
// （大文件直链白耗 CPU 与内存带宽）。委托底层 writer 的 ReadFrom（net/http
// 的 response 实现了它），底层不支持时 io.Copy 兜底；两条路径都同步累计
// 实发字节，media_bytes_total 口径不变。
func (c *countingResponseWriter) ReadFrom(src io.Reader) (int64, error) {
	var n int64
	var err error
	if rf, ok := c.ResponseWriter.(io.ReaderFrom); ok {
		n, err = rf.ReadFrom(src)
	} else {
		n, err = io.Copy(c.ResponseWriter, src)
	}
	c.n += n
	return n, err
}

// mediaKindFor 把库内 media_type 归并进 media_bytes_total 的 kind 维度：
// 视频走 video 直链计 video，图/动图计 orig（两类都从 /media/orig/ 直链出）。
func mediaKindFor(mediaType string) sysmon.MediaKind {
	if mediaType == scanner.MediaTypeVideo {
		return sysmon.MediaVideo
	}
	return sysmon.MediaOrig
}

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
		writeErr(w, http.StatusNotFound, codeNotFound, "资产不存在")
		return
	}
	if err != nil {
		s.logger.Error("查询资产失败", "err", err)
		writeErr(w, http.StatusInternalServerError, codeInternal, "内部错误")
		return
	}
	// SECURITY 红线 1（路径穿越）的 handler 侧兜底：rel_path 来自库
	//（扫描器入库时已规范化），这里仍然强制过 PathWithinRoot——库数据
	// 被污染（外部工具改库/迁移 bug）时这是最后一道闸，绝不直接拼路径发文件。
	abs := filepath.Join(row.RootPath, filepath.FromSlash(row.RelPath))
	if !filing.PathWithinRoot(row.RootPath, abs) {
		s.logger.Error("资产相对路径越界，已拦截", "assetId", row.AssetID)
		writeErr(w, http.StatusBadRequest, codePathEscape, "路径不合法")
		return
	}
	f, err := os.Open(abs)
	if errors.Is(err, fs.ErrNotExist) {
		// 库里有记录但文件已不在（外部删改）：404 引导重扫。
		writeErr(w, http.StatusNotFound, codeFileMissing, "文件不存在")
		return
	}
	if err != nil {
		s.logger.Error("打开媒体文件失败", "err", err)
		writeErr(w, http.StatusInternalServerError, codeInternal, "内部错误")
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
	// 经计数器发出，返回后按 kind 累计实际输出字节（304/Range 只计实发）。
	cw := &countingResponseWriter{ResponseWriter: w}
	http.ServeContent(cw, r, row.FileName, parseStoreTime(row.Mtime), f)
	sysmon.Default.AddMediaBytes(mediaKindFor(row.MediaType), float64(cw.n))
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
		writeErr(w, http.StatusNotFound, codeNotFound, "资产不存在")
		return
	}
	if err != nil {
		s.logger.Error("查询资产失败", "err", err)
		writeErr(w, http.StatusInternalServerError, codeInternal, "内部错误")
		return
	}
	size := s.thumbSize(gen.Md) // openapi 默认 md
	if params.Size != nil {
		size = s.thumbSize(*params.Size)
	}
	abs := filepath.Join(row.RootPath, filepath.FromSlash(row.RelPath))
	if !filing.PathWithinRoot(row.RootPath, abs) {
		s.logger.Error("资产相对路径越界，已拦截", "assetId", row.AssetID)
		writeErr(w, http.StatusBadRequest, codePathEscape, "路径不合法")
		return
	}
	if err := s.thumbs.Ensure(r.Context(), row.AssetID, abs, thumbnail.Kind(row.MediaType), []thumbnail.Size{size}); err != nil {
		// 生成失败（损坏文件/ffmpeg 异常）：给 404 占位语义——客户端
		// 对缩略图缺失的常规处理就是显示占位块，不该当服务器故障处理。
		s.logger.Warn("缩略图生成失败", "assetId", row.AssetID, "err", err)
		writeErr(w, http.StatusNotFound, codeThumbnailFailed, "缩略图不可用")
		return
	}
	key := thumbnail.CacheKey(row.AssetID, size)
	path := thumbnail.ThumbPath(s.cfg.DataDir, key)
	f, err := os.Open(path)
	if err != nil {
		s.logger.Error("打开缩略图缓存失败", "err", err)
		writeErr(w, http.StatusInternalServerError, codeInternal, "内部错误")
		return
	}
	defer func() {
		if cerr := f.Close(); cerr != nil {
			s.logger.Warn("关闭缩略图文件失败", "err", cerr)
		}
	}()
	w.Header().Set("Cache-Control", contentAddressedCacheControl)
	etag := `"` + key + `"`
	w.Header().Set("ETag", etag)
	// ServeContent 不处理 ETag 条件请求，If-None-Match 命中手动回 304
	//（省一次文件读取与传输；键即内容身份，命中即未变）。
	if r.Header.Get("If-None-Match") == etag {
		w.WriteHeader(http.StatusNotModified)
		return
	}
	// 文件名带 .webp 扩展名让 ServeContent 推断出 image/webp。
	// 缩略图恒计 thumb（304 手动返回路径在上方已 return，不进这里）。
	cw := &countingResponseWriter{ResponseWriter: w}
	http.ServeContent(cw, r, "t.webp", time.Time{}, f)
	sysmon.Default.AddMediaBytes(sysmon.MediaThumb, float64(cw.n))
}
