// assets_subtitle.go：外挂字幕直链与详情面字幕列表。
//
// 目录约定（`<库根父目录>/5  字幕/<库文件夹名>/<资产相对路径>.ass`）与枚举
// 规则收在 internal/subtitle；本文件只做「鉴权已过的取数 → 响应头 → 发文件」
// 与协议数组装配（httpapi 只鉴权、校验、序列化，AI_README_FIRST 开发流程 6）。
package httpapi

import (
	"database/sql"
	"errors"
	"io/fs"
	"net/http"
	"net/url"
	"os"
	"path/filepath"

	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store/db"
	"qimeng-media/server/internal/subtitle"
)

// 字幕 Content-Type：协议描述（GET /media/sub 的 200 响应）与本处双写，
// 改动须同步 api/openapi.yaml（代码卫生约束第 2/3 条）。charset 固定 UTF-8：
// 三端解码器（浏览器 TextDecoder 默认、Media3 SsaParser）都按 UTF-8 读取，
// 服务端不做转码（与原件直链「永不转码」同口径）——GBK/GB18030 字幕会乱码，
// 需另存为 UTF-8（限制见 CAPABILITY_MAP「外挂字幕」行）。
const (
	subtitleContentTypeSSA    = "text/x-ssa; charset=utf-8"
	subtitleContentTypeSubRip = "application/x-subrip; charset=utf-8"
	subtitleContentTypeVTT    = "text/vtt; charset=utf-8"
)

// subtitleContentType 按格式取 Content-Type（未知格式不可达：枚举与
// internal/subtitle 的支持清单一致，这里兜底 text/plain 不参与协议）。
func subtitleContentType(format string) string {
	switch format {
	case "ass", "ssa":
		return subtitleContentTypeSSA
	case "srt":
		return subtitleContentTypeSubRip
	case "vtt":
		return subtitleContentTypeVTT
	default:
		return "text/plain; charset=utf-8"
	}
}

// subtitleProtocolFormat 把 internal/subtitle 的格式串转成协议枚举。
// 两边取值集合由 internal/subtitle.supportedFormats 与 openapi AssetSubtitle
// 的 enum 双写保证；转换失败返回 ok=false（防御，正常路径不可达）。
func subtitleProtocolFormat(format string) (gen.AssetSubtitleFormat, bool) {
	f := gen.AssetSubtitleFormat(format)
	return f, f.Valid()
}

// assetSubtitles 组装详情响应的 subtitles 数组（签名直链，exp 与 origUrl 同窗）。
//
// 只对视频资产调用：字幕约定只覆盖视频；图片/动图跳过可省掉每张图一次目录
// 探测（列表/详情是高频端点）。展示性字段：读目录失败只告警并按「无字幕」
// 降级，不阻塞详情响应——与 fetchAssetCosWork 同一条失败策略。零字幕返回
// nil（协议缺省 = 无外挂字幕，客户端继续用内嵌轨）。
func (s *Server) assetSubtitles(row db.GetAssetWithLibraryRow) []gen.AssetSubtitle {
	abs := filepath.Join(row.RootPath, filepath.FromSlash(row.RelPath))
	tracks, err := subtitle.Tracks(row.RootPath, abs)
	if err != nil {
		s.logger.Warn("列举外挂字幕失败", "assetId", row.AssetID, "err", err)
		return nil
	}
	if len(tracks) == 0 {
		return nil
	}
	out := make([]gen.AssetSubtitle, 0, len(tracks))
	for _, t := range tracks {
		format, ok := subtitleProtocolFormat(t.Format)
		if !ok {
			continue
		}
		out = append(out, gen.AssetSubtitle{
			Lang:      t.Lang,
			Format:    format,
			SizeBytes: t.SizeBytes,
			// lang 是「本资产哪条字幕」的选择参数，与 thumb 的 size 同属选择面
			// 而非授权面：签名仍只锚定 /media/sub/{assetId} 路径（assets_media_url.go）。
			Url: s.signedMediaURL(mediaPathSub+row.AssetID) + "&lang=" + url.QueryEscape(t.Lang),
		})
	}
	if len(out) == 0 {
		return nil
	}
	return out
}

