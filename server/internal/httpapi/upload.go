// upload.go：流式上传端点。四道安全校验的纯函数在 filing 包
// （ValidateUpload，142 子用例），本文件负责流接收、落盘与入库编排。
// 落库口径与 scanner 对齐：media_type 用 scanner.ClassifyMedia 的唯一
// 分类（gif 单列 animated_image），视频元数据同 scanner 的"探测失败留
// 空"语义——两处写入方口径漂移会让对账与筛选出现幽灵分类。
package httpapi

import (
	"database/sql"
	"errors"
	"io"
	"net/http"
	"os"
	"path"
	"path/filepath"

	"github.com/google/uuid"

	"qimeng-media/server/internal/config"
	"qimeng-media/server/internal/events"
	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/scanner"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
	"qimeng-media/server/internal/sysmon"
)

// PostApiV1AssetsUpload 流式上传：四道校验 → 落盘 → 入库 → 广播。
//
// 冲突语义（与移动端点的 409 不同）：目标同名文件存在时自动重命名
// "基名 (2).ext"——上传是批量采集通道（手机连拍/下载器批量落库），
// 同名是常态而非意外，重命名的体验远好于让用户逐个改目标名；
// 最终文件名以响应 AssetDetail.relPath 为准。
//
// 大小上限的双层执行：Content-Length 已知时先判（超限不收流）；
// 未知长度（chunked）由 MaxBytesReader 在读流时截断。
// 落盘原子性（SECURITY 红线 4）：临时文件 + 原子 rename，见
// receiveAndStore。
//
// 超函数警戒线（>100 行）理由：oapi-codegen 生成的接口签名 + 单请求
// 直线流（校验→冲突解析→流式落盘→入库→富化→广播→响应装配），落盘
// 段已拆出 receiveAndStore，剩余的局部状态（finalName/maxBytes/lib）
// 贯穿装配响应全流程，再拆只会提升为结构体在函数间传递。
func (s *Server) PostApiV1AssetsUpload(w http.ResponseWriter, r *http.Request, params gen.PostApiV1AssetsUploadParams) {
	// 第④道前置：文件名清洗（落盘名的唯一来源；ValidateUpload 内部
	// 校验的是同一规则，这里提前拿清洗结果构造后续路径）。
	name, err := filing.SanitizeFilename(params.Filename)
	if err != nil {
		writeErr(w, http.StatusBadRequest, codeInvalidFilename, "文件名不合法")
		return
	}
	mediaType, ok := scanner.ClassifyMedia(name)
	if !ok {
		writeErr(w, http.StatusBadRequest, codeInvalidExtension, "扩展名不在白名单")
		return
	}
	// 目标目录：空 = 库根（目录语义，与移动端点一致）；非空过安全校验。
	dir := params.Dir
	if dir != "" {
		dir, err = filing.NormalizeRelPath(dir)
		if err != nil {
			writeErr(w, http.StatusBadRequest, codeInvalidParam, "目标目录不合法")
			return
		}
	}
	// libraryId 是协议必填参数（本次接线补进协议）：多库场景必须显式
	// 指定目标库，避免"唯一库"隐式约定在第二座库注册后静默漂移。
	lib, err := s.q.GetLibrary(r.Context(), params.LibraryId)
	if errors.Is(err, sql.ErrNoRows) || params.LibraryId == "" {
		writeErr(w, http.StatusBadRequest, codeLibraryNotFound, "libraryId 必填且指向已注册库")
		return
	}
	if err != nil {
		s.internalErr(w, "查询库", err)
		return
	}

	// 上传上限基线：配置文件 upload.max_bytes（部署方的物理红线）。
	maxBytes := s.cfg.Upload.MaxBytes
	if maxBytes <= 0 {
		maxBytes = config.DefaultUploadMaxBytes
	}

	// 上传行为约束（PUT /config 持久化的客户端配置，实时生效——每次请求
	// 现读 kv，无缓存）：autoAccept=false 整体关闸；maxBytesMb 与配置文件
	// 上限取小者（min）。kv 无记录/读失败/解析失败时回落配置文件值、不设门
	//（storedClientConfig 返回 nil = 无覆盖，见 config.go 注释）。
	if kvCfg := s.storedClientConfig(r.Context()); kvCfg != nil {
		if !kvCfg.Upload.AutoAccept {
			sysmon.Default.IncUpload(sysmon.UploadFail)
			writeErr(w, http.StatusForbidden, codeUploadDisabled, "上传已被关闭（设置页自动接收上传开关）")
			return
		}
		if kvMax := int64(kvCfg.Upload.MaxBytesMb) << 20; kvMax > 0 && kvMax < maxBytes {
			// kv 覆盖只在比配置文件上限更严时收窄（min 语义）：
			// 设置页不能放大部署方在配置文件里收紧的上限。
			maxBytes = kvMax
		}
	}

	if r.ContentLength > maxBytes {
		sysmon.Default.IncUpload(sysmon.UploadFail)
		writeErr(w, http.StatusRequestEntityTooLarge, codeUploadTooLarge, "文件超过大小上限")
		return
	}

	// 冲突自动重命名（读盘判断，目录还没建也成立——exists 查不到就无冲突）。
	exists := func(n string) bool {
		_, err := os.Stat(filepath.Join(lib.RootPath, filepath.FromSlash(dir), n))
		return err == nil
	}
	finalName := filing.ResolveConflict(name, exists)
	targetRel := path.Join(dir, finalName)
	targetAbs := filepath.Join(lib.RootPath, filepath.FromSlash(targetRel))
	if err := os.MkdirAll(filepath.Dir(targetAbs), 0o755); err != nil {
		s.internalErr(w, "创建目标目录", err)
		return
	}

	// 流式接收 + 落盘（含第②道魔数校验与临时文件原子 rename）。
	if !s.receiveAndStore(w, r, params, targetAbs, maxBytes) {
		return
	}

	// 元数据：size/mtime 以落盘事实为准；视频探测失败留空（scanner 同语义）。
	fi, err := os.Stat(targetAbs)
	if err != nil {
		s.internalErr(w, "读取上传文件信息", err)
		return
	}
	// 上传字节计量以落盘事实为准（fi.Size()）：此处曾只计魔数头字节数
	// （≤512B），upload_bytes_total 严重少计（OBSERVABILITY「累计字节」口径）。
	sysmon.Default.AddUploadBytes(float64(fi.Size()))
	params_ := db.UpsertAssetParams{
		AssetID:   newUploadAssetID(),
		LibraryID: lib.ID,
		RelPath:   targetRel,
		FileName:  finalName,
		MediaType: mediaType,
		SizeBytes: fi.Size(),
		Mtime:     store.FormatTimestamp(fi.ModTime()),
		CreatedAt: store.FormatTimestamp(s.now()),
		UpdatedAt: store.FormatTimestamp(s.now()),
	}
	if mediaType == scanner.MediaTypeVideo {
		// 探测走 Generator 出口（s.thumbs 必填，见 Deps 校验）：ffprobe 路径
		// 与缩略图管线/扫描探测同源（thumbnail.ffprobe_path，空=PATH 自动发现）。
		if probeRes, perr := s.thumbs.ProbeVideo(r.Context(), targetAbs); perr != nil {
			s.logger.Warn("上传视频元数据探测失败，留空待重探", "path", targetRel, "err", perr)
		} else if probeRes != nil {
			params_.DurationMs = sql.NullInt64{Int64: probeRes.Duration.Milliseconds(), Valid: true}
			params_.Width = sql.NullInt64{Int64: int64(probeRes.Width), Valid: true}
			params_.Height = sql.NullInt64{Int64: int64(probeRes.Height), Valid: true}
		}
	}
	asset, err := s.q.UpsertAsset(r.Context(), params_)
	if err != nil {
		s.internalErr(w, "入库上传资产", err)
		return
	}
	// 富化补齐（与 scanner ingest、trash 恢复同口径）：上方 UpsertAsset 只写
	// 基础列 + 探测元数据，富化列（normal=出处/角色，cos=作者关联/cos_work）
	// 缺失会让手动上传的资产在出处筛选、作者聚合里隐身。EnrichAsset 只写
	// source/characters（UpdateAssetSource/DeleteAssetCharacters）与 cos 作者
	// 关联/cos_work（UpdateAssetCosWork），不触碰 duration/宽高等探测列——
	// 与上面的探测元数据互补不冲突。尽力而为：失败/扫描器未装配（noScanner）
	// 都不让上传失败（文件已落盘、记录已入库），富化列缺失可由下次该文件
	// size/mtime 变化重 ingest 自愈（同 trash 恢复语义）。
	if err := s.scanner.EnrichAsset(r.Context(), lib.ID, asset.AssetID); err != nil && !errors.Is(err, ErrScannerUnavailable) {
		s.logger.Warn("上传落库后富化失败（待重扫自愈）", "assetId", asset.AssetID, "err", err)
	}
	// upload.done 载荷型为 events.UploadDoneEvent（协议：UploadDoneEvent schema）；
	// SSE 客户端据此做上传完成刵新。
	if err := s.bus.Publish(events.Event{Topic: events.TopicUploadDone, Payload: events.UploadDoneEvent{AssetID: asset.AssetID}}); err != nil {
		s.logger.Warn("发布上传完成事件失败", "err", err)
	}
	s.publishLibraryChanged()
	// 上传成功改变了库内文件数：library_files 指标在此刷新（变更点推送，
	// 其余三个时机见 refreshLibraryFileMetrics 注释）。
	s.refreshLibraryFileMetrics()

	// 响应返回详情（协议 201 AssetDetail）：新上传资产没有标签/作者/
	// 统计等关联数据，这里只填基础字段 + 签名直链（与详情端点同一
	// buildSummary 口径组装基础部分），客户端拿到最终 relPath 无需
	// 再发一次详情请求。
	base := buildSummary(s, asset.AssetID, asset.FileName, asset.MediaType, asset.SizeBytes,
		asset.Mtime, asset.CreatedAt, sql.NullString{}, false, 0, nil, nil)
	detail := gen.AssetDetail{
		AddedAt:    base.AddedAt,
		FileName:   base.FileName,
		Id:         base.Id,
		IsFavorite: base.IsFavorite,
		LikeCount:  base.LikeCount,
		MediaType:  base.MediaType,
		ModifiedAt: base.ModifiedAt,
		SizeBytes:  base.SizeBytes,
		Source:     base.Source,
		ThumbUrl:   base.ThumbUrl,
		Directory:  ptr(dirOf(asset.RelPath)),
		RelPath:    ptr(asset.RelPath),
		ViewCount:  ptr(0),
		PlayCount:  ptr(0),
		Characters: ptr([]string{}),
		Tags:       ptr([]gen.Tag{}),
		Authors:    ptr([]gen.Author{}),
	}
	orig := s.signedMediaURL(mediaPathOrig + asset.AssetID)
	detail.OrigUrl = &orig
	if asset.DurationMs.Valid {
		detail.DurationMs = ptr(asset.DurationMs.Int64)
	}
	if asset.Width.Valid {
		detail.Width = ptr(int(asset.Width.Int64))
	}
	if asset.Height.Valid {
		detail.Height = ptr(int(asset.Height.Int64))
	}
	writeJSON(w, http.StatusCreated, detail)
}

