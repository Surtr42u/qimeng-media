// upload.go：流式上传端点。四道安全校验的纯函数在 filing 包
// （ValidateUpload，142 子用例），本文件负责流接收、落盘与入库编排。
// 落库口径与 scanner 对齐：media_type 用 scanner.ClassifyMedia 的唯一
// 分类（gif 单列 animated_image），视频元数据同 scanner 的"探测失败留
// 空"语义——两处写入方口径漂移会让对账与筛选出现幽灵分类。
package httpapi

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
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

// 上传目标目录越界（#9 关键段闭包内以此错误类型返回，HTTP 响应统一在
// 锁外写，避免闭包内外两处 WriteHeader 路径）。
var errUploadTargetEscape = errors.New("upload target escapes library root")

// PostApiV1AssetsUpload 流式上传：四道校验 → 落盘 → 入库 → 广播。
//
// 冲突语义（与移动端点的 409 不同）：目标同名文件存在时自动重命名
// "基名 (2).ext"——上传是批量采集通道（手机连拍/下载器批量落库），
// 同名是常态而非意外，重命名的体验远好于让用户逐个改目标名；
// 最终文件名以响应 AssetDetail.relPath 为准。
//
// 大小上限的双层执行：Content-Length 已知时先判（超限不收流）；
// 未知长度（chunked）由 MaxBytesReader 在读流时截断。
// 落盘原子性（SECURITY 红线 4）：临时文件 + 原子 rename，收流见
// receiveUploadToTmp、改名见下方 #9 关键段（库锁内）。
//
// 超函数警戒线（>100 行）理由：oapi-codegen 生成的接口签名 + 单请求
// 直线流（校验→收流落 tmp→锁内冲突解析与改名→入库→富化→广播→响应装配），
// 收流段已拆出 receiveUploadToTmp，入库事务段已拆出 persistUploadedAsset；
// 剩余的局部状态（finalName/maxBytes/lib）贯穿装配响应全流程，再拆只会
// 提升为结构体在函数间传递。
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
	// 目标目录：空 = 库根（目录语义，与移动端点一致）；非空过安全校验
	//（与分片续传 create 共用同一入口，见 resolveUploadDir）。
	dir, ok := resolveUploadDir(w, params.Dir)
	if !ok {
		return
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

	// 上传行为约束（直传与分片续传两条通道共用同一闸，见 resolveUploadPolicy）：
	// autoAccept=false 整体关闸；maxBytesMb 与配置文件上限取小者（min）。
	policy := s.resolveUploadPolicy(r.Context())
	if policy.disabled {
		sysmon.Default.IncUpload(sysmon.UploadFail)
		writeErr(w, http.StatusForbidden, codeUploadDisabled, "上传已被关闭（设置页自动接收上传开关）")
		return
	}
	maxBytes := policy.maxBytes

	if r.ContentLength > maxBytes {
		sysmon.Default.IncUpload(sysmon.UploadFail)
		writeErr(w, http.StatusRequestEntityTooLarge, codeUploadTooLarge, "文件超过大小上限")
		return
	}

	// 上传落盘顺序（#9 清欠后的形态）：建目标目录（锁外，幂等且并发安全）→
	// 收流写临时文件（锁外，耗时与文件大小成正比，持库锁收流会让同库全部
	// 写入方排队等一个大文件传完）→ 锁内「exists 探测 → ResolveConflict →
	// rename」→ 入库。关键段必须持 filing.WithLibraryGate 的库锁：两段式
	// "解析冲突名→改名"之间没有互斥时，并发同名上传会解析出同一冲突名，
	// 第二次 rename 静默覆盖第一次（Windows/Linux 同为静默覆盖）；finalName
	// 在锁内确定后才用于 relPath 响应与 DB 行。目录创建不进关键段：MkdirAll
	// 幂等且并发安全，与冲突判定无关；临时文件与最终文件同目录才能原子
	// rename，故目录必须先于收流建好。
	// 自动重命名口径不变（DOMAIN_RULES §9）：目标同名存在 → "基名 (2).ext"
	// 递增，上传永不 409。

	// 上传目标目录（含校验兜底）：临时文件与最终文件都落在它下面。
	baseDirAbs := filepath.Join(lib.RootPath, filepath.FromSlash(dir))
	// SECURITY 红线 1 的 handler 侧兜底（与 media 直链/trash 同一纵深防御
	// 模式）：dir 已过 NormalizeRelPath，Join 后仍强制验根内再建目录落盘；
	// finalName 在锁内确定后对 targetAbs 还有同一道闸。
	if !filing.PathWithinRoot(lib.RootPath, baseDirAbs) {
		s.logger.Error("上传目标目录越界，已拦截", "libraryId", lib.ID, "dir", dir)
		writeErr(w, http.StatusBadRequest, codePathEscape, "目标路径不合法")
		return
	}
	if err := os.MkdirAll(baseDirAbs, dirPerm); err != nil {
		s.internalErr(w, "创建目标目录", err)
		return
	}

	// 流式接收 + 写临时文件（含第②道魔数校验；不触碰最终路径）。
	tmpAbs, ok := s.receiveUploadToTmp(w, r, params, baseDirAbs, maxBytes)
	if !ok {
		return
	}
	// 临时文件生命周期移交本函数：rename 成功后 tmpAbs 已不存在（os.Rename
	// 是移动语义，Remove 报 NotExist 被静默忽略），失败/异常路径由这里兜底
	// 清理——成功/失败统一走这一处，语义与原 receiveAndStore 的 defer 等价。
	defer func() {
		if rmErr := os.Remove(tmpAbs); rmErr != nil && !os.IsNotExist(rmErr) {
			s.logger.Warn("清理上传临时文件失败", "path", tmpAbs, "err", rmErr)
		}
	}()

	// #9 关键段（锁内）：冲突解析与改名落盘。收流在锁外完成后，这段只做
	// 只读探测与一次 rename，持锁时长与文件大小无关。
	var (
		finalName string
		targetRel string
	)
	gerr := filing.WithLibraryGate(lib.ID, func() error {
		exists := func(n string) bool {
			_, err := os.Stat(filepath.Join(baseDirAbs, n))
			return err == nil
		}
		finalName = filing.ResolveConflict(name, exists)
		targetRel = path.Join(dir, finalName)
		targetAbs := filepath.Join(lib.RootPath, filepath.FromSlash(targetRel))
		if !filing.PathWithinRoot(lib.RootPath, targetAbs) {
			s.logger.Error("上传目标路径越界，已拦截", "libraryId", lib.ID, "rel", targetRel)
			return errUploadTargetEscape
		}
		return os.Rename(tmpAbs, targetAbs)
	})
	if gerr != nil {
		if errors.Is(gerr, errUploadTargetEscape) {
			writeErr(w, http.StatusBadRequest, codePathEscape, "目标路径不合法")
			return
		}
		sysmon.Default.IncUpload(sysmon.UploadFail)
		s.internalErr(w, "落盘上传文件", gerr)
		return
	}
	targetAbs := filepath.Join(lib.RootPath, filepath.FromSlash(targetRel))
	// 计量→入库→富化→事件→响应装配：直传与分片续传（complete）两条通道
	// 共用同一管线（ADR-0028），细节见 ingestPlacedUpload。
	detail, err := s.ingestPlacedUpload(r.Context(), lib.ID, targetAbs, targetRel, finalName, mediaType)
	if err != nil {
		s.internalErr(w, "入库上传资产", err)
		return
	}
	writeJSON(w, http.StatusCreated, detail)
}