// GetMediaSubAssetId 外挂字幕直链。
//
// 鉴权由 topRouter 的 /media/ 前缀媒体签名中间件完成（与 /media/orig 同款：
// exp/sig 校验 + .. 段拒绝，403 语义在那里）；本 handler 只取数、选轨、
// 设响应头并发文件。发文件必须用 http.ServeContent：字幕拖拽/断点续读走
// Range/206，且它基于 io.Seeker 输出，不把文件整段读进内存（同 media.go）。
func (s *Server) GetMediaSubAssetId(w http.ResponseWriter, r *http.Request, assetID gen.AssetId, params gen.GetMediaSubAssetIdParams) {
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
	// SECURITY 红线 1 的 handler 侧兜底：rel_path 来自库，仍强制过根内校验，
	// 库数据被污染时绝不直接拼路径读文件（与 GetMediaOrigAssetId 同源）。
	abs := filepath.Join(row.RootPath, filepath.FromSlash(row.RelPath))
	if !filing.PathWithinRoot(row.RootPath, abs) {
		s.logger.Error("资产相对路径越界，已拦截", "assetId", row.AssetID)
		writeErr(w, http.StatusBadRequest, codePathEscape, "路径不合法")
		return
	}
	track, ok, err := subtitle.Resolve(row.RootPath, abs, params.Lang)
	if err != nil {
		s.logger.Error("解析外挂字幕失败", "assetId", row.AssetID, "err", err)
		writeErr(w, http.StatusInternalServerError, codeInternal, "内部错误")
		return
	}
	if !ok {
		// 资产存在但该语言无字幕（含语言码乱填）：404 与「文件不存在」同义，
		// 不向客户端区分存在性，避免用 404/403 差异探测目录内容。
		writeErr(w, http.StatusNotFound, codeNotFound, "字幕不存在")
		return
	}
	// 纵深防御：track.Path 由目录枚举得到（不含用户输入），仍再验一次它落在
	// 该资产的字幕目录内，防 `5  字幕` 下出现指向库外的链接/异常布局。
	dir, err := subtitle.Dir(row.RootPath, abs)
	if err != nil || !filing.PathWithinRoot(dir, track.Path) {
		s.logger.Error("字幕路径越界，已拦截", "assetId", row.AssetID)
		writeErr(w, http.StatusBadRequest, codePathEscape, "路径不合法")
		return
	}
	f, err := os.OpenInRoot(dir, filepath.Base(track.Path))
	if errors.Is(err, fs.ErrNotExist) {
		// 枚举后文件被外部删除：404 引导客户端刷新详情。
		writeErr(w, http.StatusNotFound, codeFileMissing, "字幕文件不存在")
		return
	}
	if err != nil {
		s.logger.Error("打开字幕文件失败", "assetId", row.AssetID, "err", err)
		writeErr(w, http.StatusInternalServerError, codeInternal, "内部错误")
		return
	}
	defer func() {
		if cerr := f.Close(); cerr != nil {
			s.logger.Warn("关闭字幕文件失败", "err", cerr)
		}
	}()
	// 缓存策略与原件直链同源（ADR-0027）：同窗 URL 恒定，max-age=直链窗口长，
	// 条目寿命不超过 URL 有效期下界。字幕几乎不变更，换字幕即换签名 URL。
	w.Header().Set("Content-Type", subtitleContentType(track.Format))
	w.Header().Set("Cache-Control", signedOrigCacheControl(s.ttl))
	name := filepath.Base(track.Path)
	modTime := parseStoreTime(row.Mtime)
	if fi, statErr := f.Stat(); statErr == nil {
		modTime = fi.ModTime()
	}
	// 文件名只喂 ServeContent 做扩展名推断；Content-Type 已显式设置，优先于推断。
	http.ServeContent(w, r, name, modTime, f)
}