// 上传临时文件名的前后缀：与最终落盘名同目录（同卷才能原子 rename）。
// .tmp 后缀在媒体扩展名白名单之外——进程崩溃留下的残留不会被扫描器
// 误入库（隐藏前缀 .qm-upload- 便于人工辨识与清理）。
const (
	uploadTmpPrefix = ".qm-upload-"
	uploadTmpSuffix = ".tmp"
)

// receiveAndStore 流式接收并落盘：读魔数头 → ValidateUpload 四道校验 →
// 写同目录临时文件 → 原子 rename 到最终路径（SECURITY 红线 4「临时文件 +
// 原子 rename」：并发同名上传不会交叉写坏同一文件，进程崩溃不会留下占用
// 最终名的半成品——写入中途的失败只残留白名单外的 .tmp 文件）。
// 失败时响应已写完并返回 false；upload 计数在成功 rename 后计入。
func (s *Server) receiveAndStore(w http.ResponseWriter, r *http.Request, params gen.PostApiV1AssetsUploadParams, targetAbs string, maxBytes int64) bool {
	body := http.MaxBytesReader(w, r.Body, maxBytes)
	head := make([]byte, filing.RecommendedHeadBytes)
	n, err := io.ReadFull(body, head)
	if err != nil && err != io.ErrUnexpectedEOF && err != io.EOF {
		writeUploadErr(w, err)
		return false
	}
	head = head[:n]
	if err := filing.ValidateUpload(params.Filename, min64(r.ContentLength, maxBytes), maxBytes, head); err != nil {
		sysmon.Default.IncUpload(sysmon.UploadFail)
		switch {
		case errors.Is(err, filing.ErrUploadTooLarge):
			writeErr(w, http.StatusRequestEntityTooLarge, codeUploadTooLarge, "文件超过大小上限")
		case errors.Is(err, filing.ErrUploadExtension):
			writeErr(w, http.StatusBadRequest, codeInvalidExtension, "扩展名不在白名单")
		case errors.Is(err, filing.ErrUploadMimeMismatch):
			writeErr(w, http.StatusBadRequest, codeMimeMismatch, "文件内容与扩展名不符")
		default: // ErrUploadFilename
			writeErr(w, http.StatusBadRequest, codeInvalidFilename, "文件名不合法")
		}
		return false
	}
	tmpAbs := filepath.Join(filepath.Dir(targetAbs), uploadTmpPrefix+uuid.NewString()+uploadTmpSuffix)
	renamed := false
	defer func() {
		if !renamed {
			if rmErr := os.Remove(tmpAbs); rmErr != nil && !os.IsNotExist(rmErr) {
				s.logger.Warn("清理上传临时文件失败", "path", tmpAbs, "err", rmErr)
			}
		}
	}()
	f, err := os.Create(tmpAbs)
	if err != nil {
		s.internalErr(w, "创建上传临时文件", err)
		return false
	}
	_, err = f.Write(head)
	// 头之外的全部内容流式接续写盘（不整读进内存）；err 全程用外层
	// 变量，Write 与 Copy 的失败都落入下方统一清理/响应路径。
	if err == nil && n == filing.RecommendedHeadBytes {
		_, err = io.Copy(f, body)
	}
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		sysmon.Default.IncUpload(sysmon.UploadFail)
		writeUploadErr(w, err)
		return false
	}
	if err := os.Rename(tmpAbs, targetAbs); err != nil {
		sysmon.Default.IncUpload(sysmon.UploadFail)
		s.internalErr(w, "落盘上传文件", err)
		return false
	}
	renamed = true
	sysmon.Default.IncUpload(sysmon.UploadOK)
	return true
}

// writeUploadErr 把读流错误映射为协议响应：超限 413、其余 400。
func writeUploadErr(w http.ResponseWriter, err error) {
	var mbe *http.MaxBytesError
	if errors.As(err, &mbe) {
		writeErr(w, http.StatusRequestEntityTooLarge, codeUploadTooLarge, "文件超过大小上限")
		return
	}
	writeErr(w, http.StatusBadRequest, codeInvalidBody, "上传流读取失败")
}

func min64(a, b int64) int64 {
	if a < 0 {
		return b // ContentLength 未知（-1）：交给 MaxBytesReader 兜底
	}
	if a < b {
		return a
	}
	return b
}

// newUploadAssetID 与 scanner.newAssetID 同规则（UUIDv7，防御性回退 v4）。
// 两处写入方共享"时间有序主键"语义（ADR-0004）。
func newUploadAssetID() string {
	id, err := uuid.NewV7()
	if err != nil {
		return uuid.NewString()
	}
	return id.String()
}
