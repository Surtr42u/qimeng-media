package httpapi

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"io"
	"io/fs"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"time"

	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/scanner"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/sysmon"
	"qimeng-media/server/internal/thumbnail"
)

// contentAddressedCacheControl 内容寻址资源的长缓存策略：max-age 一年
// + immutable。缩略图直链（缓存键 = SHA-256，见 GetMediaThumbAssetId 注释，
// DOMAIN_RULES §11）与 vite hash 构建产物（spa.go）两类消费方共用此单源
// ——键/文件名即内容身份，不存在"同键变内容"，激进缓存语义才成立。
const contentAddressedCacheControl = "public, max-age=31536000, immutable"

// noStoreCacheControl 禁止失效表示/鉴权响应被复用。
const noStoreCacheControl = "no-store"

// signedOrigCacheControl 原图/原视频直链（/media/orig/**）的缓存策略
// （ADR-0027）：exp 窗口对齐后同一窗口内 URL 逐字节恒定，显式 max-age 才
// 有意义——取直链窗口长（实际生效的 Server.ttl，配置 token_ttl），保证
// 缓存条目不可能比 URL 本身更长寿（URL 最短有效期 = 一个完整 ttl ≥
// max-age）。private：单用户媒体库，禁止共享缓存代发（授权语义绑在
// URL 签名上）。缩略图直链不走此头——它是内容寻址键（SHA-256），已有
// immutable 长缓存（contentAddressedCacheControl）。
// 与签名侧 exp 窗口（assets_media_url.go mediaURLExpiry）联动：签名侧
// 改动须同步此处，反之亦然。
func signedOrigCacheControl(ttl time.Duration) string {
	return "private, max-age=" + strconv.Itoa(int(ttl/time.Second))
}

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
	f, err := os.OpenInRoot(row.RootPath, filepath.FromSlash(row.RelPath))
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
	// exp 窗口对齐（ADR-0027）后同窗 URL 恒定，显式缓存头才有意义：
	// max-age=直链窗口长，缓存条目寿命不超过 URL 有效期下界（见
	// signedOrigCacheControl）。放在全部错误分支之后，错误响应不带缓存头。
	w.Header().Set("Cache-Control", signedOrigCacheControl(s.ttl))
	cw := &countingResponseWriter{ResponseWriter: w}
	http.ServeContent(cw, r, row.FileName, parseStoreTime(row.Mtime), f)
	sysmon.Default.AddMediaBytes(mediaKindFor(row.MediaType), float64(cw.n))
}

// GetMediaThumbAssetId 缩略图直链（懒生成）。
//
// 2026-09-15 批改 EnsureDetached（detached.go：单飞+后台续生）：未命中时同键
// 请求合并为一次生成，且生成挂后台 context——客户端（App 网格滚动 Coil 取消/
// 快速滑动）断开只结束本次等待，不杀 ffmpeg。旧 M1 简化（Ensure 直挂请求
// context 同步生成）在手机单机形态被实测证伪：3 分钟 765 次「context canceled」
// 全部白干、6341 资产仅 256 张落盘，客户端每次回看都重新生成（慢一拍+卡顿）。
// 新语义：客户端活着且生成完成 → 本次直接回图；已取消 → 404 占位（连接已断
// 无观感差异），生成落盘后下次请求命中缓存。缓存响应 immutable：缓存键 =
// SHA-256(策略:assetId:尺寸:表示版本)，表示版本包含原件 size/mtime、格式和配置；
// 强 ETag 另校验实际输出字节。旧版本 410，缺版本 URL 须重验证（ADR-0040）。
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
	version := s.thumbs.RepresentationVersion(row.SizeBytes, row.Mtime)
	if params.V != nil && *params.V != version {
		w.Header().Set("Cache-Control", noStoreCacheControl)
		writeErr(w, http.StatusGone, codeNotFound, "缩略图版本已过期，请刷新列表")
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
	fi, err := os.Stat(abs)
	if err != nil || s.thumbs.RepresentationVersion(fi.Size(), store.FormatTimestamp(fi.ModTime())) != version {
		w.Header().Set("Cache-Control", noStoreCacheControl)
		writeErr(w, http.StatusConflict, codeInvalidParam, "原件已变化，等待扫描后刷新")
		return
	}
	if err := s.thumbs.EnsureDetached(r.Context(), row.AssetID, abs, thumbnail.Kind(row.MediaType), []thumbnail.Size{size}); err != nil {
		// 客户端取消（滚动/离开视口）是高频预期路径：后台续生已在跑，静默返回
		// 不刷 WARN 日志、不写响应（连接已断写了也无收件人）。
		if errors.Is(err, context.Canceled) {
			return
		}
		// 其余失败（损坏文件/ffmpeg 异常/排队超时）：给 404 占位语义——客户端
		// 对缩略图缺失的常规处理就是显示占位块，不该当服务器故障处理。
		s.logger.Warn("缩略图生成失败", "assetId", row.AssetID, "err", err)
		writeErr(w, http.StatusNotFound, codeThumbnailFailed, "缩略图不可用")
		return
	}
	key := thumbnail.CacheKey(row.AssetID, size, version)
	// 扩展名与生成侧同源（StillFormat 单点）：磁盘扩展名 = 输出封装 = Content-Type。
	ext := s.thumbs.StillFormat().Ext()
	path := s.thumbs.VersionedPath(row.AssetID, size, version)
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
	// 以实际表示字节计算强验证器，重生字节变化绝不误判 304。
	hash := sha256.New()
	if _, err := io.Copy(hash, f); err != nil {
		s.internalErr(w, "校验缩略图", err)
		return
	}
	if _, err := f.Seek(0, io.SeekStart); err != nil {
		s.internalErr(w, "读取缩略图", err)
		return
	}
	fi, err = os.Stat(abs)
	if err != nil || s.thumbs.RepresentationVersion(fi.Size(), store.FormatTimestamp(fi.ModTime())) != version {
		w.Header().Set("Cache-Control", noStoreCacheControl)
		writeErr(w, http.StatusConflict, codeInvalidParam, "生成期间原件已变化，请刷新")
		return
	}
	if params.V != nil {
		w.Header().Set("Cache-Control", contentAddressedCacheControl)
	} else {
		w.Header().Set("Cache-Control", "private, no-cache")
	}
	etag := `"` + key + ":" + hex.EncodeToString(hash.Sum(nil)) + `"`
	w.Header().Set("ETag", etag)
	// ServeContent 同时处理弱验证器、通配符及条件请求列表。
	// 文件名带生效格式扩展名让 ServeContent 推断 Content-Type（webp/jpg 与
	// 磁盘扩展名、实际字节格式三者同源，见 thumbnail.StillFormat）。
	// 304 空体计 0，实际传出的缩略图字节恒计 thumb。
	cw := &countingResponseWriter{ResponseWriter: w}
	http.ServeContent(cw, r, "t"+ext, time.Time{}, f)
	sysmon.Default.AddMediaBytes(sysmon.MediaThumb, float64(cw.n))
}