// resolveUploadDir 是直传（POST /assets/upload）与分片续传（POST /uploads
// create）两条上传通道共用的目标目录规范化入口：空 = 库根；非空过
// filing.NormalizeRelPath（SECURITY 红线 1 的统一实现，穿越/绝对路径/保留
// 设备名一律拒绝）。校验失败在此写 400 响应并返回 ok=false——dir 校验口径
// 单一来源（代码卫生约束：同一逻辑第 2 次出现即抽共享函数，禁止两通道各抄一份）。
func resolveUploadDir(w http.ResponseWriter, raw string) (string, bool) {
	if raw == "" {
		return "", true
	}
	dir, err := filing.NormalizeRelPath(raw)
	if err != nil {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "目标目录不合法")
		return "", false
	}
	return dir, true
}

// resolveUploadPolicy 汇集一次上传请求的生效约束（直传与分片续传两条通道
// 共用；PUT /config 持久化的客户端配置实时生效——每次请求现读 kv，无缓存）。
// kv 无记录/读失败/解析失败时回落配置文件值、不设门（storedClientConfig
// 返回 nil = 无覆盖，见 config.go 注释）。
type uploadPolicy struct {
	maxBytes int64
	disabled bool
}

func (s *Server) resolveUploadPolicy(ctx context.Context) uploadPolicy {
	// 上限基线：配置文件 upload.max_bytes（部署方的物理红线）。
	maxBytes := s.cfg.Upload.MaxBytes
	if maxBytes <= 0 {
		maxBytes = config.DefaultUploadMaxBytes
	}
	p := uploadPolicy{maxBytes: maxBytes}
	if kvCfg := s.storedClientConfig(ctx); kvCfg != nil {
		if !kvCfg.Upload.AutoAccept {
			p.disabled = true
		}
		if kvMax := int64(kvCfg.Upload.MaxBytesMb) << 20; kvMax > 0 && kvMax < maxBytes {
			// kv 覆盖只在比配置文件上限更严时收窄（min 语义）：
			// 设置页不能放大部署方在配置文件里收紧的上限。
			p.maxBytes = kvMax
		}
	}
	return p
}

// ingestPlacedUpload 是直传（POST /assets/upload）与分片续传（complete）
// 两条上传通道共用的入库管线：文件已落到库内最终路径后调用——指标计量、
// DB 行、视频探测、富化、upload.done / library.changed 事件（后者经事件
// 订阅推动库内容修订号 bump，ADR-0026）、文件数指标、响应装配一步不缺。
// 调用方保证 finalPath 处的文件已过四道校验与冲突解析（finalName 即落盘名）；
// 返回的 detail 直接作为 201 响应体。
func (s *Server) ingestPlacedUpload(ctx context.Context, libraryID, finalPath, relPath, finalName, mediaType string) (gen.AssetDetail, error) {
	sysmon.Default.IncUpload(sysmon.UploadOK)
	// 元数据：size/mtime 以落盘事实为准；视频探测失败留空（scanner 同语义）。
	fi, err := os.Stat(finalPath)
	if err != nil {
		return gen.AssetDetail{}, fmt.Errorf("读取上传文件信息: %w", err)
	}
	// 上传字节计量以落盘事实为准（fi.Size()）：此处曾只计魔数头字节数
	// （≤512B），upload_bytes_total 严重少计（OBSERVABILITY「累计字节」口径）。
	sysmon.Default.AddUploadBytes(float64(fi.Size()))
	params_ := db.UpsertAssetParams{
		AssetID:   newUploadAssetID(),
		LibraryID: libraryID,
		RelPath:   relPath,
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
		if probeRes, perr := s.thumbs.ProbeVideo(ctx, finalPath); perr != nil {
			s.logger.Warn("上传视频元数据探测失败，留空待重探", "path", relPath, "err", perr)
		} else if probeRes != nil {
			params_.DurationMs = sql.NullInt64{Int64: probeRes.Duration.Milliseconds(), Valid: true}
			params_.Width = sql.NullInt64{Int64: int64(probeRes.Width), Valid: true}
			params_.Height = sql.NullInt64{Int64: int64(probeRes.Height), Valid: true}
		}
	}
	// 入库（独立单写事务），细节见 persistUploadedAsset。
	asset, err := s.persistUploadedAsset(ctx, params_)
	if err != nil {
		return gen.AssetDetail{}, fmt.Errorf("入库上传资产: %w", err)
	}
	// 富化补齐（与 scanner ingest、trash 恢复同口径）：上方 UpsertAsset 只写
	// 基础列 + 探测元数据，富化列（normal=出处/角色，cos=作者关联/cos_work）
	// 缺失会让手动上传的资产在出处筛选、作者聚合里隐身。EnrichAsset 只写
	// source/characters（UpdateAssetSource/DeleteAssetCharacters）与 cos 作者
	// 关联/cos_work（UpdateAssetCosWork），不触碰 duration/宽高等探测列——
	// 与上面的探测元数据互补不冲突。尽力而为：失败/扫描器未装配（noScanner）
	// 都不让上传失败（文件已落盘、记录已入库），富化列缺失可由下次该文件
	// size/mtime 变化重 ingest 自愈（同 trash 恢复语义）。
	if err := s.scanner.EnrichAsset(ctx, libraryID, asset.AssetID); err != nil && !errors.Is(err, ErrScannerUnavailable) {
		s.logger.Warn("上传落库后富化失败（待重扫自愈）", "assetId", asset.AssetID, "err", err)
	}
	// upload.done 载荷型为 events.UploadDoneEvent（协议：UploadDoneEvent schema）；
	// SSE 客户端据此做上传完成刷新。
	if err := s.bus.Publish(events.Event{Topic: events.TopicUploadDone, Payload: events.UploadDoneEvent{AssetID: asset.AssetID}}); err != nil {
		s.logger.Warn("发布上传完成事件失败", "err", err)
	}
	s.publishLibraryChanged()
	// 上传成功改变了库内文件数：library_files 指标在此刷新（变更点推送，
	// 其余三个时机见 refreshLibraryFileMetrics 注释）。
	s.refreshLibraryFileMetrics()
	return s.assembleUploadDetail(asset), nil
}

// assembleUploadDetail 装配两条上传通道 201 响应的 AssetDetail：新上传资产
// 没有标签/作者/统计等关联数据，只填基础字段 + 签名直链（与详情端点同一
// buildSummary 口径组装基础部分），客户端拿到最终 relPath 无需再发一次详情。
func (s *Server) assembleUploadDetail(asset db.Asset) gen.AssetDetail {
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
	return detail
}

// persistUploadedAsset 把资产行写入独立单写事务（SQLite 单写者，单条
// UpsertAsset + Commit）：文件已落盘后入库；失败时资产行不落库（文件已
// 落盘但无 DB 行 = 既有语义不变，重传自愈，不得半更新）。
func (s *Server) persistUploadedAsset(ctx context.Context, row db.UpsertAssetParams) (db.Asset, error) {
	tx, err := s.conn.BeginTx(ctx, nil)
	if err != nil {
		return db.Asset{}, err
	}
	defer func() { _ = tx.Rollback() }() // Commit 后 Rollback 是无害空操作
	qtx := s.q.WithTx(tx)
	asset, err := qtx.UpsertAsset(ctx, row)
	if err != nil {
		return db.Asset{}, err
	}
	return asset, tx.Commit()
}

// 上传临时文件名的前后缀：与最终落盘名同目录（同卷才能原子 rename）。
// .tmp 后缀在媒体扩展名白名单之外——进程崩溃留下的残留不会被扫描器
// 误入库（隐藏前缀 .qm-upload- 便于人工辨识与清理）。
const (
	uploadTmpPrefix = ".qm-upload-"
	uploadTmpSuffix = ".tmp"
)

// receiveUploadToTmp 流式接收写入 dirAbs 下的临时文件：读魔数头 →
// ValidateUpload 四道校验 → 写 .qm-upload-*.tmp（SECURITY 红线 4 的"临时
// 文件"半边）。不负责改名到最终路径——"探测冲突名 → rename 最终名"是
// #9 关键段，由调用方持 filing.WithLibraryGate 库锁执行（收流在锁外，
// 持锁时长与文件大小解耦）。失败时响应已写完、半成品临时文件已清理，
// 返回 ok=false；成功时临时文件所有权移交调用方（rename 或清理）。
func (s *Server) receiveUploadToTmp(w http.ResponseWriter, r *http.Request, params gen.PostApiV1AssetsUploadParams, dirAbs string, maxBytes int64) (string, bool) {
	body := http.MaxBytesReader(w, r.Body, maxBytes)
	head := make([]byte, filing.RecommendedHeadBytes)
	n, err := io.ReadFull(body, head)
	if err != nil && err != io.ErrUnexpectedEOF && err != io.EOF {
		writeUploadErr(w, err)
		return "", false
	}
	head = head[:n]
	if err := filing.ValidateUpload(params.Filename, min64(r.ContentLength, maxBytes), maxBytes, head); err != nil {
		sysmon.Default.IncUpload(sysmon.UploadFail)
		writeUploadValidationError(w, err)
		return "", false
	}
	tmpAbs := filepath.Join(dirAbs, uploadTmpPrefix+uuid.NewString()+uploadTmpSuffix)
	f, err := os.Create(tmpAbs)
	if err != nil {
		s.internalErr(w, "创建上传临时文件", err)
		return "", false
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
		// 写失败即清理半成品（原 receiveAndStore 的 defer 清理语义收拢到
		// 本函数的失败分支；成功路径的清理职责移交调用方，见 handler）。
		if rmErr := os.Remove(tmpAbs); rmErr != nil && !os.IsNotExist(rmErr) {
			s.logger.Warn("清理上传临时文件失败", "path", tmpAbs, "err", rmErr)
		}
		writeUploadErr(w, err)
		return "", false
	}
	return tmpAbs, true
}

// writeUploadValidationError 把四道校验哨兵错误映射为协议响应（直传收流
// 与分片续传 complete 终检两处共用，映射口径单一来源）。
func writeUploadValidationError(w http.ResponseWriter, err error) {
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
